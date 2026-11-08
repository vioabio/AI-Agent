// Separate bridge: never changes the frozen general damage API or silently activates a saved Tera type.
const {calculate,Generations,Pokemon,Move,Field}=require('@smogon/calc')
const {createHash}=require('node:crypto')
const gen=Generations.get(9), id=s=>String(s||'').toLowerCase().replace(/[^a-z0-9]/g,'')
const stats=['hp','atk','def','spa','spd','spe'], stages=stats.slice(1)
const known=(collection,s)=>{const entry=collection.get(id(s));if(!entry)throw Error('Unknown name');return entry.name}
function keys(value,allowed){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!allowed.includes(k)))throw Error('Unsupported condition')}
function integer(n,min,max){if(!Number.isInteger(n)||n<min||n>max)throw Error('Out of range');return n}
function bool(v){if(v!==undefined&&typeof v!=='boolean')throw Error('Expected boolean');return v||false}
function member(input){
 keys(input,['species','build','state']);const b=input.build||{},s=input.state||{}
 keys(b,['moves','ability','item','nature','level','evs','ivs','teraType'])
 keys(s,['teraActive','hpPercent','status','boosts','abilityOn','alliesFainted'])
 const evs={},ivs={},boosts={};keys(b.evs||{},stats);keys(b.ivs||{},stats);keys(s.boosts||{},stages)
 for(const stat of stats){evs[stat]=integer(b.evs?.[stat]??0,0,252);ivs[stat]=integer(b.ivs?.[stat]??31,0,31)}
 if(Object.values(evs).reduce((a,b)=>a+b,0)>510)throw Error('EV total')
 for(const stat of stages)boosts[stat]=integer(s.boosts?.[stat]??0,-6,6)
 if(s.status&&!['slp','psn','brn','frz','par','tox'].includes(s.status))throw Error('Unknown status')
 const active=bool(s.teraActive);if(active&&!b.teraType)throw Error('Missing active Tera type')
 if(active&&id(b.teraType)==='stellar')throw Error('Stellar first-use tracking is not supported by this bridge')
 const ability=known(gen.abilities,b.ability),name=known(gen.species,input.species)
 const p=new Pokemon(gen,name,{level:integer(b.level??50,1,100),evs,ivs,boosts,nature:known(gen.natures,b.nature||'hardy'),ability,
  item:b.item?known(gen.items,b.item):undefined,teraType:active?known(gen.types,b.teraType):undefined,status:s.status||'',abilityOn:bool(s.abilityOn),alliesFainted:integer(s.alliesFainted??0,0,5)})
 const hp=integer(s.hpPercent??100,1,100);p.originalCurHP=Math.max(1,Math.floor(p.maxHP()*hp/100))
 return {pokemon:p,assumptions:{species:p.name,build:{...b,level:p.level,nature:p.nature,ability:p.ability,item:p.item||'',evs,ivs},state:{teraActive:active,activeTeraType:p.teraType||null,hpPercent:hp,status:s.status||'',boosts,abilityOn:bool(s.abilityOn),alliesFainted:s.alliesFainted??0}}}
}
function side(s={}){keys(s,['isReflect','isLightScreen','isAuroraVeil','isHelpingHand','isFriendGuard','isTailwind','isProtected']);return Object.fromEntries(Object.entries(s).map(([k,v])=>[k,bool(v)]))}
function calculateGym(input){
 keys(input,['attacker','defender','move','field','critical','hits'])
 const a=member(input.attacker),d=member(input.defender),f=input.field||{}
 keys(f,['gameType','weather','terrain','isGravity','attackerSide','defenderSide'])
 if(!['Singles','Doubles'].includes(f.gameType))throw Error('Game type required')
 if(f.weather&&!['Sun','Rain','Sand','Snow','Harsh Sunshine','Heavy Rain','Strong Winds'].includes(f.weather))throw Error('Unknown weather')
 if(f.terrain&&!['Electric','Grassy','Misty','Psychic'].includes(f.terrain))throw Error('Unknown terrain')
 const field={gameType:f.gameType,weather:f.weather||undefined,terrain:f.terrain||undefined,isGravity:bool(f.isGravity),attackerSide:side(f.attackerSide),defenderSide:side(f.defenderSide)}
 const move=new Move(gen,known(gen.moves,input.move),{isCrit:bool(input.critical),...(input.hits==null?{}:{hits:integer(input.hits,1,10)})})
 if(move.category==='Status')throw Error('Status moves require action simulation')
 const result=calculate(gen,a.pokemon,d.pokemon,move,new Field(field)),[minimum,maximum]=result.range(),hp=d.pokemon.maxHP()
 if(!Number.isFinite(minimum+maximum)||minimum<0||maximum<minimum)throw Error('Invalid damage')
 return {engine:'@smogon/calc',engineVersion:require('@smogon/calc/package.json').version,generation:9,
  range:{minimum,maximum},percent:{minimum:100*minimum/hp,maximum:100*maximum/hp},defenderHp:hp,defenderCurrentHp:d.pokemon.curHP(),attackerBaseStats:a.pokemon.species.baseStats,defenderBaseStats:d.pokemon.species.baseStats,moveType:move.type,
  guaranteedKo:minimum>=d.pokemon.curHP(),possibleKo:maximum>=d.pokemon.curHP(),
  assumptions:{attacker:a.assumptions,defender:d.assumptions,field,move:move.name,critical:move.isCrit,hits:move.hits},
  requestSha256:createHash('sha256').update(JSON.stringify(input)).digest('hex'),
  limitation:'Single action under supplied assumptions, not NPC hidden stats, AI action order, hit probability, residual damage, multi-turn simulation or win rate.'}
}
module.exports={calculateGym}
if(require.main===module){let raw='';process.stdin.setEncoding('utf8');process.stdin.on('data',c=>{raw+=c;if(Buffer.byteLength(raw)>32768)process.exit(1)});process.stdin.on('end',()=>{try{const result=calculateGym(JSON.parse(raw));result.requestSha256=createHash('sha256').update(raw).digest('hex');process.stdout.write(JSON.stringify(result))}catch(e){process.stderr.write('Unsupported or invalid gym battle conditions');process.exitCode=1}})}
