// Derived only from the report currently bound to this form; this is not a legality engine.
export const learningNotesVersion = 'recorded-learning-level-notes-v1'
export function validLearningEntries(entries) {
  return Array.isArray(entries) && entries.length <= 32 && entries.every(row => row &&
    /^[a-z0-9][a-z0-9-]{0,79}$/.test(row.methodKey || '') && Number.isInteger(row.levelLearnedAt) &&
    row.levelLearnedAt >= 0 && row.levelLearnedAt <= 100 &&
    (row.order === null || (Number.isInteger(row.order) && row.order >= 0 && row.order <= 1000)))
}
export function buildLearningNotes(move, level) {
  if (move.status !== 'RECORDED' || !move.learning || !validLearningEntries(move.learning.entries)) return []
  const entries = [...move.learning.entries].sort((a, b) => a.methodKey < b.methodKey ? -1 : a.methodKey > b.methodKey ? 1 : a.levelLearnedAt - b.levelLearnedAt)
  const notes = [...new Set(entries.map(row => {
    switch (row.methodKey) {
      case 'level-up':
        if (row.levelLearnedAt === 0) return '升级记录：等级字段为 0，不能据此解释为可在 0 级学习。'
        return `升级学习：记录等级 ${row.levelLearnedAt}；` + (!Number.isInteger(level) || level < 1 || level > 100
          ? '未提供有效配置等级，暂不比较。'
          : row.levelLearnedAt > level ? `高于配置等级 ${level}，需另核对取得方式。`
            : `不高于配置等级 ${level}，实际取得仍待核对。`)
      case 'machine': return '招式学习器：快照记录此途径；未核对编号、获取与使用条件。'
      case 'egg': return '蛋招式：快照记录此类别；未核对遗传、传授或具体操作条件。'
      case 'tutor': return '教授招式：快照记录此类别；未核对NPC、地点或操作条件。'
      default: return `其他学习方式（${row.methodKey}）：仅保留快照类别，具体操作待核对。`
    }
  }))]
  return notes.length > 6 ? [...notes.slice(0, 6), `另有 ${notes.length - 6} 条记录，详见来源；不据此推断其他路径。`] : notes
}
