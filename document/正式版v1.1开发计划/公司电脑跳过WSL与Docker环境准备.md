# 公司电脑跳过 WSL 与 Docker 环境准备

## 背景

当前电脑属于公司设备，执行 `wsl --install` 时出现“请求的操作需要提升”和 HTTP 403。为避免触碰公司设备策略，本阶段暂不安装 WSL、Docker Desktop 或本地 PostgreSQL/PGVector。

本步骤仅作为后续个人电脑或已获 IT 授权环境的准备手册保存，不代表当前项目已经完成真实 PostgreSQL + PGVector 验收。

## 当前现象

```text
wsl --status
未安装适用于 Linux 的 Windows 子系统。

wsl --install
请求的操作需要提升。
已禁止(403)。
```

常见原因包括：

- PowerShell 未使用管理员权限。
- 公司组策略禁止启用虚拟化组件。
- Microsoft Store 或 WSL 下载地址被网络策略拦截。
- BIOS/UEFI 未开启 CPU 虚拟化。
- 当前账户没有安装系统组件的权限。

## 后续在获授权环境中的执行顺序

### 1. 使用管理员 PowerShell

以管理员身份打开 PowerShell，确认窗口标题包含“管理员”，然后执行：

```powershell
wsl --install --no-distribution
```

完成后重启电脑：

```powershell
Restart-Computer
```

### 2. 检查 WSL

重启后执行：

```powershell
wsl --status
wsl --version
```

如需绕过 Microsoft Store 更新，可尝试：

```powershell
wsl --update --web-download
```

### 3. 手动启用 Windows 功能

如果安装命令仍然受限，在管理员 PowerShell 中执行：

```powershell
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
Restart-Computer
```

重启后：

```powershell
wsl --set-default-version 2
wsl --status
```

### 4. 安装 Linux 发行版

```powershell
wsl --list --online
wsl --install -d Ubuntu
```

完成标准：

```powershell
wsl -l -v
```

能够看到类似结果：

```text
NAME      STATE    VERSION
Ubuntu    Stopped  2
```

### 5. 安装并验证 Docker Desktop

在允许安装的设备上安装 Docker Desktop，并选择 WSL 2 后端。验证：

```powershell
docker --version
docker compose version
docker run --rm hello-world
```

### 6. 启动本项目依赖

进入项目目录：

```powershell
Set-Location D:\ai-intern-cjt\my\AI-Agent
```

准备本地 `.env`（不要提交到 Git），然后执行：

```powershell
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

确认 `postgres` 与 `vio-ai-agent` 为 `healthy` 后，再进行 Flyway、PGVector、知识发布版本和索引血缘验收。

## 公司电脑上的处理原则

- 当前阶段跳过 WSL、Docker 和本地 PGVector，不反复尝试绕过公司策略。
- 不在公司电脑上安装未经 IT 批准的虚拟化组件。
- 不把数据库密码、API Key 或 `.env` 内容提交到仓库。
- 本地 fixture 或 legacy memory 只能用于功能演示，不能替代真实 PGVector 版本血缘验收。
- 后续真实验收应在个人电脑、开发容器或公司批准的 staging 环境完成。

## 当前项目状态

```text
WSL/Docker 本地环境       暂停
PostgreSQL + PGVector     待授权环境执行
版本化知识发布            待数据库可用后执行
active index              待数据库可用后执行
lineageStatus=COMPLETE    待新 Run 验收
```

