// One-time new-input authoring. Do not regenerate after observing model results.
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {digest,loadSuite} from './evaluation.mjs';
const dir=resolve('evaluation/campus/release-publish-2026-10-31');
if(existsSync(dir))throw Error('Already frozen');
const candidate=JSON.parse(readFileSync('artifacts/campus/release-regression-2026-10-31/plan-v5.json'));
const p=loadSuite(resolve('evaluation/campus/release-final-2026-10-31'));
const fixtures=structuredClone(p.fixtures),sources=structuredClone(p.sources);
const ember=structuredClone(fixtures.damage['sun-levels'].input);
ember.move='ember';ember.attacker.build.moves=['ember'];ember.attacker.build.level=48;ember.defender.build.level=53;
const immune=structuredClone(fixtures.damage['surf'].input);
immune.move='tackle';immune.attacker.build.moves=['tackle'];immune.attacker.build.level=42;
immune.defender.species='froslass';immune.defender.build.level=57;immune.defender.build.moves=['shadow-ball'];immune.defender.build.ability='cursed-body';
for(const [name,input]of Object.entries({'sun-ember':ember,'immune-tackle':immune}))fixtures.damage[name]={input,result:JSON.parse(execFileSync(process.execPath,['tools/pokemon-engine/calculate.cjs'],{input:JSON.stringify(input),encoding:'utf8'}))};
const specs=[
 ['S2-DAMAGE-95','S2-DAMAGE-91','请调用 calculateBattleDamage 计算以下对局，核对不同等级、天气、伤害范围及其局限，不提供未经核对的额外策略。输入：'+JSON.stringify(ember),'sun-ember'],
 ['S2-DAMAGE-96','S2-DAMAGE-91','请调用 calculateBattleDamage 计算以下明确条件，只报告实际输入、范围和数值解读，不补充未查询的配招建议。输入：'+JSON.stringify(immune),'immune-tackle'],
 ['S2-FACTS-95','S2-FACTS-91','查询雪妖女六项种族值，核对“总和490”是否正确；说明为什么总和不能直接作为等级57的实际能力合计。'],
 ['S2-GUIDES-95','S2-GUIDES-91','检索本地组队笔记：同样有属性覆盖的队伍，为什么还要比较可靠输出、速度控制与轮转机会？引用原文，并说明替换成员的取舍。'],
 ['S2-TEAM-95','S2-TEAM-91','读取我的队伍 {teamId}，第一位招式改成 surf、protect、rain-dance 这三招，其他配置不变，仅生成预览，等待页面确认，不讨论最优性。']
];
const cases=specs.map(([id,base,prompt,key])=>{
 const c=structuredClone(p.cases.find(c=>c.id===base));c.id=id;c.prompt=prompt;c.groupId='publish-'+id;
 c.annotation={by:'Codex post-v5 new-input author',date:'2026-10-31',humanReview:'pending',basis:'Related new inputs after v5 freeze; not third-party blind traffic'};
 if(key){c.expected.damageFixture=key;c.expected.criteria[0].text='实际工具输入逐字段匹配新请求，包括等级、天气、招式与原配置';c.expected.criteria[1].text='范围、HP和百分比匹配冻结引擎；不追加无依据的培养结论或胜率';}
 if(id==='S2-TEAM-95'){c.expected.change={field:'招式',before:'surf / protect / ice-beam / rain-dance',after:'surf / protect / rain-dance'};c.expected.criteria[0].text='仅第一位四招替换为明确的三招，其他字段不变';}
 if(id==='S2-FACTS-95'){c.expected.criteria[0].text='六项70/80/70/80/70/110，总和480，区分等级实际能力';c.expected.criteria[0].sourceIds=['pokeapi-478','reference-vs-rules-v1'];}
 if(id==='S2-DAMAGE-96')c.annotation.basis='Known zero-damage native-engine preflight regression; excluded from unseen new-input denominator';
 return c;
});
const previous=['s2','release-2026-10-31','release-confirmation-2026-10-31','release-final-2026-10-31'].flatMap(n=>loadSuite(resolve('evaluation/campus',n)).cases);
if(cases.some(c=>previous.some(old=>old.prompt===c.prompt)))throw Error('Duplicate prompt');
mkdirSync(dir,{recursive:true});
const data={'cases.json':cases,'sources.json':sources,'fixtures.json':fixtures,'splits.json':{dev:[],heldout:cases.map(c=>c.id)}};
for(const[file,value]of Object.entries(data))writeFileSync(resolve(dir,file),JSON.stringify(value,null,2)+'\n');
writeFileSync(resolve(dir,'manifest.json'),JSON.stringify({schemaVersion:1,datasetId:'campus-release-publish-2026-10-31-v1',caseCount:5,newInputCount:4,knownPreflightRegressionCount:1,createdAt:new Date().toISOString(),candidateFrozenAt:candidate.frozenAt,independentTrafficSample:false,status:'post-v5-four-new-inputs-plus-preflight-regression-not-third-party-blind-review',sha256:Object.fromEntries(Object.keys(data).map(file=>[file,digest(readFileSync(resolve(dir,file)))]))},null,2)+'\n');
loadSuite(dir);console.log('Frozen four new inputs plus one preflight regression');
