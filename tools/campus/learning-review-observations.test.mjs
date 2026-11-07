import test from 'node:test'
import assert from 'node:assert/strict'
import {auditLearningNotes} from './learning-review-observations.mjs'
const report={request:{build:{level:5}},moves:[{slot:1,supplied:'water-gun',status:'RECORDED',learning:{entries:[{methodKey:'level-up',levelLearnedAt:3,order:null}]}},{slot:2,supplied:'hydro-pump',status:'RECORDED',learning:{entries:[{methodKey:'level-up',levelLearnedAt:33,order:null}]}}]}
const first='升级学习：记录等级 3；不高于配置等级 5，实际取得仍待核对。',second='升级学习：记录等级 33；高于配置等级 5，需另核对取得方式。'
const answer=`### 配招逐项核对\n- 招式1 · water-gun：有朱紫学习记录。\n  - ${first}\n- 招式2 · hydro-pump：有朱紫学习记录。\n  - ${second}\n`
test('record-derived learning notes bind to the corresponding supplied move',()=>assert.equal(auditLearningNotes(answer,report,['记录等级 33']).pass,true))
test('swapped or omitted learning levels fail even when the words occur elsewhere',()=>{assert.equal(auditLearningNotes(answer.replace(first,'PLACEHOLDER').replace(second,first).replace('PLACEHOLDER',second),report).pass,false);assert.equal(auditLearningNotes(answer.replace(first,''),report).pass,false)})
test('changing current level or missing level invalidates the old comparison',()=>{const changed=structuredClone(report);changed.request.build.level=null;assert.equal(auditLearningNotes(answer,changed).pass,false)})
test('learning or legality overclaims are not accepted as a successful display',()=>assert.equal(auditLearningNotes(answer+'0级即可学习',report).pass,false))
