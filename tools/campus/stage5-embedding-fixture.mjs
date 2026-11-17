import {createServer} from 'node:http'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
export function createEmbeddingFixture() {
let mode='READY',requests=0
const batches=[]
const server=createServer(async(req,res)=>{
 let bytes=0,body='';for await(const chunk of req){bytes+=chunk.length;if(bytes>200000){res.writeHead(413).end();return}body+=chunk}
 const reply=(status,value)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value))}
 if(req.url==='/status'&&req.method==='GET')return reply(200,{fixture:'stage5-embedding-controlled-v1',mode,requests,batches})
 if(req.url==='/mode'&&req.method==='POST'){
  let next;try{next=JSON.parse(body).mode}catch{return reply(400,{})}
  if(!['READY','FAIL','DIMENSION','SHUTDOWN'].includes(next))return reply(400,{})
  mode=next;reply(200,{mode});if(mode==='SHUTDOWN')server.close();return
 }
 if(req.url!=='/embeddings'||req.method!=='POST')return reply(404,{})
 requests++
 let input;try{input=JSON.parse(body)}catch{return reply(400,{})}
 if(!Array.isArray(input.input)||input.input.length<1||input.input.length>8||input.input.some(t=>typeof t!=='string'||t.length>4000))return reply(400,{})
 batches.push(input.input.length);if(batches.length>1000)return reply(429,{})
 if(mode==='FAIL')return reply(503,{error:'CONTROLLED_PROVIDER_FAILURE'})
 return reply(200,{model:input.model,data:input.input.map((_,index)=>({index,embedding:mode==='DIMENSION'?[1]:[1,0]}))})
})
return server
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))createEmbeddingFixture().listen(4333,'127.0.0.1',()=>console.log('Controlled embedding fixture ready on 4333'))
