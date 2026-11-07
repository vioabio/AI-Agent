import {sameJson} from './evaluation.mjs'
export function auditRecordingPhases(readOnly,preview,priorFileSha256){
 if(!/^[a-f0-9]{64}$/.test(priorFileSha256)||preview.continuationFromSha256!==priorFileSha256)throw Error('Preview must bind the preserved failed first phase')
 if(!sameJson(readOnly.runtime?.implementationSha256,preview.runtime?.implementationSha256)||!readOnly.runtime?.implementationSha256||readOnly.publicFrontendIndexSha256!==preview.publicFrontendIndexSha256)throw Error('Different runtime or frontend between phases')
 const first=readOnly.tasks?.[0],second=preview.tasks?.[0]
 if(readOnly.tasks?.length!==1||preview.tasks?.length!==1||![first,second].every(t=>Number.isSafeInteger(t.modelCalls)&&t.modelCalls>0))throw Error('Two actual model tasks required')
 const expected=structuredClone(preview.before)
 if(!expected?.members?.[0]?.build)throw Error('Original configured team required')
 expected.members[0].build.level=51
 const changes=preview.preview?.changes
 const previewChecks={completed:second.status==='COMPLETED',noWriteBeforeDecision:preview.noWriteBeforeConfirmation===true,
  onlyRequestedPreview:Array.isArray(changes)&&changes.length===1&&changes[0].slot===1&&changes[0].field==='等级'&&changes[0].before==='50'&&changes[0].after==='51',
  originalLevel:preview.before.members[0].build.level===50,exactStoredTeam:sameJson(expected,preview.after),applied:preview.outcome?.status==='APPLIED',
  receiptMatches:second.answer===preview.outcome?.message,reloadReadBack:preview.reloadReadBack===true,cleanup:!preview.cancelFailed&&!preview.logoutFailed}
 const firstPass=readOnly.pass===true&&first.audit?.pass===true,previewPass=preview.pass===true&&Object.values(previewChecks).every(Boolean)
 return {schemaVersion:'demo-recording-phase-audit-v1',readOnlyPhasePass:firstPass,previewPhasePass:previewPass,overallPass:firstPass&&previewPass,previewChecks,modelCalls:first.modelCalls+second.modelCalls,
  answerDeltaCounts:[first.answerDeltaCount,second.answerDeltaCount],tokenByTokenStreamingVerified:false,approvalActor:preview.approvalActor,humanStrategyAcceptance:false,
  fullRecruitingVideoComplete:false,scope:'Independent recorded phases, not a single uninterrupted successful demo. One answer delta does not establish token streaming.'}
}
