import {createHash} from 'node:crypto'

// Explicit resource-hash coverage, not all classes or an attestation of JVM hot reload.
export const componentManifestVersion='u6-runtime-components-v26'
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
 ['ReferenceRulesReply','agent'],['GymFocusedFactReply','agent'],['OwnedTeamReadRoute','agent'],['ExplicitDamageRequest','agent'],
 ['AgentToolObservation','observability'],['AgentTracingConfiguration','observability'],['ToolExecutionGateway','tools/governance'],
 ['TaskSkillRegistry','agent'],['TaskContextAssembler','agent'],['GuideReferenceIdentity','agent'],
 ['LocalPokeApiRepository','pokemon/reference'],['RegionalFormName','pokemon/reference'],['AgentRunObservation','observability'],
 ['DomainTracingObservationHandler','observability'],['SpecialistAgentCoordinator','agent'],
 ['PokemonAnswerBlock','agent'],['DamageAnswerBlock','agent'],['EvidenceAnswerBlock','agent'],
 ['EvidenceAnswerBlock$Source','agent'],['TeamSuggestionAnswerBlock','agent'],
 ['LocalDamageEngine','pokemon/engine'],['LocalDamageEngine$Combatant','pokemon/engine'],['DamageToolSchema','tools/governance'],
 ['TeamAnswerEvidence','agent'],['DamageAnswerEvidence','agent'],['DamageDialogueRoute','agent'],['GameVersionRequest','agent'],['CompetitiveTeamsTool','tools'],
 ['LocalGuideSemanticSearch','pokemon/guides'],['GuideSemanticProperties','pokemon/guides'],['GuideEmbeddingClient','pokemon/guides'],
 ['GuideApplicability','pokemon/guides'],['SqliteGuideKnowledgeRetriever','pokemon/guides'],['GuideChunks','pokemon/guides'],
 ['TypeMatchupReply','agent'],['SpecialistRole','agent'],['GymCatalogAnswer','agent'],['TrainingMechanicsReply','agent'],['GymDialogueRouter','agent'],
 ['ConfigurationExplanationRoute','agent'],['ConfigurationExplanationRoute$Topic','agent'],['ConversationRecallReply','agent'],
 ['GymConversationContext','agent'],['GymTurnPlan','agent'],['SourceInstructionReply','agent'],
 ['GuideRerankConfiguration','pokemon/guides'],['GuideRerankConfiguration$HttpReranker','pokemon/guides'],
 ['GuideRerankConfiguration$HttpReranker$LimitedBody','pokemon/guides'],['GuideRerankPipeline','pokemon/guides'],['GuideReranker','pokemon/guides'],
 ['McpCancellationTransport','mcp/client'],['McpCancellationTransport$Scope','mcp/client'],
 ['McpCancellationTransport$Invocation','mcp/client'],['McpCancellationTransport$Snapshot','mcp/client'],
 ['CancellableMcpToolCallback','mcp/client'],['McpCancellationConfiguration','config'],['McpCancellationConfiguration$1','config']
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
