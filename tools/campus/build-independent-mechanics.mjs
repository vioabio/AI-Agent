// Build six curated notes from a fixed upstream text snapshot. Never evaluates upstream TypeScript.
import fs from 'node:fs'
import path from 'node:path'
import {createHash} from 'node:crypto'
import {fileURLToPath} from 'node:url'
import {canonical,digest} from './evaluation.mjs'
export const selections=[
 ['ability','torrent','torrent','激流','HP不高于最大HP的1/3（阈值向下取整）时，使用水属性攻击所用的进攻能力乘以1.5；不能把它等同于最终伤害或胜率固定增加50%。'],
 ['ability','rain-dish','raindish','雨盘','有雨天气时，回合结束回复最大HP的1/16（向下取整）；携带万能伞时不触发此效果。'],
 ['ability','snow-cloak','snowcloak','雪隐','下雪时，针对持有者使用的招式命中率乘以0.8。此修正不等于每次攻击必定落空。'],
 ['ability','cursed-body','cursedbody','诅咒之躯','受到攻击命中后，有30%概率使该攻击招式被定身法封住；若攻击方已有招式被定身，则不触发。该段文字未给出持续回合数。'],
 ['move','ice-beam','icebeam','冰冻光束','有10%概率使目标陷入冰冻。这是效果概率描述，不代表任何目标都能被冰冻，完整例外仍需核对。'],
 ['move','water-pulse','waterpulse','水之波动','有20%概率使目标混乱。这是效果概率描述，不代表任何目标都能陷入混乱，完整例外仍需核对。']
]
export function topDescription(text,id) {
 const lines=text.split('\n'),start=lines.findIndex(l=>l===`\t${id}: {`);if(start<0)throw Error('Missing upstream entry '+id)
 let end=start+1;while(end<lines.length&&lines[end]!=='\t},')end++
 const rows=lines.slice(start,end+1).map((line,i)=>({line,number:start+i+1})).filter(r=>r.line.startsWith('\t\tdesc: '))
 if(rows.length!==1||end>=lines.length)throw Error('Ambiguous current description '+id)
 const value=rows[0].line.slice('\t\tdesc: '.length).replace(/,$/,'');const desc=JSON.parse(value)
 if(typeof desc!=='string'||desc.length>2000)throw Error('Invalid bounded description')
 return {description:desc,line:rows[0].number,rawLine:rows[0].line}
}
export function build(source,out) {
 if(fs.existsSync(out))throw Error('Never overwrite a catalog')
 const meta=JSON.parse(fs.readFileSync(path.join(source,'source.json'),'utf8'))
 if(meta.repository!=='smogon/pokemon-showdown'||meta.commit!=='8f9ab4949d0bf64d5a5ac985a298d1313f2b9a29')throw Error('Unexpected pinned source')
 const pinnedFiles={'data/text/abilities.ts':'49ee85887e2345b9bd54d42cd24849a57ab7938e','data/text/moves.ts':'1978774d8d9d4acdf2d9ae732cee7bf46874759c','data/scripts.ts':'d9fd7818fbd44da56bd2b55f2f650f0333f0c1c4'}
 if(JSON.stringify(canonical(meta.files))!==JSON.stringify(canonical(pinnedFiles)))throw Error('Unpinned source file identities')
 const files={};for(const [file,blob] of Object.entries(meta.files)) {
  const bytes=fs.readFileSync(path.join(source,file.replaceAll('/','_'))),actual=createHash('sha1').update('blob '+bytes.length+'\0').update(bytes).digest('hex')
  if(actual!==blob)throw Error('Upstream Git blob mismatch '+file);files[file]={bytes,text:bytes.toString('utf8'),blob,sha256:'sha256:'+digest(bytes)}
 }
 if(!/^export const Scripts: BattleScriptsData = \{\n\tgen: 9,\n\};\n?$/.test(files['data/scripts.ts'].text))throw Error('Base generation is not explicitly 9')
 const license=fs.readFileSync(path.join(source,'LICENSE'));const licenseBlob=createHash('sha1').update('blob '+license.length+'\0').update(license).digest('hex')
 if(licenseBlob!=='118e1fe24a70c2c458983920662d53aa431bacfb')throw Error('License source mismatch')
 const excerpts=[];const records=selections.map(([kind,slug,id,nameZh,explanationZh])=>{
  const sourcePath=kind==='ability'?'data/text/abilities.ts':'data/text/moves.ts',file=files[sourcePath],description=topDescription(file.text,id)
  excerpts.push({kind,slug,sourcePath,line:description.line,rawLine:description.rawLine,fileSha256:file.sha256,blobSha:file.blob})
  const row={schemaVersion:'independent-mechanic-facts-v1',kind,slug,nameZh,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',generation:9,
   sourceRepository:meta.repository,sourceCommit:meta.commit,sourcePath,sourceUrl:`https://github.com/${meta.repository}/blob/${meta.commit}/${sourcePath}#L${description.line}`,
   sourceBlobSha:file.blob,sourceFileSha256:file.sha256,sourceLine:description.line,descriptionEn:description.description,descriptionSha256:'sha256:'+digest(description.description),
   explanationZh,reviewStatus:'PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW',scope:'PINNED_COMMUNITY_GEN9_TEXT_NOT_OFFICIAL_GAME_OR_LEGALITY'}
  row.projectionSha256='sha256:'+digest(JSON.stringify(canonical(row)));return row
 })
 const catalog=JSON.stringify({schemaVersion:'independent-mechanic-catalog-v1',records},null,2)+'\n',excerptText=JSON.stringify({schemaVersion:'independent-mechanic-excerpts-v1',excerpts},null,2)+'\n'
 fs.mkdirSync(out,{recursive:true});for(const [name,value]of Object.entries({'catalog-v1.json':catalog,'excerpts-v1.json':excerptText,'LICENSE.showdown':license,'generation-v1.txt':files['data/scripts.ts'].bytes,'metadata-v1.json':JSON.stringify({schemaVersion:'independent-mechanic-metadata-v1',repository:meta.repository,commit:meta.commit,recordCount:records.length,baseGeneration:9,catalogSha256:'sha256:'+digest(catalog),excerptsSha256:'sha256:'+digest(excerptText),licenseSha256:'sha256:'+digest(license),generationMarkerSha256:files['data/scripts.ts'].sha256,reviewStatus:'PENDING_HUMAN_REVIEW',sourceFiles:Object.fromEntries(Object.entries(files).map(([p,f])=>[p,{blobSha:f.blob,sha256:f.sha256}]))},null,2)+'\n'}))fs.writeFileSync(path.join(out,name),value,{flag:'wx'})
 return {records:records.length,commit:meta.commit,catalogSha256:'sha256:'+digest(catalog)}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [source,out,...extra]=process.argv.slice(2);if(!source||!out||extra.length)throw Error('Usage: node tools/campus/build-independent-mechanics.mjs FIXED_PUBLIC_SOURCE_DIR NEW_CATALOG_DIR');console.log(JSON.stringify(build(source,out)))}
