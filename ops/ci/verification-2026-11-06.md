# Compose CI 修复与验证记录（2026-11-06）

## 为什么此前修复后仍报错

此前验证侧重单元测试和局部配置，没有跑完实际打包程序的 Compose 业务链路。前一个启动阻断修复后，后续缺陷才暴露；局部通过不能证明镜像启动、就绪检查和代理鉴权都正常。

本轮在隔离的 PostgreSQL、后端和 Nginx 容器中逐项复现并修复：

| 缺陷 | 修改 |
| --- | --- |
| Docker 构建没有复制 `pom.xml` 声明的外部评测资源，JDBC 仓储启动时报缺少 manifest | 只复制冻结的 manifest、metric-dictionary；打包阶段检查两者实际存在于可执行 JAR 中 |
| `/ready` 使用全局健康聚合，未使用的 Redis 探测失败导致 503 | 使用配置好的 readiness 健康组，保留数据库检查和异常时拒绝就绪的行为 |
| `${VIO_API_KEY}` 写在 YAML Map 键中，真实启动时未绑定为期望的密钥，代理业务请求返回 401 | demo/prod 改用 `bootstrap-api-key` 属性值注入，再合并到不可变令牌配置；兼容已有配置构造器 |

没有关闭鉴权、跳过冻结规则校验或把受保护的业务接口加入匿名白名单。

## 验证结果

- 最终后端 Maven 回归：1408 次测试执行，0 failures、0 errors、3 skipped。
- 真实 ConfigData 测试：demo/prod 两种配置都能使用注入的测试令牌认证，拒绝占位符及其错误绑定字面值。
- 就绪测试覆盖：readiness 正常而全局聚合失败时允许就绪；健康组缺失、异常或 DOWN 时拒绝就绪。
- 部署契约 Node 测试：8/8 通过。
- 真实 Docker 运行链路：PostgreSQL、后端和 Nginx，使用隔离端口及 CI 假凭证，不调用真实模型。
- HTTP 探测：readiness、health、ready、前端首页均为 200；经 Nginx 创建 Run 为 202，取消为 200，事件列表为 200 且符合数组契约。
- 清理隔离数据卷后重复验证通过：全新 PostgreSQL 成功应用 28 条迁移至 v31，全部 HTTP 与业务探测再次通过；结束后清理本轮容器与数据卷。
- JAR 检查：两份冻结规则与源文件内容一致，不包含私有 `application-local.yml`。

证据位于本机忽略目录 `artifacts/ci-container-20261106/`：`backend-final.log`、`jar-verification.json`、`result.json`、`containers.log`、`cleanup.log`。

## 验证边界

冷缓存 Docker 构建在下载 Maven 离线依赖时停滞，不能声称完整冷构建已通过。本机先使用同版本 Linux Maven/JDK 完成公开源码打包验证；最终修改使用本机 JDK 21 和依赖缓存执行 clean package，再使用仓库 Dockerfile 的相同运行阶段构造镜像并运行 Compose 业务探测。构建输入来自公开 Git 快照及本轮修改，不读取本机密钥配置。

GitHub Actions 的最终结果仍须以推送后运行记录为准；这里证明的是最终后端回归、资源打包和真实容器运行链路，不代表外部镜像仓库或 Maven 下载永远可用。
