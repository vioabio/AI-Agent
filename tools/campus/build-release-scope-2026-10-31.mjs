import{readFileSync,writeFileSync,mkdirSync,existsSync}from'node:fs';
import{resolve}from'node:path';
import{loadSuite,digest}from'./evaluation.mjs';
const dir=resolve('evaluation/campus/release-scope-2026-10-31');if(existsSync(dir))throw Error('Already frozen');
const candidate=JSON.parse(readFileSync('artifacts/campus/release-regression-2026-10-31/plan-v7.json'));
const old=loadSuite(resolve('evaluation/campus/s2')),first=loadSuite(resolve('evaluation/campus/release-2026-10-31'));
const specs=[
 ['S2-GUIDES-97',old,'S2-GUIDES-08','检索本地攻略后解释：资料快照标明了抓取日期，为什么仍不能据此判断成员能参加下一场赛事？请区分图鉴来源、版本组和赛事规则公告。',['实际节选图鉴与比赛规则原文，区分日期/版本组和赛事公告','不能由有图鉴数据推导参赛资格或当前强度'],['reference-vs-rules-v1']],
 ['S2-GUIDES-98',old,'S2-GUIDES-07','请检索并引用一段原文：图鉴里有scarlet-violet版本组的招式记录，是否足以证明具体配招组合满足指定排位规则？我没有提供赛事公告，请明确能确认什么。',['实际取得并引用图鉴/版本/规则区别笔记，不能只返回组队泛论','没有公告不能判断资格，标题作者与引文真实'],['reference-vs-rules-v1']],
 ['S2-CLARIFICATION-97',old,'S2-CLARIFICATION-08','桥接字段没有太晶和异常状态，我只想知道当前伤害接口能表达哪些条件、哪些条件不能覆盖；请说明限制，不计算，也不扩写机制倍率。',['准确描述接口边界，不调用计算、搜索或修改','不扩写特攻影响出手次序等无依据机制，不给虚构倍率或伤害'],['damage-engine','damage-assumptions-v1']],
 ['S2-FACTS-97',first,'S2-FACTS-71','请查小火龙与雪妖女的图鉴，比较速度种族值，说明两者差多少；这里只比较种族值，不把它当作当前等级的实数。',['雪妖女110、小火龙65，差45，实际速度边界正确','不扩写未经查询的特性机制，不宣称必定先手'],['pokeapi-478','pokeapi-4','reference-vs-rules-v1']]
];
const cases=specs.map(([id,suite,base,prompt,criteria,sourceIds])=>{
 const c=structuredClone(suite.cases.find(c=>c.id===base));c.id=id;c.groupId='scope-new-'+id;c.split='heldout';c.prompt=prompt;
 c.expected.criteria=criteria.map((text,i)=>({id:'C'+(i+1),text,sourceIds}));
 c.annotation={by:'Codex after-v7-freeze author',date:'2026-10-31',humanReview:'pending',basis:'New source-linked related inputs after candidate freeze; not third-party blind review'};return c;
});
const previous=['s2','release-2026-10-31','release-confirmation-2026-10-31','release-final-2026-10-31','release-publish-2026-10-31'].flatMap(n=>loadSuite(resolve('evaluation/campus',n)).cases);
if(cases.some(c=>previous.some(p=>p.prompt===c.prompt)))throw Error('Duplicate prompt');
mkdirSync(dir,{recursive:true});const data={'cases.json':cases,'sources.json':old.sources,'fixtures.json':old.fixtures,'splits.json':{dev:[],heldout:cases.map(c=>c.id)}};
for(const[f,v]of Object.entries(data))writeFileSync(resolve(dir,f),JSON.stringify(v,null,2)+'\n');
writeFileSync(resolve(dir,'manifest.json'),JSON.stringify({schemaVersion:1,datasetId:'campus-release-scope-2026-10-31-v1',caseCount:4,createdAt:new Date().toISOString(),candidateFrozenAt:candidate.frozenAt,independentTrafficSample:false,status:'new-inputs-after-v7-freeze-not-third-party-blind-review',sha256:Object.fromEntries(Object.keys(data).map(f=>[f,digest(readFileSync(resolve(dir,f)))]))},null,2)+'\n');
loadSuite(dir);console.log('Frozen four post-v7 new inputs');
