import{readFileSync,writeFileSync,mkdirSync,existsSync}from'node:fs';import{resolve}from'node:path';import{loadSuite,digest}from'./evaluation.mjs';
const dir=resolve('evaluation/campus/release-compare-2026-10-31');if(existsSync(dir))throw Error('Already frozen');
const candidate=JSON.parse(readFileSync('artifacts/campus/release-regression-2026-10-31/plan-v8.json'));
const old=loadSuite(resolve('evaluation/campus/s2')),confirmation=loadSuite(resolve('evaluation/campus/release-confirmation-2026-10-31')),scope=loadSuite(resolve('evaluation/campus/release-scope-2026-10-31')),first=loadSuite(resolve('evaluation/campus/release-2026-10-31'));
const cases=[confirmation.cases.find(c=>c.id==='S2-FACTS-81'),...scope.cases,old.cases.find(c=>c.id==='S2-DAMAGE-02')].map(c=>({...structuredClone(c),split:'heldout',annotation:{by:'Codex',date:'2026-10-31',humanReview:'pending',basis:'Previously observed regression; original prompt and criteria preserved'}}));
for(const[id,prompt,speeds,sourceIds]of[
 ['S2-FACTS-98','请查雪妖女与杰尼龟的图鉴，比较速度种族值并说明差值；不必提供额外特性介绍，实际先后也不作判断。','雪妖女110、杰尼龟43、差67',['pokeapi-478','pokeapi-7','reference-vs-rules-v1']],
 ['S2-FACTS-99','请查杰尼龟与小火龙的图鉴，对比速度种族值相差多少；无需给实际出手顺序或额外特性介绍。','杰尼龟43、小火龙65、差22',['pokeapi-7','pokeapi-4','reference-vs-rules-v1']]
]){
 const c=structuredClone(first.cases.find(c=>c.id==='S2-FACTS-71'));Object.assign(c,{id,prompt,split:'heldout',groupId:'v8-new-'+id});c.expected.criteria=[{id:'C1',text:speeds+'，不当作等级实际速度',sourceIds},{id:'C2',text:'没有额外特性、实战培养或先手判断，回答遵循明确排除的内容',sourceIds}];c.annotation={by:'Codex',date:'2026-10-31',humanReview:'pending',basis:'New untouched wording fixed after v8 freeze, related negative-control variants, not third-party blind review'};cases.push(c);
}
const previous=['s2','release-2026-10-31','release-confirmation-2026-10-31','release-final-2026-10-31','release-publish-2026-10-31','release-scope-2026-10-31'].flatMap(n=>loadSuite(resolve('evaluation/campus',n)).cases);
if(cases.slice(-2).some(c=>previous.some(p=>p.prompt===c.prompt)))throw Error('Duplicate new input');
mkdirSync(dir,{recursive:true});const data={'cases.json':cases,'sources.json':old.sources,'fixtures.json':old.fixtures,'splits.json':{dev:[],heldout:cases.map(c=>c.id)}};for(const[f,v]of Object.entries(data))writeFileSync(resolve(dir,f),JSON.stringify(v,null,2)+'\n');
writeFileSync(resolve(dir,'manifest.json'),JSON.stringify({schemaVersion:1,datasetId:'campus-release-compare-2026-10-31-v1',caseCount:8,newInputCount:2,observedRegressionCount:6,createdAt:new Date().toISOString(),candidateFrozenAt:candidate.frozenAt,independentTrafficSample:false,status:'v8-targeted-regression-plus-two-untouched-inputs',sha256:Object.fromEntries(Object.keys(data).map(f=>[f,digest(readFileSync(resolve(dir,f)))]))},null,2)+'\n');loadSuite(dir);console.log('Frozen v8: six observed regression and two new inputs');
