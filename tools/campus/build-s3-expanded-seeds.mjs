// This is a development-seed authoring command, not an independent annotation process.
import {readFileSync,writeFileSync,existsSync}from'node:fs'
import {frozenFileDigest}from'./evaluation.mjs'
const path='evaluation/campus/s3/retrieval-expanded-dev.json'
if(existsSync(path))throw Error('Dataset already exists; version instead of overwriting')
const corpusFile='src/main/resources/pokemon-guides/expanded-v1.json'
const docs=JSON.parse(readFileSync(corpusFile,'utf8')).documents
const inputs=[
  ['species-form-identity-v1','图鉴里的formId、speciesId和全国编号分别指什么？','formId来自宝可梦资源编号'],
  ['species-form-identity-v1','地区形态和同名的成员应该先确认什么？','遇到地区形态或同名对象时'],
  ['move-version-and-identifiers-v1','朱紫招式候选是怎样筛出来的？空列表说明没招式吗？','只收集scarlet-violet对应条目'],
  ['move-version-and-identifiers-v1','中文招式名称能直接猜成计算用的slug吗？','不要由中文名称猜造标识'],
  ['build-values-and-defaults-v1','为什么图鉴种族值不能叫50级的实际能力？','不能标成50级或100级的实际能力'],
  ['build-values-and-defaults-v1','没有填等级、性格和个体值时用了什么默认配置？','等级50、性格hardy、努力值全0、个体值全31'],
  ['damage-request-checklist-v1','只有一支队伍和属性倍率能确定具体伤害吗？','不能确定实际伤害'],
  ['damage-request-checklist-v1','要算对手受到的伤害，双方的配置需要问哪些条件？','双方等级、性格、努力值、个体值、特性、道具'],
  ['field-settings-and-unsupported-v1','天气和场地没提供，结果应怎样解读？','以空值回读'],
  ['field-settings-and-unsupported-v1','太晶、能力强化、墙和入场伤害是否已经被桥接计算？','没有表达能力强化、异常状态、墙、入场伤害或太晶条件'],
  ['status-moves-and-output-v1','变化招式为何不能算直接伤害，是没有战术价值吗？','不表示该招式没有战术价值'],
  ['status-moves-and-output-v1','只挑最高伤害能说明稳定击倒或胜率吗？','不是只挑最大值宣称稳定击倒'],
  ['ability-item-traceability-v1','特性候选是不是已经选好的特性？','候选只说明资料列出这个名称'],
  ['ability-item-traceability-v1','道具名称验证和计算assumptions回读应该怎样核对？','核对assumptions中实际使用的名称'],
  ['team-change-confirmation-v1','AI给出队伍修改建议后是否就已经保存了？','用户确认之前不写入保存队伍'],
  ['team-change-confirmation-v1','队伍编辑区已经保存了新配置，旧预览能覆盖吗？','旧预览不能覆盖新状态']
]
const cases=inputs.map(([id,query,contains],i)=>{
  if(!docs.find(d=>d.id===id)?.content.includes(contains))throw Error('Label is not present in source')
  return{id:`S3X-R${String(i+1).padStart(2,'0')}`,query,relevantDocumentIds:[id],relevantEvidence:[{documentId:id,contains}]}
})
const negatives=[['火星种菜完全无关问题'],['努力值性格',{battleFormatId:'vgc'}],['努力值性格',{language:'en-US'}],
  ['努力值性格',{rulesetVersionId:'other'}],['努力值性格',{gameReleaseId:'other'}],['zzzxxyyqqq'],['烘焙蛋糕配方'],['深海采矿设备']]
negatives.forEach(([query,scope],i)=>cases.push({id:`S3X-N${String(i+1).padStart(2,'0')}`,query,...(scope?{scope}:{}),relevantDocumentIds:[]}))
writeFileSync(path,JSON.stringify({datasetId:'campus-s3-expanded-dev-v1',split:'development-only',
  labelOrigin:'AI-assisted source-linked seeds; independent human relevance review pending',corpusFile,corpusSha256:frozenFileDigest(corpusFile),
  defaults:{gameReleaseId:'pokemon-scarlet-violet',language:'zh-CN',battleFormatId:'casual-singles',rulesetVersionId:'casual-v1'},cases},null,2)+'\n')
console.log(`Frozen ${cases.length} development cases; source phrases are partial evidence seeds, not exhaustive relevance labels`)
