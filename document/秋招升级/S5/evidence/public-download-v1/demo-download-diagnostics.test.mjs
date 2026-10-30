import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {prepareDemoReference} from './prepare-demo-reference.mjs'

function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'demo-download-diagnostics-'))
 t.after(()=>{if(path.dirname(path.resolve(root))!==path.resolve(os.tmpdir())||!path.basename(root).startsWith('demo-download-diagnostics-'))throw Error('Unsafe temporary root');fs.rmSync(root,{recursive:true,force:true})})
 return {root,out:path.join(root,'artifacts','bundle')}
}
test('allowlisted nested TLS codes are reported for each fixed route without secrets or partial output',async t=>{
 const {root,out}=fixture(t);let calls=0
 await assert.rejects(prepareDemoReference(out,{root,fetcher:async()=>{calls++;throw new TypeError('private-token',{cause:Object.assign(Error('secret proxy address'),{code:'UNABLE_TO_VERIFY_LEAF_SIGNATURE'})})}}),error=>{
  assert.equal(error.report.requests,4);assert.equal(error.report.automaticRetries,0);assert.equal(error.report.destinationCreated,false)
  assert.equal(new Set(error.report.attempts.map(a=>a.url)).size,4)
  assert.ok(error.report.attempts.every(a=>a.status==='FAILED'&&a.code==='PUBLIC_RESOURCE_TRANSPORT_UNABLE_TO_VERIFY_LEAF_SIGNATURE'))
  assert.doesNotMatch(JSON.stringify(error.report)+error.message,/private-token|secret proxy/);return true
 })
 assert.equal(calls,4);assert.equal(fs.existsSync(out),false)
})
test('unknown all-caps messages and cause codes cannot enter public failure reports',async t=>{
 const {root,out}=fixture(t)
 await assert.rejects(prepareDemoReference(out,{root,fetcher:async()=>{throw Object.assign(Error('PRIVATE_CREDENTIAL'),{cause:{code:'SECRET_VALUE'}})}}),error=>{
  assert.ok(error.report.attempts.every(a=>a.code==='PUBLIC_RESOURCE_DOWNLOAD_FAILED'))
  assert.doesNotMatch(JSON.stringify(error.report)+error.message,/PRIVATE_CREDENTIAL|SECRET_VALUE/);return true
 })
 assert.equal(fs.existsSync(out),false)
})
test('aborts have a bounded timeout code without exposing transport messages',async t=>{
 const {root,out}=fixture(t)
 await assert.rejects(prepareDemoReference(out,{root,fetcher:async()=>{throw new DOMException('secret','AbortError')}}),error=>error.report.attempts.every(a=>a.code==='PUBLIC_RESOURCE_TIMEOUT'))
 assert.equal(fs.existsSync(out),false)
})
test('explicit serial mode sends each fixed request once with at most one active download',async t=>{
 const {root,out}=fixture(t);let active=0,maximum=0,calls=0
 await assert.rejects(prepareDemoReference(out,{root,serial:true,fetcher:async()=>{
  calls++;active++;maximum=Math.max(maximum,active)
  await new Promise(resolve=>setTimeout(resolve,5));active--;throw Object.assign(Error('private'),{code:'ECONNRESET'})
 }}),error=>error.report.concurrency===1&&error.report.automaticRetries===0&&error.report.attempts.length===4)
 assert.equal(calls,4);assert.equal(maximum,1);assert.equal(fs.existsSync(out),false)
})
test('invalid download modes fail before any transport or destination writes',async t=>{
 const {root,out}=fixture(t);let calls=0
 await assert.rejects(prepareDemoReference(out,{root,serial:'yes',fetcher:async()=>{calls++}}),/boolean/)
 assert.equal(calls,0);assert.equal(fs.existsSync(out),false)
})
