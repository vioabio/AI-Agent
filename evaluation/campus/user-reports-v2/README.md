# 用户反馈开发回归 v2（暂存）

保留[v1](../user-reports-v1/README.md)两题问法、两组切分、参考快照、必要检查和验收文字；新版本只更换到14篇资料并将对应的物种笔记加入允许审核来源。仍为开发专用，两条各重放两次，正式入集0，不能计为新增两个独立问题或100题成绩。

新增物种笔记是原始种族值支持下的条件建议，不是独立策略标准答案。来源仍待人工复核。实际人工审核必须绑定本轮观测，不能复用v1审核或将AI辅助意见计入通过。

```powershell
node tools/campus/s2-evaluate.mjs validate --suite evaluation/campus/user-reports-v2
node tools/campus/s2-evaluate.mjs run --suite evaluation/campus/user-reports-v2 --base-url http://127.0.0.1:18162/api --repetitions 2 --output artifacts/campus/species-guides/my-model-run
node tools/campus/s2-evaluate.mjs report --suite evaluation/campus/user-reports-v2 --report artifacts/campus/species-guides/my-model-run/observations.json --output artifacts/campus/species-guides/my-recomputed
```

服务使用独立的`local,evaluation`与READ_ONLY_TOOLS模式、账号/历史/队伍/收藏/攻略/公开缓存目录，禁止指向日常8123。和v1一样，公开缓存只包含两个Pokemon/species配对，外部资料代理明确不可用，模型仍调用真实供应商；这不是完全离线模型、完整公网工具或日常OFF模式的验收。

资料版本校验不通过直接拒绝运行，失败与未执行保留在四次分母。语义相关性、建议可用性和策略完整性需另审。详见[本批实施记录](../../../document/秋招升级/S3/物种培养资料与主题检索_2026-10-26.md)。
