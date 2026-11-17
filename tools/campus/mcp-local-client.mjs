import {spawn} from 'node:child_process'
import {setTimeout as delay} from 'node:timers/promises'
import {McpResponseWindow} from './mcp-response-window.mjs'

// Local STDIO only. Credentials and stderr contents are deliberately not returned to callers.
export class LocalMcpClient {
 constructor(config,{timeoutMs=15000}={}) {
  if(!config?.command||config.args?.length!==3||config.args[0]!=='-cp'||config.args[2]!=='com.vio.vioaiagent.mcp.PokemonMcpServerMain')throw Error('MCP_CONFIG_INVALID')
  this.timeoutMs=timeoutMs;this.pending=new Map();this.next=0;this.buffer='';this.received=new McpResponseWindow();this.closed=false
  this.observation={invalidFrames:0,duplicateResponses:0,stderrBytes:0,forcedTermination:false}
  this.child=spawn(config.command,config.args,{env:{...process.env,...config.env},stdio:['pipe','pipe','pipe'],windowsHide:true})
  this.child.stdout.setEncoding('utf8')
  this.child.stdout.on('data',chunk=>this.consume(chunk))
  this.child.stderr.on('data',chunk=>{this.observation.stderrBytes+=chunk.length})
  this.child.stdin.on('error',()=>this.fail(Error('MCP_INPUT_CLOSED')))
  this.exited=new Promise(resolve=>{
   this.child.once('error',()=>{this.closed=true;this.fail(Error('MCP_START_FAILED'));resolve(null)})
   this.child.once('exit',()=>{this.closed=true;this.fail(Error('MCP_PROCESS_EXITED'))})
   this.child.once('close',code=>resolve(code))
  })
 }
 consume(chunk) {
  this.buffer+=chunk
  if(Buffer.byteLength(this.buffer)>1048576){this.fail(Error('MCP_OUTPUT_LIMIT'));this.child.kill();return}
  for(let index;(index=this.buffer.indexOf('\n'))>=0;){
   const line=this.buffer.slice(0,index);this.buffer=this.buffer.slice(index+1)
   let message
   try{message=JSON.parse(line);if(message.jsonrpc!=='2.0')throw Error()}catch{this.observation.invalidFrames++;this.fail(Error('MCP_INVALID_FRAME'));this.child.kill();return}
   if(!Object.hasOwn(message,'id'))continue
   const key=JSON.stringify(message.id)
   try{this.received.observe(message.id,this.next)}catch(error){
    if(error.message==='MCP_DUPLICATE_RESPONSE')this.observation.duplicateResponses++
    else this.observation.invalidFrames++
    this.fail(error);this.closed=true;this.child.kill();return
   }
   const pending=this.pending.get(key)
   if(pending){clearTimeout(pending.timer);this.pending.delete(key);pending.resolve(message)}
  }
 }
 fail(error){for(const item of this.pending.values()){clearTimeout(item.timer);item.reject(error)}this.pending.clear()}
 send(message){if(this.closed)throw Error('MCP_PROCESS_CLOSED');this.child.stdin.write(JSON.stringify(message)+'\n')}
 request(method,params={}){
  if(this.pending.size>=128)return Promise.reject(Error('MCP_CLIENT_BUSY'))
  if(!Number.isSafeInteger(this.next+1))return Promise.reject(Error('MCP_REQUEST_ID_EXHAUSTED'))
  const id=++this.next,key=JSON.stringify(id)
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{this.pending.delete(key);try{this.send({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId:id}})}catch{}reject(Error('MCP_REQUEST_TIMEOUT'))},this.timeoutMs)
   this.pending.set(key,{resolve,reject,timer})
   try{this.send({jsonrpc:'2.0',id,method,params})}catch(error){clearTimeout(timer);this.pending.delete(key);reject(error)}
  })
 }
 async initialize(){
  const reply=await this.request('initialize',{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'vio-http-equivalence',version:'1'}})
  if(reply.result?.protocolVersion!=='2024-11-05')throw Error('MCP_INITIALIZE_FAILED')
  this.send({jsonrpc:'2.0',method:'notifications/initialized'})
  const tools=await this.request('tools/list')
  if(!Array.isArray(tools.result?.tools))throw Error('MCP_DISCOVERY_FAILED')
  return tools.result.tools.map(item=>item.name).sort()
 }
 async call(name,args){return this.request('tools/call',{name,arguments:args})}
 async close(){
  if(!this.closed)this.child.stdin.end()
  let timer
  const exited=await Promise.race([this.exited.then(()=>true),new Promise(resolve=>{timer=setTimeout(()=>resolve(false),3000)})]);clearTimeout(timer)
  if(!exited){this.observation.forcedTermination=true;this.child.kill();await Promise.race([this.exited,delay(2000)])}
  if(this.buffer.trim())this.observation.invalidFrames++
  return this.observation
 }
}
