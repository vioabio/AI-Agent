import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {localEvaluationSession} from './local-evaluation-session.mjs'

test('evaluation session rejects remote origins and unexpected base paths',()=>{
  for(const url of ['https://example.com/api','http://127.0.0.1/other','http://user:pass@localhost/api'])
    assert.throws(()=>localEvaluationSession(url))
})
test('evaluation requests reject unbounded or invalid timeouts before networking',async()=>{
  const session=localEvaluationSession('http://127.0.0.1:18125/api')
  for(const timeout of [0,-1,150001,NaN,1.5])await assert.rejects(session.api('/probe',undefined,200,timeout),{message:'BOUNDED_TIMEOUT_REQUIRED'})
})

test('isolated login retains cookies and CSRF internally and unwraps replies',async()=>{
  let registered=false,loggedOut=false,registrations=0,logins=0,registeredCredentials
  const server=createServer(async(request,response)=>{
    let body='';for await(const chunk of request)body+=chunk
    response.setHeader('Content-Type','application/json')
    if(request.url==='/api/auth/session'){
      response.setHeader('Set-Cookie',['CSRF-TOKEN=fixture%20nonce; Path=/','SESSION=fixture; Path=/'])
      return response.end(JSON.stringify({mode:'local'}))
    }
    assert.match(request.headers.cookie,/SESSION=fixture/)
    assert.equal(request.headers['x-csrf-token'],'fixture nonce')
    assert.equal(request.headers.origin,'http://localhost:3000')
    if(request.url==='/api/auth/local/register'){
      const data=JSON.parse(body);assert.match(data.username,/^u0_[a-f0-9]{16}$/);assert.ok(data.password.length>=32)
      registrations++;registeredCredentials=data
      registered=true;response.statusCode=201;return response.end('{}')
    }
    if(request.url==='/api/logout'){loggedOut=true;response.statusCode=204;return response.end()}
    if(request.url==='/api/auth/local/login'){assert.deepEqual(JSON.parse(body),registeredCredentials);logins++}
    response.end(JSON.stringify({code:0,data:{ok:true}}))
  })
  await new Promise(done=>server.listen(0,'127.0.0.1',done))
  try {
    const session=localEvaluationSession(`http://127.0.0.1:${server.address().port}/api`)
    await session.login('隔离测试')
    assert.equal(registered,true);assert.deepEqual(await session.api('/probe'),{ok:true})
    await session.reauthenticate();assert.equal(registrations,1);assert.equal(logins,2)
    assert.equal(JSON.stringify(session),'{}');await session.logout();assert.equal(loggedOut,true)
    await assert.rejects(session.reauthenticate(),{message:'REGISTERED_TEST_ACCOUNT_REQUIRED'})
  } finally {server.closeAllConnections();await new Promise(done=>server.close(done))}
})

test('HTTP errors expose the status without returning provider response text',async()=>{
  const server=createServer((request,response)=>{request.resume();response.statusCode=503;response.end('private response detail')})
  await new Promise(done=>server.listen(0,'127.0.0.1',done))
  try {
    const session=localEvaluationSession(`http://127.0.0.1:${server.address().port}/api`)
    await assert.rejects(session.api('/probe'),{message:'HTTP_503'})
  } finally {server.closeAllConnections();await new Promise(done=>server.close(done))}
})
