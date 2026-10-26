# 服务器私有部署与访问

部署日期：2026-10-26。当前仅部署真实 HR 工作台；安卓手机采集继续在本机运行。

## 访问

服务器：`47.98.197.126`，SSH 端口 `22`。
Web 与 API 都只监听服务器回环地址，不开放公网招聘页面。

在 Windows 项目根目录运行并保持终端开启：

```powershell
.\scripts\open_hr_server_tunnel.ps1
```

等价命令：

```powershell
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L 127.0.0.1:8443:127.0.0.1:18443 root@47.98.197.126
```

按终端提示输入 SSH 密码，随后访问 **https://localhost:8443/login**。
浏览器首次访问会提示本服务的自签名 localhost 证书；核验后可继续。
该证书仅用于 SSH 私有入口，不是公网域名证书。

部署时核验的证书 SHA-256 指纹：

```text
D5:06:C1:1B:1E:CB:AD:86:4E:65:BA:ED:25:94:6F:BA:8F:9A:97:A8:89:3B:B2:59:96:4C:92:57:D1:DA:97:CD
```

使用原有 HR 账号，或本机已创建的只读账号登录。密码不记录在本文或仓库中。
如果本机 8443 端口已有本次建立的隧道，不要重复启动；原隧道断开后再运行命令。
关闭 SSH 终端只会断开访问隧道，不会停止服务器服务。

## 部署结构

| 项目 | 位置 |
| --- | --- |
| 当前发布 | `/opt/ai-recruitment/releases/20261026-01` |
| 当前版本链接 | `/opt/ai-recruitment/current` |
| 独立配置 | `/opt/ai-recruitment/shared/hr.env`，权限 600 |
| 批次元数据 | `/opt/ai-recruitment/shared/batches.json`，容器只读挂载 |
| TLS 证书和私钥 | `/opt/ai-recruitment/shared/tls/` |
| API 容器 | `ai-recruitment-api` |
| API 镜像 | `ai-recruitment-api:20261026-01` |
| API 回环端口 | `127.0.0.1:18080` |
| Nginx 回环 HTTPS | `127.0.0.1:18443` |
| Nginx 站点配置 | `/etc/nginx/conf.d/ai-recruitment-private.conf` |

前端是 Vite 生产构建产物，由现有 Nginx 服务静态文件并代理 `/api/`。
API 使用 Python 3.12，容器以非 root 用户运行、只读根文件系统、限制内存与 CPU，
设置自动重启和日志轮转。没有替换原有业务容器，没有新增公网监听端口。

## 配置与账号

本次经用户明确授权，仅将本机 `HR_REVIEW_DATABASE_URL` 传至服务器配置文件。
该连接目前复用原项目数据库账号，尚未完成独立最小权限账号隔离。
需要收敛数据库权限时，由 DBA 创建专用账号，并在服务器原位替换该项后重建 API 容器。
不要把服务器配置文件、数据库密码或 SSH 凭据加入 Git。

生产配置为：`APP_ENV=production`、`HR_COOKIE_SECURE=true`、`HR_ENABLE_OPENAPI=false`。
数据库迁移不会随容器启动执行；本次没有修改生产表结构或候选人审核状态。

批次文件是部署时导入的 11 个已完成批次元数据快照，字段只有批次 ID、开始和结束时间。
后续手机采集产生的新批次不会自动同步到服务器；需按同一格式更新该文件。
为兼容当前单文件绑定挂载，请在文件原位更新并执行 `docker restart ai-recruitment-api`；
如采用原子替换文件，应重建容器以重新绑定新文件。

## 日常检查

登录服务器后执行：

```bash
docker ps --filter name=ai-recruitment-api
docker inspect --format '{{.State.Health.Status}}' ai-recruitment-api
docker logs --tail 50 ai-recruitment-api
curl -k -I https://127.0.0.1:18443/login
nginx -t
```

日志按隐私要求处理，避免复制配置、完整简历或敏感链接到问题反馈中。

## 后续发布与回退

1. 在新发布目录解包源码，安装前端依赖并完成生产构建。
2. 使用 `deploy/Dockerfile.api` 构建在线镜像；或准备 Linux/Python 3.12 的
   `.wheelhouse/`，使用 `deploy/requirements-api.lock` 与 `deploy/Dockerfile.api-offline`
   进行带哈希校验的离线构建。离线锁文件面向本次 Linux x86_64 环境。
3. 保留旧镜像、旧发布目录和现有服务器私有配置。先在另一个回环端口验证新容器，
   验证成功后再安排切换；切勿直接覆盖正在运行的镜像标签。
4. 首次启动脚本 `deploy/start-api.sh IMAGE_TAG` 会在同名容器存在时退出，避免误覆盖。
5. 更新 Nginx 前先运行 `nginx -t`，通过后再平滑 reload。需要回退时，恢复旧发布链接、
   旧镜像和对应回环代理配置，再验证登录和只读查询。

## 本次验证

- 前端 TypeScript 检查和生产构建通过。
- API 回归测试 20 项通过；对齐了历史测试样本的 90 分门槛，以及“职位选择不代表发送成功”的断言。
- 通过真实 SSH/TLS 隧道验证登录、安全 Cookie、候选人列表、看板、批次和日报预览。
- 验证会话使用只读账号，完成后已注销；未提交候选人审核。
- 服务 API 文档返回 404，未登录 API 返回 401，HTTPS 登录页返回 200。
