// Synthetic browser/contract fixture only; backend/source correctness is verified separately.
export function buildCheckFixture(request){
 const checksum='sha256:'+'a'.repeat(64)
 return {schemaVersion:'pokemon-build-check-v1',request:JSON.parse(JSON.stringify(request)),syntaxStatus:'VALID_SUPPLIED_FIELDS',missingFields:[],status:'REFERENCE_MATCHED',
  source:{pokemonId:request.pokemonId,slug:'squirtle',nameZh:'杰尼龟',sourceUrl:'https://pokeapi.co/api/v2/pokemon/7/',pokemonChecksum:checksum},abilityStatus:'MATCHED',
  moves:request.build.moves.map((move,index)=>({slot:index+1,supplied:move,normalized:move.toLowerCase().replaceAll(' ','-'),status:'RECORDED',learning:{schemaVersion:'pokemon-learnset-facts-v1',pokemonId:request.pokemonId,moveSlug:move.toLowerCase().replaceAll(' ','-'),sourceChecksum:checksum,entries:[{methodKey:'machine',levelLearnedAt:0,order:null}]}})),
  scope:'SUPPLIED_FIELDS_AND_REFERENCE_MEMBERSHIP_NOT_JOINT_LEGALITY',reportSha256:'sha256:'+'b'.repeat(64)}
}
export function buildCheckRequest(){return {pokemonId:7,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',build:{moves:['water-pulse','ice-beam','protect','surf'],ability:'torrent',item:'',nature:'modest',level:50,evs:{hp:0,atk:0,def:0,spa:252,spd:4,spe:252},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}}}}
