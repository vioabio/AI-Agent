import { parsePokemonBlock } from './answer-block.js'
import { normalizeTransferTeam } from './team-transfer.js'
const exact = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k))
export function parseTrainingBlock(value) {
  if (!exact(value, ['schemaVersion','blockId','kind','teamId','revision','teamName','gameReleaseId','dataReleaseId','rulesetVersionId','scope','members']) || value.schemaVersion !== 'answer-block-v1' || value.kind !== 'trainingPlan' || value.scope !== 'SAVED_BUILD_SNAPSHOT_NOT_TRAINING_ADVICE' || !/^training-[a-f0-9]{64}$/.test(value.blockId) || !Number.isSafeInteger(value.teamId) || value.teamId < 1 || !Number.isSafeInteger(value.revision) || value.revision < 0 || value.gameReleaseId !== 'pokemon-scarlet-violet' || value.dataReleaseId !== 'pokeapi-reference-v1' || !Array.isArray(value.members) || value.members.length < 1 || value.members.length > 6) return null
  try {
    const members = value.members.map(member => {
      if (!exact(member, ['slot','pokemon','build'])) throw Error()
      const pokemon = parsePokemonBlock(member.pokemon), build = member.build
      if (!pokemon || pokemon.gameReleaseId !== value.gameReleaseId || pokemon.dataReleaseId !== value.dataReleaseId || !build || !['moves','ability','item','nature','level','evs','ivs'].every(k => Object.hasOwn(build,k)) || typeof build.ability !== 'string' || typeof build.item !== 'string' || !exact(build.evs,['hp','atk','def','spa','spd','spe']) || !exact(build.ivs,['hp','atk','def','spa','spd','spe'])) throw Error()
      return { ...member, pokemon }
    })
    const normalized = normalizeTransferTeam({name:value.teamName,rulesetId:value.rulesetVersionId,dataReleaseId:value.dataReleaseId,members:members.map(m=>({slot:m.slot,formId:m.pokemon.formId,build:m.build}))})
    return {...value,members:members.map(m=>({...m,build:normalized.members.find(n=>n.slot===m.slot).build}))}
  } catch { return null }
}
