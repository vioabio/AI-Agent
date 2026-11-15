import {digest, eventBoundaryReached} from './evaluation.mjs'

/** Validate recorded events. Passing this check says nothing about semantic answer quality. */
export function verifyTurnEvents(turn, caseId, turnIndex, bytes) {
  if (!/^[A-Z0-9-]{1,80}$/.test(caseId) || !Number.isInteger(turnIndex) || turnIndex < 0
      || turn.eventsFile !== `${caseId}-${turnIndex}-events.json`) throw Error('EVENT_FILENAME_MISMATCH')
  if (typeof bytes !== 'string' || Buffer.byteLength(bytes) > 16777216
      || digest(bytes) !== turn.eventsSha256) throw Error('EVENT_HASH_MISMATCH')
  const events = JSON.parse(bytes)
  if (!Array.isArray(events) || !events.length || events.length > 10000) throw Error('EVENT_ARRAY_REQUIRED')
  let previousId = -1n
  for (const event of events) {
    if (!event || event.runId !== turn.runId) throw Error('EVENT_RUN_MISMATCH')
    const id = String(event.eventId ?? event.id)
    if (!/^[1-9][0-9]{0,19}$/.test(id) || BigInt(id) <= previousId) throw Error('EVENT_ORDER_INVALID')
    previousId = BigInt(id)
    if (event.type === 'answer.delta' && typeof event.payload?.text !== 'string') throw Error('ANSWER_DELTA_INVALID')
  }
  if (events.filter(event => event.type === 'answer.delta').map(event => event.payload.text).join('') !== turn.answer)
    throw Error('ANSWER_EVENT_MISMATCH')
  const terminal = events.filter(event => ['run.completed', 'run.failed', 'run.cancelled', 'run.timed.out', 'run.timed_out'].includes(event.type))
  if (turn.status !== 'COMPLETED' || !eventBoundaryReached(turn.status, events)
      || terminal.length !== 1 || terminal[0].type !== 'run.completed') throw Error('EVENT_TERMINAL_MISMATCH')
  return {events:events.length, runId:turn.runId, eventsSha256:turn.eventsSha256}
}

export function verifySelectedEvents(report, readEvents) {
  const turns = []
  for (const id of report.selectedCaseIds) {
    const record = report.records.find(row => row.caseId === id)
    for (const [index, turn] of record.turns.entries()) turns.push(verifyTurnEvents(turn, id, index, readEvents(turn.eventsFile)))
  }
  return {status:'VERIFIED', turns:turns.length, events:turns.reduce((sum, turn) => sum + turn.events, 0)}
}
