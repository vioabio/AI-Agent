import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'

/** Opt-in transport adapter: only an explicit credential-free loopback HTTP proxy. */
export function downloadViaProxy(output,{proxy,serial=false,requestTimeoutMs=15000,run=spawnSync,environment=process.env,proxySupported=process.allowedNodeEnvironmentFlags.has('--use-env-proxy')}={}){
 if(!proxySupported)throw Error('NODE_ENV_PROXY_UNSUPPORTED')
 let uri
 try{uri=new URL(proxy)}catch{throw Error('LOOPBACK_PROXY_REQUIRED')}
 if(uri.protocol!=='http:'||!['127.0.0.1','localhost','[::1]'].includes(uri.hostname)||!uri.port||uri.username||uri.password||uri.pathname!=='/'||uri.search||uri.hash)throw Error('LOOPBACK_PROXY_REQUIRED')
 if(typeof output!=='string'||!output||typeof serial!=='boolean'||!Number.isInteger(requestTimeoutMs)||requestTimeoutMs<1||requestTimeoutMs>60000)throw Error('INVALID_DOWNLOAD_ARGUMENTS')
 const env={}
 for(const key of ['PATH','Path','SystemRoot','SYSTEMROOT','WINDIR','TEMP','TMP','USERPROFILE'])if(typeof environment[key]==='string')env[key]=environment[key]
 env.HTTPS_PROXY=uri.origin;env.HTTP_PROXY=uri.origin;env.NO_PROXY='';env.ALL_PROXY=''
 const script=fileURLToPath(new URL('./prepare-demo-reference.mjs',import.meta.url))
 const args=['--use-env-proxy',script,output,...(serial?['--serial']:[]),'--timeout-ms',String(requestTimeoutMs)]
 // The child validates its fresh artifacts destination before its fixed-route network calls.
 const result=run(process.execPath,args,{env,encoding:'utf8',shell:false,windowsHide:true,timeout:requestTimeoutMs*4+10000,maxBuffer:65536})
 let report
 try{report=JSON.parse((result.status===0?result.stdout:result.stderr)?.trim())}catch{}
 if(result.error||!report||report.ready!==(result.status===0)||!['demo-reference-preparation-v1','demo-reference-download-failure-v1'].includes(report.schemaVersion)){
  return {ready:false,code:'PROXY_DOWNLOAD_PROCESS_FAILED',transport:'EXPLICIT_LOOPBACK_HTTP_PROXY',modelCalls:0}
 }
 return {...report,transport:'EXPLICIT_LOOPBACK_HTTP_PROXY',tlsVerification:'NODE_DEFAULT',proxyCredentialsAccepted:false}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [output,...args]=process.argv.slice(2),options={}
 try{
  for(let i=0;i<args.length;i++){
   if(args[i]==='--proxy'&&!Object.hasOwn(options,'proxy')&&args[i+1])options.proxy=args[++i]
   else if(args[i]==='--serial'&&!Object.hasOwn(options,'serial'))options.serial=true
   else if(args[i]==='--timeout-ms'&&!Object.hasOwn(options,'requestTimeoutMs')&&/^\d+$/.test(args[i+1]??''))options.requestTimeoutMs=Number(args[++i])
   else throw Error('INVALID_DOWNLOAD_ARGUMENTS')
  }
  const report=downloadViaProxy(output,options);console.log(JSON.stringify(report,null,2));if(!report.ready)process.exitCode=1
 }catch{console.error(JSON.stringify({ready:false,code:'PROXY_DOWNLOAD_CONFIGURATION_REJECTED',modelCalls:0}));process.exitCode=1}
}
