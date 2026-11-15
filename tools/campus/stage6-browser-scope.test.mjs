import test from 'node:test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import {existsSync} from 'node:fs'
import {resolve} from 'node:path'

test('governance browser runner rejects unknown scope before creating an artifact directory',()=>{
  const directory='artifacts/governance-scope-must-not-exist'
  assert.equal(existsSync(directory),false)
  const result=spawnSync(process.execPath,[resolve('tools/campus/upgrade-stage6-conflict-account-browser.mjs'),directory,'http://127.0.0.1:18124/api','invalid'],{encoding:'utf8',timeout:10000})
  assert.notEqual(result.status,0);assert.equal(existsSync(directory),false)
})

test('termination browser runner rejects personal backends, invalid mode and outside evidence paths',()=>{
  const directory='artifacts/termination-guard-must-not-exist'
  const script=resolve('tools/campus/upgrade-stage6-termination-browser.mjs')
  for(const [output,api,mode] of [[directory,'http://127.0.0.1:8123/api','enabled'],
    [directory,'https://example.com/api','enabled'],[directory,'http://127.0.0.1:18124/api','unknown'],
    ['termination-outside-artifacts','http://127.0.0.1:18124/api','disabled']]) {
    assert.equal(existsSync(output),false)
    const result=spawnSync(process.execPath,[script,output,api,mode],{encoding:'utf8',timeout:10000})
    assert.notEqual(result.status,0);assert.equal(existsSync(output),false)
  }
})
