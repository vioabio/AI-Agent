import fs from 'node:fs'
import {createHash} from 'node:crypto'
const hash=value=>'sha256:'+createHash('sha256').update(value).digest('hex')
const guideText=fs.readFileSync('src/main/resources/pokemon-machines/crafting-guide-v1.json','utf8').replaceAll('\r\n','\n')
const guide=JSON.parse(guideText),java=fs.readFileSync('src/main/java/com/vio/vioaiagent/pokemon/reference/MachineCraftingGuide.java','utf8')
if(!java.includes('"'+hash(guideText)+'"'))throw Error('Java guide pin differs from resource')
if(!process.argv[2])throw Error('Supply the saved public official HTML snapshot path; this command never downloads or executes it')
const source=fs.readFileSync(process.argv[2]),html=source.toString('utf8')
if(hash(source)!==guide.sourcePageSha256)throw Error('Official snapshot hash differs from registered source')
if(!html.includes(`<link rel="canonical" href="${guide.sourceUrl}"`))throw Error('Canonical official source differs')
const anchors=['所有宝可梦中心','联盟支付点（LP）','一次之后便会消失','随着冒险进展'].map(text=>{
 const offset=html.indexOf(text);if(offset<0)throw Error('Missing source anchor: '+text)
 return {text,offset,length:text.length,sha256:hash(text)}
})
console.log(JSON.stringify({schemaVersion:'machine-crafting-source-audit-v1',guideSha256:hash(guideText),sourceUrl:guide.sourceUrl,checkedAt:guide.checkedAt,sourcePageSha256:hash(source),sourceLanguage:'zh-CN',anchors,facts:guide.facts.length,scope:guide.scope,reviewStatus:guide.reviewStatus,note:'File identity and source anchors only; not independent human semantic review or per-TM recipe verification'},null,2))
