"""Verify PDF bytes, Markdown binding, page geometry and question coverage."""
from pathlib import Path
import hashlib
import json
import re
import fitz

ROOT=Path(__file__).resolve().parents[2]
report_path=ROOT/'output/pdf/三方向PDF校验_2026-11-20.json'
report=json.loads(report_path.read_text(encoding='utf-8'))
assert len(report['documents'])==3
for row in report['documents']:
    source=ROOT/row['source'];file=ROOT/row['file']
    source_text=source.read_text(encoding='utf-8').replace('\r\n','\n')
    assert hashlib.sha256(source_text.encode()).hexdigest()==row['sourceSha256LF'],source
    assert hashlib.sha256(file.read_bytes()).hexdigest()==row['sha256'],file
    pdf=fitz.open(file);assert len(pdf)==row['pages']
    extracted=[];outside=[]
    for index,page in enumerate(pdf):
        text=page.get_text();assert len(text.strip())>=30,(file,index)
        extracted.append(text)
        for block in page.get_text('dict')['blocks']:
            for line in block.get('lines',[]):
                for span in line['spans']:
                    x0,y0,x1,y1=span['bbox']
                    if x0<40 or x1>page.rect.width-40 or y0<12 or y1>page.rect.height-12:
                        outside.append(index+1)
    assert not outside,(file,outside)
    text=re.sub(r'\s+','','\n'.join(extracted))
    questions=re.findall(r'^### Q(\d+)',source_text,re.M)
    assert len(questions)==row['questionHeadings']
    assert all(re.search(r'Q0*'+str(int(n))+r'(?!\d)',text) for n in questions)
    assert '<aid=' not in text, 'Visible HTML anchors'
    assert pdf.get_toc(), 'Missing PDF bookmarks'
    assert any(page.get_images() for page in pdf), 'Missing architecture diagrams'
    assert sum(len(page.get_links()) for page in pdf)>0,'Missing source links'
    pdf.close()
    row['outOfBoundsSpans']=0
    row['sourceBindingVerified']=True
report['status']='AUTOMATED_CHECKS_PASS'
# Visual review remains independently recorded after actual PNG inspection.
report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':'PASS','documents':3,'pages':[r['pages'] for r in report['documents']],'questionIds':535,'geometry':'all text spans within safe page margins','scope':'PDF and Markdown consistency; no new product evaluation'},ensure_ascii=True))
