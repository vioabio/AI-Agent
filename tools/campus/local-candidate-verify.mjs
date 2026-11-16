import assert from 'node:assert/strict'
import {readFileSync,realpathSync,statSync,existsSync,writeFileSync,readdirSync} from 'node:fs'
import {resolve,relative,isAbsolute} from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'

export function verifyLocalCandidate(directory) {
  const root=realpathSync(directory)
  const manifest=JSON.parse(readFileSync(resolve(root,'manifest.json'),'utf8'))
  assert.equal(manifest.schemaVersion,'vio-local-candidate-v1')
  assert.equal(manifest.status,'READY_FOR_LOCAL_REVIEW')
  assert.equal(manifest.humanApproval,'PENDING')
  assert.equal(manifest.repository,'https://github.com/vioabio/AI-Agent.git')
  assert.match(manifest.baseCommit,/^[a-f0-9]{40}$/)
  assert.equal(manifest.sourceState,'WORKTREE_PATCH_AND_ADDITIONS')
  assert.ok(Array.isArray(manifest.files)&&manifest.files.length>0)
  const paths=new Set()
  for(const file of manifest.files) {
    assert.ok(typeof file.path==='string'&&!isAbsolute(file.path)&&!file.path.includes('\\'))
    assert.ok(file.path.split('/').every(part=>part&&part!=='.'&&part!=='..'))
    assert.ok(!paths.has(file.path));paths.add(file.path)
    const actual=realpathSync(resolve(root,file.path)),inside=relative(root,actual)
    assert.ok(inside&&!isAbsolute(inside)&&inside!=='..'&&!inside.startsWith('../')&&!inside.startsWith('..\\'))
    assert.equal(statSync(actual).size,file.bytes)
    assert.match(file.sha256,/^[a-f0-9]{64}$/)
    assert.equal(createHash('sha256').update(readFileSync(actual)).digest('hex'),file.sha256,file.path)
  }
  for(const file of ['app.jar','frontend-dist/index.html','tools/pokemon-engine/calculate-v2.cjs','tools/pokemon-engine/package-lock.json','source/changes.patch','CANDIDATE.md'])assert.ok(paths.has(file),file)
  function inspect(directory) {
    for(const entry of readdirSync(directory,{withFileTypes:true})) {
      assert.ok(!entry.isSymbolicLink(),'Candidate cannot contain symbolic links')
      const full=resolve(directory,entry.name)
      if(entry.isDirectory())inspect(full)
      else assert.ok(full===resolve(root,'manifest.json')||paths.has(relative(root,full).replaceAll('\\','/')),'Unlisted candidate file')
    }
  }
  inspect(root)
  return {schemaVersion:'vio-local-candidate-verification-v1',candidate:manifest.candidate,status:'FILES_VERIFIED',
    files:paths.size,baseCommit:manifest.baseCommit,sourceState:manifest.sourceState,humanApproval:'PENDING'}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [directory,output]=process.argv.slice(2)
  assert.ok(directory&&output&&!existsSync(output))
  const result=verifyLocalCandidate(directory)
  writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify(result))
}
