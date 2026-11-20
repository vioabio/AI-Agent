"""Read-only consistency checks for generated three-role Markdown documents."""
from pathlib import Path
import hashlib
import json
import re
from urllib.parse import unquote

ROOT=Path(__file__).resolve().parents[2]
DOC=ROOT/'document/学习与面试'

def normalized_hash(path):
    return hashlib.sha256(path.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode('utf-8')).hexdigest()

def verify():
    manifest=json.loads((DOC/'三方向文档源码索引_2026-11-20.json').read_text(encoding='utf-8'))
    assert manifest['modules']==25 and manifest['totalQuestions']==535 and manifest['newQuestions']==75
    assert len(manifest['sourceSnapshots'])==25
    for row in manifest['sourceSnapshots']:
        source=ROOT/row['path']
        assert source.is_file(),source
        assert normalized_hash(source)==row['sha256'],f'Source changed: {source}; regenerate and review'
        lines=source.read_text(encoding='utf-8-sig').splitlines()
        assert row['anchor'] in lines[row['firstLine']-1],row
    links=0
    for name,expected in manifest['outputs'].items():
        file=DOC/name
        assert normalized_hash(file)==expected,f'Output changed: {name}'
        text=file.read_text(encoding='utf-8-sig')
        fence=None
        for line in text.splitlines():
            match=re.match(r'^\s{0,3}(`{3,}|~{3,})(.*)$',line)
            if not match:continue
            marker,rest=match.groups()
            if fence is None:fence=marker
            elif marker[0]==fence[0] and len(marker)>=len(fence) and not rest.strip():fence=None
        assert fence is None,f'Unclosed code fence: {name}'
        for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)',text):
            target=target.split('#')[0].strip('<>')
            if not target or '://' in target or target.startswith('mailto:'):continue
            links+=1
            assert (file.parent/unquote(target)).exists(),f'Missing link: {name}: {target}'
        if '面试文档' in name:
            numbers=[int(n) for n in re.findall(r'^### Q(\d+)',text,re.M)]
            assert numbers==list(range(1,536)), 'Question IDs must be unique and contiguous'
        else:
            modules=re.findall(r'^### M(\d+) ',text,re.M)
            assert modules==[f'{n:02d}' for n in range(1,26)]
            for section in ['开发步骤','详细知识点与设计原因','真实代码摘录','动手实验','完成标准']:
                assert text.count(f'**{section}')>=25,section
    print(json.dumps({'status':'PASS','modules':25,'questions':535,'localLinks':links,'sourceSnapshots':25,'scope':'Documentation only; no product tests or new answer evaluation'},ensure_ascii=False))

if __name__=='__main__':verify()
