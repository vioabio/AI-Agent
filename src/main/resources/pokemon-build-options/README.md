# 队伍编辑候选目录

`catalog-v1.json` 从 PokeAPI 官方 CSV 的固定提交生成，用于中文/英文名称检索、招式类型与参数展示。来源提交、下载时间和每份源文件的 SHA-256 位于 `catalog-v1.meta.json`。运行时核对随包目录摘要，不依赖逐项网络查询名称。

目录包含 374 个特性、937 个招式、356 个道具。道具按 `holdable` 标记或明确的携带装备类别筛选：上游部分新道具缺少标记，因此同时纳入 held-items、choice、plates 等类别；排除关键道具、招式机与材料类别。目录可能不完整，也不是朱／紫可获得道具清单。名称缺失时保留英文标识。

成员特性与隐藏标记来自该形态的 PokéAPI 响应；招式仅采用 `scarlet-violet` 的学习记录。特性说明仅采用该版本组的简体中文 flavor text，缺失时明确提示，不用旧版或自动翻译文本代替。名称、数值和特性参考均不构成补丁冻结、组合可得或正式赛制合法性的证明。

更新：`python tools/generate-build-option-catalog.py --revision <完整 PokeAPI/pokeapi 提交 SHA>`。省略参数时只解析一次 master 提交，再从同一提交下载全部文件。更新后应审查差异并运行 `LocalBuildOptionsTest`。

上游：[PokeAPI CSV 数据](https://github.com/PokeAPI/pokeapi/tree/master/data/v2/csv)、[API 文档](https://pokeapi.co/docs/v2)。宝可梦名称及相关内容归其权利人所有。
