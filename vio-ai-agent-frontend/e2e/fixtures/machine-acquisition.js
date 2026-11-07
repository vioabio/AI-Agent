import {readFileSync} from 'node:fs'
import {commonAcquisitionRegions} from '../../../tools/campus/machine-acquisition.mjs'
const c=JSON.parse(readFileSync(new URL('../../../src/main/resources/pokemon-machines/acquisition-catalog-v1.json',import.meta.url),'utf8'))
export function machineAcquisitionFixture(moveSlug){
 const row=structuredClone(c.records.find(r=>r.moveSlug===moveSlug))
 return {schemaVersion:'pokemon-machine-acquisition-reference-v1',gameReleaseId:c.gameReleaseId,moveSlug,label:row?.label??null,nameZh:row?.nameZh??null,...(row?commonAcquisitionRegions(row,c.regionMappings):{status:'NOT_CATALOGUED',regions:[]}),sources:row?structuredClone(c.sources):[],unlockStatus:'NOT_CROSS_CHECKED',checkedAt:c.checkedAt,reviewStatus:c.reviewStatus,scope:c.scope,catalogSha256:'sha256:e8cee2cd0fc2e36a1a794329d826f2fefbab988d59053d9b2ca12c850031bd3e'}
}
