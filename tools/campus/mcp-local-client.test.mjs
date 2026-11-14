import test from 'node:test'
import assert from 'node:assert/strict'
import {LocalMcpClient} from './mcp-local-client.mjs'
import {McpResponseWindow} from './mcp-response-window.mjs'

function client(){
 const c=Object.create(LocalMcpClient.prototype)
 Object.assign(c,{next:1,pending:new Map(),received:new McpResponseWindow(),buffer:'',closed:false,
  observation:{invalidFrames:0,duplicateResponses:0},child:{kill(){c.killed=true}}})
 return c
}
test('duplicate reply rejects remaining work and closes the connection',()=>{
 const c=client();let resolved=0,rejected
 c.pending.set('1',{resolve(){resolved++},reject(){}})
 c.consume('{"jsonrpc":"2.0","id":1,"result":{}}\n')
 assert.equal(resolved,1)
 c.pending.set('2',{reject(error){rejected=error.message}})
 c.consume('{"jsonrpc":"2.0","id":1,"result":{}}\n')
 assert.equal(rejected,'MCP_DUPLICATE_RESPONSE');assert.equal(c.pending.size,0)
 assert.equal(c.closed,true);assert.equal(c.killed,true);assert.equal(c.observation.duplicateResponses,1)
})
test('unsolicited ID closes the connection without resolving unrelated work',()=>{
 const c=client();let rejected
 c.pending.set('1',{reject(error){rejected=error.message},resolve(){assert.fail('Unrelated reply accepted')}})
 c.consume('{"jsonrpc":"2.0","id":2,"result":{}}\n')
 assert.equal(rejected,'MCP_UNEXPECTED_RESPONSE');assert.equal(c.killed,true)
 assert.equal(c.observation.invalidFrames,1)
})
test('client pending limit and safe integer exhaustion reject before sending',async()=>{
 const c=client();c.pending=new Map(Array.from({length:128},(_,i)=>[i,{}]))
 await assert.rejects(c.request('ping'),/MCP_CLIENT_BUSY/)
 assert.equal(c.next,1)
 c.pending.clear();c.next=Number.MAX_SAFE_INTEGER
 await assert.rejects(c.request('ping'),/MCP_REQUEST_ID_EXHAUSTED/)
 assert.equal(c.pending.size,0)
})
