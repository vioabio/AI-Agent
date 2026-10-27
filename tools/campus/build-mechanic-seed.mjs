import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {loadSuite,validateSuite,frozenFileDigest,digest} from './evaluation.mjs'
export function buildMechanicSeed(destination) {
 if(fs.existsSync(destination))throw Error('Never overwrite a suite')
 const parent='evaluation/campus/user-reports-v2',old=loadSuite(parent),catalogFile='src/main/resources/pokemon-mechanics/references-v1.json',catalog=JSON.parse(fs.readFileSync(catalogFile,'utf8'))
 const sources=structuredClone(old.sources),cases=structuredClone(old.cases),at=new Date().toISOString()
 for(const r of catalog.records)sources.push({id:`resource-${r.kind}-${r.slug}`,locator:r.sourceUrl,sha256:r.checksum,capturedAt:r.retrievedAt,
  scope:'Public resource fields; SV short text and separately marked project paraphrase, pending independent review; not complete mechanics/learnset/legality',resource:r})
 for(const item of cases){const ref=old.fixtures.references[item.expected.referenceFixture],keys=catalog.records.filter(r=>r.kind==='nature'||(r.kind==='ability'?ref.availableAbilitySlugs:ref.availableMoveSlugs).includes(r.slug)).map(r=>`resource-${r.kind}-${r.slug}`)
  for(const criterion of item.expected.criteria)if(criterion.id!=='species-facts')criterion.sourceIds.push(...keys)
  item.annotation.sourceExtension='PINNED_RESOURCE_FIELDS_AND_SV_PARAPHRASE_NOT_NEW_USER_QUESTION';item.annotation.parentManifestSha256=frozenFileDigest(path.join(parent,'manifest.json'))
 }
 const values={'cases.json':cases,'sources.json':sources,'splits.json':old.splits,'fixtures.json':old.fixtures}
 const bytes=Object.fromEntries(Object.entries(values).map(([k,v])=>[k,JSON.stringify(v,null,2)+'\n']))
 const manifest={...old.manifest,datasetId:'campus-user-report-dev-v3',createdAt:at,parentManifestSha256:frozenFileDigest(path.join(parent,'manifest.json')),
  resourceCatalogSha256:frozenFileDigest(catalogFile),newFormalAdmissions:0,sha256:Object.fromEntries(Object.entries(bytes).map(([k,v])=>[k,digest(v)]))}
 validateSuite({manifest,cases,sources,splits:old.splits,fixtures:old.fixtures});fs.mkdirSync(destination,{recursive:true})
 for(const [k,v] of Object.entries(bytes))fs.writeFileSync(path.join(destination,k),v,{flag:'wx'})
 fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'})
 return {datasetId:manifest.datasetId,cases:2,newFormalAdmissions:0,resourceSources:catalog.records.length}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [out,...extra]=process.argv.slice(2);if(!out||extra.length)throw Error('Usage: node tools/campus/build-mechanic-seed.mjs NEW_SUITE');console.log(JSON.stringify(buildMechanicSeed(out)))}
