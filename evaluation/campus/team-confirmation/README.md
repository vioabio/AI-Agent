# 队伍确认保存：独立开发回归

这组4条固定开发案例补充S2的确认保存评测，不改动原S2题集、分组或保留集。计划定义在[team-confirmation.mjs](../../../tools/campus/team-confirmation.mjs)，采集时记录计划、源码与运行身份摘要。

| 案例 | 操作与期望 |
| --- | --- |
| confirm | 读真实队伍，预览仅把第1位等级50改51；确认前不写，确认后APPLIED并回读；重复决定409 |
| deny | 拒绝预览，Run失败、结果DENIED，配置不变且无成功回答 |
| cancel | 取消Run后再确认旧预览，接口409、结果CANCELLED，配置不变 |
| conflict | 其他请求修改队名再恢复原内容；旧预览确认后CONFLICT，不能覆盖新的修订 |

四条都核对实际读取工具、唯一预期终态、存储全字段、回执与历史；正常保存还执行其他账号读取/确认404、错误审批ID404及通用审批入口403。采集器单独检查审批恢复没有新增模型调用、成功消息用量与事件一致。失败/未运行案例保留在4条固定分母中。

只允许本机HTTP地址，新建专用账号与测试队伍。审批由脚本模拟，不代表用户本人点击或策略通过人工审核。不要对共享账号或生产地址执行。密码、Cookie和恢复码不保存。需要本机已有模型配置与公开的杰尼龟Pokemon/species缓存；模型调用会产生实际用量。

```powershell
# Maven测试结束后，在独立端口和数据库启动后端，再执行；输出目录必须尚不存在
node tools/campus/s2-team-confirmation-scenario.mjs http://127.0.0.1:18149/api artifacts/campus/team-confirmation/my-capture
node --test tools/campus/team-confirmation.test.mjs
```

完整隔离启动命令、结果和未验收范围见[实施记录](../../../document/秋招升级/S2/队伍确认保存与异常回归_2026-10-26.md)。4/4是这组工程行为检查，不是策略准确率、整体任务成功率或保留集成绩。HTTP受控集成测试、API fixture浏览器测试与真实模型HTTP采集分别报告。
