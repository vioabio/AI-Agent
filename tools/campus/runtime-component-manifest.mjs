import {createHash} from 'node:crypto'

// Explicit resource-hash coverage, not all classes or an attestation of JVM hot reload.
export const componentManifestVersion='u6-runtime-components-v1'
export const coreComponents=Object.freeze([
 ['AccountScopeReply','agent'],['DefaultRunExecutor','run'],['ToolCallAgent','agent'],
 ['GuideAnswerEvidence','agent'],['VioManus','agent'],['PokemonAgentRunService','run']
].map(Object.freeze))
export const additionalComponents=Object.freeze([
 ['LocalGuideSearch','pokemon/guides'],['SavedTrainingRoadmap','agent'],['SavedTrainingResources','agent'],
 ['TrainingIntentState','agent'],['TrainingTargetCheck','agent'],['TrainingLearningCheck','agent'],
 ['TrainingResourceBudget','agent'],['SavedTrainingGymReview','agent'],['TrainingStatProjection','agent'],
 ['TrainingLevelConstraints','agent'],['StaticStatCalculator','pokemon/calculator'],['TrainingProgressCheck','agent'],
 ['GymResourcePolicy','pokemon/gym'],['GuideRevisionPolicy','pokemon/guides'],['TeamMutationIntent','agent'],
 ['ReferenceRulesReply','agent'],['GymFocusedFactReply','agent']
].map(Object.freeze))
export const componentManifestSha256=createHash('sha256').update(JSON.stringify({version:componentManifestVersion,coreComponents,additionalComponents})).digest('hex')

export function matchesComponents(rows,expected){
 if(!Array.isArray(rows)||rows.length!==expected.length)return false
 const names=new Set(expected.map(([name])=>name)),seen=new Set()
 return rows.every(row=>{
  if(!row||!names.has(row.name)||seen.has(row.name)||row.matches!==true
   ||typeof row.expected!=='string'||!/^[a-f0-9]{64}$/.test(row.expected)||row.actual!==row.expected)return false
  seen.add(row.name);return true
 })
}
export function matchesCurrentBuild(comparison){
 return comparison?.schemaVersion==='runtime-artifact-comparison-v3'
  &&comparison.componentManifestVersion===componentManifestVersion
  &&comparison.componentManifestSha256===componentManifestSha256
  &&comparison.status==='ARTIFACTS_MATCH'
  &&matchesComponents(comparison.classes,coreComponents)
  &&matchesComponents(comparison.additionalComponents,additionalComponents)
  &&comparison.cleanup==='LOGGED_OUT'
}
