import {createServer} from 'node:http'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'

/** Synthetic local scores exercise transport and fallback. They do not measure relevance. */
export function createRerankFixture() {
  let mode='READY',requests=0,redirects=0
  const calls=[]
  const server=createServer(async(request,response)=>{
    let body='',size=0
    for await(const chunk of request){size+=chunk.length;if(size>2_000_000){response.writeHead(413).end();return}body+=chunk}
    const reply=(status,value)=>{response.writeHead(status,{'Content-Type':'application/json'});response.end(JSON.stringify(value))}
    if(request.url==='/status'&&request.method==='GET')return reply(200,{fixture:'u6-controlled-rerank-v1',mode,requests,redirects,calls})
    if(request.url==='/mode'&&request.method==='POST') {
      let next;try{next=JSON.parse(body).mode}catch{return reply(400,{})}
      if(!['READY','FAIL','DUPLICATE','MISSING','OUT_OF_RANGE','MALFORMED','TIMEOUT','OVERSIZED','REDIRECT','SHUTDOWN'].includes(next))return reply(400,{})
      mode=next;reply(200,{mode});if(mode==='SHUTDOWN')server.close();return
    }
    if(request.url==='/redirect-target'){redirects++;return reply(500,{})}
    if(request.url!=='/rerank'||request.method!=='POST')return reply(404,{})
    if(request.headers.authorization!=='Bearer controlled-test-only')return reply(401,{})
    let input;try{input=JSON.parse(body)}catch{return reply(400,{})}
    if(input.model!=='controlled-rerank-v1'||typeof input.query!=='string'||input.query.length>4000
        ||!Array.isArray(input.documents)||!input.documents.length||input.documents.length>50
        ||input.documents.some(doc=>typeof doc!=='string'||doc.length>20000)||input.top_n!==input.documents.length)return reply(400,{})
    if(requests>=1000)return reply(429,{})
    requests++;calls.push({mode,querySha256:digest(input.query),documentHashes:input.documents.map(digest),model:input.model,topN:input.top_n})
    if(mode==='FAIL')return reply(503,{error:'CONTROLLED_PRIVATE_ERROR'})
    if(mode==='REDIRECT'){response.writeHead(302,{Location:'/redirect-target'}).end();return}
    if(mode==='OVERSIZED'){response.writeHead(200,{'Content-Type':'application/json'}).end('x'.repeat(2_000_001));return}
    if(mode==='TIMEOUT'){await new Promise(done=>setTimeout(done,2000));if(response.destroyed)return}
    if(mode==='MALFORMED')return reply(200,{results:'invalid'})
    let results=input.documents.map((_,index)=>({index:mode==='DUPLICATE'?0:index,relevance_score:index+0.5}))
    if(mode==='DUPLICATE'&&results.length===1)results.push({...results[0]})
    if(mode==='MISSING')results=results.slice(0,-1)
    if(mode==='OUT_OF_RANGE')results.at(-1).index=results.length
    reply(200,{results})
  })
  return server
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))
  createRerankFixture().listen(4334,'127.0.0.1',()=>console.log('Controlled rerank fixture ready on 4334'))
