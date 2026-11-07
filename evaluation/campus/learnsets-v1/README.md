# 招式学习途径开发回归v1

本目录固定[计划](plan.json)：3个新编写的功能开发场景，各重复2次；1个额外HTTP探针。期望方式、等级、可空order、公开来源文件摘要和原缓存包manifest摘要在运行前固定。

它不改变正式36题、原用户反馈v1～v4、攻略标注或保留集；新增正式入集0。来源仍是PokéAPI，不计独立权威、人工审核或完整学习操作验收。API只记录朱紫版本组，未记录不表示不可学习。

验证命令：

```powershell
node --test tools/campus/learnset-observations.test.mjs
node tools/campus/learnset-scenario.mjs http://127.0.0.1:18165/api artifacts/campus/user-reports/public-bundle-2026-10-26 artifacts/campus/learnsets/my-new-run
```

先启动独立`local,evaluation / READ_ONLY_TOOLS`实例，将账号、历史、队伍、收藏、攻略索引和公开缓存全部指向新目录，导入计划绑定的原公开缓存包，未内置资源代理不可用；模型真实联网。禁止使用日常8123/3000及已有账号数据。输出目录必须不存在，复测另建目录，不能覆盖失败证据。

采集结果分列：HTTP原始快照/预声明期望核对、真实调用入参和实际输出关联、正文方式/等级展示、历史与用量一致性、源码/class身份；人工验收保持待办。普通场景的自然语言理解、完整培养、机制独立正确性和赛事合法性仍未由这些检查证明。

实现和最终证据见[开发记录](../../../document/秋招升级/S3/招式学习途径与回答核验_2026-10-26.md)。
