import {validLearningEntries} from './build-learning.js'
export const machineReferenceScope='MEMBER_MACHINE_RECORD_AND_PINNED_SV_TM_NOT_ACQUISITION_OR_LEGALITY'
export const machineReferenceLabels={MEMBER_MACHINE_NOT_RECORDED:'该成员快照未记录学习器途径，不等于不能学习。',NOT_CATALOGUED:'成员有学习器记录，但本批编号资料未收录。',REFERENCE_UNAVAILABLE:'成员学习记录暂不可用，本次未取得编号。'}
export const machineLearningIdentity=l=>JSON.stringify([l.pokemonId,l.moveSlug,l.dataReleaseId,l.gameReleaseId,l.sourceChecksum,l.projectionChecksum])
const rowsIdentity=rows=>JSON.stringify(rows.map(e=>[e.methodKey,e.levelLearnedAt,e.order]))
export function normalizeMachineReference(raw,expected){
  const fail=()=>{throw Error('学习器结果与当前学习记录不一致，请重新检查配招')}
  if(!raw||raw.schemaVersion!=='pokemon-machine-reference-v1'||raw.scope!==machineReferenceScope||!['MATCHED',...Object.keys(machineReferenceLabels)].includes(raw.status)||!/^sha256:[a-f0-9]{64}$/.test(raw.reportSha256||''))fail()
  for(const key of ['pokemonId','moveSlug','dataReleaseId','gameReleaseId'])if(raw[key]!==expected[key])fail()
  if(raw.learning&&(!validLearningEntries(raw.learning.entries)||machineLearningIdentity(raw.learning)!==machineLearningIdentity(expected)||rowsIdentity(raw.learning.entries)!==rowsIdentity(expected.entries)))fail()
  if(raw.status==='REFERENCE_UNAVAILABLE'){if(raw.learning||raw.machine)fail();return structuredClone(raw)}
  if(!raw.learning)fail()
  const memberMachine=raw.learning.entries?.some(e=>e.methodKey==='machine')===true
  if(raw.status==='MEMBER_MACHINE_NOT_RECORDED'){if(memberMachine||raw.machine)fail();return structuredClone(raw)}
  if(!memberMachine)fail()
  if(raw.status==='NOT_CATALOGUED'){if(raw.machine)fail();return structuredClone(raw)}
  const m=raw.machine
  if(!m||!Number.isSafeInteger(m.machineId)||m.machineId<1||!/^tm\d{2,3}$/.test(m.itemSlug||'')||m.label!==`TM${String(Number(m.itemSlug.slice(2))).padStart(3,'0')}`||
    m.moveUrl!==`https://pokeapi.co/api/v2/move/${expected.moveSlug}/`||m.machineUrl!==`https://pokeapi.co/api/v2/machine/${m.machineId}/`||m.itemUrl!==`https://pokeapi.co/api/v2/item/${m.itemSlug}/`)fail()
  for(const key of ['moveChecksum','machineChecksum','itemChecksum'])if(!/^sha256:[a-f0-9]{64}$/.test(m[key]||''))fail()
  return structuredClone(raw)
}
