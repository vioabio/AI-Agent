const types = {
  一般: '#73786b', 火: '#ba542c', 水: '#2879b4', 电: '#967316', 草: '#428340', 冰: '#358b96',
  格斗: '#a54d43', 毒: '#96519b', 地面: '#986f3e', 飞行: '#7976ab', 超能力: '#b75077',
  虫: '#758629', 岩石: '#8c7b42', 幽灵: '#746098', 龙: '#6557b1', 恶: '#716058', 钢: '#617e8c', 妖精: '#ad6590'
}

export function inlineParts(text) {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`|\[[^\]\n]+\]\([^\s<>]+?\)|https?:\/\/[^\s<>\u3002\uff1b\uff0c]+)/g).filter(Boolean).flatMap(part => {
    if(part.startsWith('**')) return [{text:part.slice(2,-2),bold:true,code:false}]
    if(part.startsWith('`')) return [{text:part.slice(1,-1),bold:false,code:true}]
    const markdown=/^\[([^\]]+)\]\((https?:\/\/[^\s<>]+)\)$/.exec(part)
    const address=markdown?markdown[2]:part.startsWith('http')?part.replace(/[.,;!?)]+$/,''):null
    if(address) {
      try {
        const url=new URL(address)
        if(['https:','http:'].includes(url.protocol)&&!url.username&&!url.password) {
          const result=[{text:markdown?markdown[1]:address,href:url.href,bold:false,code:false}]
          if(!markdown&&address.length<part.length)result.push({text:part.slice(address.length),bold:false,code:false})
          return result
        }
      } catch { /* Invalid addresses remain literal Vue text nodes. */ }
    }
    return [{text:part,bold:false,code:false}]
  })
}

function sectionStyle(title) {
  if (/图鉴|资料事实|基础资料/.test(title)) return { tone: 'dex', icon: 'book' }
  if (/培养|配招|养成/.test(title)) return { tone: 'training', icon: 'star' }
  if (/搭配|队伍|队友/.test(title)) return { tone: 'team', icon: 'team' }
  if (/提醒|限制|缺失|待确认|注意/.test(title)) return { tone: 'note', icon: 'info' }
  return { tone: 'plain', icon: 'pokeball' }
}

/** A small Markdown subset; output is rendered only through Vue text nodes. */
export function pokemonMessageSections(content = '') {
  const sections = [{ title: '', tone: 'plain', blocks: [] }]
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  const cells = text => text.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index]
    const line = raw.trim()
    const heading = /^#{1,3}\s+(.+)$/.exec(line) || /^\*\*([^*]+)\*\*$/.exec(line)
    if (heading) {
      const title = heading[1].replace(/\*\*/g, '')
      sections.push({ title, ...sectionStyle(title), blocks: [] })
      continue
    }
    const blocks = sections[sections.length - 1].blocks
    if (!line || /^[-*_]{3,}$/.test(line)) continue
    const typeLine = /^(?:\*\*)?属性(?:\*\*)?[：:]\s*(.+)$/.exec(line)
    if (typeLine) {
      const names = typeLine[1].replace(/\*\*/g, '').split(/\s*[/／、·+]\s*/)
      if (names.length <= 2 && names.every(name => Object.hasOwn(types, name))) {
        blocks.push({ kind: 'types', types: names.map(name => ({ name, color: types[name] })) })
        continue
      }
    }
    if (line.includes('|') && index + 1 < lines.length
        && cells(line).length === cells(lines[index + 1]).length
        && cells(lines[index + 1]).every(cell => /^:?-{3,}:?$/.test(cell))) {
      const headers = cells(line).map(inlineParts)
      const rows = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        const row = cells(lines[index])
        if (row.length !== headers.length) break
        rows.push(row.map(inlineParts))
        index++
      }
      index--
      blocks.push({ kind: 'table', headers, rows })
      continue
    }
    const bullet = /^(?:([-*+])|\d+[.)])\s+(.+)$/.exec(line)
    if (bullet) {
      const ordered = !bullet[1]
      const last = blocks[blocks.length - 1]
      if (last?.kind === 'list' && last.ordered === ordered) last.items.push(inlineParts(bullet[2]))
      else blocks.push({ kind: 'list', ordered, items: [inlineParts(bullet[2])] })
    } else blocks.push({ kind: 'paragraph', parts: inlineParts(raw) })
  }
  return sections.filter(section => section.title || section.blocks.length)
}
