# 第二来源机制开发回归v1

[固定计划](plan.json)在真实运行前声明4条新编写问题，各重复2次：激流门槛与进攻能力、雪隐天气与命中修正、诅咒之躯概率及限制、冰冻光束概率及适用性边界。绑定固定6条目录与来源提交，不修改原36题、旧反馈和保留集。

```powershell
node --test tools/campus/independent-mechanics.test.mjs
node tools/campus/independent-mechanics-scenario.mjs http://127.0.0.1:18166/api artifacts/campus/independent-mechanics/my-new-run
```

先启动独立`local,evaluation / READ_ONLY_TOOLS`实例，账号、历史、队伍、收藏、攻略索引和公开缓存全部指向新目录。为模型可能发起的普通图鉴读取导入旧公开缓存包；详细机制本身来自随包离线文本，不访问任意网络。未缓存资料代理不可用，模型仍真实联网；禁止使用日常8123/3000及已有账号数据。

输出目录必须不存在，原失败材料不得覆盖。报告分列运行前/后各6项HTTP投影、错误版本400、未收录404、实际工具输入/输出证明、正文必要内容、来源/待审状态、历史/用量与源码/class身份。唯一调用数与终态通知数分开统计。

这不是官方机制验证、第二引擎对照、完整例外核对、人工质量验收或保留集准确率。新增正式入集0；实际审核需绑定本轮答案与事件，不能套用旧意见。

构建器`tools/campus/build-independent-mechanics.mjs`只接受固定上游提交及精确Git blob身份；原始公开来源在`artifacts/campus/independent-mechanics/source-2026-10-27/`，校验后生成新目录，不能覆盖旧目录。构建器不会执行上游TypeScript；MIT许可随目录保留。

完整实现与结果见[开发记录](../../../document/秋招升级/S3/第二来源机制说明与核验_2026-10-27.md)。
