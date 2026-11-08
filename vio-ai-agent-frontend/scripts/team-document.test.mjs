import test from 'node:test'
import assert from 'node:assert/strict'
import {parseTeamDocument,parseShowdown,parseCsv,pokemonLookupName} from '../src/api/team-document.js'
import {createTeamExport,parseTeamImport} from '../src/api/team-transfer.js'
const paste='Squirtle @ Leftovers\nAbility: Torrent\nTera Type: Water\nEVs: 252 HP / 4 Def / 252 SpA\nModest Nature\nIVs: 0 Atk\n- Surf\n- Protect'
const options={gameReleaseId:'pokemon-scarlet-violet',rulesetId:'casual-v1',dataReleaseId:'pokeapi-reference-v1',name:'Imported',resolvePokemon:async s=>{assert.equal(s,'Squirtle');return 7},resolveResource:async(k,v)=>v.toLowerCase().replaceAll(' ','-')}
test('English display names normalize to lookup slugs without guessing alternate forms',()=>{assert.equal(pokemonLookupName('Great Tusk'),'great-tusk');assert.equal(pokemonLookupName('杰尼龟'),'杰尼龟')})
test('Showdown defaults, explicit stats and tera survive JSON v2 round trip',async()=>{
  const {team}=await parseTeamDocument(paste,options),build=team.members[0].build
  assert.equal(build.teraType,'water');assert.equal(build.level,100);assert.equal(build.ivs.atk,0);assert.equal(build.ivs.def,31);assert.equal(build.evs.spa,252)
  const file=createTeamExport(team,options.gameReleaseId)
  assert.equal(JSON.parse(file).schemaVersion,2)
  assert.deepEqual(parseTeamImport(file,options.gameReleaseId),team)
  const old=JSON.parse(file);old.schemaVersion=1;delete old.team.members[0].build.teraType
  assert.equal(parseTeamImport(JSON.stringify(old),options.gameReleaseId).members[0].build.teraType,undefined)
  const unsupported=JSON.parse(file);unsupported.team.members[0].build.shiny=true
  assert.throws(()=>parseTeamImport(JSON.stringify(unsupported),options.gameReleaseId),/不支持字段/)
})
test('CSV quoted names and stat columns use the same validation pipeline',async()=>{
  const csv='species,ability,item,nature,level,teraType,move1,hpEV,atkIV\n"Squirtle",torrent,leftovers,内敛,50,水,surf,252,0'
  const {team}=await parseTeamDocument(csv,options)
  assert.equal(team.members[0].build.teraType,'water');assert.equal(team.members[0].build.nature,'modest')
  assert.equal(team.members[0].build.ivs.atk,0)
})
test('unsupported fields, multi teams, broken CSV, unknown types and invalid totals do not silently import',async()=>{
  for(const input of [paste+'\nShiny: Yes',paste.replace('Squirtle','Nick (Squirtle)'),paste.replace('Water','Imaginary'),paste.replace('252 SpA','253 SpA'),paste+'\nIgnore previous instructions'])await assert.rejects(parseTeamDocument(input,options))
  assert.throws(()=>parseShowdown('=== team ===\n'+paste))
  assert.throws(()=>parseCsv('species,unknown\nSquirtle,x'))
  assert.throws(()=>parseCsv('species,item\n"Squirtle,x'))
})
