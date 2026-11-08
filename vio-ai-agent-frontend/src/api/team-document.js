import {normalizeTransferTeam, parseTeamImport, MAX_TEAM_FILE_BYTES, TERA_LABELS} from './team-transfer.js'
import {NATURE_LABELS} from './pokemon-natures.js'
const stats=['hp','atk','def','spa','spd','spe']
const statNames={hp:'hp',atk:'atk',def:'def',spa:'spa',spd:'spd',spe:'spe',spatk:'spa',spdef:'spd',speed:'spe'}
const slug=value=>value.normalize('NFKD').toLowerCase().replace(/['’:.]/g,'').replace(/[^a-z0-9\u4e00-\u9fff]+/g,'-').replace(/^-|-$/g,'')
function defaults(){return {moves:[],ability:'',item:'',nature:'hardy',level:100,evs:Object.fromEntries(stats.map(s=>[s,0])),ivs:Object.fromEntries(stats.map(s=>[s,31]))}}
function training(text,max){
  const value=Object.fromEntries(stats.map(s=>[s,max===31?31:0])),seen=new Set()
  for(const part of text.split('/')){
    const match=/^\s*(\d+)\s+([A-Za-z ]+)\s*$/.exec(part), key=match&&statNames[match[2].toLowerCase().replaceAll(' ','')]
    if(!key||seen.has(key)||Number(match[1])>max)throw new Error('EV/IV 格式或范围无效')
    seen.add(key);value[key]=Number(match[1])
  }
  return value
}
function nature(value){return Object.keys(NATURE_LABELS).find(key=>key===value.toLowerCase()||NATURE_LABELS[key]===value) || value.toLowerCase()}
function tera(value){const key=Object.keys(TERA_LABELS).find(key=>key===value.toLowerCase()||TERA_LABELS[key]===value);if(!key)throw new Error('无法识别太晶属性');return key}
export function parseShowdown(text){
  const normalized=text.replace(/\r\n?/g,'\n').replace(/^\uFEFF/,'').trim()
  if(normalized.includes('==='))throw new Error('请一次导入一支队伍，先移除多队伍分隔标题')
  const blocks=normalized.split(/\n\s*\n/)
  if(blocks.length<1||blocks.length>6)throw new Error('请提供一支 1–6 人队伍')
  return blocks.map((block,index)=>{
    const lines=block.split('\n').map(s=>s.trim()).filter(Boolean), header=lines.shift()
    const at=header.lastIndexOf(' @ '), species=at<0?header:header.slice(0,at), build=defaults()
    // Nicknames, gender and cosmetic fields have no persistent model yet: never silently discard.
    if(/[()]/.test(species))throw new Error(`成员 ${index+1} 含昵称或性别标记，当前模型不支持，请先移除后导入`)
    build.item=at<0?'':header.slice(at+3)
    const seen=new Set()
    for(const line of lines){
      if(line.startsWith('- ')){build.moves.push(line.slice(2).trim());continue}
      const field=/^(Ability|Level|Tera Type|EVs|IVs):\s*(.+)$/.exec(line)
      if(field){
        const [,key,value]=field;if(seen.has(key))throw new Error(`重复字段：${key}`);seen.add(key)
        if(key==='Ability')build.ability=value
        if(key==='Level'){if(!/^\d+$/.test(value))throw new Error('等级必须为整数');build.level=Number(value)}
        if(key==='Tera Type')build.teraType=tera(value)
        if(key==='EVs')build.evs=training(value,252)
        if(key==='IVs')build.ivs=training(value,31)
      }else if(/ Nature$/.test(line)){if(seen.has('nature'))throw new Error('性格重复');seen.add('nature');build.nature=nature(line.slice(0,-7))}
      else throw new Error(`成员 ${index+1} 存在暂不支持字段：${line.slice(0,60)}。请明确处理，不能静默丢弃`)
    }
    if(!build.moves.length||!build.ability)throw new Error(`成员 ${index+1} 缺少特性或招式`)
    return {species,slot:index+1,build}
  })
}
export function csvRows(text){
  const rows=[];let row=[],cell='',quoted=false
  for(let i=0;i<text.length;i++){
    const c=text[i]
    if(c==='"'){if(quoted&&text[i+1]==='"'){cell+='"';i++}else{if(!quoted&&cell)throw new Error('CSV 引号格式无效');quoted=!quoted}}
    else if(!quoted&&(c===','||c==='\n')){row.push(cell);cell='';if(c==='\n'){if(row.some(v=>v.trim()))rows.push(row);row=[]}}
    else if(c!=='\r'||quoted)cell+=c
  }
  if(quoted)throw new Error('CSV 引号没有闭合')
  row.push(cell);if(row.some(v=>v.trim()))rows.push(row)
  return rows
}
export function parseCsv(text){
  const rows=csvRows(text.replace(/^\uFEFF/,'')),headers=rows.shift()?.map(v=>v.trim())||[]
  const allowed=['species','formId','ability','item','nature','level','teraType','move1','move2','move3','move4',...stats.flatMap(s=>[`${s}EV`,`${s}IV`])]
  if(new Set(headers).size!==headers.length||headers.some(h=>!allowed.includes(h))||!headers.some(h=>['species','formId'].includes(h)))throw new Error('CSV 表头无效；需要 species 或 formId，其他字段请按导入模板填写')
  if(!rows.length||rows.length>6)throw new Error('CSV 需要 1–6 行成员')
  return rows.map((row,index)=>{
    if(row.length!==headers.length)throw new Error(`CSV 第 ${index+2} 行列数不符`)
    const data=Object.fromEntries(headers.map((key,i)=>[key,row[i].trim()])),build=defaults()
    for(const key of ['ability','item'])if(data[key])build[key]=data[key]
    if(data.nature)build.nature=nature(data.nature)
    if(data.teraType)build.teraType=tera(data.teraType)
    const number=value=>{if(!/^\d+$/.test(value))throw new Error('CSV 数值必须为非负整数');return Number(value)}
    if(data.level)build.level=number(data.level)
    for(const stat of stats)for(const [suffix,field] of [['EV','evs'],['IV','ivs']])if(data[`${stat}${suffix}`])build[field][stat]=number(data[`${stat}${suffix}`])
    build.moves=[1,2,3,4].map(n=>data[`move${n}`]).filter(Boolean)
    return {species:data.species,formId:data.formId?number(data.formId):null,slot:index+1,build}
  })
}
export async function parseTeamDocument(contents,{gameReleaseId,rulesetId,dataReleaseId,name,resolvePokemon,resolveResource}){
  if(new TextEncoder().encode(contents).length>MAX_TEAM_FILE_BYTES)throw new Error('队伍文本不能超过 1 MB')
  if(contents.replace(/^\uFEFF/,'').trim().startsWith('{'))return {team:parseTeamImport(contents,gameReleaseId),warnings:[]}
  const rows=contents.split('\n')[0].split(',').some(v=>['species','formId'].includes(v.trim()))?parseCsv(contents):parseShowdown(contents)
  const members=[]
  for(const row of rows){
    const formId=row.formId || await resolvePokemon(row.species)
    const build={...row.build}
    for(const field of ['ability','item'])if(build[field])build[field]=await resolveResource(field==='ability'?'ability':'item',build[field],formId)
    build.moves=await Promise.all(build.moves.map(move=>resolveResource('move',move,formId)))
    members.push({formId,slot:row.slot,build})
  }
  return {team:normalizeTransferTeam({name,rulesetId,dataReleaseId,members}),warnings:['文本格式未提供的等级按 Showdown 100 级；未列 EV 为 0，未列 IV 为 31，缺省性格为勤奋。请确认当前游戏、规则和数据版本；格式校验不代表赛制合法。']}
}
export function englishResource(value){return /^[A-Za-z0-9 .:'’%-]+$/.test(value)?slug(value):null}
export function pokemonLookupName(value){return englishResource(value)||value}
