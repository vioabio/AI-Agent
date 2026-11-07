# N6b 反馈闭环证据 producer

`run-n6b-feedback-closure.ps1` 是一个 **fail-closed 的输入投影器**。它只接受
授权执行器已经捕获的反馈闭环记录，校验 `answerId → runId → traceId →
evaluationRunId → old/new release` 血缘后，生成校验器消费的三个不可变文件。
脚本不会调用生产 API、不会选择或伪造 answer、不会执行 reindex/publish，也不会
把输入对象中的任意字段（尤其是密钥）原样复制到证据目录。因此，脚本成功只代表
“显式输入通过结构和血缘校验”，不能单独证明线上闭环已经执行；真实执行证据仍需由
N6b 授权 runner 采集后再通过本脚本投影。

## 输入 bundle

`-InputBundle` 可以是 JSON 文件，也可以是一个包含三个 canonical 文件的旧 run
目录。JSON 顶层建议如下（数组至少一项）：

```json
{
  "schemaVersion": "v1.1",
  "feedbackRunId": "n6b-20260903-001",
  "publishedReleaseId": "pokemon-agent-2026-09-03",
  "feedbackClosure": [
    {
      "answerId": "answer-...",
      "runId": "run-...",
      "traceId": "trace-...",
      "feedbackId": "feedback-...",
      "feedbackRunId": "n6b-20260903-001",
      "evaluationRunId": "eval-...",
      "previousReleaseId": "pokemon-agent-2026-09-01",
      "publishedReleaseId": "pokemon-agent-2026-09-03",
      "operatorId": "operator-...",
      "status": "CLOSED",
      "lineageStatus": "COMPLETE"
    }
  ],
  "answerReleaseRegistry": {
    "entries": [
      {
        "answerId": "answer-...",
        "runId": "run-...",
        "traceId": "trace-...",
        "feedbackRunId": "n6b-20260903-001",
        "evaluationRunId": "eval-...",
        "previousReleaseId": "pokemon-agent-2026-09-01",
        "publishedReleaseId": "pokemon-agent-2026-09-03",
        "operatorId": "operator-...",
        "status": "PUBLISHED",
        "lineageStatus": "COMPLETE"
      }
    ]
  },
  "reanswerVerification": [
    {
      "answerId": "answer-...",
      "runId": "run-...",
      "traceId": "trace-...",
      "feedbackId": "feedback-...",
      "feedbackRunId": "n6b-20260903-001",
      "evaluationRunId": "eval-...",
      "previousReleaseId": "pokemon-agent-2026-09-01",
      "publishedReleaseId": "pokemon-agent-2026-09-03",
      "operatorId": "operator-...",
      "status": "VERIFIED",
      "lineageStatus": "COMPLETE"
    }
  ]
}
```

`feedback`/`closure`、`registry`/`answerRegistry` 和 `reanswer`/`verification`
是兼容别名，但输出始终使用上面的 canonical 名称。记录中的核心字段必须显式
提供；`unknown`、`UNTRACEABLE`、`pending`、`trace-unknown` 和尖括号占位符都会
被拒绝。`oldReleaseId`/`sourceReleaseId`、`newReleaseId`/`targetReleaseId`、
`operator`/`actorId` 等输入别名会被投影为 canonical 字段；三个集合必须覆盖
完全相同的 `answerId`，且共享血缘字段必须逐项一致。若输入显式携带
`dataReleaseId`、`knowledgeReleaseId`、`indexReleaseId`、`correctionCaseId` 或
通知/定向评测 ID，脚本会按白名单保留；未提供时不会补默认值。

## 运行

```powershell
$feedbackRunId = 'n6b-' + [guid]::NewGuid().ToString('N')
$publishedReleaseId = $env:VIO_PUBLISHED_RELEASE_ID
$evidenceDir = Join-Path 'artifacts/v1.1/test' $feedbackRunId

.\ops\feedback\run-n6b-feedback-closure.ps1 `
  -PublishedReleaseId $publishedReleaseId `
  -FeedbackRunId $feedbackRunId `
  -EvidenceDir $evidenceDir `
  -InputBundle 'path/to/captured-n6b-bundle.json' `
  -NoClobber
```

随后运行仓库校验器：

```powershell
node tools\validate-feedback-lineage.mjs `
  --feedback "$evidenceDir\n6-feedback-closure.jsonl" `
  --registry "$evidenceDir\n6-answer-release-registry.json" `
  --reanswer "$evidenceDir\n6b-reanswer-verification.jsonl" `
  --published-release $publishedReleaseId `
  --feedback-run-id $feedbackRunId `
  --report "$evidenceDir\n6b-feedback-lineage-validation.json" `
  --no-clobber
```

缺少 `-InputBundle`、输入不完整、已有输出文件、内部校验器缺失或校验失败时，
脚本均返回非零，且不会创建三个输出文件。输出目录必须位于仓库内；重试请创建
新的 `feedbackRunId`/目录，不要删除或覆盖旧证据。
