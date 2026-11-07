# 新环境与隔离 MySQL 验收记录

日期：2026-11-06（Asia/Shanghai）。关联 [V2 主计划](AI_APPLICATION_UPGRADE_PLAN_V2.md) 与 [核心接入手册](UPGRADE_REMAINING_IMPLEMENTATION.md)。

本记录中的“第 1、2 阶段”对应本次约定的 **CI/新环境验收、隔离 MySQL 验收**，不是 V2 原有第 2 阶段的人工质量评测。真实模型、真实简历标签、手机操作和生产发布继续暂缓。

## 验收结果

| 范围 | 结果与证据 |
| --- | --- |
| 原推送 CI | [42002136252](https://github.com/vioabio/AI-HR/actions/runs/42002136252)：后端、前端成功，MySQL 失败；保留失败历史 |
| 修复后 CI | [42157126032](https://github.com/vioabio/AI-HR/actions/runs/42157126032)，代码 `a1c9c4f`；**backend / frontend / mysql-queue 全部成功**，集成、统一验收和启动报告均已留存 |
| 本机隔离 MySQL | MySQL **8.4.9**；独立 datadir、127.0.0.1:13316、`hr_upgrade_test_*` 新空库；完整检查通过 |
| 远程隔离 MySQL | GitHub Actions 临时 MySQL **8.4.13** 服务，完整检查通过；不是生产数据库 |
| 独立进程启动 | 真实启动 Uvicorn，以 HTTP 验证登录/退出、待办、工具回合、已投影结果和原文证据；通过 |
| 定向回归 | 评分夹具、队列/投影、迁移 **29 项通过** |
| 全新 CI 全量检查 | 后端 **539 passed，2 条既有依赖警告**；前端 **42 passed**；类型检查、构建和体积门槛通过 |
| 本机全新克隆与 venv | 从远程克隆 `a1c9c4f`，无 `.env`；完成依赖安装与 `pip check`；**539 项通过**，HTTP 启动、五组统一验收/源码复核、HR 投影端到端验收均通过 |

本机最终 MySQL 报告：[mysql-5cf79c438847](../artifacts/mysql/mysql-5cf79c438847/report.json)。独立进程报告：[clean-26b7d12f3d1c](../artifacts/environment-acceptance/clean-26b7d12f3d1c/report.json)。这些目录被 gitignore；其他机器应重跑命令或查看对应 Actions 的报告附件，不依赖本机文件。

远程 CI 在全新 Windows runner 安装后端并执行全量测试、统一合成验收、HR 投影集成、真实 HTTP 启动检查；全新 Ubuntu runner 使用冻结的 pnpm lockfile 安装、类型检查、测试、构建和体积门槛；另一 Ubuntu job 使用临时 MySQL 服务。三组成功共同构成本次新环境证据，不依赖开发机已有 `.venv` 或 `node_modules`。

CI 的 JSON 附件配置保留 7 天；过期后可以重新运行工作流生成，不把本机忽略目录当作仓库交付内容。

本机干净副本位于 `tmp/stage12-clean-checkout`，独立 venv 位于 `tmp/stage12-clean-env`。直接联网 pip 安装曾停在构建依赖；本轮从 PyPI 官方文件下载并逐一核对 SHA256，使用独立 wheel 目录完成全新安装，没有复制旧环境的 site-packages。版本/摘要清单位于 `tmp/stage12-wheels/manifest.json`；这次离线安装不是提交了依赖锁文件，远程 CI 的直接联网安装另已通过。

干净副本的报告分别为 `clean-9d3317950060`、`acceptance-20261106T033929Z-c039765b`、`remaining-c9d8a160c4d3`，都在该副本的 `artifacts/` 下。第一次额外指定的 pytest basetemp 缺少父目录，测试初始化失败；改回项目默认 `.pytest-tmp` 后全量 539 项通过，没有修改测试断言或产品行为。

## 修复了什么

1. MySQL 验收原夹具给 `beauty_continuity` 30 分，超过冻结默认契约的 25 分上限。改成 30/20/40，总分仍为 90，并在运行前校验契约、增加回归测试。没有放宽业务评分规则。
2. 既有 MySQL `DATETIME` 字段会将新任务时间取整，可能使 `available_at` 暂时晚于数据库当前时间。验收现在有界等待真实数据库时间，再启动测量/进程竞争；保持 Worker 只领取已到期任务的语义，不把数据库时钟改成模拟时间来掩盖问题。
3. 新增 MySQL HR 投影验证：自动增长主键、重复入队、重复投影、缺少原始来源的事务回滚、新版本阻止旧投影，以及人工状态、反馈、revision 和两项抑制字段保留。
4. 失败报告增加文件名/函数/行号，不输出异常原文、连接串或局部变量。报告记录验收脚本 SHA256。
5. 新增 `eval.clean_environment_acceptance` 和 CI 步骤。子进程不继承应用凭据、模型端点及代理变量；仅创建独立合成库、调用自己启动的 loopback 假模型服务。父进程完成检查后停止服务。
6. 文档提交后的 [42157945562](https://github.com/vioabio/AI-HR/actions/runs/42157945562) 再次运行时，后端全量 539 项、统一/投影验收、MySQL、前端均通过，但新增 HTTP 启动验收偶发读到尚未写完的空端口文件。改为临时文件写完后原子替换，并增加暂停写入时的并发读回归测试；最终提交结果见[最新主分支 CI](https://github.com/vioabio/AI-HR/actions/workflows/ci.yml?query=branch%3Amain)。不通过重复运行来掩盖此竞争。

## MySQL 实测范围与边界

- 以 metadata 初始化 **07 基线**，实际执行 `07 → 08 → 07 → 08` 并检查三张新增表的创建/移除。这不是从空库执行所有历史迁移的声明；首个历史迁移依赖已有招聘业务表。
- 1/2/4 并发分别处理 12 个 stub 任务，36 个全部成功。测量的是数据库和固定 stub 的耗时，不是模型吞吐或端到端延迟。
- 8 个真实子进程同时争抢，得到 4 个唯一租约，符合共享在途上限。进程退出不释放租约；随后使用注入时钟仅推进过期/重试时间，恢复 8 个任务，旧 token 完成请求被拒绝。
- 身份不变的重复提交复用 job；重复投影只生成一条缓存。人工 `revision=8`、反馈和抑制标志保持不变。缺 locator 时无机器字段写入；新版本创建后旧结果不能重新成为当前投影。
- 当前不宣称真实 usage、计费、模型限流、生产权限、设备操作或真实数据质量已验收。`quality_claim_allowed=false`、`release_approved=false` 继续成立。

## 复现方式

全新目录克隆后，使用 Python 3.12 创建虚拟环境：

```powershell
git clone https://github.com/vioabio/AI-HR.git AI-HR
cd AI-HR
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m scripts.run_upgrade_acceptance --include-sdk --verify
.\.venv\Scripts\python.exe -m eval.remaining_acceptance
.\.venv\Scripts\python.exe -m eval.clean_environment_acceptance
```

这些合成命令不需要创建 `.env` 或提供模型密钥。最后一条启动并关闭独立 HTTP 服务，输出唯一报告目录；不是常驻演示命令。手动演示继续使用核心接入手册中的前后端命令。

前端在 `web/hr-review` 执行：

```powershell
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm test
pnpm run build
pnpm run check:bundle
```

隔离 MySQL 必须另行准备 loopback 上的全新空库，再显式提供 `UPGRADE_MYSQL_TEST_URL` 并运行：

```powershell
# URL 从自己的隔离测试环境注入；库名必须以 hr_upgrade_test_ 开头
.\.venv\Scripts\python.exe -m eval.mysql_queue_acceptance --execute
```

脚本拒绝非 loopback、非指定库名前缀、附带 query 参数和非空库；不删除测试库。重复运行应换一个新空库。也可直接查看仓库 Actions 的 `mysql-queue` 任务，不依赖本机 Docker。

## 后续开发顺序

完成本次环境验收后，后续依次为：实际供应商小批量适配 → 人工标签与独立质量对照 → 按实测收益决定混合检索/工具默认策略 → 小范围灰度与部署运维验收。此前用户选择的“只做工具与合成验证”继续有效，这些真实调用和质量工作未自动启动。
