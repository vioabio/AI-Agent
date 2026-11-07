# v1.0/v1.1 评测与反馈契约

本目录是 M6 的**可审计评测输入**，不把样例数量或脚本退出码包装成线上质量成绩。

## 目录

```text
evaluation/
├── contracts/              # 反馈、评测运行和结果契约
├── datasets/               # 版本化 JSONL 黄金集（当前为可运行样例）
├── v1.1/                   # v1.1 manifest、dictionary 和七类数据集
├── metric-dictionary.json   # 指标定义、分母、阈值和回滚条件
├── manifest.json            # 套件版本、最低规模和状态
└── quality_gate.mjs         # CI/Release 质量门禁
```

## 运行

```powershell
# 只检查契约、JSONL 格式和确定性样例（适合本地/普通 CI）
node evaluation/quality_gate.mjs --mode=ci

# 候选发布门禁：要求所有套件达到最低规模并提供实测报告
node evaluation/quality_gate.mjs --mode=release --report artifacts/v1.0/eval/release-report.json
```

`--mode=release` 在数据集仍为 `scaffold`、样本不足或指标报告缺失时必须失败。当前仓库只提交脱敏的小型样例，不能声称达到规格书中的 200/100/50/100 条规模；补充经过人工审核的真实数据后再切换 `manifest.json` 的 `status` 为 `ready`。

## v1.1 评测执行

v1.1 使用独立的 `evaluation/v1.1/manifest.json` 和
`evaluation/v1.1/metric-dictionary.json`，不覆盖 v1.0 输入。评测运行器只接受
显式的 case/metric 结果，不会把数据集样例当作通过：

```powershell
node evaluation/run-v1.1-eval.mjs `
  --manifest evaluation/v1.1/manifest.json `
  --dictionary evaluation/v1.1/metric-dictionary.json `
  --evaluation-run-id eval-local-001 `
  --run-dir artifacts/v1.1/eval/eval-local-001
```

没有 `--case-results` 或样本未达到 manifest 的最低规模时，输出每套 suite 和
aggregate 的 `pending` 文件并以非零退出；这表示“评测输入未完成”，不是质量通过。
只有 reviewer 签收的 `manifest.ready.json`、真实运行结果、OTel/安全/契约证据齐全
后，才允许调用 `tools/v1.1-quality-gate.mjs --mode=release`。

## 数据约束

每行至少包含：

```json
{
  "caseId": "fact-0001",
  "suite": "fact",
  "input": {},
  "expected": {},
  "assertion": "deterministic|human_label|security_policy",
  "versions": {
    "gameReleaseId": "sv-v1",
    "rulesetVersionId": "rules-v1",
    "dataReleaseId": "data-v1"
  }
}
```

确定性事实/计算必须由程序断言；LLM-as-Judge 只能作为辅助字段，不能覆盖程序断言失败。安全红队样例的 `expected.blocked` 必须为 `true`。
