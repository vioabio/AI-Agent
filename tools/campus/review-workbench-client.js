(() => {
  const bundle = JSON.parse(document.getElementById('bundle').textContent)
  const $ = id => document.getElementById(id)
  let current = 0
  const submitted = new Set()
  let reviews = bundle.rows.map(row => ({ key: row.key, observationHash: row.observationHash, reviewerType: 'human', reviewer: '', reviewedAt: null,
    note: '', attested: false, decisions: row.criteria.map(c => ({ criterionId: c.id, verdict: 'unknown', reason: '', sourceIds: [] })),
    citations: row.excerpts.map(e => ({ excerptId: e.id, verdict: 'unknown', reason: '', sourceIds: [] })) }))
  const error = message => { $('error').textContent = message }
  function node(tag, text, className) { const element = document.createElement(tag); if (text !== undefined) element.textContent = text; if (className) element.className = className; return element }
  function option(select, value, text) { const item = node('option', text); item.value = value; select.append(item) }
  function changed() { submitted.delete(reviews[current].key); refreshNav() }
  function download(value, filename) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' }))
    const anchor = node('a'); anchor.href = url; anchor.download = filename; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  function source(id) {
    const doc = bundle.documents.find(d => d.id === id) || bundle.documents[0]
    $('source-select').value = doc.id; $('source-meta').textContent = `${doc.author} · ${doc.updatedAt}`; $('source').textContent = doc.content
  }
  function refreshNav() {
    $('progress').textContent = `固定 ${bundle.rows.length} 条 · 已加入导出 ${submitted.size} 条 · 尚未导入正式评分`
    $('cases').replaceChildren()
    bundle.rows.forEach((row, i) => {
      const button = node('button', `${submitted.has(row.key) ? '✓ ' : ''}${row.mode}\n${row.caseId}`)
      button.style.whiteSpace = 'pre-line'; button.setAttribute('aria-current', String(i === current))
      button.addEventListener('click', () => { current = i; render() }); $('cases').append(button)
    })
  }
  function decisionPanel(item, value, citation) {
    const wrapper = node('div', undefined, 'decision')
    wrapper.append(node('h3', citation ? `引用 ${item.id}` : `${item.id} · ${item.text}`))
    if (citation) {
      wrapper.append(node('blockquote', item.text, 'excerpt'))
      wrapper.append(node('p', item.documentIds.length ? '逐字匹配来源：' + item.documentIds.map(id => bundle.documents.find(d => d.id === id).title).join('、') : '未匹配冻结原文；不能计作来源正确', item.documentIds.length ? 'muted' : 'badge warning'))
      if (item.documentIds.length) { const open = node('button', '查看匹配原文'); open.addEventListener('click', () => source(item.documentIds[0])); wrapper.append(open) }
    }
    const controls = node('div', undefined, 'decision-controls'), label = node('label', '判断'), verdict = node('select')
    verdict.setAttribute('aria-label', `${item.id} 判断`)
    const labels = citation ? { unknown: '未知 / 待核对', supports: '支持且相关', irrelevant: '原文真实但不切题', unsupported: '不支持 / 不真实' } : { unknown: '未知 / 待核对', pass: '通过', fail: '不通过' }
    for (const [key, text] of Object.entries(labels)) option(verdict, key, text)
    verdict.value = value.verdict; verdict.addEventListener('change', () => { value.verdict = verdict.value; changed() }); label.append(verdict)
    const sourceLabel = node('label', '核对依据'), select = node('select'); select.setAttribute('aria-label', `${item.id} 依据`)
    option(select, '', '未选择 / 无可用来源')
    for (const doc of bundle.documents) option(select, doc.id, doc.title)
    select.value = value.sourceIds[0] || ''; select.addEventListener('change', () => { value.sourceIds = select.value ? [select.value] : []; changed() }); sourceLabel.append(select)
    controls.append(label, sourceLabel); wrapper.append(controls)
    const reason = node('textarea'); reason.maxLength = 2000; reason.placeholder = '填写理由；指出对应的结论、原文或缺失信息'; reason.setAttribute('aria-label', `${item.id} 理由`)
    reason.value = value.reason; reason.addEventListener('input', () => { value.reason = reason.value; changed() }); wrapper.append(reason)
    return wrapper
  }
  function render() {
    const row = bundle.rows[current], review = reviews[current]; error(''); refreshNav()
    $('case-title').textContent = `${row.mode} · ${row.caseId}`; $('question').textContent = row.question; $('answer').textContent = row.answer
    $('engineering').textContent = row.engineeringPass ? '工程核对通过' : '工程核对未通过'
    const matched = row.excerpts.filter(e => e.documentIds.length).length
    $('literal').textContent = `原文匹配 ${matched}/${row.excerpts.length} · 相关性待审核`; $('literal').className = matched === row.excerpts.length ? 'badge' : 'badge warning'
    $('criteria').replaceChildren(...row.criteria.map((c, i) => decisionPanel(c, review.decisions[i], false)))
    $('citations').replaceChildren(...row.excerpts.map((e, i) => decisionPanel(e, review.citations[i], true)))
    if (!row.excerpts.length) $('citations').append(node('p', '当前回答未检测到块引用；仍需核对事实与来源声明，不能据此自动判定正确。', 'muted'))
    $('reviewer').value = review.reviewer; $('reviewer-type').value = review.reviewerType; $('note').value = review.note; $('attested').checked = review.attested
    source(row.targetDocumentIds[0]); $('next').disabled = current === bundle.rows.length - 1
  }
  $('source-select').addEventListener('change', () => source($('source-select').value))
  for (const doc of bundle.documents) option($('source-select'), doc.id, doc.title)
  for (const [id, field] of [['reviewer', 'reviewer'], ['note', 'note'], ['reviewer-type', 'reviewerType']]) $(id).addEventListener('input', () => { reviews[current][field] = $(id).value; changed() })
  $('attested').addEventListener('change', () => { reviews[current].attested = $('attested').checked; changed() })
  $('next').addEventListener('click', () => { if (current < bundle.rows.length - 1) { current++; render() } })
  $('submit-row').addEventListener('click', () => {
    const review = reviews[current], row = bundle.rows[current]
    if (!review.reviewer.trim() || !review.note.trim() || (review.reviewerType === 'human' && !review.attested)) return error('请填写审核者、总说明，并确认已实际核对当前条。')
    if ([...review.decisions, ...review.citations].some(d => !d.reason.trim())) return error('请为每项判断填写理由；无法判断时保留“未知”。')
    if (review.decisions.some(d => d.verdict === 'pass' && !d.sourceIds.length)) return error('通过项需选择核对依据。')
    if (review.citations.some(d => d.verdict === 'supports' && (!d.sourceIds.length || d.sourceIds.some(id => !row.excerpts.find(e => e.id === d.excerptId).documentIds.includes(id))))) return error('支持引用需选择实际逐字匹配的来源；未匹配引用不能标为支持。')
    review.reviewedAt = new Date().toISOString(); submitted.add(review.key); error(''); refreshNav()
  })
  $('export').addEventListener('click', () => {
    if (!submitted.size) return error('请先将实际核对过的条目加入导出；也可以先保存草稿。')
    download({ schemaVersion: 'ablation-reviews-v1', bundleSha256: bundle.bundleSha256, reviews: reviews.filter(r => submitted.has(r.key)) }, 'vio-submitted-reviews.json')
  })
  $('save-draft').addEventListener('click', () => download({ schemaVersion: 'ablation-review-draft-v1', bundleSha256: bundle.bundleSha256, reviews }, 'vio-review-draft.json'))
  $('load-draft').addEventListener('click', () => $('draft-file').click())
  $('draft-file').addEventListener('change', async () => {
    try {
      const file = $('draft-file').files[0]; if (!file || file.size > 2000000) throw Error('草稿文件为空或过大')
      const draft = JSON.parse(await file.text())
      if (draft.schemaVersion !== 'ablation-review-draft-v1' || draft.bundleSha256 !== bundle.bundleSha256 || draft.reviews?.length !== reviews.length) throw Error('草稿与当前回答不匹配')
      const safeText = value => typeof value === 'string' && value.length <= 2000
      const restored = reviews.map((old, i) => {
        const value = draft.reviews[i]
        if (value.key !== old.key || value.observationHash !== old.observationHash || !safeText(value.reviewer) || value.reviewer.length > 80 || !safeText(value.note)
            || !['human', 'ai-assisted'].includes(value.reviewerType)) throw Error('草稿身份或文字无效')
        const pick = (values, expected, idName, allowed) => {
          if (!Array.isArray(values) || values.length !== expected.length) throw Error('草稿条目缺失')
          return expected.map((entry, index) => {
            const d = values[index]
            if (d[idName] !== entry[idName] || !allowed.includes(d.verdict) || !safeText(d.reason) || !Array.isArray(d.sourceIds)
                || d.sourceIds.length > bundle.documents.length || d.sourceIds.some(id => !bundle.documents.some(doc => doc.id === id))) throw Error('草稿判定无效')
            return { [idName]: d[idName], verdict: d.verdict, reason: d.reason, sourceIds: [...d.sourceIds] }
          })
        }
        return { ...old, reviewerType: value.reviewerType, reviewer: value.reviewer, note: value.note, attested: false, reviewedAt: null,
          decisions: pick(value.decisions, old.decisions, 'criterionId', ['pass', 'fail', 'unknown']),
          citations: pick(value.citations, old.citations, 'excerptId', ['supports', 'irrelevant', 'unsupported', 'unknown']) }
      })
      reviews = restored; submitted.clear(); render(); error('草稿已恢复；请逐条重新确认后加入导出。')
    } catch (e) { error(`导入失败：${e.message}`) }
    $('draft-file').value = ''
  })
  render()
})()
