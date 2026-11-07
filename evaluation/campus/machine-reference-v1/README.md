# 学习器编号HTTP开发验证

`plan.json`在运行前固定8个场景：杰尼龟4项编号、雪妖女冰冻光束、雪妖女水炮无machine记录、杰尼龟冲浪编号未收录、杰尼龟水流喷射仅蛋招式类别。运行前后各查询一次，总分母16，直接对照原始公开Pokemon行和固定move/machine/item来源。

```powershell
node tools/campus/machine-references.mjs
node tools/campus/machine-reference-http.mjs http://127.0.0.1:18169/api artifacts/campus/machine-reference/NEW_OUTPUT
```

需预先启动独立local,evaluation实例及新数据目录，启用本地账号和参考缓存，评测模式READ_ONLY_TOOLS。采集器新建专用账号和一支测试队伍，检查前后完整回读不变；不保存密码、Cookie或API Key。仅请求本地接口，不请求大模型；零模型调用不作为模型能力或成本对比成绩。输出目录必须不存在，失败保留，不覆盖旧材料。

来源是新增4组公开编号快照及既有Pokemon/species离线包。`machine-references.mjs`同时检查目录代码固定身份、源文件摘要及双向版本关联；Node回归有错误版本、错道具反向关系、损坏摘要与成员快照错配。浏览器另外验证短编号、来源链接、未收录、503、配置变化、过期快照和迟到响应。

这些是新编写接口场景，非真实用户反馈或独立保留集，正式入集0、人工验收待审；旧36题、学习提示模型失败记录及其他历史证据不改。详见[实施记录](../../../document/秋招升级/S3/学习器编号与成员记录核对_2026-10-27.md)。
