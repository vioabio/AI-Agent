import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve, basename } from 'node:path'
import { auditModelUsage } from './model-usage.mjs'
import { digest } from './evaluation.mjs'
const [input, output] = process.argv.slice(2)
if (!input || !output || process.argv.length !== 4) throw Error('Usage: node tools/campus/s4-usage.mjs OBSERVATIONS_JSON NEW_OUTPUT_DIRECTORY')
const path = resolve(input), destination = resolve(output)
if (existsSync(destination)) throw Error('Use new output; never replace evidence')
const raw = readFileSync(path), report = JSON.parse(raw)
if (!report.runtime?.features?.modelUsage) throw Error('Runtime did not attest usage instrumentation')
const result = auditModelUsage(report, record => {
  if (!record.eventsFile || basename(record.eventsFile) !== record.eventsFile) throw Error('Invalid events path')
  const bytes = readFileSync(resolve(dirname(path), record.eventsFile))
  if (digest(bytes) !== record.eventsSha256) throw Error('Event identity mismatch')
  return JSON.parse(bytes)
})
mkdirSync(destination, { recursive: true })
writeFileSync(resolve(destination, 'usage-verification.json'), JSON.stringify({ ...result, observationsSha256: digest(raw),
  configuredProvider: report.runtime.configuredProvider, configuredModelId: report.runtime.configuredModelId,
  promptVersion: report.runtime.promptVersion }, null, 2) + '\n')
console.log(JSON.stringify(result, null, 2))
if (result.snapshotChecksPassed !== result.denominator) process.exitCode = 1
