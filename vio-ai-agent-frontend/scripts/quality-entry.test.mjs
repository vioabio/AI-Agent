import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

import { frontendRoot, repoRoot, runQualityCheck } from './quality-runner.mjs'
import { classifyCheckResult, runFrontendQuality } from './run-frontend-quality.mjs'
import { parseTarget } from './run-e2e.mjs'

const testRoot = path.join(repoRoot, 'tmp', `n5-quality-entry-${process.pid}`)
const rel = name => path.relative(repoRoot, path.join(testRoot, name)).split(path.sep).join('/')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

test.after(() => {
  fs.rmSync(testRoot, { recursive: true, force: true })
})

test('package.json 暴露 v1.1 前端质量入口，工具声明与配置齐全', () => {
  const pkg = readJson(path.join(frontendRoot, 'package.json'))
  assert.equal(pkg.scripts.lint, 'node scripts/run-quality-check.mjs lint')
  assert.equal(pkg.scripts.typecheck, 'node scripts/run-quality-check.mjs typecheck')
  assert.equal(pkg.scripts['test:e2e'], 'node scripts/run-e2e.mjs')
  assert.equal(pkg.scripts['quality:frontend'], 'node scripts/run-frontend-quality.mjs')
  assert.match(pkg.scripts['openapi:fetch'], /curl\s+--fail\s+--silent\s+--show-error\b/)
  assert.match(pkg.scripts['openapi:diff'], /--base contracts\/openapi-v1\.0\.json\s+--candidate contracts\/openapi-v1\.1\.json\s+--report artifacts\/v1\.1\/test\/openapi-diff\.json/)
  const qualitySource = fs.readFileSync(path.join(frontendRoot, 'scripts', 'run-frontend-quality.mjs'), 'utf8')
  for (const name of ['lint', 'typecheck', 'security:contract', 'test:contract', 'test:e2e', 'build']) {
    assert.match(qualitySource, new RegExp(`name: ['"]${name.replace(':', '\\:')}['"]`))
  }
  const tools = readJson(path.join(frontendRoot, 'quality-tools.json'))
  assert.equal(tools.schemaVersion, 'v1.1')
  for (const kind of ['lint', 'typecheck', 'e2e']) {
    assert.ok(tools.tools[kind].runner)
    assert.ok(tools.tools[kind].packages.length > 0)
    for (const config of tools.tools[kind].configs) assert.ok(fs.existsSync(path.join(frontendRoot, config)))
  }
  const e2eSource = fs.readFileSync(path.join(frontendRoot, 'e2e', 'pokemon-workbench.spec.js'), 'utf8')
  assert.equal((e2eSource.match(/^\s*test\(/gm) || []).length, 8)
  assert.doesNotMatch(e2eSource, /(?:apiKey|token|secret|password)\s*[:=]\s*['"]/i)

  const eslintSource = fs.readFileSync(path.join(frontendRoot, 'eslint.config.js'), 'utf8')
  assert.match(eslintSource, /files:\s*\[['"]scripts\/\*\*\/\*\.\{js,mjs\}['"]\]/)
  assert.match(eslintSource, /['"]no-console['"]:\s*['"]off['"]/)
})

test('缺少真实 lint/typecheck 依赖时报告 environment-pending，不伪造 passed', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  for (const kind of ['lint', 'typecheck']) {
    const output = rel(`${kind}.json`)
    const code = runQualityCheck(kind, ['--probe-only', '--output', output])
    const report = readJson(path.join(repoRoot, output))
    assert.ok([0, 2].includes(code))
    assert.notEqual(report.status, 'passed')
    assert.equal(report.commandExecuted, false)
    if (code === 2) {
      assert.equal(report.status, 'environment-pending')
      assert.equal(report.passed, false)
      assert.ok(report.missing.length > 0)
    } else {
      assert.equal(report.status, 'ready-to-run')
    }
  }
})

test('E2E 探针不启动浏览器、不访问网络，并在依赖缺失时 fail-closed', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('e2e.json')
  const html = rel('e2e.html')
  const result = spawnSync(process.execPath, [path.join(frontendRoot, 'scripts', 'run-e2e.mjs'), '--probe-only', '--output', output, '--html', html], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.ok([0, 2].includes(result.status))
  const report = readJson(path.join(repoRoot, output))
  assert.notEqual(report.status, 'passed')
  assert.equal(report.commandExecuted, false)
  assert.ok(fs.existsSync(path.join(repoRoot, html)))
  assert.match(fs.readFileSync(path.join(repoRoot, html), 'utf8'), /N5/)
})

test('E2E 生产样式目标被拒绝，URL 不接受凭据或敏感查询参数', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('unsafe-e2e.json')
  const result = spawnSync(process.execPath, [path.join(frontendRoot, 'scripts', 'run-e2e.mjs'), '--probe-only', '--allow-pending', '--base-url', 'https://prod.example.invalid/?apiKey=do-not-log', '--output', output, '--html', rel('unsafe-e2e.html')], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  const report = readJson(path.join(repoRoot, output))
  assert.notEqual(report.status, 'passed')
  assert.doesNotMatch(JSON.stringify(report), /do-not-log/)
  assert.equal(report.status, 'blocked')
})

test('E2E 目标 URL 的 hash 片段也不得携带 token/key 等凭据', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('unsafe-e2e-fragment.json')
  const result = spawnSync(process.execPath, [path.join(frontendRoot, 'scripts', 'run-e2e.mjs'), '--probe-only', '--base-url', 'http://127.0.0.1:3000/#token=do-not-log', '--output', output, '--html', rel('unsafe-e2e-fragment.html')], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  const report = readJson(path.join(repoRoot, output))
  assert.equal(report.status, 'blocked')
  assert.doesNotMatch(JSON.stringify(report), /do-not-log/)
})

test('E2E 目标 URL 的编码 hash 凭据同样被拒绝', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('unsafe-e2e-encoded-fragment.json')
  const result = spawnSync(process.execPath, [
    path.join(frontendRoot, 'scripts', 'run-e2e.mjs'),
    '--probe-only', '--base-url', 'http://127.0.0.1:3000/#token%3Ddo-not-log',
    '--output', output, '--html', rel('unsafe-e2e-encoded-fragment.html')
  ], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  const report = readJson(path.join(repoRoot, output))
  assert.equal(report.status, 'blocked')
  assert.doesNotMatch(JSON.stringify(report), /do-not-log/)
})

test('质量报告支持 no-clobber，避免覆盖既有验收证据', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('no-clobber.json')
  runQualityCheck('lint', ['--probe-only', '--output', output])
  assert.throws(() => runQualityCheck('lint', ['--probe-only', '--output', output, '--no-clobber']), /输出已存在|EEXIST/)
})

test('quality:frontend no-clobber 在子检查启动前阻止覆盖', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('aggregate-no-clobber.json')
  fs.writeFileSync(path.join(repoRoot, output), 'keep-me\n', 'utf8')
  let calls = 0
  assert.throws(() => runFrontendQuality({
    output,
    noClobber: true,
    spawn() { calls += 1; return { status: 0, stdout: '', stderr: '' } }
  }), /输出已存在/)
  assert.equal(calls, 0)
  assert.equal(fs.readFileSync(path.join(repoRoot, output), 'utf8'), 'keep-me\n')
})

test('质量 wrapper 不读取浏览器存储或调用 npm/npx 安装依赖', () => {
  const files = ['quality-runner.mjs', 'run-quality-check.mjs', 'run-e2e.mjs']
  for (const name of files) {
    const source = fs.readFileSync(path.join(frontendRoot, 'scripts', name), 'utf8')
    assert.doesNotMatch(source, /(?:localStorage|sessionStorage)\s*\./)
    assert.doesNotMatch(source, /\b(?:npm|npx)\s+(?:install|ci|exec)\b/i)
  }
  const e2eSource = fs.readFileSync(path.join(frontendRoot, 'scripts', 'run-e2e.mjs'), 'utf8')
  assert.match(e2eSource, /report\.command[\s\S]*redactOutput/)
})

test('质量 wrapper 对未知参数和位置参数 fail-closed，不静默忽略', () => {
  assert.throws(() => runQualityCheck('lint', ['--typo-option']), /未知参数/)
  assert.throws(() => runQualityCheck('lint', ['--constructor=ignored']), /未知参数/)
  assert.throws(() => runQualityCheck('lint', ['unexpected-positional']), /位置参数/)
})

test('E2E wrapper 对未知参数 fail-closed，Playwright 参数必须显式放在 -- 后', () => {
  const script = path.join(frontendRoot, 'scripts', 'run-e2e.mjs')
  const unknown = spawnSync(process.execPath, [script, '--unknown-option', '--probe-only'], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(unknown.status, 1)
  assert.match(`${unknown.stdout}\n${unknown.stderr}`, /未知参数/)

  // A Playwright flag after the separator is intentionally passed through;
  // dependency absence still keeps the probe environment-pending.
  const passthrough = spawnSync(process.execPath, [script, '--probe-only', '--', '--grep', 'workbench'], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.ok([0, 2].includes(passthrough.status))

  const unsafePassthrough = spawnSync(process.execPath, [script, '--probe-only', '--', '--config=evil.config.js'], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(unsafePassthrough.status, 1)
  assert.match(`${unsafePassthrough.stdout}\n${unsafePassthrough.stderr}`, /禁止透传 Playwright 完整性参数/)

  for (const blockedFlag of ['-cevil.config.js', '--pass-with-no-tests=true', '--list=true', '--debug=true']) {
    const compact = spawnSync(process.execPath, [script, '--probe-only', '--', blockedFlag], {
      cwd: frontendRoot,
      encoding: 'utf8',
      windowsHide: true
    })
    assert.equal(compact.status, 1, `应拒绝完整性参数 ${blockedFlag}`)
    assert.match(`${compact.stdout}\n${compact.stderr}`, /禁止透传 Playwright 完整性参数/)
  }
})

test('Playwright 本地 webServer 与 loopback base URL 使用同一 host/port', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('ipv6-e2e.json')
  const result = spawnSync(process.execPath, [
    path.join(frontendRoot, 'scripts', 'run-e2e.mjs'),
    '--probe-only', '--base-url', 'http://[::1]:4173',
    '--output', output, '--html', rel('ipv6-e2e.html')
  ], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.ok([0, 2].includes(result.status))
  const report = readJson(path.join(repoRoot, output))
  assert.equal(report.target, 'local')
  assert.equal(report.baseUrl, 'http://[::1]:4173/')

  const config = fs.readFileSync(path.join(frontendRoot, 'playwright.config.js'), 'utf8')
  assert.match(config, /--host \$\{viteHost\} --port \$\{localPort\} --strictPort/)
  assert.match(config, /url: parsedBaseURL\.origin/)
  assert.doesNotMatch(config, /url:\s*['"]http:\/\/127\.0\.0\.1:3000['"]/)
})

test('E2E 执行保留已校验 URL，持久报告仅保存脱敏 endpoint', () => {
  const target = parseTarget({
    'base-url': 'http://127.0.0.1:4173/workbench?locale=zh-CN#result',
    target: 'local'
  })
  assert.equal(target.status, 'ready')
  assert.equal(target.executionBaseUrl, 'http://127.0.0.1:4173/workbench?locale=zh-CN#result')
  assert.equal(target.baseUrl, 'http://127.0.0.1:4173/workbench')
  assert.doesNotMatch(JSON.stringify({ baseUrl: target.baseUrl }), /locale|result/)
})

test('本地 E2E 自动服务器拒绝 HTTPS，避免配置启动 HTTP 却访问 HTTPS', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const output = rel('https-local-e2e.json')
  const result = spawnSync(process.execPath, [
    path.join(frontendRoot, 'scripts', 'run-e2e.mjs'),
    '--probe-only', '--base-url', 'https://127.0.0.1:3000',
    '--output', output, '--html', rel('https-local-e2e.html')
  ], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  const report = readJson(path.join(repoRoot, output))
  assert.equal(report.status, 'blocked')
  assert.equal(report.reason, 'local-target-must-use-http')
})

test('E2E JSON 与 HTML 产物路径不能互相覆盖', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const same = rel('same-output.json')
  const result = spawnSync(process.execPath, [
    path.join(frontendRoot, 'scripts', 'run-e2e.mjs'),
    '--probe-only', '--output', same, '--html', same
  ], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  assert.match(`${result.stdout}\n${result.stderr}`, /不能是同一文件/)
  assert.equal(fs.existsSync(path.join(repoRoot, same)), false)
})

test('E2E HTML 不能覆盖 JSON 派生日志文件', () => {
  fs.mkdirSync(testRoot, { recursive: true })
  const json = rel('collision.json')
  const html = rel('collision.log')
  const result = spawnSync(process.execPath, [
    path.join(frontendRoot, 'scripts', 'run-e2e.mjs'),
    '--probe-only', '--output', json, '--html', html
  ], {
    cwd: frontendRoot,
    encoding: 'utf8',
    windowsHide: true
  })
  assert.equal(result.status, 1)
  assert.match(`${result.stdout}\n${result.stderr}`, /不能复用同一文件/)
  assert.equal(fs.existsSync(path.join(repoRoot, json)), false)
  assert.equal(fs.existsSync(path.join(repoRoot, html)), false)
})

test('quality:frontend 在 pending 后继续执行全部检查，并保持聚合非零', () => {
  const output = rel('frontend-quality.json')
  const calls = []
  const statuses = [2, 2, 0, 0, 2, 0]
  const result = runFrontendQuality({
    output,
    spawn(executable, args) {
      calls.push([executable, ...args])
      const status = statuses[calls.length - 1]
      return { status, stdout: '', stderr: status === 2 ? 'environment-pending: dependency missing' : '' }
    }
  })
  assert.equal(result.code, 2)
  assert.equal(result.report.status, 'environment-pending')
  assert.equal(calls.length, 6)
  assert.deepEqual(result.report.checks.map(check => check.name),
    ['lint', 'typecheck', 'security:contract', 'test:contract', 'test:e2e', 'build'])
  assert.equal(readJson(path.join(repoRoot, output)).checks.length, 6)
})

test('聚合器不把裸 exit 2 当成可放行的 environment-pending', () => {
  assert.equal(classifyCheckResult({ exitCode: 2, output: 'type errors found' }), 'failed')
  assert.equal(classifyCheckResult({ exitCode: 2, output: 'environment-pending: package-not-installed' }), 'environment-pending')
})
