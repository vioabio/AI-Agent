import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest, sameJson } from './evaluation.mjs'
import { validateAblationPlan, excerptEvidence, modes } from './ablation.mjs'
import { attemptsFrom } from './answer-quality.mjs'
import { normalizeModelUsage } from '../../vio-ai-agent-frontend/src/api/model-usage.js'
import { createReviewBundle, validateReviewBundle, emptyReviews, compileReviews } from './ablation-review.mjs'
import { renderReviewHtml } from './review-workbench-html.mjs'

const planFile = 'evaluation/campus/ablation/manifest.json'
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'))
function frozenInputs() {
  const plan = validateAblationPlan(read(planFile))
  if (frozenFileDigest(plan.corpusFile) !== plan.corpusSha256) throw Error('Frozen corpus changed')
  return { plan, corpus: read(plan.corpusFile), planSha256: frozenFileDigest(planFile) }
}
export function loadReviewEvidence(files) {
  if (files.length !== 3) throw Error('Exactly three capture files required')
  const { plan, corpus, planSha256 } = frozenInputs()
  const captures = files.map(file => read(file))
  for (const [i, capture] of captures.entries()) {
    if (capture.planSha256 !== planSha256 || capture.corpusSha256 !== plan.corpusSha256) throw Error('Wrong fixed plan/corpus')
    for (const record of capture.records || []) {
      const bound = (name, suffix) => {
        const file = record[`${name}File`]
        if (!file || !new RegExp(`^[A-Z0-9-]+\\.${suffix}\\.json$`).test(file)) throw Error('Invalid evidence path')
        const bytes = fs.readFileSync(path.join(path.dirname(files[i]), file))
        if (digest(bytes) !== record[`${name}Sha256`]) throw Error('Changed evidence bytes')
        return JSON.parse(bytes)
      }
      const events = bound('events', 'events')
      const answer = events.filter(e => e.type === 'answer.delta').map(e => e.payload?.text || '').join('')
      if (record.collected && answer !== record.answer) throw Error('Changed captured answer')
      if (!record.collected) continue
      const history = bound('history', 'history'), item = plan.cases.find(c => c.id === record.caseId)
      if (!item) throw Error('Unknown case')
      const stored = history.find(m => m.runId === record.runId && m.role === 'assistant'), attempts = attemptsFrom(events)
      record.historyMatches = stored?.content === answer && history.some(m => m.runId === record.runId && m.role === 'user' && m.content === item.prompt)
      record.usageMatchesHistory = Boolean(stored) && sameJson(attempts.map(normalizeModelUsage), (stored.metadata?.modelUsageAttempts || []).map(normalizeModelUsage))
      record.startedTools = events.filter(e => e.type === 'tool.started').map(e => e.payload.toolName)
      record.runtimeTools = capture.runtime.evaluationPolicy.effectiveTools
      record.modelCalls = attempts.reduce((sum, a) => sum + a.modelCallCount, 0)
      record.modelUsageAttempts = attempts
      record.excerpts = excerptEvidence(answer, corpus.documents)
    }
  }
  return createReviewBundle(plan, captures, corpus.documents, files.map(file => digest(fs.readFileSync(file))))
}
export function verifyFrozenBundle(bundle) {
  validateReviewBundle(bundle)
  const { plan, corpus, planSha256 } = frozenInputs()
  const documents = corpus.documents.map(d => ({ id: d.id, title: d.title, author: d.author, updatedAt: d.updatedAt, content: d.content }))
  if (bundle.planSha256 !== planSha256 || bundle.corpusSha256 !== plan.corpusSha256 || !sameJson(bundle.documents, documents)
      || bundle.denominator !== plan.cases.length * modes.length) throw Error('Bundle does not match frozen questions/sources')
  for (const row of bundle.rows) {
    const item = plan.cases.find(c => c.id === row.caseId)
    if (!modes.includes(row.mode) || !item || row.question !== item.prompt
        || !sameJson(row.criteria, item.semanticCriteria.map((text, i) => ({ id: `C${i + 1}`, text })))) throw Error('Changed question or criteria')
  }
  return bundle
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, ...args] = process.argv.slice(2)
  let bundle, output, reviews
  if (command === 'build' && args.length === 4) { bundle = loadReviewEvidence(args.slice(0, 3)); output = args[3] }
  else if (command === 'render' && args.length === 2) { bundle = verifyFrozenBundle(read(args[0])); output = args[1] }
  else if (command === 'report' && [2, 3].includes(args.length)) { bundle = verifyFrozenBundle(read(args[0])); output = args[1]; reviews = args[2] ? read(args[2]) : undefined }
  else throw Error('build MODEL_CAPTURE GUIDE_CAPTURE READONLY_CAPTURE NEW_OUTPUT | render BUNDLE NEW_OUTPUT | report BUNDLE NEW_OUTPUT [SUBMITTED_REVIEWS]')
  if (fs.existsSync(output)) throw Error('Never overwrite review evidence')
  const report = compileReviews(bundle, reviews)
  fs.mkdirSync(output, { recursive: true })
  const write = (file, value) => fs.writeFileSync(path.join(output, file), JSON.stringify(value, null, 2) + '\n')
  write('bundle.json', bundle); write('report.json', report)
  if (reviews) write('submitted-reviews.json', reviews)
  if (command !== 'report') {
    write('draft.json', emptyReviews(bundle))
    fs.writeFileSync(path.join(output, 'review.html'), renderReviewHtml(bundle))
  }
  fs.writeFileSync(path.join(output, 'report.md'), ['# 开发回答审核状态', '',
    `固定${report.denominator}条；工程通过${report.engineeringPass}条，原文不匹配${report.literalMismatchCases}条。`,
    `人工提交${report.humanReviewed}条，AI辅助${report.assistedReviewed}条；已验证任务${report.verifiedTaskPass}条，失败${report.fail}条，待审核${report.pendingReview}条。`, '',
    '| 案例 | 工程 | 原文未匹配 | 审核 | 任务结果 |', '| --- | --- | --- | --- | --- |',
    ...report.rows.map(r => `| ${r.key} | ${r.engineeringPass} | ${r.literalFailures.join(', ') || '无'} | ${r.reviewState} | ${r.taskOutcome} |`), '',
    '人工身份为自述，工具不提供身份认证。未审核不计为通过；逐字匹配不证明引用相关性或机制正确。', ''].join('\n'))
  const producers = ['ablation-review.mjs', 'review-workbench.mjs', 'review-workbench-html.mjs', 'review-workbench-client.js']
  write('receipt.json', { schemaVersion: 'review-workbench-receipt-v1', bundleSha256: bundle.bundleSha256, createdAt: new Date().toISOString(),
    producerSha256: Object.fromEntries(producers.map(name => [name, digest(fs.readFileSync(new URL(name, import.meta.url)))])),
    artifactSha256: Object.fromEntries(fs.readdirSync(output).map(file => [file, digest(fs.readFileSync(path.join(output, file)))])) })
  console.log(JSON.stringify({ output, denominator: report.denominator, engineeringPass: report.engineeringPass, literalMismatchCases: report.literalMismatchCases,
    humanReviewed: report.humanReviewed, verifiedTaskPass: report.verifiedTaskPass, fail: report.fail, pendingReview: report.pendingReview }))
}
