import test from 'node:test'
import assert from 'node:assert/strict'
import { trainingBlockToDraft } from '../src/api/training-draft.js'
const stats = n => Object.fromEntries(['hp','atk','def','spa','spd','spe'].map(k=>[k,n]))
const context = {gameReleaseId:'pokemon-scarlet-violet',rulesetVersionId:'casual-v1',dataReleaseId:'pokeapi-reference-v1'}
const block = () => ({schemaVersion:'answer-block-v1',blockId:`training-${'a'.repeat(64)}`,kind:'trainingPlan',teamId:91,revision:2,teamName:'测试队伍',...context,scope:'SAVED_BUILD_SNAPSHOT_NOT_TRAINING_ADVICE',members:[{slot:1,pokemon:{schemaVersion:'answer-block-v1',blockId:'pokemon-7',kind:'pokemon',formId:7,name:'杰尼龟',types:['水'],stats:[44,48,65,50,64,43],gameReleaseId:context.gameReleaseId,dataReleaseId:context.dataReleaseId,evidenceDigest:`sha256:${'b'.repeat(64)}`},build:{moves:['water-gun','protect'],ability:'torrent',item:'',nature:'modest',level:51,evs:stats(0),ivs:stats(31),teraType:'water'}}]})
test('snapshot becomes a new portable draft without source identifiers or authority',()=>{
  const raw=block(),draft=trainingBlockToDraft(raw,context)
  assert.deepEqual(Object.keys(draft).sort(),['dataReleaseId','members','name','rulesetId'])
  assert.deepEqual(Object.keys(draft.members[0]).sort(),['build','formId','slot'])
  assert.deepEqual(draft.members[0].build,raw.members[0].build)
  draft.members[0].build.moves.push('surf');draft.members[0].build.ivs.hp=0
  assert.equal(raw.members[0].build.moves.length,2);assert.equal(raw.members[0].build.ivs.hp,31)
})
test('all workspace version mismatches reject without changing input or context',()=>{
  const raw=block(),before=JSON.stringify(raw)
  for(const key of ['gameReleaseId','rulesetVersionId','dataReleaseId']){
    const changed={...context,[key]:'other'},saved=JSON.stringify(changed)
    assert.throws(()=>trainingBlockToDraft(raw,changed),/版本/)
    assert.equal(JSON.stringify(changed),saved)
  }
  assert.equal(JSON.stringify(raw),before)
})
test('incomplete and reference-only cards cannot introduce guessed defaults',()=>{
  const raw=block();delete raw.members[0].build.level
  assert.throws(()=>trainingBlockToDraft(raw,context),/不完整/)
  assert.throws(()=>trainingBlockToDraft({...block(),kind:'teamSuggestion'},context),/不完整/)
  assert.throws(()=>trainingBlockToDraft({...block(),save:true},context),/不完整/)
})
test('nonconsecutive slots and explicit missing optional tera remain intact',()=>{
  const raw=block();raw.members[0].slot=6;delete raw.members[0].build.teraType
  const draft=trainingBlockToDraft(raw,context)
  assert.equal(draft.members[0].slot,6);assert.equal(draft.members[0].build.teraType,undefined)
  assert.equal(draft.members[0].build.item,'')
})
