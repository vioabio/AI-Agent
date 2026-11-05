# 本机 HR 工作台入口

真实数据：<http://127.0.0.1:5173/login>，使用现有 HR 账号密码。
后端读取本机 `.env` 的 `HR_REVIEW_DATABASE_URL`，连接已有招聘数据库。
审核操作会修改真实候选人的人工状态，请在演示入口练习。

演示数据：<http://localhost:5174/login>。
账号 `demo`，密码 `DemoReview2026!`，仅供本机演示使用。
演示使用 `artifacts/hr_demo/demo-v4.sqlite3`，包含虚构候选人。旧 v2/v3 文件保留，
新版本独立初始化，不覆盖旧演示审核数据。
目前包含 99 份虚构简历（最初 3 份及新增 96 份）、两个岗位、四个新增批次，
覆盖不同 AI 结论、评分、沟通结果及审核进度，并包含履历、评分证据和审核事件。
工作台沿用业务筛选规则，列表人数可能少于数据库中的原始简历数。
扩充数据由 `scripts/seed_hr_demo.py` 按固定候选人标识补齐，重复启动不重复插入，
也不会覆盖已经完成的演示审核。
与真实数据库完全独立，适合体验页面和审核流程，不代替 MySQL 兼容性验证。
真实入口默认使用 `hr_review_session/hr_review_csrf`，演示入口使用
`hr_demo_session/hr_demo_csrf`，登录、审核和退出均按各自名称读取和清除。
可继续使用上面的两个域名；会话隔离现在也支持同一主机名下的两个入口。
自定义真实入口前缀时，将后端 `HR_COOKIE_PREFIX` 与前端 `VITE_HR_COOKIE_PREFIX` 配成相同值。
演示前端需保留 `VITE_HR_DEMO=true`，默认读取 `hr_demo_csrf`；不要给演示前端设置真实前缀。

扩充虚构简历已带原文快照，可在详情“关键证据”中点击“查看原文”；精简旧记录保留无引用展示。
详情同时提供“按岗位查阅原文”，手动检索当前分析快照并定位片段；检索不会更改评分或提交审核。
检索组件、合成对照及独立端口浏览器验证见 [第 4 阶段记录](UPGRADE_STAGE_4.md)。

已有真实数据库需先按 [第 3 阶段实施记录](UPGRADE_STAGE_3.md) 迁移至 `20261105_04`，
新建演示库由本地 metadata 初始化，不连接真实库，也不自动迁移旧演示文件。

## 重启

在 `D:\jianlizhuanqu` 打开四个 PowerShell 终端，各执行一个脚本：

```powershell
.\scripts\start_hr_real.ps1
.\scripts\start_hr_real_web.ps1
.\scripts\start_hr_demo.ps1
.\scripts\start_hr_demo_web.ps1
```

已在运行的服务无需重复启动。前端需要安装好 `node_modules`。
这四个入口均不启动手机采集或发送日报。
终端前台运行时按 Ctrl+C 停止对应服务。

## 独立影子任务演示（第 5 阶段）

这是另外一个本地入口，使用 `demo-v5-shadow.sqlite3`，保留默认 v4 文件。包含 7 个
合成任务、AI/FDE 预计算结果、等待/失败/重试状态及原文引用，不调用真实模型。

```powershell
# 根目录，终端一
.\.venv\Scripts\python.exe -m uvicorn scripts.run_hr_demo:build_shadow_demo_app --factory --host 127.0.0.1 --port 18081

# 终端二
cd web/hr-review
$env:HR_API_PROXY_TARGET="http://127.0.0.1:18081"
$env:VITE_HR_DEMO="true"
node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15174 --strictPort
```

打开 <http://127.0.0.1:15174/login>，使用相同的本地演示账号。进入“影子分析任务”，
可查看合成结果、点击原文；负责人可提前安排符合条件的重试，只修改调度时间。
演示 Worker 不自动运行，不提交正式审核。截图、迁移和测试见 [第 5 阶段第 5 节](UPGRADE_STAGE_5.md#5-第二轮合成采集与-hr-影子流程集成)。

普通 v4 入口和真实入口默认不启用影子任务；`HR_ENABLE_SHADOW_TASKS` 默认 false。
本阶段只支持隔离合成 SQLite，生产环境或 MySQL 不能启用该页面服务。

当前队列/影子 schema 已增量至 `20261105_07`（请求计划与 usage 回执）；v5 本地
演示入口显式初始化新增表，不重置旧任务。SDK 协议验证是独立命令
`python -m eval.sdk_queue_demo`，不会启动页面、真实模型或手机，详见阶段文档第 6 节。

## AI/FDE 主工作台演示（v6）

新增独立 `demo-v6-ai.sqlite3` 和 `batches-ai-v6.json`。主库有 96 份 AI/FDE 虚构简历，
不修改 v4/v5 文件；已有人工操作在重启后保留。影子页仍保留原有 7 个合成示例。

```powershell
# 仓库根目录，终端一
.\.venv\Scripts\python.exe -m uvicorn scripts.run_hr_demo:build_ai_demo_app --factory --host 127.0.0.1 --port 18182

# 仓库根目录，另开终端二
cd web/hr-review
$env:HR_API_PROXY_TARGET="http://127.0.0.1:18182"
$env:VITE_HR_DEMO="true"
$env:VITE_HR_COOKIE_PREFIX="hr_ai_demo"
node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15176 --strictPort
```

打开 <http://127.0.0.1:15176/login>，`demo` / `DemoReview2026!`。首页岗位选项已支持 AI 开发
和 FDE，权限、90 分待办口径、人工 revision/审计保持原规则。主库种子是 96 份，当前
“全部候选人”按既有可行动口径显示 36 条（AI 16/FDE 20），并非全库浏览；七张指标卡
中的“可行动候选人”还限定待审核状态。日报原始三态按所选批次另行统计。

v6 使用独立 `hr_ai_demo` Cookie，前后端前缀必须一致。切回 v4/v5 时将
`VITE_HR_COOKIE_PREFIX` 改为 `hr_demo` 并重启前端；切换已构建的生产 preview 必须重新
构建，环境变量不会追溯修改 dist。v6 是预计算合成演示，没有真实模型或手机 Worker。

两终端按 Ctrl+C 停止；无需删除库或降级迁移。样本、实际 API/Chrome 结果及生产
preview 复现见 [阶段 7 AI/FDE 演示整合](UPGRADE_STAGE_7.md#7-第四轮aifde-主工作台独立演示)。
