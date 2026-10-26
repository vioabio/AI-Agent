# 本机 HR 工作台入口

真实数据：<http://127.0.0.1:5173/login>，使用现有 HR 账号密码。
后端读取本机 `.env` 的 `HR_REVIEW_DATABASE_URL`，连接已有招聘数据库。
审核操作会修改真实候选人的人工状态，请在演示入口练习。

演示数据：<http://localhost:5174/login>。
账号 `demo`，密码 `DemoReview2026!`，仅供本机演示使用。
演示使用 `artifacts/hr_demo/demo-v2.sqlite3`，包含虚构候选人。
目前包含 99 份虚构简历（最初 3 份及新增 96 份）、两个岗位、四个新增批次，
覆盖不同 AI 结论、评分、沟通结果及审核进度，并包含履历、评分证据和审核事件。
工作台沿用业务筛选规则，列表人数可能少于数据库中的原始简历数。
扩充数据由 `scripts/seed_hr_demo.py` 按固定候选人标识补齐，重复启动不重复插入，
也不会覆盖已经完成的演示审核。
与真实数据库完全独立，适合体验页面和审核流程，不代替 MySQL 兼容性验证。
请保留两个入口各自的域名，避免浏览器会话 Cookie 互相覆盖。

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
