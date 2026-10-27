import {machineReferenceScope} from '../../src/api/machine-reference.js'
export function machineFixture(learning){
 const itemSlug=({'water-pulse':'tm11','ice-beam':'tm135',protect:'tm07','hydro-pump':'tm142'})[learning.moveSlug]
 const machineId=({'water-pulse':1826,'ice-beam':1950,protect:1822,'hydro-pump':1957})[learning.moveSlug]
 const hash='sha256:'+'a'.repeat(64)
 return {schemaVersion:'pokemon-machine-reference-v1',pokemonId:learning.pokemonId,moveSlug:learning.moveSlug,dataReleaseId:learning.dataReleaseId,gameReleaseId:learning.gameReleaseId,status:itemSlug?'MATCHED':'NOT_CATALOGUED',learning:structuredClone(learning),machine:itemSlug?{machineId,itemSlug,label:`TM${String(Number(itemSlug.slice(2))).padStart(3,'0')}`,moveUrl:`https://pokeapi.co/api/v2/move/${learning.moveSlug}/`,machineUrl:`https://pokeapi.co/api/v2/machine/${machineId}/`,itemUrl:`https://pokeapi.co/api/v2/item/${itemSlug}/`,moveChecksum:hash,machineChecksum:hash,itemChecksum:hash}:null,scope:machineReferenceScope,reportSha256:hash}
}
export function machineLearning(moveSlug='water-pulse'){return {schemaVersion:'pokemon-learnset-facts-v1',pokemonId:7,pokemonSlug:'squirtle',moveSlug,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',sourceChecksum:'sha256:'+'a'.repeat(64),projectionChecksum:'sha256:'+'b'.repeat(64),entries:[{methodKey:'machine',levelLearnedAt:0,order:null}]}}
