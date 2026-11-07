# 用户反馈开发回归 v3（暂存）

延续[v2](../user-reports-v2/README.md)原两题、问法、图鉴快照、验收文字及开发切分；仅为机制阶段追加12项资源投影为允许审核来源。正式入集0、独立用户问题不增加，不能将每版重复任务累加成用户规模。

`resourceCatalogSha256`登记资源目录，采集器v6将两个资源文件纳入来源身份。实际参数证据、HTTP投影、显示检查与语义审核分别统计；中文释义、完整机制及策略有效性仍待独立复核。

```powershell
node tools/campus/s2-evaluate.mjs validate --suite evaluation/campus/user-reports-v3
node tools/campus/s2-evaluate.mjs run --suite evaluation/campus/user-reports-v3 --base-url http://127.0.0.1:18163/api --repetitions 2 --output artifacts/campus/mechanics/my-model
node tools/campus/mechanics-scenario.mjs http://127.0.0.1:18163/api artifacts/campus/mechanics/my-http artifacts/campus/mechanics/my-model/observations.json
node tools/campus/s2-evaluate.mjs report --suite evaluation/campus/user-reports-v3 --report artifacts/campus/mechanics/my-model/observations.json --output artifacts/campus/mechanics/my-recomputed
```

运行使用独立`local,evaluation`、READ_ONLY_TOOLS、账号/历史/队伍/收藏/攻略和公开缓存路径，禁止指向日常8123。原图鉴只装两个Pokemon/species配对，新增12项机制投影来自应用内置文件，其他资源代理明确不可用；模型仍需真实供应商。这不是完全离线模型或全部公网工具路径验收。

机制专项记录每条实际参数是否绑定已知资源、HTTP字段是否一致、正文是否确实展示。对于有限的明确资源查询问法，必要检查只要求至少一项成功读取并展示，不代表同时完成“特性与招式”全部请求。本批两次雪妖女实际只读雪隐，尚无招式工具读取；该缺口留在质量待办。

人工审核模板来自本次实际观测；AI辅助不计人工通过，旧版意见不能套用新回答。详见[实施记录](../../../document/秋招升级/S3/机制资料投影与问答证据_2026-10-26.md)。
