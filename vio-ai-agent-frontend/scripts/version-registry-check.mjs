import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const contractPath = path.resolve(frontendRoot, '../contracts/version-registry-v1.1.json')
const fixturePath = path.resolve(frontendRoot, '../data/v1.1-fixture/fixture.json')
const registryPath = path.resolve(frontendRoot, 'src/api/version-registry.js')
const failures = []

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch (error) {
    failures.push(`${path.relative(frontendRoot, file)} 无法读取/解析：${error.message}`)
    return null
  }
}

const contract = readJson(contractPath)
const fixture = readJson(fixturePath)
if (contract) {
  if (contract.schemaVersion !== 'v1.1') failures.push('registry schemaVersion 必须为 v1.1')
  if (contract.policy?.oneToOneAliases !== true) failures.push('registry 必须声明 oneToOneAliases=true')
  const canonical = new Set(contract.canonical?.battleFormatIds || [])
  if (!canonical.size) failures.push('canonical battleFormatIds 不能为空')
  if (canonical.size !== (contract.canonical?.battleFormatIds || []).length) failures.push('canonical battleFormatIds 不能重复')
  const aliases = contract.aliases?.battleFormatId || {}
  for (const [alias, id] of Object.entries(aliases)) {
    if (!alias.trim() || !id.trim()) failures.push('battleFormat alias/id 不能是空字符串')
    if (!canonical.has(id)) failures.push(`alias ${alias} 指向未登记 canonical ID ${id}`)
  }
  const targetCounts = new Map()
  Object.values(aliases).forEach(id => targetCounts.set(id, (targetCounts.get(id) || 0) + 1))
  for (const id of canonical) {
    if (!targetCounts.has(id)) failures.push(`canonical battleFormatId ${id} 没有任何显式 alias`)
    if (aliases[id] !== id) failures.push(`canonical battleFormatId ${id} 必须包含 identity alias`)
  }
}

try {
  const module = await import(pathToFileURL(registryPath).href)
  const runtimeAliases = module.BATTLE_FORMAT_ALIASES || {}
  const contractAliases = contract?.aliases?.battleFormatId || {}
  if (JSON.stringify(runtimeAliases) !== JSON.stringify(contractAliases)) {
    failures.push('前端运行时 BATTLE_FORMAT_ALIASES 与 contracts/version-registry-v1.1.json 不一致')
  }
  const options = module.BATTLE_FORMAT_OPTIONS || []
  const optionAliases = options.map(option => option.alias)
  if (new Set(optionAliases).size !== optionAliases.length) failures.push('前端赛制选项 alias 不能重复')
  for (const option of options) {
    if (runtimeAliases[option.alias] !== option.id) failures.push(`前端选项 ${option.alias} 未指向注册表目标`)
  }
  for (const alias of ['casual-singles', 'casual-doubles', 'singles', 'doubles']) {
    try {
      const resolved = module.resolveBattleFormat(alias)
      if (!resolved?.battleFormatId) failures.push(`alias ${alias} 解析结果为空`)
    } catch (error) {
      failures.push(`已登记 alias ${alias} 解析失败：${error.message}`)
    }
  }
  const runtimeGameAliases = module.GAME_RELEASE_ALIASES || {}
  const contractGameAliases = contract?.aliases?.gameReleaseId || {}
  if (JSON.stringify(runtimeGameAliases) !== JSON.stringify(contractGameAliases)) {
    failures.push('前端运行时 GAME_RELEASE_ALIASES 与 contracts/version-registry-v1.1.json 不一致')
  }
  for (const alias of ['pokemon-scarlet-violet', 'scarlet-violet', 'sv']) {
    try {
      const resolved = module.resolveGameRelease(alias)
      if (!resolved?.gameReleaseId) failures.push(`游戏版本 alias ${alias} 解析结果为空`)
    } catch (error) {
      failures.push(`已登记游戏版本 alias ${alias} 解析失败：${error.message}`)
    }
  }
  try {
    module.resolveBattleFormat('ranked-singles')
    failures.push('未登记 ranked-singles 不应被静默解析')
  } catch (error) {
    if (error.code !== 'UNREGISTERED_BATTLE_FORMAT_ALIAS') failures.push('未知 alias 未返回稳定拒绝码')
  }
  const fixtureFormatId = fixture?.canonicalVersions?.battleFormatId
  if (!fixtureFormatId) {
    failures.push('fixture canonicalVersions.battleFormatId 不能为空')
  } else {
    try {
      const resolved = module.resolveBattleFormat(fixtureFormatId)
      if (resolved.battleFormatId !== fixtureFormatId) {
        failures.push('fixture battleFormatId 必须直接使用 canonical ID，不能写 alias')
      }
    } catch (error) {
      failures.push(`fixture battleFormatId 未登记：${error.message}`)
    }
  }
} catch (error) {
  failures.push(`前端 registry 模块无法加载：${error.message}`)
}

if (failures.length) {
  console.error('Version registry check FAILED')
  failures.forEach(item => console.error(`- ${item}`))
  process.exitCode = 1
} else {
  console.log('Version registry check passed: aliases resolve one-to-one to frozen battle format IDs.')
}
