import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {setTimeout as delay} from 'node:timers/promises'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {LocalMcpClient} from './mcp-local-client.mjs'
import {digest} from './evaluation.mjs'

export function verifySoak(report) {
  assert.equal(report.scope,'PRODUCTION_STDIO_BOUNDED_RATE_SOAK')
  assert.ok(report.durationSeconds>=report.requestedSeconds)
  assert.ok(report.requestedSeconds>=60)
  assert.ok(report.samples.length>=2)
  assert.ok(report.samples.every(row=>row.pending===0&&row.clientRss>0&&row.serverRss>0))
  assert.ok(report.pings>=report.requestedSeconds)
  assert.ok(report.successfulLookups>0&&report.successfulCalculations>0)
  assert.equal(report.observation.invalidFrames,0)
  assert.equal(report.observation.duplicateResponses,0)
  assert.equal(report.observation.forcedTermination,false)
  return {status:'VERIFIED',memoryThresholdApplied:false,qualityBenefit:'NOT_EVALUATED'}
}

async function serverRss(pid) {
  assert.ok(Number.isSafeInteger(pid)&&pid>0)
  if(process.platform==='win32') {
    const result=await promisify(execFile)('powershell.exe',['-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command',`(Get-Process -Id ${pid}).WorkingSet64`],{windowsHide:true,timeout:10000})
    return Number(result.stdout.trim())
  }
  const status=fs.readFileSync(`/proc/${pid}/status`,'utf8')
  return Number(status.match(/^VmRSS:\s+(\d+)/m)?.[1])*1024
}

async function main() {
  const [destination,configuration,seed,secondsText='3600']=process.argv.slice(2)
  const seconds=Number(secondsText),artifacts=fs.realpathSync('artifacts'),output=path.resolve(destination)
  assert.ok(Number.isSafeInteger(seconds)&&seconds>=60&&seconds<=14400)
  assert.equal(path.dirname(output),artifacts);assert.equal(fs.existsSync(output),false)
  const seedPath=fs.realpathSync(seed),relative=path.relative(artifacts,seedPath)
  assert.ok(relative&&!relative.startsWith('..')&&!path.isAbsolute(relative))
  const config=JSON.parse(fs.readFileSync(configuration,'utf8').replace(/^\uFEFF/,''))?.mcpServers?.['vio-pokemon']
  assert.equal(config?.args?.[2],'com.vio.vioaiagent.mcp.PokemonMcpServerMain')
  fs.mkdirSync(output);const cache=path.join(output,'public-cache');fs.cpSync(seedPath,cache,{recursive:true})
  const report={scope:'PRODUCTION_STDIO_BOUNDED_RATE_SOAK',status:'RUNNING',requestedSeconds:seconds,
    startedAt:new Date().toISOString(),pings:0,successfulLookups:0,successfulCalculations:0,samples:[],
    sourceSha256:digest(fs.readFileSync(fileURLToPath(import.meta.url))),releaseDecision:'NOT_EVALUATED'}
  const save=()=>fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n')
  const request={request:{generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle'},defender:{species:'squirtle'},move:'tackle',field:{gameType:'Singles'}}}
  let client,started
  try {
    client=new LocalMcpClient({...config,env:{...config.env,VIO_MCP_CACHE_DIRECTORY:cache}})
    report.tools=await client.initialize();started=performance.now()
    let nextSample=0,round=0
    while(performance.now()-started<seconds*1000) {
      const roundStarted=performance.now()
      for(let index=0;index<2;index++) {assert.deepEqual((await client.request('ping')).result,{});report.pings++}
      if(round%60===0) {
        const fact=await client.call('lookupPokemonReference',{query:'7',dataReleaseId:'pokeapi-reference-v1'})
        assert.equal(fact.result.isError,false);assert.equal(JSON.parse(fact.result.content.map(row=>row.text||'').join('')).fact.hp,44);report.successfulLookups++
        const calculation=await client.call('calculateBattleDamage',request)
        assert.equal(calculation.result.isError,false);assert.equal(JSON.parse(calculation.result.content.map(row=>row.text||'').join('')).range.maximum,16);report.successfulCalculations++
        client.child.stdout.pause()
        const burst=Promise.all(Array.from({length:6},()=>client.request('tools/list')))
        await delay(100);client.child.stdout.resume()
        assert.ok((await burst).every(row=>Array.isArray(row.result?.tools)))
      }
      const elapsed=(performance.now()-started)/1000
      if(elapsed>=nextSample) {
        report.samples.push({elapsedSeconds:elapsed,requests:client.next,pending:client.pending.size,clientRss:process.memoryUsage().rss,serverRss:await serverRss(client.child.pid)})
        nextSample=elapsed+60;save()
      }
      round++;await delay(Math.max(0,1000-(performance.now()-roundStarted)))
    }
    report.durationSeconds=(performance.now()-started)/1000
    report.samples.push({elapsedSeconds:report.durationSeconds,requests:client.next,pending:client.pending.size,clientRss:process.memoryUsage().rss,serverRss:await serverRss(client.child.pid)})
  } catch(error) {report.status='FAIL';report.failure=error.code||error.name;process.exitCode=1}
  finally {
    if(client)report.observation=await client.close()
    if(report.status!=='FAIL')try{report.integrity=verifySoak(report);report.status='PASS'}catch(error){report.status='FAIL';report.failure=error.code||error.name;process.exitCode=1}
    report.finishedAt=new Date().toISOString();save();console.log(JSON.stringify({status:report.status,pings:report.pings,durationSeconds:report.durationSeconds}))
  }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main()
