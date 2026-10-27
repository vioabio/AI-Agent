import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createHash} from 'node:crypto'
const hash=value=>'sha256:'+createHash('sha256').update(value).digest('hex')
export const recipeRows=[['protect','TM007','守住'],['water-pulse','TM011','水之波动'],['ice-beam','TM135','冰冻光束'],['hydro-pump','TM142','水炮']]
const text=html=>html.replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()
export function extractRecipe(html,sourceId,moveSlug,label){
 if(typeof html!=='string'||html.length>2_000_000)throw Error('Source is missing or oversized')
 let slice,offset,leaguePoints,materials
 if(sourceId==='pokemondb'){
  const matches=[...html.matchAll(/<tr\b[^>]*>[\s\S]*?<\/tr>/gi)].filter(m=>m[0].includes(`href="/move/${moveSlug}"`))
  if(matches.length!==1)throw Error('Recipe row must be unique')
  slice=matches[0][0];offset=matches[0].index
  if(!slice.includes(`class="cell-num">${label.slice(2)}</td>`))throw Error('TM and move differ')
  const recipes=[...text(slice).matchAll(/\(TM Machine:\s*([\d,]+) LP,\s*([^)]*)\)/g)]
  if(recipes.length!==1)throw Error('Missing or duplicated recipe')
  leaguePoints=Number(recipes[0][1].replaceAll(',',''))
  materials=recipes[0][2].split(',').map(s=>{const m=s.trim().match(/^(\d+) ([A-Za-z ]+)$/);if(!m)throw Error('Invalid material');return {name:m[2],quantity:Number(m[1])}})
 }else if(sourceId==='serebii'){
  const matches=[...html.matchAll(new RegExp(`<td class="fooinfo" rowspan="3">\\s*${label}\\s*</td>`,'g'))]
  if(matches.length!==1)throw Error('Recipe row must be unique')
  offset=matches[0].index;const end=html.indexOf('</table>',offset);if(end<0)throw Error('Missing recipe table')
  slice=html.slice(offset,end+8)
  if(!slice.includes(`href="/attackdex-sv/${moveSlug.replaceAll('-','')}.shtml"`))throw Error('TM and move differ')
  const lp=[...text(slice).matchAll(/\b(\d+) LP\b/g)];if(lp.length!==1)throw Error('Invalid LP')
  leaguePoints=Number(lp[0][1])
  materials=[...slice.matchAll(/<a href="\/itemdex\/[a-z]+\.shtml">([A-Za-z ]+)<\/a>\s*\*\s*(\d+)/g)].map(m=>({name:m[1],quantity:Number(m[2])}))
 }else throw Error('Unsupported source')
 validateQuantities(leaguePoints,materials)
 return {sourceId,leaguePoints,materials,excerpt:{offset,length:slice.length,sha256:hash(slice)}}
}
function validateQuantities(lp,materials){
 if(!Number.isSafeInteger(lp)||lp<1||lp>100000||!Array.isArray(materials)||materials.length<1||materials.length>3||new Set(materials.map(m=>m.name)).size!==materials.length||materials.some(m=>!Number.isSafeInteger(m.quantity)||m.quantity<1||m.quantity>99||typeof m.name!=='string'||!/^([A-Za-z]+ )*[A-Za-z]+$/.test(m.name)))throw Error('Invalid recipe quantities')
}
export function joinRecipe(record){
 const observations=record.observations
 if(observations?.length!==2||observations[0].sourceId!=='pokemondb'||observations[1].sourceId!=='serebii')throw Error('Both registered sources are required')
 observations.forEach(r=>validateQuantities(r.leaguePoints,r.materials))
 const identity=r=>JSON.stringify([r.leaguePoints,[...r.materials].sort((a,b)=>a.name.localeCompare(b.name)).map(m=>[m.name,m.quantity])])
 const agrees=identity(observations[0])===identity(observations[1])
 return {status:agrees?'CONSISTENT_REFERENCE':'SOURCE_DISAGREEMENT',leaguePoints:agrees?observations[0].leaguePoints:null,materials:agrees?observations[0].materials:[]}
}
export function buildRecipeCatalog(sourceDirectory){
 const sources=[{id:'pokemondb',publisher:'Pokémon Database',url:'https://pokemondb.net/scarlet-violet/tms'},{id:'serebii',publisher:'Serebii',url:'https://www.serebii.net/scarletviolet/tm.shtml'}]
 const pages=new Map(sources.map(s=>{const bytes=fs.readFileSync(path.join(sourceDirectory,s.id+'.html'));s.pageSha256=hash(bytes);return [s.id,bytes.toString('utf8')]}))
 return {schemaVersion:'pokemon-machine-recipe-catalog-v1',gameReleaseId:'pokemon-scarlet-violet',checkedAt:'2026-10-27',scope:'COMMUNITY_RECIPE_REFERENCE_NOT_UNLOCK_ACQUISITION_OR_LEGALITY',reviewStatus:'PENDING_HUMAN_AND_IN_GAME_VERIFICATION',sources,records:recipeRows.map(([moveSlug,label,nameZh])=>({moveSlug,label,nameZh,observations:sources.map(s=>extractRecipe(pages.get(s.id),s.id,moveSlug,label))}))}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [mode,sourceDirectory,destination]=process.argv.slice(2),catalog=buildRecipeCatalog(sourceDirectory)
 const body=JSON.stringify(catalog,null,2)+'\n'
 if(mode==='build'){if(!destination)throw Error('Supply a fresh destination');fs.writeFileSync(destination,body,{flag:'wx'})}
 else if(mode==='verify'){
  const saved=fs.readFileSync('src/main/resources/pokemon-machines/recipe-catalog-v1.json','utf8').replaceAll('\r\n','\n')
  if(saved!==body)throw Error('Rebuilt source projection differs')
  if(!fs.readFileSync('src/main/java/com/vio/vioaiagent/pokemon/reference/MachineRecipeCatalog.java','utf8').includes('"'+hash(body)+'"'))throw Error('Java pin differs')
 }else throw Error('Use build or verify, with the saved public source directory')
 console.log(JSON.stringify({catalogSha256:hash(body),records:catalog.records.map(r=>({moveSlug:r.moveSlug,label:r.label,...joinRecipe(r)})),scope:catalog.scope}))
}
