// Reduce fixed-origin public responses; never copy complete upstream resources into the application.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest } from './evaluation.mjs'
export const explanations={
 'ability:torrent':'HP较低时增强水属性招式。短文未给出触发门槛或增强倍率。',
 'ability:rain-dish':'下雨时逐渐回复HP。短文未给出具体回复比例。',
 'ability:snow-cloak':'下雪时提升闪避能力。短文未给出具体幅度。',
 'ability:cursed-body':'受到招式伤害后，可能使该招式不能使用。短文未给出触发概率和持续时间。',
 'move:water-pulse':'攻击目标，有可能使目标混乱。短文未给出具体触发概率。',
 'move:ice-beam':'攻击目标，有可能使目标冰冻。短文未给出具体触发概率。',
 'move:shadow-ball':'攻击目标，有可能降低目标特防。短文未给出具体触发概率。',
 'move:protect':'提供保护，连续使用会提高失败机会。短文不是招式例外和完整判定规则。',
 'move:spikes':'在对方场地布撒陷阱，对换入的对手造成伤害。短文未完整说明适用条件和层数效果。',
 'move:destiny-bond':'使用后若被对手击倒，击倒它的对手也会倒下；连续使用更容易失败。具体持续范围等条件仍需进一步机制资料。'
}
export const descriptionHashes={
 'ability:torrent':'sha256:4a27ebfe875b5d4edfd660b6a027569eee9d52b323a9d4be501d3ac852f9e10d',
 'ability:rain-dish':'sha256:abe721d8b3050bb02957c9da08b74e3c62babd84f9581fb12cbbbe1d5aa402f9',
 'ability:snow-cloak':'sha256:7f29190843ff81e39fc98a0a84f5375469b5c68f04254e51e9c8ad2003f26ce5',
 'ability:cursed-body':'sha256:aacceb4f147011d603e1e0723e46bd133055a5929b7fd4943824511d4bc30ccc',
 'move:water-pulse':'sha256:489c734166253834a9cbfeb7a55f56b365e4b10a3c8591b198da55cb4a498458',
 'move:ice-beam':'sha256:507bfc9e45cb77c05e8c8d6a937b9341f60aa65a961a83360c8227f5a021d521',
 'move:shadow-ball':'sha256:7cdb3dbedd22beea4f4ac4ac71b2f98f29a600c38a74703b2209de50727200c0',
 'move:protect':'sha256:f076dd56e08cc7db1b0c034238f5322b105fa94c33751b950fe1b2c143245d82',
 'move:spikes':'sha256:0d033b4a175f3c637fdca65995c4f76707a424be6fad6548a4a3c41059d6c813',
 'move:destiny-bond':'sha256:1ab079a11148eaa94a71500ab9cb09b819863c837a899c7c203329695785de45'
}
export const seal=object=>({...object,projectionSha256:'sha256:'+digest(Object.fromEntries(Object.entries(object).sort(([a],[b])=>a<b?-1:a>b?1:0)))})
export function projectResource(kind,envelope) {
 const d=JSON.parse(envelope.payload)
 if(!['ability','move','nature'].includes(kind)||!Number.isSafeInteger(d.id)||d.id<1||!/^[a-z0-9][a-z0-9-]{0,79}$/.test(d.name)
    ||envelope.sha256!=='sha256:'+digest(envelope.payload)||envelope.url!==`https://pokeapi.co/api/v2/${kind}/${d.name}/`||!Number.isFinite(Date.parse(envelope.retrievedAt)))throw Error('Invalid source identity')
 const zh=(d.names||[]).filter(n=>n.language?.name?.toLowerCase()==='zh-hans')
 if(zh.length>1)throw Error('Ambiguous Chinese name')
 const result={schemaVersion:'pokemon-resource-facts-v1',kind,slug:d.name,resourceId:d.id,dataReleaseId:'pokeapi-reference-v1',sourceUrl:envelope.url,checksum:envelope.sha256,
  retrievedAt:envelope.retrievedAt,sourceKind:'BUNDLED_PUBLIC_REFERENCE',fieldScope:'UPSTREAM_REFERENCE_NOT_PATCH_FROZEN',nameZh:zh[0]?.name||d.name}
 if(kind==='move')Object.assign(result,{typeKey:d.type?.name,damageClass:d.damage_class?.name,power:d.power,accuracy:d.accuracy,pp:d.pp,priority:d.priority})
 if(kind==='nature'){if(!Object.hasOwn(d,'increased_stat')||!Object.hasOwn(d,'decreased_stat'))throw Error('Missing nature fields')
  Object.assign(result,{increasedStat:d.increased_stat?.name??null,decreasedStat:d.decreased_stat?.name??null,neutralNature:d.increased_stat===null&&d.decreased_stat===null})}
 const entries=(d.flavor_text_entries||[]).filter(e=>e.version_group?.name==='scarlet-violet')
 const preferred=entries.filter(e=>e.language?.name?.toLowerCase()==='zh-hans'),chosen=preferred.length?preferred:entries.filter(e=>e.language?.name==='en')
 if(chosen.length>1)throw Error('Ambiguous version text')
 if(chosen.length){const text=chosen[0].flavor_text.replace(/\s+/g,' ').trim(),words=text.split(' '),english=chosen[0].language.name==='en';
  Object.assign(result,{descriptionLanguage:chosen[0].language.name.toLowerCase(),descriptionVersionGroup:'scarlet-violet',descriptionExcerpt:english?words.slice(0,25).join(' '):text,
   descriptionSha256:'sha256:'+digest(text),descriptionTruncated:english&&words.length>25})
  if(descriptionHashes[`${kind}:${d.name}`]===result.descriptionSha256)Object.assign(result,{explanationZh:explanations[`${kind}:${d.name}`],explanationStatus:'PROJECT_PARAPHRASE_PENDING_HUMAN_REVIEW'})
 }
 return seal(result)
}
export function buildMechanicReferences(rawDirectory,destination) {
 if(fs.existsSync(destination))throw Error('Use a new catalog directory')
 const manifest=JSON.parse(fs.readFileSync(path.join(rawDirectory,'manifest.json'),'utf8'))
 if(manifest.schemaVersion!=='mechanics-raw-v1'||manifest.files.length>32)throw Error('Invalid source manifest')
 const records=manifest.files.map(f=>{if(!/^(ability|move|nature)-[a-z0-9-]+\.json$/.test(f.file))throw Error('Unsafe resource path')
  const bytes=fs.readFileSync(path.join(rawDirectory,f.file));if(digest(bytes)!==f.fileSha256)throw Error('Changed raw snapshot')
  return projectResource(f.file.split('-')[0],JSON.parse(bytes))})
 if(new Set(records.map(r=>`${r.kind}:${r.slug}`)).size!==records.length)throw Error('Duplicate source identity')
 const catalog={schemaVersion:'pokemon-mechanic-catalog-v1',records},bytes=JSON.stringify(catalog,null,2)+'\n'
 const metadata={schemaVersion:'pokemon-mechanic-catalog-metadata-v1',catalogSha256:'sha256:'+digest(bytes),recordCount:records.length,sourceManifestSha256:'sha256:'+digest(fs.readFileSync(path.join(rawDirectory,'manifest.json'))),
  assembledAt:new Date().toISOString(),reviewStatus:'PENDING_HUMAN_REVIEW',scope:'Public field projection and explicitly marked project paraphrase; no complete mechanics, learnset or competition acceptance'}
 fs.mkdirSync(destination,{recursive:true});for(const [name,text] of [['references-v1.json',bytes],['references-v1.meta.json',JSON.stringify(metadata,null,2)+'\n']])fs.writeFileSync(path.join(destination,name),text,{flag:'wx'})
 return {records:records.length,descriptions:records.filter(r=>r.descriptionVersionGroup).length,metadata}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [raw,out,...extra]=process.argv.slice(2);if(!raw||!out||extra.length)throw Error('Usage: node tools/campus/build-mechanic-references.mjs RAW_SOURCE_DIRECTORY NEW_CATALOG_DIRECTORY')
 console.log(JSON.stringify(buildMechanicReferences(raw,out)))
}
