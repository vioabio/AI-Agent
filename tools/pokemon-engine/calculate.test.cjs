const {test}=require('node:test')
const assert=require('node:assert/strict')
const {calculateInput}=require('./calculate.cjs')
function battle(){return {generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle',build:{ability:'torrent',nature:'modest',level:50,evs:{spa:252}}},defender:{species:'charmander',build:{level:50}},move:'surf',field:{gameType:'Singles'}}}
test('real engine resolves PokeAPI slugs and returns deterministic ranges plus assumptions',()=>{
  const r=calculateInput(battle())
  assert.equal(r.engineVersion,'0.12.0');assert.ok(r.range.minimum>0);assert.ok(r.range.maximum>=r.range.minimum)
  assert.equal(r.attackerBaseStats.hp,44);assert.equal(r.defenderBaseStats.hp,39)
  assert.deepEqual(r.range,calculateInput(battle()).range)
})
test('weather changes actual engine output',()=>{
  const clear=calculateInput(battle());const rain=battle();rain.field.weather='Rain'
  assert.ok(calculateInput(rain).range.minimum>clear.range.minimum)
})
test('unknown species, status moves, generation and invalid EV are rejected',()=>{
  for(const patch of [{generation:8},{move:'protect'},{attacker:{species:'made-up'}},{attacker:{species:'squirtle',build:{evs:{hp:252,atk:252,spa:252}}}}])assert.throws(()=>calculateInput({...battle(),...patch}))
})
