import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTrainingBlock } from '../src/api/training-answer-block.js'
import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'
const values = n => Object.fromEntries(['hp','atk','def','spa','spd','spe'].map(key=>[key,n]))
const member = () => ({slot:1,pokemon:{schemaVersion:'answer-block-v1',blockId:'pokemon-7',kind:'pokemon',formId:7,name:'杰尼龟',types:['水'],stats:[44,48,65,50,64,43],gameReleaseId:'pokemon-scarlet-violet',dataReleaseId:'pokeapi-reference-v1',evidenceDigest:`sha256:${'a'.repeat(64)}`},build:{moves:['water-gun'],ability:'torrent',item:'',nature:'modest',level:51,evs:values(0),ivs:values(31),teraType:'water'}})
const block = () => ({schemaVersion:'answer-block-v1',blockId:`training-${'b'.repeat(64)}`,kind:'trainingPlan',teamId:91,revision:2,teamName:'培养测试队伍',gameReleaseId:'pokemon-scarlet-violet',dataReleaseId:'pokeapi-reference-v1',rulesetVersionId:'casual-v1',scope:'SAVED_BUILD_SNAPSHOT_NOT_TRAINING_ADVICE',members:[member()]})
test('saved settings preserve tera, empty item and arrays without introducing defaults',()=>{
  const raw=block(),parsed=parseTrainingBlock(raw);assert.ok(parsed)
  assert.equal(parsed.members[0].build.teraType,'water');assert.equal(parsed.members[0].build.item,'')
  parsed.members[0].build.moves.push('protect');parsed.members[0].build.ivs.hp=0
  assert.equal(raw.members[0].build.moves.length,1);assert.equal(raw.members[0].build.ivs.hp,31)
})
test('incomplete saved settings, unknown actions and impossible values cannot render',()=>{
  for(const key of ['moves','ability','item','nature','level','evs','ivs']){
    const raw=block();delete raw.members[0].build[key];assert.equal(parseTrainingBlock(raw),null,key)
  }
  for(const mutate of [v=>v.save=true,v=>v.scope='APPROVED',v=>v.members.push(member()),v=>v.members[0].build.evs=values(252),v=>v.members[0].build.ivs.hp=32,v=>v.members[0].build.teraType='unknown',v=>v.members[0].build.command='save',v=>v.members[0].build.evs.extra=1,v=>v.members[0].build.ability=null,v=>v.members[0].pokemon.dataReleaseId='other']){
    const raw=block();mutate(raw);assert.equal(parseTrainingBlock(raw),null)
  }
})
test('snapshot events deduplicate and reject frozen-rule mismatch without losing text',()=>{
  let model=createRunViewModel({runId:'r'})
  model=reduceRunEvent(model,{id:'1',type:'context.confirmed',data:{gameReleaseId:'pokemon-scarlet-violet',dataReleaseId:'pokeapi-reference-v1',rulesetVersionId:'casual-v1'}})
  model=reduceRunEvent(model,{id:'2',type:'answer.delta',data:{text:'原回答'}})
  for(const id of ['3','4'])model=reduceRunEvent(model,{id,type:'team.matrix',data:{answerBlock:block()}})
  assert.equal(model.cards.teams.length,1)
  model=reduceRunEvent(model,{id:'5',type:'team.matrix',data:{answerBlock:{...block(),rulesetVersionId:'other'}}})
  assert.equal(model.cards.teams.length,1);assert.equal(model.answer,'原回答')
})
