const { calculate, Generations, Pokemon, Move, Field } = require('@smogon/calc')
const engineVersion = require('@smogon/calc/package.json').version
const id = value => String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '')
const stats = ['hp','atk','def','spa','spd','spe']
function named(collection, value, kind, optional=false) {
  if(optional && !value) return undefined
  const entry = collection.get(id(value))
  if(!entry) throw new Error(`Unknown ${kind}: use an English name or slug`)
  return entry.name
}
function buildPokemon(gen, input) {
  if(!input || typeof input.species !== 'string') throw new Error('Pokemon species is required')
  const b = input.build || {}, evs={}, ivs={}
  for(const stat of stats) {
    evs[stat] = b.evs?.[stat] ?? 0
    ivs[stat] = b.ivs?.[stat] ?? 31
    if(!Number.isInteger(evs[stat]) || evs[stat]<0 || evs[stat]>252 || !Number.isInteger(ivs[stat]) || ivs[stat]<0 || ivs[stat]>31) throw new Error('Invalid EV or IV')
  }
  const level = b.level ?? 50
  if(!Number.isInteger(level)||level<1||level>100||Object.values(evs).reduce((a,n)=>a+n,0)>510) throw new Error('Invalid level or EV total')
  const options={level,evs,ivs,nature:named(gen.natures,b.nature||'hardy','nature'),
    ...(b.item ? {item:named(gen.items,b.item,'item')} : {}),
    ...(b.ability ? {ability:named(gen.abilities,b.ability,'ability')} : {})}
  return new Pokemon(gen,named(gen.species,input.species,'species'),options)
}
function calculateInput(input) {
  if(input?.generation!==9) throw new Error('Only explicitly selected generation 9 is supported')
  const gen=Generations.get(9), attacker=buildPokemon(gen,input.attacker),defender=buildPokemon(gen,input.defender)
  const move=new Move(gen,named(gen.moves,input.move,'move'))
  if(move.category==='Status') throw new Error('Status moves have no direct damage range')
  const f=input.field||{}
  const gameType=f.gameType||'Singles'
  if(!['Singles','Doubles'].includes(gameType))throw new Error('Invalid game type')
  if(f.weather && !['Sun','Rain','Sand','Hail','Snow','Harsh Sunshine','Heavy Rain','Strong Winds'].includes(f.weather))throw new Error('Invalid weather')
  if(f.terrain && !['Electric','Grassy','Misty','Psychic'].includes(f.terrain))throw new Error('Invalid terrain')
  const field=new Field({gameType,weather:f.weather||undefined,terrain:f.terrain||undefined})
  const result=calculate(gen,attacker,defender,move,field)
  const [minimum,maximum]=result.range(),hp=defender.maxHP()
  return {engine:'@smogon/calc',engineVersion,generation:9,dataReleaseId:input.dataReleaseId,
    range:{minimum,maximum},defenderHp:hp,percent:{minimum:100*minimum/hp,maximum:100*maximum/hp},
    // calc's KO-description formatter can throw on an all-zero damage array.
    // Preserve the computed numeric result without asking it for KO odds.
    description:maximum===0 ? `${attacker.name} ${move.name} vs. ${defender.name}: 0-0 (0%)` : result.desc(),
    attackerBaseStats:attacker.species.baseStats,defenderBaseStats:defender.species.baseStats,
    assumptions:{attacker:assumptions(attacker),defender:assumptions(defender),move:move.name,field:{gameType,weather:f.weather||null,terrain:f.terrain||null}},
    limitation:'Assumes full HP, no stat boosts, screens, hazards, status, Tera or other unprovided field effects. This is not a legality or win-rate result.'}
}
function assumptions(pokemon){return {species:pokemon.name,level:pokemon.level,nature:pokemon.nature,ability:pokemon.ability,item:pokemon.item,evs:pokemon.evs,ivs:pokemon.ivs}}
module.exports={calculateInput}
if(require.main===module) {
  let raw=''
  process.stdin.setEncoding('utf8')
  process.stdin.on('data',chunk=>{raw+=chunk;if(Buffer.byteLength(raw)>32768){process.stderr.write('Input too large');process.exit(1)}})
  process.stdin.on('end',()=>{try{process.stdout.write(JSON.stringify(calculateInput(JSON.parse(raw))))}catch{process.stderr.write('Calculation rejected: invalid or unsupported battle configuration');process.exitCode=1}})
}
