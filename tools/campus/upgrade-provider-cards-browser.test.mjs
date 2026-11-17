import test from 'node:test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import {existsSync} from 'node:fs'
import {resolve} from 'node:path'

test('real-provider browser runner rejects personal backend and remote targets before creating evidence',()=>{
  const output='artifacts/cards-cli-guard-must-not-exist'
  assert.equal(existsSync(output),false)
  for (const api of ['http://127.0.0.1:8123/api','https://example.com/api']) {
    const result=spawnSync(process.execPath,[resolve('tools/campus/upgrade-provider-cards-browser.mjs'),api,output],{encoding:'utf8',timeout:10000})
    assert.notEqual(result.status,0)
    assert.equal(existsSync(output),false)
  }
})

test('real-provider browser runner rejects invalid mode and overwriting an existing artifact directory',()=>{
  const script=resolve('tools/campus/upgrade-provider-cards-browser.mjs')
  for (const [output,mode] of [['artifacts','enabled'],['artifacts/cards-cli-guard-must-not-exist','unknown']]) {
    const result=spawnSync(process.execPath,[script,'http://127.0.0.1:18124/api',output,mode],{encoding:'utf8',timeout:10000})
    assert.notEqual(result.status,0)
    assert.equal(result.error,undefined)
  }
})
test('browser runner rejects an invalid port before creating output',()=>{
  const output='artifacts/cards-cli-guard-must-not-exist'
  const result=spawnSync(process.execPath,[resolve('tools/campus/upgrade-provider-cards-browser.mjs'),'http://127.0.0.1:18124/api',output,'enabled','invalid'],{encoding:'utf8',timeout:10000})
  assert.notEqual(result.status,0);assert.equal(existsSync(output),false)
})
