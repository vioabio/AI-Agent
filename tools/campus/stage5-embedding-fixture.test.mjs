import test from 'node:test'
import assert from 'node:assert/strict'
import {createEmbeddingFixture} from './stage5-embedding-fixture.mjs'
async function withFixture(work){
 const server=createEmbeddingFixture();await new Promise(done=>server.listen(0,'127.0.0.1',done))
 const base=`http://127.0.0.1:${server.address().port}`
 const request=(path,data)=>fetch(base+path,{method:data===undefined?'GET':'POST',...(data===undefined?{}:{headers:{'Content-Type':'application/json'},body:typeof data==='string'?data:JSON.stringify(data)})})
 try{await work(request)}finally{server.closeAllConnections();await new Promise(done=>server.close(done))}
}
test('controlled embedding fixture counts bounded batches and exposes dimension and provider failures',async()=>{
 await withFixture(async request=>{
  const payload={model:'fixture',input:['第一条','第二条']}
  const response=await (await request('/embeddings',payload)).json()
  assert.equal(response.data.length,2);assert.equal(response.data[0].embedding.length,2)
  assert.equal((await (await request('/status')).json()).requests,1)
  await request('/mode',{mode:'DIMENSION'})
  assert.equal((await (await request('/embeddings',payload)).json()).data[0].embedding.length,1)
  await request('/mode',{mode:'FAIL'});assert.equal((await request('/embeddings',payload)).status,503)
  await request('/mode',{mode:'READY'});assert.equal((await request('/embeddings',{model:'fixture',input:Array(9).fill('x')})).status,400)
 })
})
test('malformed fixture control requests are rejected without losing the local server',async()=>{
 await withFixture(async request=>{
  assert.equal((await request('/mode','invalid-json')).status,400)
  assert.equal((await request('/mode',{mode:'arbitrary-mode'})).status,400)
  assert.equal((await request('/status')).status,200)
 })
})
