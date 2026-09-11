import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const repoRoot = path.resolve(frontendRoot, '..')
const apiSource = fs.readFileSync(path.join(frontendRoot, 'src', 'api', 'index.js'), 'utf8')
const workbenchSource = fs.readFileSync(path.join(frontendRoot, 'src', 'views', 'PokemonWorkbench.vue'), 'utf8')
const openapi = JSON.parse(fs.readFileSync(path.join(repoRoot, 'contracts', 'openapi-v1.1.json'), 'utf8'))

test('四类确定性计算 API 与 v1.1 OpenAPI 的请求/Result<T> 输出契约一致', () => {
  const contracts = [
    ['type-effectiveness', 'calculateTypeEffectiveness', 'TypeEffectivenessCalculationRequest', 'CalculationResult'],
    ['speed', 'calculateSpeed', 'SpeedCalculationRequest', 'CalculationResult'],
    ['damage', 'calculateDamage', 'DamageCalculationRequest', 'DamageCalculationResult'],
    ['legality', 'checkTeamLegality', 'LegalityCalculationRequest', 'LegalityCalculationResult']
  ]
  for (const [suffix, functionName, requestSchema, resultSchema] of contracts) {
    const endpoint = `/pokemon/calculations/${suffix}`
    const operation = openapi.paths[endpoint]?.post
    assert.ok(operation, `OpenAPI 必须登记 ${endpoint} 入口`)
    assert.equal(operation.requestBody.required, true)
    assert.equal(operation.requestBody.content['application/json'].schema.$ref,
      `#/components/schemas/${requestSchema}`)
    const responseSchema = operation.responses?.['200']?.content?.['application/json']?.schema
    assert.equal(responseSchema?.type, 'object', `${endpoint} 必须返回 Result<T> 对象`)
    assert.equal(responseSchema?.properties?.data?.$ref,
      `#/components/schemas/${resultSchema}`, `${endpoint} data 必须指向结果 DTO`)
    assert.match(apiSource, new RegExp(`export async function ${functionName}\\(`))
    assert.match(apiSource, new RegExp(endpoint.slice(1).replaceAll('/', '\\/')))
  }
  assert.match(apiSource, /credentials: 'include'/)
  assert.match(apiSource, /'Content-Type': 'application\/json'/)
  assert.match(apiSource, /return readJson\(response, '速度线计算失败'\)/)
  assert.match(apiSource, /return readJson\(response, '伤害计算失败'\)/)
  assert.match(apiSource, /return readJson\(response, '队伍合法性校验失败'\)/)
})

test('速度、伤害和合法性封装发送规范化请求并解包 Result<T>', async () => {
  const executableSource = apiSource.replace('import.meta.env.MODE', "'test'")
  const moduleUrl = `data:text/javascript;base64,${Buffer.from(executableSource).toString('base64')}`
  const api = await import(moduleUrl)
  const previousFetch = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return {
      ok: true,
      status: 200,
      json: async () => ({
        code: 0,
        message: 'success',
        data: { requestNumber: calls.length },
        timestamp: 1
      })
    }
  }
  try {
    assert.deepEqual(await api.calculateSpeed({
      dataReleaseId: ' data-v1 ', base: '100', level: '50', iv: '31', ev: '252', natureMultiplier: '1.1'
    }), { requestNumber: 1 })
    assert.deepEqual(await api.calculateDamage({
      dataReleaseId: 'data-v1', level: 50, power: 80, attack: 120, defense: 100, modifier: 1.5
    }), { requestNumber: 2 })
    assert.deepEqual(await api.checkTeamLegality({
      dataReleaseId: 'data-v1', rulesetVersionId: ' rules-v1 ',
      members: [{ formKey: ' pikachu ', moves: [' thunderbolt '] }]
    }), { requestNumber: 3 })

    assert.deepEqual(calls.map(call => call.url), [
      'http://localhost:8123/api/pokemon/calculations/speed',
      'http://localhost:8123/api/pokemon/calculations/damage',
      'http://localhost:8123/api/pokemon/calculations/legality'
    ])
    for (const call of calls) {
      assert.equal(call.options.method, 'POST')
      assert.equal(call.options.credentials, 'include')
      assert.equal(call.options.headers['Content-Type'], 'application/json')
    }
    assert.deepEqual(JSON.parse(calls[0].options.body), {
      dataReleaseId: 'data-v1', base: 100, level: 50, iv: 31, ev: 252, natureMultiplier: 1.1
    })
    assert.deepEqual(JSON.parse(calls[1].options.body), {
      dataReleaseId: 'data-v1', level: 50, power: 80, attack: 120, defense: 100, modifier: 1.5
    })
    assert.deepEqual(JSON.parse(calls[2].options.body), {
      dataReleaseId: 'data-v1', rulesetVersionId: 'rules-v1',
      members: [{ formKey: 'pikachu', moves: ['thunderbolt'] }]
    })
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('Result<T> 在 HTTP 200 但业务码失败时仍 fail-closed', async () => {
  const executableSource = apiSource.replace('import.meta.env.MODE', "'test'")
  const moduleUrl = `data:text/javascript;base64,${Buffer.from(executableSource).toString('base64')}`
  const api = await import(moduleUrl)
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ code: 403, message: '没有权限', data: null, timestamp: 1 })
  })
  try {
    await assert.rejects(() => api.fetchPokemonFacts({ dataReleaseId: 'data-v1' }), error =>
      error?.status === 403 && error?.code === 'API_RESULT_403' && /没有权限/.test(error.message))
  } finally {
    if (previousFetch === undefined) delete globalThis.fetch
    else globalThis.fetch = previousFetch
  }
})

test('工作台只展示服务端 CalculationCard，不复制属性表或在浏览器计算倍率', () => {
  assert.match(workbenchSource, /CalculationCard/)
  assert.match(workbenchSource, /runTypeCalculation/)
  assert.match(workbenchSource, /calculationResult\.value = result/)
  assert.match(workbenchSource, /calculationResult\.dataReleaseId/)
  assert.match(workbenchSource, /calculationResult\.calculationVersion/)
  assert.doesNotMatch(workbenchSource, /typeChart|\bCHART\b|multiplier\s*=/i)
})
