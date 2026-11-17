import test from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync,mkdirSync,writeFileSync,rmSync,realpathSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join,dirname,basename} from 'node:path'
import {createHash} from 'node:crypto'
import {verifyLocalCandidate} from './local-candidate-verify.mjs'

function fixture() {
  const temporaryRoot=realpathSync(tmpdir())
  const root=mkdtempSync(join(temporaryRoot,'vio-local-candidate-'))
  const files=['app.jar','frontend-dist/index.html','tools/pokemon-engine/calculate-v2.cjs','tools/pokemon-engine/package-lock.json','source/changes.patch','CANDIDATE.md']
  const manifest={schemaVersion:'vio-local-candidate-v1',candidate:'fixture',status:'READY_FOR_LOCAL_REVIEW',humanApproval:'PENDING',repository:'https://github.com/vioabio/AI-Agent.git',baseCommit:'a'.repeat(40),sourceState:'WORKTREE_PATCH_AND_ADDITIONS',files:[]}
  for(const path of files){mkdirSync(dirname(join(root,path)),{recursive:true});writeFileSync(join(root,path),'fixture');manifest.files.push({path,bytes:7,sha256:createHash('sha256').update('fixture').digest('hex')})}
  const save=()=>writeFileSync(join(root,'manifest.json'),JSON.stringify(manifest))
  save();return {root,manifest,save,cleanup:()=>{
    assert.equal(dirname(root),temporaryRoot)
    assert.ok(basename(root).startsWith('vio-local-candidate-'))
    rmSync(root,{recursive:true,force:true})
  }}
}
test('candidate verifies actual bytes and retains pending human review',()=>{
  const f=fixture();try{assert.equal(verifyLocalCandidate(f.root).status,'FILES_VERIFIED');assert.equal(verifyLocalCandidate(f.root).humanApproval,'PENDING')}finally{f.cleanup()}
})
test('tampering, duplicate paths, traversal and invented approval reject',()=>{
  for(const mutate of [f=>writeFileSync(join(f.root,'app.jar'),'changed'),f=>f.manifest.files.push(f.manifest.files[0]),f=>f.manifest.files[0].path='../outside',f=>f.manifest.humanApproval='APPROVED',f=>f.manifest.files.pop(),f=>writeFileSync(join(f.root,'unlisted.env'),'fixture')]) {
    const f=fixture();try{mutate(f);f.save();assert.throws(()=>verifyLocalCandidate(f.root))}finally{f.cleanup()}
  }
})
