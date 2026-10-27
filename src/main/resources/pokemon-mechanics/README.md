# 机制参考投影 v1

此目录保存12项公开资料的有界投影：激流、雨盘、雪隐、诅咒之躯；水之波动、冰冻光束、暗影球、守住、撒菱、同命；内敛、胆小。它与14篇攻略笔记分开计数，不是新增12篇攻略或12条用户问题。

`references-v1.json` 保留名称、来源、原始响应摘要、抓取时间、必要数值和朱紫短文身份；`references-v1.meta.json` 绑定目录摘要和原始材料清单摘要。完整API响应只在本机`artifacts/campus/mechanics/raw-2026-10-26`，不随应用批量复制。

本次朱紫短文只有英文。中文释义由项目编写，并绑定准确的英文原文摘要，状态始终为`PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW`；上游文字变化后不会自动复用旧释义。英文节选最多25词，截断时明确提示。没有朱紫短文不借用剑盾等旧版说明；未返回的概率、持续回合、招式例外和合法性不补造。

招式威力、命中、PP、优先度和性格升降字段来自当前上游资源，未按特定补丁冻结。朱紫文字的版本组和这些基础字段的范围分别表示。`null`保留为未提供，不替换成0或必中；性格缺少升降字段时不能推断中性性格。

内置项优先按本目录读取，保留原抓取日期，不自动声称最新。其他名称通过既有固定源缓存读取；已缓存响应损坏时拒绝，不静默覆盖。原资料仓储/客户端源码保持冻结，由独立服务提供新投影。道具仍沿用原接口，尚不作为新的机制事实输出。

开发者更新资料时，先在新目录抓取、构建并审核，保留旧版，再显式切换加载路径和评测身份：

```powershell
New-Item -ItemType Directory -Force artifacts/campus/mechanics | Out-Null
.\tools\fetch-mechanic-references.ps1 -OutputDirectory artifacts/campus/mechanics/raw-next
node tools/campus/build-mechanic-references.mjs artifacts/campus/mechanics/raw-next src/main/resources/pokemon-mechanics-next
```

抓取仅允许固定PokéAPI源及资源类别/slug，不读模型密钥；可使用公开的资料代理偏好或`-Proxy`指定无凭据HTTP代理。输出目录必须不存在。摘要用于完整性和复现，不是上游签名或独立机制审核。

详见[机制资料投影与问答证据](../../../../document/秋招升级/S3/机制资料投影与问答证据_2026-10-26.md)。
