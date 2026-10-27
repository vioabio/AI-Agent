# 用户反馈开发回归 v4（暂存）

延续[v3](../user-reports-v3/README.md)原两题、快照、来源、开发切分与五项验收文字。新增预先声明的`expected.resourceKinds`：杰尼龟原问法未明确查询资源，列表为空；雪妖女原界面问法明确查询特性与招式，要求`ability / move`均有成功、候选匹配且正文确实展示的参考。

类别检查在运行前固定，不以“至少一项成功”代替两类成功，不因原文存在或资源名称出现就计通过。失败、错误成员资源和未展示均保留为缺口。它仍只检查每类一个参考示例，不等于全部特性、四个招式、完整机制或配招合法性。

两题仍是原两条候选的重复开发记录，正式新增入集0，不能累计成不同用户或100题。实际人工审核需绑定本轮新观测，AI辅助意见不计人工通过。

```powershell
node tools/campus/s2-evaluate.mjs validate --suite evaluation/campus/user-reports-v4
node tools/campus/s2-evaluate.mjs run --suite evaluation/campus/user-reports-v4 --base-url http://127.0.0.1:18164/api --repetitions 2 --output artifacts/campus/resource-coverage/my-model
node tools/campus/mechanics-scenario.mjs http://127.0.0.1:18164/api artifacts/campus/resource-coverage/my-http artifacts/campus/resource-coverage/my-model/observations.json
node tools/campus/s2-evaluate.mjs report --suite evaluation/campus/user-reports-v4 --report artifacts/campus/resource-coverage/my-model/observations.json --output artifacts/campus/resource-coverage/my-recomputed
```

先启动独立的`local,evaluation / READ_ONLY_TOOLS`实例；账号、历史、队伍、收藏、索引和公开缓存均使用新目录，禁止复用日常8123。图鉴使用同一两个Pokemon/species配对，机制使用同一12项内置投影，其他资源代理不可用；模型仍真实联网。该配置不代表全部日常工具或所有问法的验收。

实施、原始记录与限制见[特性招式逐类覆盖与补读](../../../document/秋招升级/S3/特性招式逐类覆盖与补读_2026-10-26.md)。
