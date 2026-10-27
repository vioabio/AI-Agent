// Original, explicitly conditional notes derived from pinned public facts. No copied strategy article or metagame claims.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { digest, frozenFileDigest } from './evaluation.mjs'
import { validateSnapshot } from '../reference-cache.mjs'
import { referenceFromSnapshots } from './build-user-report-seed.mjs'

const baseFile='src/main/resources/pokemon-guides/expanded-v1.json'
const topics={
  'team-roles-v1':['team'], 'training-configuration-v1':['training'], 'damage-assumptions-v1':['damage'],
  'reference-vs-rules-v1':['reference'], 'species-form-identity-v1':['reference'],
  'move-version-and-identifiers-v1':['resource'], 'build-values-and-defaults-v1':['training','project'],
  'damage-request-checklist-v1':['damage'], 'field-settings-and-unsupported-v1':['damage'],
  'status-moves-and-output-v1':['damage','resource'], 'ability-item-traceability-v1':['resource'],
  'team-change-confirmation-v1':['project']
}
export function originalSpeciesNote(slug,fact,retrievedAt) {
  const intro=`事实依据：PokéAPI公开快照（抓取日期${retrievedAt.slice(0,10)}），${fact.speciesNameZh}的六项种族值为HP ${fact.hp}、攻击${fact.attack}、防御${fact.defense}、特攻${fact.spAttack}、特防${fact.spDefense}、速度${fact.speed}。这些不是等级50的实际能力，也不证明任何招式机制或比赛资格。`
  const strategy=slug==='squirtle' ? [
    '角色建议（策略推断）：杰尼龟速度种族值43，特攻50与攻击48接近。围绕它做休闲主题队时，不宜未经目标速度核对就按高速清场成员培养；物攻或特攻路线应由实际可用招式、目标对手和队伍需求决定，不能仅凭两点种族值差距断言特攻最优。',
    '培养起点（条件示例）：若目标是体验特攻输出，同时补充HP，可先用等级50、HP努力值252、特攻252、特防4，其余0、个体值暂按31作比较起点。508点分配满足项目填写边界，不代表最优耐久或击倒线；性格、特性、道具与四个招式尚需查询和确认。若希望承担耐久角色，应重新分配并用目标对局验证，而不是沿用这个输出模板。',
    '搭配与取舍（策略建议）：给杰尼龟保留主题位置时，先为队伍补足可靠输出与速度控制，再核对队友能否分担全队已确认的弱点。速度控制需要具体招式证据，不能由角色名称推定招式可学。若改用其他成员或进化形态，需另查其数据，不能把杰尼龟数值直接套用。'
  ] : [
    '角色建议（策略推断）：雪妖女速度种族值110，高于它自身其他五项；攻击与特攻同为80，HP、防御和特防均为70。可优先测试速度导向的输出或干扰候选路线，但实际先后手受等级、性格、努力值、对手及对局条件影响，不能承诺总能先手。干扰路线也必须先确认具体招式与机制。',
    '培养起点（条件示例）：若已核对的招式支持特攻输出，可先用等级50、特攻努力值252、速度252、HP 4，其余0、个体值暂按31作比较起点。这个508点示例牺牲了其他耐久投入；性格、特性、道具和四个招式暂不代选。若目标改为物理输出或耐久交换，需重新设计，不能由双攻相等推定两种路线表现相同。',
    '搭配与取舍（策略建议）：将雪妖女作为速度导向候选时，可让其他位置补持续输出和耐久换入，再检查全队弱点是否重复。只有属性与种族值不足以证明安全换入、稳定击倒或联防成立；特性触发、变化招式、道具互动都需要另读机制来源，不能凭名称解释。'
  ]
  return [intro,...strategy,'范围与缺失：以上是面向朱紫休闲单打的项目原创条件建议，待独立人工复核。没有实时使用率、赛事成绩、完整学习途径或对局计算，暂不提供当前最强阵容、完整配招合法性、伤害百分比或胜率结论。下一步请先明确成员角色与目标对手，再查询招式/特性/道具，必要时计算。'].join('\n\n')
}
export function auditSpeciesGuides(corpus,sources,base) {
  if(corpus.schemaVersion!==3||corpus.catalogId!=='local-guides-species-overview-v1'||corpus.documents.length!==base.documents.length+2
    ||sources.baseCorpusSha256!==frozenFileDigest(baseFile))throw Error('Invalid species corpus/base')
  for(const old of base.documents){const doc=corpus.documents.find(d=>d.id===old.id);const {applicability,...copy}=doc||{};
    if(JSON.stringify(copy)!==JSON.stringify(old)||!applicability||JSON.stringify(applicability.topics)!==JSON.stringify(topics[old.id]))throw Error('Changed baseline or missing declared topics')}
  for(const slug of ['squirtle','froslass']){
    const source=sources.references.find(r=>r.fact.slug===slug),doc=corpus.documents.find(d=>d.id===`${slug}-conditional-overview-v1`)
    if(!source||!doc||doc.content!==originalSpeciesNote(slug,source.fact,source.pokemonSnapshot.retrievedAt)
      ||doc.sourceUrl!==source.fact.sourceUrl||doc.applicability.subjects[0]!==slug||doc.content.length>2000)throw Error('Unbound species note')
  }
  return {documents:corpus.documents.length,speciesNotes:2,baseDocumentsUnchanged:true,humanReview:'pending',scope:'Pinned factual projections and original conditional suggestions, not a competitive strategy gold set'}
}
export function buildSpeciesGuides(bundleDirectory,destination) {
  if(fs.existsSync(destination))throw Error('Use a new corpus directory; never overwrite old sources')
  const base=JSON.parse(fs.readFileSync(baseFile,'utf8')),manifest=JSON.parse(fs.readFileSync(path.join(bundleDirectory,'manifest.json'),'utf8'))
  const entry=file=>{const meta=manifest.files.find(f=>f.file===file),bytes=fs.readFileSync(path.join(bundleDirectory,file));
    if(!meta||digest(bytes)!==meta.fileSha256)throw Error('Changed public bundle');return validateSnapshot(bytes,file)}
  const references=['squirtle','froslass'].map(slug=>({...referenceFromSnapshots(entry(`pokemon-${slug}.json`),entry(`pokemon-species-${slug}.json`)),
    pokemonSnapshot:manifest.files.find(f=>f.file===`pokemon-${slug}.json`),speciesSnapshot:manifest.files.find(f=>f.file===`pokemon-species-${slug}.json`)}))
  const codePath='src/main/java/com/vio/vioaiagent/pokemon/domain/TeamBuild.java'
  const documents=base.documents.map(d=>{if(!topics[d.id])throw Error('Unclassified baseline document');return {...d,applicability:{subjects:[],topics:topics[d.id]}}})
  for(const source of references){const f=source.fact;documents.push({id:`${f.slug}-conditional-overview-v1`,title:`${f.speciesNameZh}：休闲单打的培养起点与搭配取舍`,gameReleaseId:f.gameReleaseId,
    keywords:`${f.speciesNameZh} ${f.slug} 培养 搭配 对战策略 强势阵容 特性 招式`,author:'VIO project',updatedAt:'2026-10-26',sourceUrl:f.sourceUrl,
    content:originalSpeciesNote(f.slug,f,source.pokemonSnapshot.retrievedAt),language:'zh-CN',battleFormatId:'casual-singles',rulesetVersionId:'casual-v1',
    provenance:{kind:'PROJECT_NOTE',contentUsage:'ORIGINAL_NO_UPSTREAM_COPY',acquiredAt:'2026-10-26',reviewStatus:'PENDING_HUMAN_REVIEW',
      references:[{title:`${f.speciesNameZh} Pokemon资源字段快照`,publisher:'PokéAPI',url:f.sourceUrl,checkedAt:source.pokemonSnapshot.retrievedAt.slice(0,10),relation:'API_FIELD_REFERENCE'}],
      codeReferences:[{path:codePath,sha256:frozenFileDigest(codePath)}]},applicability:{subjects:[f.slug,f.speciesNameZh],topics:['training','team','reference','resource']}})}
  const corpus={schemaVersion:3,catalogId:'local-guides-species-overview-v1',documents},sources={schemaVersion:'species-guide-sources-v1',
    baseCorpusFile:baseFile,baseCorpusSha256:frozenFileDigest(baseFile),publicBundleManifestSha256:frozenFileDigest(path.join(bundleDirectory,'manifest.json')),
    assembledAt:new Date().toISOString(),references,scope:'Existing public cache snapshots; assembly is not a fresh upstream fetch. Advice is a conditional project inference, pending human review.'}
  const result=auditSpeciesGuides(corpus,sources,base)
  fs.mkdirSync(destination,{recursive:true});for(const [file,value] of Object.entries({'overview-v1.json':corpus,'sources-v1.json':sources}))fs.writeFileSync(path.join(destination,file),JSON.stringify(value,null,2)+'\n',{flag:'wx'})
  return result
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const [bundle,out,...extra]=process.argv.slice(2);if(!bundle||!out||extra.length)throw Error('Usage: node tools/campus/build-species-guides.mjs PUBLIC_BUNDLE NEW_CORPUS_DIRECTORY')
  console.log(JSON.stringify(buildSpeciesGuides(bundle,out)))
}
