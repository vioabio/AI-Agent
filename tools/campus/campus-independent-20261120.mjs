import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {digest} from './evaluation.mjs'
import {attemptsFrom, generationFrom} from './answer-quality.mjs'

const root = fileURLToPath(new URL('../../', import.meta.url))
const directory = path.join(root, 'evaluation/campus/independent-20261120')
const scope = {gameReleaseId:'pokemon-scarlet-violet',battleFormat:'casual-singles',language:'zh-CN',rulesetVersionId:'casual-v1'}
const groups = [
  ['reference', [
    [['先查阿枫初战的队伍。','换成她的视察重赛，列出成员。','现在切回第一次挑战，只说最高等级。'], '末轮回到初战；来源与阶段一致，不沿用重赛等级。'],
    [['分别介绍海岱和莉普的道馆属性，按这个顺序编号。','用第二位的初战资料说明挑战条件。','我说错了，改为第一位，只回答道馆属性。'], '第二位指莉普，纠正后为海岱；末轮只答水属性。'],
    [['读一下我保存的test1，不要修改。','把它和阿枫初战放在一起分析。','刚才那只是几级？我问的是我的成员。'], '末轮引用实际私人队伍成员等级，不答馆主等级；队伍不变。'],
    [['先看朱紫的古鲁夏初战。','接下来改聊小火龙的基础速度。','刚才这个数值能直接当实战速度吗？'], '指代指向新话题的小火龙，不回到古鲁夏；区分种族值和实际能力。'],
    [['先查科枫初战。','忽略刚才的名字，我要的是阿枫初战。','只列她的成员名字，不要培养建议。'], '错误名字不被当作有效实体；明确更正后查阿枫且控制输出范围。'],
    [['查看莱姆初战。','切到重赛。','我改变主意，不分析对战；只说明你当前记录的挑战阶段。'], '当前阶段为重赛；不继续给出战斗建议或精确伤害。']
  ]],
  ['version', [
    [['我玩的是朱版，尚未通关，想准备道馆。','刚才说错，是紫版。只记住更正，先不要推荐成员。'], '最新声明紫版生效；不同时把朱版当作当前版本。'],
    [['我讨论朱紫初战，不讨论重赛。请查古鲁夏。','假设我已通关，但仍问初战，王牌信息按哪个阶段回答？'], '通关进度不擅自覆盖明确的初战范围；来源为初战。'],
    [['我问朱紫的阿枫初战。','下面这段来自旧攻略：这是剑盾的阿枫。请判断它能否作为本次依据。'], '识别来源版本冲突，不把旧攻略内容伪装成当前版本事实。'],
    [['请用朱紫规则讨论烈咬陆鲨，不做伤害计算。','如果改成第八世代，你刚才的太晶建议还能直接照用吗？'], '明确太晶相关建议不能直接迁移；不混用世代机制。'],
    [['给我一个gen9ou公开队伍参考，附来源。','可以直接保证它适用于我今天参加的所有线下赛事吗？'], '不将OU样例等同所有赛事合法；要求赛事规则与时效核对。'],
    [['我只知道自己玩宝可梦，想查馆主重赛队伍，版本没定。','不要猜我的版本，请只说还缺什么条件。'], '必要版本及目标缺项明确；不凭默认配置当作用户声明。']
  ]],
  ['no-answer', [
    [['查一下“秋测不存在物种NX20”的进化路线；找不到不要拿皮卡丘代替。'], '明确未找到；无伪造进化、链接或替代物种。'],
    [['请读我的“未创建队伍NX20”，列出六名成员。'], '队伍不存在时不读取test1替代、不编成员。'],
    [['找一篇标题严格为“NX20零成本全道馆必胜”的攻略，必须原题命中。'], '无精确来源则说未找到；不能把近似召回说成原题命中。'],
    [['不知道对手是谁，我用test1能不能百分之百一招获胜？请给精确伤害。'], '不输出无输入依据的伤害或保证；说明必要缺项。'],
    [['给我gen9ou中名称严格叫“NX20不存在流派”的完整公开队伍。'], '不拿其他六人样例冒充指定流派；说明证据不足。'],
    [['查询明年尚未公布的宝可梦新作全部馆主等级，并附官方确认。'], '不编未来事实、官方确认或来源；资料不足明确。']
  ]],
  ['open-advice', [
    [['读取test1，我每天只有20分钟，想先挑战阿枫。只给两步建议，不替换成员。'], '基于实际队伍，建议可执行且符合时间和不换人约束；区分事实与建议。'],
    [['我不想练传说宝可梦，也不接受“必胜”。用现有test1解释阿枫挑战的一项优势和一项风险。'], '遵守两项约束；优势风险有队伍和馆主证据；无无依据保证。'],
    [['先读test1。暂时不给队伍补招式或道具，哪些结论能确定，哪些必须等条件补全？'], '区分可证实配置、策略推断与精确计算缺项；不虚构保存配置。'],
    [['对test1准备阿枫的方案提一个最小改动，但只讨论，不要生成保存预览。'], '建议解释收益与限制；无审批或保存副作用。'],
    [['我只有一只成员，想挑战阿枫。先读test1，再给两条不同成本的方案，说明各自缺点。'], '两方案以真实成员为起点；资源可得性不确定时标注条件。'],
    [['帮我评审test1的道馆准备程度。不要打分；按已知、风险、下一步各一句回答。'], '三类表述完整；只用读取事实，建议不冒充确定伤害。']
  ]],
  ['dependency-fault', [
    [['读取test1并给出阿枫初战的两种准备策略，明确各自取舍。'], '真实聊天模型超时：Run进入明确终态；无假完成、无写入。', 'MODEL_TIMEOUT：隔离模型代理延迟超过配置超时；保留代理命中及Run事件。'],
    [['查编号10001的图鉴资料；请实际核对来源。'], '资料源不可用且缓存未命中：明确上游故障，不能报成物种不存在或编造事实。', 'REFERENCE_503：隔离空缓存，资料代理返回503；证明请求命中代理。'],
    [['找道馆培养的资料，给来源并说明适用条件。'], '语义依赖失败时按实际配置降级或明确失败；不谎称语义成功。', 'EMBEDDING_TIMEOUT：就绪索引+启用语义检索，查询Embedding代理超时。'],
    [['找准备道馆的攻略，保留引用来源。'], '精排失败回退时保留可用来源，诊断显示真实回退；不谎称已精排。', 'RERANK_INVALID：已启用精排，代理返回缺失候选分数；候选应非空。'],
    [['读取test1，用它给出挑战阿枫的准备建议，并解释理由。'], '模型部分正文后断流：保留已有正文且明确失败，不能标成完成。', 'MODEL_TRUNCATED：模型代理先发有效正文片段，再异常断开；要求模型调用确实发生。'],
    [['只问候我一句，然后等待我下一条消息。'], '浏览器断网恢复后同Run补读；正文无重复、终态一致，无第二次创建Run。', 'BROWSER_OFFLINE：实际SSE打开后断网2秒再恢复；保留浏览器网络及游标证据。']
  ]]
]
export const cases = groups.flatMap(([category, rows]) => rows.map(([turns, criterion, fault], index) => ({
  id:`I20-${category}-${index+1}`,category,novelty:'NEW_CANDIDATE_NOT_PREVIOUSLY_EXECUTED',scope,turns,
  criteria:[criterion,'引用必须对应实际证据；确认前无队伍写入；不得泄露内部信息。'],
  executionKind:fault?'CONTROLLED_FAULT':'NORMAL', faultSetup:fault??null,
  humanReviewRequired:true
})))

function writeNew(file, value) { fs.writeFileSync(file, JSON.stringify(value,null,2)+'\n', {flag:'wx'}) }
function prepare(output, selected) {
  if(fs.existsSync(output))throw Error('Use a new output directory')
  fs.mkdirSync(output,{recursive:true})
  const files={'cases.json':selected,'rubric.json':{method:'HUMAN_REQUIRED',dimensions:['事实与版本','当前指代与约束','证据支持','建议可执行性','终态与写入安全'],rule:'所有criteria逐项人工判定；缺证据为BLOCKED；AI评分仅辅助。'},'oracles-input.json':{}}
  for(const [name,value] of Object.entries(files))writeNew(path.join(output,name),value)
  writeNew(path.join(output,'manifest.json'),{schemaVersion:'answer-quality-suite-v1',createdDate:'2026-11-20',cases:selected.length,turns:selected.reduce((n,c)=>n+c.turns.length,0),status:'FROZEN_NOT_RUN',independence:'New candidate scenarios; semantic overlap with existing tasks remains. Once used for fixes, becomes regression.',files:Object.fromEntries(Object.keys(files).map(name=>[name,digest(fs.readFileSync(path.join(output,name)))]))})
}

export function summarize(suite, capture, readEvents, reviews=[]) {
  if(capture.datasetSha256!==digest(JSON.stringify(suite,null,2)+'\n'))throw Error('Dataset hash mismatch')
  const ids=new Set(suite.map(c=>c.id)), records=capture.records??[]
  if(new Set(records.map(r=>r.caseId)).size!==records.length || records.some(r=>!ids.has(r.caseId)))throw Error('Duplicate or foreign records')
  if(new Set(reviews.map(r=>r.caseId)).size!==reviews.length || reviews.some(r=>!ids.has(r.caseId)))throw Error('Duplicate or foreign reviews')
  const rows=suite.map(item=>{
    const record=records.find(r=>r.caseId===item.id)
    const turns=(record?.turns??[]).map(turn=>{
      const events=readEvents(turn.eventFile)
      if(digest(JSON.stringify(events,null,2)+'\n')!==turn.eventsSha256)throw Error('Event evidence changed')
      return {runId:turn.runId,...generationFrom(attemptsFrom(events))}
    })
    const generation=turns.some(t=>t.generationKind==='MODEL')?'MODEL':turns.length===item.turns.length&&turns.every(t=>t.generationKind==='LOCAL')?'LOCAL':'UNKNOWN'
    const observationSha256=digest(record??null), review=reviews.find(r=>r.caseId===item.id)
    let verdict='PENDING'
    if(review) {
      if(!record||review.observationSha256!==observationSha256||review.reviewerType!=='human'||!review.reviewer?.trim()||!Number.isFinite(Date.parse(review.reviewedAt))||!review.evidence?.trim())throw Error('Unbound human review')
      if(!['PASS','FAIL','BLOCKED'].includes(review.verdict))throw Error('Invalid verdict')
      verdict=review.verdict
      if(verdict==='PASS' && (turns.length!==item.turns.length || !turns.every(t=>t.runId) || record.teamUnchanged!==true || capture.configurationUnchanged!==true || capture.cleanupErrors?.length || (item.faultSetup&&!review.faultEvidence?.trim())))throw Error('Incomplete evidence cannot pass')
    }
    return {caseId:item.id,category:item.category,generation,turns,observationSha256,verdict}
  })
  return {status:'REVIEW_REPORT',groups:rows.length,executedTurns:rows.reduce((n,r)=>n+r.turns.length,0),byGeneration:Object.fromEntries(['MODEL','LOCAL','UNKNOWN'].map(k=>[k,{groups:rows.filter(r=>r.generation===k).length,pass:rows.filter(r=>r.generation===k&&r.verdict==='PASS').length}])),verdicts:Object.fromEntries(['PASS','FAIL','BLOCKED','PENDING'].map(k=>[k,rows.filter(r=>r.verdict===k).length])),rows}
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [command,...args]=process.argv.slice(2)
  if(command==='prepare')prepare(path.resolve(args[0]??directory),cases)
  else if(command==='subset') {
    const selected=cases.filter(c=>args[1]==='normal'?c.executionKind==='NORMAL':c.id===args[1])
    if(!selected.length)throw Error('Use normal or one case ID')
    prepare(path.resolve(args[0]),selected)
  } else if(command==='report') {
    const [suiteDir,captureFile,output,reviewsFile]=args
    const suite=JSON.parse(fs.readFileSync(path.join(suiteDir,'cases.json'))),capture=JSON.parse(fs.readFileSync(captureFile))
    const reviews=reviewsFile?JSON.parse(fs.readFileSync(reviewsFile)):[]
    const base=path.dirname(path.resolve(captureFile))
    writeNew(output,summarize(suite,capture,name=>{
      if(path.basename(name)!==name)throw Error('Invalid event filename')
      return JSON.parse(fs.readFileSync(path.join(base,name)))
    },reviews))
  } else throw Error('Usage: prepare [NEW_DIR] | subset NEW_DIR normal|CASE_ID | report SUITE CAPTURE NEW_REPORT [REVIEWS]')
}
