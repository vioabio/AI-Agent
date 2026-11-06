import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const bash=process.platform==='win32'
  ? path.join(process.env.ProgramFiles || 'C:\\Program Files','Git','bin','bash.exe') : 'bash'

function run({up=0, probe=0, diagnostic=0, down=0}={}) {
  // A shell function substitutes Docker; these tests never start containers.
  const script=`
docker() {
  echo "DOCKER $*"
  case "$*" in
    'compose up -d --build') return ${up} ;;
    'compose ps -a') return ${diagnostic} ;;
    'compose logs --no-color --tail=200 vio-ai-agent postgres') return ${diagnostic} ;;
    'compose ps -a -q vio-ai-agent') echo test-container; return 0 ;;
    'compose down -v') return ${down} ;;
  esac
}
source ops/ci/compose-smoke-cleanup.sh
docker compose up -d --build
(exit ${probe})
echo SMOKE_FINISHED
`
  const result=spawnSync(bash,['-e','-c',script],{cwd:root,encoding:'utf8'})
  assert.ifError(result.error)
  return result
}

test('startup failure prints diagnostics before teardown and preserves its status',()=>{
  const result=run({up:17})
  assert.equal(result.status,17)
  assert.match(result.stdout,/DOCKER compose logs/)
  assert.match(result.stdout,/DOCKER inspect/)
  assert.ok(result.stdout.indexOf('DOCKER compose logs')<result.stdout.indexOf('DOCKER compose down'))
  assert.doesNotMatch(result.stdout,/SMOKE_FINISHED/)
})
test('probe failure remains a failure even when diagnostics also fail',()=>{
  const result=run({probe:22,diagnostic:9})
  assert.equal(result.status,22)
  assert.match(result.stdout,/DOCKER compose down -v/)
})
test('successful smoke cleans up without error diagnostics; failed cleanup is visible',()=>{
  const success=run()
  assert.equal(success.status,0)
  assert.match(success.stdout,/SMOKE_FINISHED/)
  assert.match(success.stdout,/DOCKER compose down -v/)
  assert.doesNotMatch(success.stdout,/DOCKER compose logs/)
  assert.equal(run({down:19}).status,19)
})
test('both workflows register the cleanup before starting Compose',()=>{
  for(const file of ['ci.yml','deploy.yml']) {
    const workflow=fs.readFileSync(path.join(root,'.github/workflows',file),'utf8')
    const setup=workflow.indexOf('source ops/ci/compose-smoke-cleanup.sh')
    assert.ok(setup>=0 && setup<workflow.indexOf('docker compose up -d --build'),file)
  }
})
