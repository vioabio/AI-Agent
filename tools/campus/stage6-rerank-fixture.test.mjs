import test from 'node:test'
import assert from 'node:assert/strict'
import {createRerankFixture} from './stage6-rerank-fixture.mjs'
import {digest} from './evaluation.mjs'

async function fixture(check) {
  const server=createRerankFixture();await new Promise(done=>server.listen(0,'127.0.0.1',done))
  const base='http://127.0.0.1:'+server.address().port
  const post=(route,body,authorized=true)=>fetch(base+route,{method:'POST',headers:{'Content-Type':'application/json',...(authorized?{Authorization:'Bearer controlled-test-only'}:{})},body:JSON.stringify(body)})
  try{await check(base,post)}finally{server.closeAllConnections();await new Promise(done=>server.close(done))}
}
const input={model:'controlled-rerank-v1',query:'private-query',documents:['first source','second source'],top_n:2}
test('controlled reranker scores the supplied candidates and records only content digests',()=>fixture(async(base,post)=>{
  const r=await post('/rerank',input);assert.equal(r.status,200)
  assert.deepEqual((await r.json()).results,[{index:0,relevance_score:0.5},{index:1,relevance_score:1.5}])
  const status=await(await fetch(base+'/status')).json()
  assert.deepEqual(status.calls[0].documentHashes,input.documents.map(digest));assert.equal(status.requests,1)
  assert.ok(!JSON.stringify(status).includes('private-query'));assert.ok(!JSON.stringify(status).includes('first source'))
}))
test('controlled malformed, unavailable and redirect scenarios remain explicit',()=>fixture(async(base,post)=>{
  for(const mode of ['FAIL','DUPLICATE','MISSING','OUT_OF_RANGE','MALFORMED','REDIRECT']) {
    assert.equal((await post('/mode',{mode})).status,200)
    const r=await fetch(base+'/rerank',{method:'POST',redirect:'manual',headers:{Authorization:'Bearer controlled-test-only','Content-Type':'application/json'},body:JSON.stringify(input)})
    assert.equal(r.status,mode==='FAIL'?503:mode==='REDIRECT'?302:200)
  }
  assert.equal((await(await fetch(base+'/status')).json()).redirects,0)
}))
test('controlled provider rejects wrong authorization, missing candidates and invalid controls',()=>fixture(async(base,post)=>{
  assert.equal((await post('/rerank',input,false)).status,401)
  assert.equal((await post('/rerank',{...input,documents:[]})).status,400)
  assert.equal((await post('/rerank',{...input,top_n:1})).status,400)
  assert.equal((await post('/mode',{mode:'unknown'})).status,400)
  assert.equal((await(await fetch(base+'/status')).json()).requests,0)
}))
