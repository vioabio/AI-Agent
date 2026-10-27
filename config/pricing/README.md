# 本地参考价格目录

默认关闭费用估算，Token用量仍正常记录。需要时由本机配置文件启用；无需新增服务器，也不要把API Key写入价格文件。

```powershell
$env:VIO_PRICING_ENABLED='true'
$env:VIO_PRICING_CATALOG_PATH='D:\ai-intern-cjt\ai-intern-folder\my\AI-Agent\config\pricing\my-prices.json'
$env:VIO_PRICING_SCOPE='my-confirmed-scope'
```

复制示例并填写自己的价格、提供商、精确模型ID、范围、币种、来源URL和UTC有效窗口。更新单价时创建新版本；启动时加载目录，调用开始时冻结选择结果。重启后生效。不要将本地合同、地区、时段或模型模式不同的费率混用；应用不会自动判断这些商务条件。

`deepseek-offpeak-2026-10-24.example.json`来自2026-10-24核对的[DeepSeek官方价格页](https://api-docs.deepseek.com/quick_start/pricing/)：示范USD下的deepseek-flash周末低谷报价。示例只允许UTC 2026-10-24这一天，这是本地示范窗口，不是供应商承诺有效期；过期后保持未知。它**不适用于DashScope/qwen-plus**，也不会自动启用。

- `SPLIT_CACHE`：`inputPerMillion`用于未命中输入，`cachedInputPerMillion`用于缓存命中输入，`outputPerMillion`用于总输出；必须取得缓存计数。
- `ALL_INPUT`：仅用于已经确认全部输入统一单价的报价/合同，不需要缓存拆价。不能借此把公共缓存差别价格假装成统一单价。
- 输出以原生总输出计费，推理明细是其中一部分，不额外加一次。
- 单价为每百万Token的十进制数；只支持USD、CNY，不做汇率换算。不同币种不合并金额。
- `sourceType`支持`OFFICIAL_LIST`、`LOCAL_CONTRACT`、`TEST_FIXTURE`；测试价格会在界面标明，不能作为实际账单。

当前百炼SDK只保留总输入/输出/总数，分缓存报价所需的明细可能无法取得，费用会保持未知。缓存字段依据见[百炼官方说明](https://www.alibabacloud.com/help/en/model-studio/context-cache)；SDK限制及验证范围见[开发记录](../../document/秋招升级/S4/价格版本与参考费用估算_2026-10-24.md)。
