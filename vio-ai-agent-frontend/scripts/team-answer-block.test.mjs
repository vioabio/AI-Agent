import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTeamBlock } from '../src/api/team-answer-block.js'
import { createRunViewModel, reduceRunEvent } from '../src/api/run-contract.js'
const member = id => ({ schemaVersion: 'answer-block-v1', blockId: `pokemon-${id}`, kind: 'pokemon', formId: id, name: `成员${id}`, types: ['水'], stats: [44, 48, 65, 50, 64, 43], gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', evidenceDigest: `sha256:${'a'.repeat(64)}` })
const block = () => ({ schemaVersion: 'answer-block-v1', blockId: `team-${'b'.repeat(64)}`, kind: 'teamSuggestion', gameReleaseId: 'pokemon-scarlet-violet', dataReleaseId: 'pokeapi-reference-v1', scope: 'REFERENCE_MEMBERS_NOT_SAVED_TEAM', members: [member(7), member(25)], missing: ['尚未核对招式、特性、道具、性格、等级、EV/IV及太晶配置', '尚未验证资源可获得性、联合合法性、伤害或胜率；本卡片未保存队伍'] })
test('reference members remain immutable copies with explicit missing configuration', () => {
  const raw=block(), parsed=parseTeamBlock(raw);assert.ok(parsed)
  parsed.members[0].stats[0]=1;parsed.missing.pop()
  assert.equal(raw.members[0].stats[0],44);assert.equal(raw.missing.length,2)
})
test('invalid and authority-bearing team cards are rejected', () => {
  const raw=block()
  for(const bad of [{...raw,save:true},{...raw,scope:'SAVED'},{...raw,missing:[]},{...raw,members:[]},{...raw,members:Array(7).fill(member(7))},{...raw,members:[member(7),member(7)]},{...raw,members:[{...member(7),dataReleaseId:'other'}]},{...raw,members:[{...member(7),command:'save'}]}]) assert.equal(parseTeamBlock(bad),null)
})
test('team events deduplicate, enforce Run versions and preserve readable text on failure', () => {
  let model=createRunViewModel({runId:'r'})
  model=reduceRunEvent(model,{id:'1',type:'context.confirmed',data:{gameReleaseId:'pokemon-scarlet-violet',dataReleaseId:'pokeapi-reference-v1'}})
  model=reduceRunEvent(model,{id:'2',type:'answer.delta',data:{text:'策略正文'}})
  for(const id of ['3','4'])model=reduceRunEvent(model,{id,type:'team.matrix',data:{answerBlock:block()}})
  assert.equal(model.cards.teams.length,1)
  model=reduceRunEvent(model,{id:'5',type:'team.matrix',data:{answerBlock:{...block(),dataReleaseId:'wrong'}}})
  assert.equal(model.cards.teams.length,1);assert.equal(model.answer,'策略正文')
})
