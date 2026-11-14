import test from 'node:test'
import assert from 'node:assert/strict'
import {McpResponseWindow} from './mcp-response-window.mjs'

test('100000 sequential replies retain a fixed window and detect recent duplicates',()=>{
 const window=new McpResponseWindow(64)
 for(let id=1;id<=100000;id++)window.observe(id,id)
 assert.equal(window.size,64);assert.equal(window.slots.byteLength,512)
 assert.throws(()=>window.observe(100000,100000),/MCP_DUPLICATE_RESPONSE/)
})
test('out of order replies within the active window are accepted exactly once',()=>{
 const window=new McpResponseWindow(16)
 for(const id of [4,2,1,3])window.observe(id,4)
 assert.equal(window.size,4)
 assert.throws(()=>window.observe(2,4),/MCP_DUPLICATE_RESPONSE/)
})
test('expired responses fail closed after wraparound',()=>{
 const window=new McpResponseWindow(16)
 window.observe(1,1);window.observe(17,17)
 assert.throws(()=>window.observe(1,17),/MCP_UNEXPECTED_RESPONSE/)
 window.observe(2,17)
})
test('unsolicited, typed or unsafe IDs cannot masquerade as issued replies',()=>{
 for(const id of [null,undefined,'1',0,-1,2,1.5,Number.MAX_SAFE_INTEGER+1]){
  assert.throws(()=>new McpResponseWindow().observe(id,1),/MCP_UNEXPECTED_RESPONSE/)
 }
})
test('window limits cannot allocate arbitrary memory',()=>{
 for(const size of [0,15,65537,Infinity,1.5])assert.throws(()=>new McpResponseWindow(size),/MCP_WINDOW_INVALID/)
})
