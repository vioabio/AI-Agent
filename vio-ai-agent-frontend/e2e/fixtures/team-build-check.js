import {buildCheckFixture,buildCheckRequest} from './build-check.js'
import {duplicateFormSlots,teamBuildCheckScope} from '../../src/api/team-build-check.js'
export function teamBuildCheckRequest(){const r=buildCheckRequest();return {dataReleaseId:r.dataReleaseId,gameReleaseId:r.gameReleaseId,members:[{slot:1,pokemonId:r.pokemonId,build:r.build}]}}
export function teamBuildCheckFixture(request){
 const members=request.members.map(m=>({slot:m.slot,report:buildCheckFixture({pokemonId:m.pokemonId,build:m.build,dataReleaseId:request.dataReleaseId,gameReleaseId:request.gameReleaseId})}))
 const counts={REFERENCE_MATCHED:members.length,INCOMPLETE:0,REFERENCE_UNAVAILABLE:0,REFERENCE_MISMATCH:0,INVALID_INPUT:0}
 return {schemaVersion:'pokemon-team-build-check-v1',request:structuredClone(request),members,counts,duplicateFormSlots:duplicateFormSlots(request),jointStatus:'NOT_VERIFIED',scope:teamBuildCheckScope,reportSha256:'sha256:'+'c'.repeat(64)}
}
