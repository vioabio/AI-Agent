import fs from 'node:fs'
import {randomBytes} from 'node:crypto'
const output='artifacts/campus/request-validation/invalid-input-v1.json'
if(fs.existsSync(output))throw Error('Fresh report required')
const base='http://127.0.0.1:18259/api',cookies=new Map(),checks={}
async function api(route,body,expected=200){
 const response=await fetch(base+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{Origin:'http://localhost:3000','Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(cookies.get('CSRF-TOKEN')?{'X-CSRF-TOKEN':cookies.get('CSRF-TOKEN')}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 for(const line of response.headers.getSetCookie()){const[k,...v]=line.split(';')[0].split('=');cookies.set(k,v.join('='))}
 if(response.status!==expected)throw Error('Unexpected HTTP '+response.status+' at '+route)
 return response.json()
}
try{
 if((await api('/auth/session')).mode!=='local')throw Error('Local mode required')
 const username='validation_'+randomBytes(5).toString('hex')
 for(const [name,body] of [['missingFields',{}],['shortPassword',{username,password:'short'}],['utf8ByteLimit',{username,password:'密'.repeat(25)}]]){
  const result=await api('/auth/local/register',body,400)
  if(typeof result.message!=='string'||result.message.includes('short')||result.message.includes('密'.repeat(25)))throw Error('Invalid error response')
  if((await api('/auth/session')).authenticated!==false)throw Error('Rejected registration authenticated')
  checks[name]=true
 }
 const registration=await api('/auth/local/register',{username,password:randomBytes(24).toString('hex'),nickname:'参数校验隔离验证'},201)
 if(registration.account?.username!==username)throw Error('Valid registration failed')
 checks.usernameNotReserved=true
 if((await api('/auth/session')).authenticated!==false)throw Error('Registration unexpectedly logged in')
 checks.noSessionCreated=true
 fs.writeFileSync(output,JSON.stringify({pass:true,checks,modelCalls:0,scope:'Real public JAR local account validation only; credentials and recovery code not retained'},null,2)+'\n',{flag:'wx'})
 console.log(JSON.stringify({pass:true,checks,modelCalls:0}))
}catch(error){fs.writeFileSync(output,JSON.stringify({pass:false,checks,error:error.message,modelCalls:0},null,2)+'\n',{flag:'wx'});process.exitCode=1}
