import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {loadSuite,validateSuite,frozenFileDigest,digest} from './evaluation.mjs'
export function buildCoverageSeed(destination) {
 if(fs.existsSync(destination))throw Error('Never overwrite a suite')
 const parent='evaluation/campus/user-reports-v3',old=loadSuite(parent),cases=structuredClone(old.cases)
 for(const item of cases){item.expected.resourceKinds=item.expected.referenceFixture==='froslass'?['ability','move']:[];
  item.annotation.coverageExtension='PREDECLARED_CANDIDATE_BOUND_KIND_CHECKS_NOT_COMPLETE_MECHANICS';item.annotation.parentManifestSha256=frozenFileDigest(path.join(parent,'manifest.json'))}
 const values={'cases.json':cases,'sources.json':old.sources,'splits.json':old.splits,'fixtures.json':old.fixtures},bytes=Object.fromEntries(Object.entries(values).map(([k,v])=>[k,JSON.stringify(v,null,2)+'\n']))
 const manifest={...old.manifest,datasetId:'campus-user-report-dev-v4',createdAt:new Date().toISOString(),parentManifestSha256:frozenFileDigest(path.join(parent,'manifest.json')),
  categoryCheckScope:'Each predeclared kind requires a successful candidate-bound read and displayed sample; not all entities, full mechanisms or a moveset',newFormalAdmissions:0,
  sha256:Object.fromEntries(Object.entries(bytes).map(([k,v])=>[k,digest(v)]))}
 validateSuite({manifest,cases,sources:old.sources,splits:old.splits,fixtures:old.fixtures});fs.mkdirSync(destination,{recursive:true})
 for(const [k,v] of Object.entries(bytes))fs.writeFileSync(path.join(destination,k),v,{flag:'wx'});fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'})
 return {datasetId:manifest.datasetId,cases:cases.length,explicitKinds:cases.map(c=>({id:c.id,requiredKinds:c.expected.resourceKinds})),newFormalAdmissions:0}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const [out,...extra]=process.argv.slice(2);if(!out||extra.length)throw Error('Usage: node tools/campus/build-resource-coverage-seed.mjs NEW_SUITE');console.log(JSON.stringify(buildCoverageSeed(out)))}
