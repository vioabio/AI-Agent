import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest,sameJson} from './evaluation.mjs'
export function validateMachineSources(catalog,read){
 if(catalog.schemaVersion!=='pokemon-machine-catalog-v1'||catalog.records?.length!==4)throw Error('Invalid fixed machine catalogue')
 const results=[],seen=new Set(),sv=v=>v?.name==='scarlet-violet'&&v?.url==='https://pokeapi.co/api/v2/version-group/25/'
 for(const row of catalog.records){
  if(!['water-pulse','ice-beam','protect','hydro-pump'].includes(row.moveSlug)||seen.has(row.moveSlug)||!Number.isSafeInteger(row.machineId)||row.machineId<1||!/^tm\d{2,3}$/.test(row.itemSlug))throw Error('Invalid source identity');seen.add(row.moveSlug)
  const source=(file,hash)=>{const bytes=read(file);if('sha256:'+digest(bytes)!==hash)throw Error('Source hash mismatch');return JSON.parse(bytes)}
  const move=source(`move-${row.moveSlug}.json`,row.moveSha256),machine=source(`machine-${row.machineId}.json`,row.machineSha256),item=source(`item-${row.itemSlug}.json`,row.itemSha256),url=`https://pokeapi.co/api/v2/machine/${row.machineId}/`
  const link=body=>{const rows=body.machines.filter(m=>sv(m.version_group));return rows.length===1&&rows[0].machine.url===url}
  if(move.name!==row.moveSlug||machine.id!==row.machineId||item.name!==row.itemSlug||machine.move.name!==row.moveSlug||machine.item.name!==row.itemSlug||!sv(machine.version_group)||machine.move.url!==`https://pokeapi.co/api/v2/move/${move.id}/`||machine.item.url!==`https://pokeapi.co/api/v2/item/${item.id}/`||!link(move)||!link(item))throw Error('Move machine item version join mismatch')
  results.push({moveSlug:row.moveSlug,machineId:row.machineId,itemSlug:row.itemSlug,label:`TM${String(Number(row.itemSlug.slice(2))).padStart(3,'0')}`})
 }
 return results
}
export function verifyMemberMachine(report,envelope){
 const pokemon=JSON.parse(envelope.payload),source=pokemon.moves.find(m=>m.move.name===report.moveSlug),rows=(source?.version_group_details||[]).filter(v=>v.version_group.name==='scarlet-violet').map(v=>({methodKey:v.move_learn_method.name,levelLearnedAt:v.level_learned_at,order:v.order??null})),sort=a=>a.map(r=>JSON.stringify([r.methodKey,r.levelLearnedAt,r.order])).sort()
 return report.dataReleaseId==='pokeapi-reference-v1'&&report.gameReleaseId==='pokemon-scarlet-violet'&&report.learning?.dataReleaseId===report.dataReleaseId&&report.learning?.gameReleaseId===report.gameReleaseId&&report.learning?.versionGroup==='scarlet-violet'&&report.pokemonId===pokemon.id&&report.learning?.pokemonId===pokemon.id&&report.learning?.moveSlug===report.moveSlug&&report.learning?.sourceChecksum===envelope.sha256&&envelope.sha256==='sha256:'+digest(envelope.payload)&&sameJson(sort(report.learning.entries),sort(rows))
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const base='src/main/resources/pokemon-machines',raw=fs.readFileSync(base+'/catalog-v1.json','utf8').replaceAll('\r\n','\n'),java=fs.readFileSync('src/main/java/com/vio/vioaiagent/pokemon/reference/MachineReferenceCatalog.java','utf8')
 if(!java.includes('"sha256:'+digest(raw)+'"'))throw Error('Java catalogue pin differs from saved source')
 console.log(JSON.stringify({catalogSha256:'sha256:'+digest(raw),records:validateMachineSources(JSON.parse(raw),f=>fs.readFileSync(base+'/source/'+f)),sourceFiles:12,scope:'Pinned mappings and reference joins, not learning acquisition, formal legality or model planning'}))
}
