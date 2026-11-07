import test from 'node:test'
import assert from 'node:assert/strict'
import {downloadViaProxy} from './download-demo-via-proxy.mjs'
const proxy='http://127.0.0.1:7897'
test('proxy child receives only explicit public transport settings and no inherited secret or TLS bypass',()=>{
 let calls=0
 const result=downloadViaProxy('artifacts/new-bundle',{proxy,serial:true,proxySupported:true,environment:{PATH:'public',HTTPS_PROXY:'http://secret',NO_PROXY:'*',NODE_OPTIONS:'--tls-min-v1.0',NODE_TLS_REJECT_UNAUTHORIZED:'0',MODEL_API_KEY:'secret'},run:(file,args,options)=>{
  calls++;assert.equal(file,process.execPath);assert.equal(args[0],'--use-env-proxy');assert.ok(args[1].endsWith('prepare-demo-reference.mjs'))
  assert.deepEqual(args.slice(2),['artifacts/new-bundle','--serial','--timeout-ms','15000'])
  assert.deepEqual(options.env,{PATH:'public',HTTPS_PROXY:proxy,HTTP_PROXY:proxy,NO_PROXY:'',ALL_PROXY:''})
  assert.equal(options.shell,false);assert.equal(options.windowsHide,true)
  return {status:0,stdout:JSON.stringify({schemaVersion:'demo-reference-preparation-v1',ready:true,requests:4}),stderr:''}
 }})
 assert.equal(calls,1);assert.equal(result.ready,true);assert.equal(result.transport,'EXPLICIT_LOOPBACK_HTTP_PROXY')
})
test('remote credential-bearing or malformed proxies are rejected before child execution',()=>{
 let calls=0
 for(const value of ['http://example.com:7897','http://user:secret@127.0.0.1:7897','https://127.0.0.1:7897','http://127.0.0.1','http://127.0.0.1:7897/path','http://127.0.0.1:7897/?secret=yes','bad']){
  assert.throws(()=>downloadViaProxy('artifacts/new',{proxy:value,proxySupported:true,run:()=>calls++}),/LOOPBACK_PROXY_REQUIRED/)
 }
 assert.equal(calls,0)
})
test('unsupported Node or invalid budgets fail without a network subprocess',()=>{
 assert.throws(()=>downloadViaProxy('artifacts/new',{proxy,proxySupported:false}),/UNSUPPORTED/)
 for(const requestTimeoutMs of [0,60001,'15000',Infinity])assert.throws(()=>downloadViaProxy('artifacts/new',{proxy,proxySupported:true,requestTimeoutMs}),/INVALID/)
})
test('child failures retain sanitized fixed-route diagnostics and never expose stderr text',()=>{
 const failed=downloadViaProxy('artifacts/new',{proxy,proxySupported:true,run:()=>({status:1,stdout:'',stderr:JSON.stringify({schemaVersion:'demo-reference-download-failure-v1',ready:false,attempts:[]})})})
 assert.equal(failed.ready,false);assert.deepEqual(failed.attempts,[])
 const crashed=downloadViaProxy('artifacts/new',{proxy,proxySupported:true,run:()=>({status:1,stderr:'private credentials',error:Error('secret')})})
 assert.equal(crashed.code,'PROXY_DOWNLOAD_PROCESS_FAILED');assert.doesNotMatch(JSON.stringify(crashed),/credentials|secret/)
})
