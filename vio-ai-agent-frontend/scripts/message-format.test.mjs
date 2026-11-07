import test from 'node:test'
import assert from 'node:assert/strict'
import { pokemonMessageSections, inlineParts } from '../src/api/message-format.js'

test('source markdown and bare web addresses become safe links, never executable HTML or javascript', () => {
  const url='https://www.smogon.com/forums/threads/example.3712513/'
  assert.equal(inlineParts(`[自行访问来源](${url})`)[0].href,url)
  assert.equal(inlineParts(`来源：${url}。`)[1].href,url)
  for(const text of ['[危险](javascript:alert(1))','[危险](data:text/html,test)','[凭据](https://user:password@example.com/)','`https://example.com/`']) {
    assert.ok(inlineParts(text).every(part=>!part.href))
  }
})

test('训练家笔记区分图鉴、培养、搭配与提醒，属性只从明确字段读取', () => {
  const sections = pokemonMessageSections('速度较快。\n### 图鉴速览（资料事实）\n属性：冰 / 幽灵\n### 培养方案（策略建议）\n- **速度**优先\n- 配置待确认\n### 队伍搭配\n组合待验证\n### 训练家提醒\n不能证明当前热门程度')
  assert.deepEqual(sections.map(section => section.tone), ['plain', 'dex', 'training', 'team', 'note'])
  assert.deepEqual(sections[1].blocks[0].types.map(type => type.name), ['冰', '幽灵'])
  assert.equal(sections[2].blocks[0].kind, 'list')
  assert.equal(sections[2].blocks[0].items.length, 2)
  assert.equal(pokemonMessageSections('火属性攻击并不表示它是火属性')[0].blocks[0].kind, 'paragraph')
  assert.equal(pokemonMessageSections('属性：未知')[0].blocks[0].kind, 'paragraph')
})

test('流式不完整文本不会丢失，完整表格转换为表头和数据行', () => {
  const text = '### 培养方案\n| 项目 | 建议 |\n| --- | --- |\n| 努力值 | 待确认 |\n下一段尚未**完成'
  const sections = pokemonMessageSections(text)
  assert.equal(sections[0].blocks[0].kind, 'table')
  assert.equal(sections[0].blocks[0].rows.length, 1)
  assert.equal(sections[0].blocks[1].parts[0].text, '下一段尚未**完成')
  const partial = pokemonMessageSections('| 项目 | 建议 |\n| ---')
  assert.equal(partial[0].blocks[0].kind, 'paragraph')
})

test('HTML和危险链接保持文本，不生成可执行节点或样式', () => {
  const source = '<img src=x onerror=alert(1)>\n[点击](javascript:alert(1))\n属性：<script>'
  const blocks = pokemonMessageSections(source)[0].blocks
  assert.equal(blocks.length, 3)
  assert.ok(blocks.every(block => block.kind === 'paragraph'))
  assert.equal(blocks[0].parts[0].text, '<img src=x onerror=alert(1)>')
})
