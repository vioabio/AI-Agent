const {test}=require('node:test')
const assert=require('node:assert/strict')
const {calculateInput}=require('./calculate-v2.cjs')
test('SDK omission of an empty item and its default abilities are observable',()=>{
 const r=JSON.parse(JSON.stringify(calculateInput({generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle'},defender:{species:'charmander'},move:'surf'})))
 assert.equal(Object.hasOwn(r.assumptions.attacker,'item'),false);assert.equal(Object.hasOwn(r.assumptions.defender,'item'),false)
 assert.equal(r.assumptions.attacker.ability,'Torrent');assert.equal(r.assumptions.defender.ability,'Blaze')
})
function battle(){return {generation:9,dataReleaseId:'pokeapi-reference-v1',attacker:{species:'squirtle',build:{ability:'torrent',nature:'modest',level:50,evs:{spa:252}}},defender:{species:'charmander',build:{level:50}},move:'surf',field:{gameType:'Singles'}}}

test('zero damage remains a valid result even when SDK KO description cannot format it',()=>{
 const input=battle();input.move='tackle';input.defender={species:'froslass',build:{ability:'cursed-body',level:57}}
 const result=calculateInput(input)
 assert.deepEqual(result.range,{minimum:0,maximum:0})
 assert.deepEqual(result.percent,{minimum:0,maximum:0})
 assert.equal(result.defenderHp,164)
 assert.equal(result.assumptions.move,'Tackle')
 assert.equal(result.assumptions.defender.species,'Froslass')
 assert.match(result.description,/0-0 \(0%\)/)
 assert.equal(result.moveType,'Normal')
 assert.deepEqual(result.defenderTypes,['Ice','Ghost'])
})
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

test('versioned bridge preserves the frozen v1 positive-damage contract',()=>{
 const legacy=require('./calculate.cjs').calculateInput
 for(const weather of [undefined,'Rain','Sun']){
  const input=battle();input.field.weather=weather
  const {moveType,defenderTypes,...compatible}=calculateInput(input)
  assert.equal(moveType,'Water');assert.deepEqual(defenderTypes,['Fire'])
  assert.deepEqual(compatible,legacy(input))
 }
})
test('unknown species, status moves, generation and invalid EV are rejected',()=>{
  for(const patch of [{generation:8},{move:'protect'},{attacker:{species:'made-up'}},{attacker:{species:'squirtle',build:{evs:{hp:252,atk:252,spa:252}}}}])assert.throws(()=>calculateInput({...battle(),...patch}))
})
