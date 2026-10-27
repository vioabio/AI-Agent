import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
import {recipeRows} from './machine-recipes.mjs'
const hash=s=>'sha256:'+createHash('sha256').update(s).digest('hex')
export const acquisitionRegions=[
 {id:'south-province-area-one',nameEn:'South Province (Area One)',paths:{pokemondb:'/location/paldea-south-province-area-one',serebii:'/pokearth/paldea/southprovinceareaone.shtml'}},
 {id:'south-province-area-four',nameEn:'South Province (Area Four)',paths:{pokemondb:'/location/paldea-south-province-area-four',serebii:'/pokearth/paldea/southprovinceareafour.shtml'}},
 {id:'socarrat-trail',nameEn:'Socarrat Trail',paths:{pokemondb:'/location/paldea-socarrat-trail',serebii:'/pokearth/paldea/socarrattrail.shtml'}},
 {id:'casseroya-lake',nameEn:'Casseroya Lake',paths:{pokemondb:'/location/paldea-casseroya-lake',serebii:'/pokearth/paldea/casseroyalake.shtml'}}
]
export function extractAcquisition(html,sourceId,slug,label){
 if(typeof html!=='string'||html.length>2_000_000)throw Error('Source missing or oversized')
 let slice,offset
 if(sourceId==='pokemondb'){
  const rows=[...html.matchAll(/<tr\b[^>]*>[\s\S]*?<\/tr>/gi)].filter(m=>m[0].includes(`href="/move/${slug}"`))
  if(rows.length!==1||!rows[0][0].includes(`class="cell-num">${label.slice(2)}</td>`))throw Error('Unique move and TM row required')
  slice=rows[0][0];offset=rows[0].index
 }else if(sourceId==='serebii'){
  const rows=[...html.matchAll(/<td class="fooinfo" rowspan="3">\s*(TM\d{3})\s*<\/td>/g)],matches=rows.filter(m=>m[1]===label)
  if(matches.length!==1)throw Error('Unique TM row required')
  offset=matches[0].index;const next=rows.find(m=>m.index>offset);slice=html.slice(offset,next?.index??html.length)
  if(!slice.includes(`href="/attackdex-sv/${slug.replaceAll('-','')}.shtml"`))throw Error('TM and move differ')
 }else throw Error('Unknown source')
 const locations=[...slice.matchAll(/<a href="(\/(?:location|pokearth)\/[a-z0-9/.-]+)">([^<]+)<\/a>/g)].map(m=>({path:m[1],label:m[2]}))
 const clean=slice.replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim(),unlockNote=clean.match(/Crafting Recipe:\s*(.*)$/)?.[1]??null
 if(locations.length>40||new Set(locations.map(l=>l.path)).size!==locations.length)throw Error('Repeated or excessive locations')
 return {sourceId,locations,unlockNote,excerpt:{offset,length:slice.length,sha256:hash(slice)}}
}
export function commonAcquisitionRegions(record,regions=acquisitionRegions){
 const rows=record.observations
 if(rows?.length!==2||rows[0].sourceId!=='pokemondb'||rows[1].sourceId!=='serebii'||rows.some(r=>!Array.isArray(r.locations)))throw Error('Both observations required')
 const common=regions.filter(region=>rows.every(row=>row.locations.some(l=>l.path===region.paths[row.sourceId]))).map(({id,nameEn})=>({id,nameEn}))
 return {status:rows.some(r=>!r.locations.length)?'SOURCE_INCOMPLETE':common.length?'COMMON_REGION_REFERENCE':'NO_COMMON_REGION',regions:common}
}
export function buildAcquisitionCatalog(directory){
 const sources=[{id:'pokemondb',publisher:'Pokémon Database',url:'https://pokemondb.net/scarlet-violet/tms'},{id:'serebii',publisher:'Serebii',url:'https://www.serebii.net/scarletviolet/tm.shtml'}]
 const pages=new Map(sources.map(s=>{const bytes=fs.readFileSync(path.join(directory,s.id+'.html'));s.pageSha256=hash(bytes);return [s.id,bytes.toString('utf8')]}))
 return {schemaVersion:'pokemon-machine-acquisition-catalog-v1',gameReleaseId:'pokemon-scarlet-violet',checkedAt:'2026-10-27',scope:'COMMON_COMMUNITY_REGIONS_NOT_EXACT_LOCATION_UNLOCK_OR_LEGALITY',reviewStatus:'PENDING_HUMAN_AND_IN_GAME_VERIFICATION',sources,regionMappings:acquisitionRegions,records:recipeRows.map(([moveSlug,label,nameZh])=>({moveSlug,label,nameZh,observations:sources.map(s=>extractAcquisition(pages.get(s.id),s.id,moveSlug,label))}))}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [mode,directory,destination]=process.argv.slice(2),catalog=buildAcquisitionCatalog(directory),body=JSON.stringify(catalog,null,2)+'\n'
 if(mode==='build'){if(!destination)throw Error('Fresh output path required');fs.writeFileSync(destination,body,{flag:'wx'})}
 else if(mode==='verify'){
  if(fs.readFileSync('src/main/resources/pokemon-machines/acquisition-catalog-v1.json','utf8').replaceAll('\r\n','\n')!==body)throw Error('Source projection changed')
  if(!fs.readFileSync('src/main/java/com/vio/vioaiagent/pokemon/reference/MachineAcquisitionCatalog.java','utf8').includes('"'+hash(body)+'"'))throw Error('Java identity differs')
 }else throw Error('Use build or verify with the saved source directory')
 console.log(JSON.stringify({catalogSha256:hash(body),records:catalog.records.map(r=>({moveSlug:r.moveSlug,label:r.label,...commonAcquisitionRegions(r)})),unlockStatus:'NOT_CROSS_CHECKED',scope:catalog.scope}))
}
