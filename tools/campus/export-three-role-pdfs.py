"""Export complete Markdown books and architecture diagrams with embedded CJK fonts.

Run with system Python (markdown_it, PyMuPDF) and --runtime-site pointing to
the Codex bundled Python site-packages when reportlab/pypdf are not installed.
"""
from pathlib import Path
import argparse
import hashlib
import html
import json
import re
import sys
from urllib.parse import quote,unquote

parser=argparse.ArgumentParser()
parser.add_argument('--runtime-site')
parser.add_argument('--diagrams-only',action='store_true')
args=parser.parse_args()
if args.runtime_site:sys.path.append(args.runtime_site)
import fitz
from markdown_it import MarkdownIt
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.platypus import BaseDocTemplate,PageTemplate,Frame,Paragraph,Spacer,PageBreak,LongTable,TableStyle,Image
from reportlab.platypus.tableofcontents import TableOfContents

ROOT=Path(__file__).resolve().parents[2]
DOC=ROOT/'document/学习与面试'
OUT=ROOT/'output/pdf'
TMP=ROOT/'tmp/pdfs/three-role-20261120'
ASSETS=DOC/'assets'
for p in [OUT,TMP,ASSETS]:p.mkdir(parents=True,exist_ok=True)
pdfmetrics.registerFont(TTFont('CJK',r'C:/Windows/Fonts/msyh.ttc',subfontIndex=0))
pdfmetrics.registerFont(TTFont('CJKB',r'C:/Windows/Fonts/msyhbd.ttc',subfontIndex=0))
pdfmetrics.registerFontFamily('CJK',normal='CJK',bold='CJKB',italic='CJK',boldItalic='CJKB')
INK=colors.HexColor('#18352f');GREEN=colors.HexColor('#23735c');PALE=colors.HexColor('#eef5f1')
WIDTH,HEIGHT=595.28,841.89
CW=WIDTH-88

def wrapped(text,width,size=9,font='CJK'):
    result=[];line=''
    for char in text:
        if char=='\n' or pdfmetrics.stringWidth(line+char,font,size)>width:
            result.append(line);line='' if char=='\n' else char
        else:line+=char
    if line:result.append(line)
    return result

def label(c,text,x,y,width,size=10,color=INK,center=False):
    c.setFont('CJK',size);c.setFillColor(color)
    for line in wrapped(text,width,size):
        if center:c.drawCentredString(x+width/2,y,line)
        else:c.drawString(x,y,line)
        y-=size*1.5

def arrow(c,x1,y1,x2,y2):
    import math
    c.setStrokeColor(GREEN);c.setLineWidth(1);c.line(x1,y1,x2,y2)
    angle=math.atan2(y2-y1,x2-x1)
    for offset in [-0.45,0.45]:c.line(x2,y2,x2-7*math.cos(angle+offset),y2-7*math.sin(angle+offset))

def box(c,x,y,w,h,title,body):
    c.setFillColor(PALE);c.setStrokeColor(GREEN);c.roundRect(x,y,w,h,7,fill=1)
    label(c,title,x+9,y+h-19,w-18,11)
    label(c,body,x+9,y+h-37,w-18,8.5)

def diagrams():
    name='vio-system-architecture-20261120'
    file=TMP/(name+'.pdf');c=canvas.Canvas(str(file),pagesize=(560,680))
    label(c,'VIO 当前系统架构',20,655,520,18)
    label(c,'单后端进程 / 外部模型API / 本地Node计算',20,630,520,10)
    layers=[('Vue工作区','对话、图鉴、队伍、证据卡片；Run事件reducer与游标恢复'),
            ('HTTP与身份边界','Session / CSRF / 资源owner；REST创建、查询、确认和取消'),
            ('Run运行时','准入、幂等、状态机、期限、取消、审批恢复与事件'),
            ('执行器与工具治理','程序领域路由或ReAct循环 → 工具网关：权限、参数、策略与审计')]
    for i,(title,body) in enumerate(layers):
        y=543-i*91;box(c,20,y,520,67,title,body)
        if i<3:arrow(c,280,y,280,y-24)
    y=152
    for x,title,body in [(20,'资料与攻略','PokéAPI / 缓存\nSQLite BM25'),(197,'确定性计算','Java → Node\n@smogon/calc'),(374,'队伍工作流','快照 → 预览确认\nCAS → 保存回读')]:
        arrow(c,280,270,x+83,230);box(c,x,y,166,78,title,body)
    box(c,20,75,520,58,'证据、SSE与可观测性','输入/来源核验；正文与结构化块；Run/模型/工具Observation及usage')
    for x in [103,280,457]:arrow(c,x,152,x,133)
    label(c,'可选：Skills / 顺序专用角色 / Embedding+RRF / 精排 / MCP / OTLP',20,52,520,8.5)
    label(c,'可选持久化与身份适配分别验收；本图不承诺跨重启或多实例恢复。',20,33,520,8.5)
    c.save();raster(file,ASSETS/(name+'.png'))
    name='vio-request-sequence-20261120';file=TMP/(name+'.pdf');c=canvas.Canvas(str(file),pagesize=(560,580))
    label(c,'一次请求与确认保存时序',20,555,520,17)
    xs=[55,165,275,385,505]
    for x,title in zip(xs,['浏览器','身份 / Run','执行器','工具网关','资料 / 队伍']):
        c.setFillColor(PALE);c.rect(x-48,497,96,31,fill=1,stroke=0)
        label(c,title,x-46,508,92,9,center=True)
        c.setDash(3,3);c.setStrokeColor(colors.HexColor('#a3b9ad'));c.line(x,492,x,52);c.setDash()
    events=[(0,1,'1. 创建Run / 可信主体与准入'),(1,0,'2. 返回Run ID / 订阅SSE'),(1,2,'3. 历史、状态与执行'),(2,3,'4. 工具请求 / 权限与参数校验'),(3,4,'5. 读取资料、快照或计算'),(4,2,'6. 实际结果与来源证据'),(2,0,'7. 正文 / 卡片 / 修改预览'),(0,1,'8. 页面确认 / 审批绑定'),(1,4,'9. 重验期限与快照 / CAS提交'),(4,0,'10. 回读结果 / SSE终态')]
    for i,(a,b,text) in enumerate(events):
        y=465-i*39;arrow(c,xs[a],y,xs[b],y)
        label(c,text,min(xs[a],xs[b])+4,y+10,abs(xs[b]-xs[a])-8,7.6)
    label(c,'断线使用原Run与游标补读；未确认、过期或冲突均不能落盘。',20,28,520,9)
    c.save();raster(file,ASSETS/(name+'.png'))

def raster(file,destination):
    doc=fitz.open(file);doc[0].get_pixmap(matrix=fitz.Matrix(2,2)).save(destination);doc.close()

styles={
 'body':ParagraphStyle('body',fontName='CJK',fontSize=9.2,leading=15,spaceAfter=6,wordWrap='CJK',textColor=INK,rightIndent=10),
 'h1':ParagraphStyle('h1',fontName='CJKB',fontSize=17,leading=25,spaceBefore=16,spaceAfter=10,keepWithNext=True,wordWrap='CJK',textColor=GREEN),
 'h2':ParagraphStyle('h2',fontName='CJKB',fontSize=13,leading=20,spaceBefore=13,spaceAfter=8,keepWithNext=True,wordWrap='CJK',textColor=GREEN),
 'h3':ParagraphStyle('h3',fontName='CJKB',fontSize=10.7,leading=17,spaceBefore=10,spaceAfter=6,keepWithNext=True,wordWrap='CJK',textColor=INK),
 'code':ParagraphStyle('code',fontName='CJK',fontSize=7.1,leading=11,spaceAfter=0,wordWrap='CJK',backColor=colors.HexColor('#f3f5f4'),borderPadding=4),
 'cell':ParagraphStyle('cell',fontName='CJK',fontSize=7.7,leading=12,wordWrap='CJK'),
 'toc':ParagraphStyle('toc',fontName='CJK',fontSize=9,leading=14,spaceBefore=2,wordWrap='CJK'),
}

def inline(children,base):
    out=[]
    for token in children or []:
        kind=token.type
        if kind in ['text','code_inline']:out.append(html.escape(token.content))
        elif kind in ['softbreak','hardbreak']:out.append('<br/>')
        elif kind=='strong_open':out.append('<b>')
        elif kind=='strong_close':out.append('</b>')
        elif kind=='em_open':out.append('<i>')
        elif kind=='em_close':out.append('</i>')
        elif kind=='link_open':
            target=token.attrGet('href') or ''
            if target.startswith('#'):out.append('<font color="#23735c">')
            else:
                if '://' not in target and not target.startswith('mailto:'):
                    rel=(base/unquote(target.split('#')[0])).resolve().relative_to(ROOT).as_posix()
                    target='https://github.com/vioabio/AI-Agent/blob/main/'+quote(rel)
                out.append(f'<a href="{html.escape(target,quote=True)}" color="#23735c">')
        elif kind=='link_close':
            # Track separately below to close the correct element.
            out.append('</LINK>')
        elif kind=='image':out.append(html.escape(token.content))
    text=''.join(out)
    # No nested links in CommonMark; close each corresponding opening tag.
    while '</LINK>' in text:
        pos=text.index('</LINK>');prefix=text[:pos]
        closing='</font>' if prefix.rfind('<font ')>prefix.rfind('<a ') else '</a>'
        text=text[:pos]+closing+text[pos+7:]
    return re.sub(r'\*\*([^*]+?)\*\*',r'<b>\1</b>',text)

class Book(BaseDocTemplate):
    def __init__(self,file,title):
        super().__init__(str(file),pagesize=(WIDTH,HEIGHT),leftMargin=44,rightMargin=44,topMargin=48,bottomMargin=43,title=title,author='VIO project documentation',allowSplitting=1)
        self.book_title=title;self.heading_counter=0
        frame=Frame(44,43,CW,HEIGHT-91,id='normal',leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0)
        self.addPageTemplates(PageTemplate(id='book',frames=frame,onPage=self.decorate))
    def decorate(self,c,doc):
        c.setFillColor(GREEN);c.setFont('CJK',8)
        c.drawString(44,HEIGHT-28,self.book_title)
        c.setStrokeColor(colors.HexColor('#cadbd2'));c.line(44,HEIGHT-34,WIDTH-44,HEIGHT-34)
        c.setFont('CJK',8);c.drawString(44,24,'VIO | 2026-11-20 | 三方向学习与面试')
        c.drawRightString(WIDTH-44,24,str(doc.page))
    def beforeDocument(self):self.heading_counter=0
    def afterFlowable(self,flowable):
        if isinstance(flowable,Paragraph) and flowable.style.name in ['h1','h2']:
            self.heading_counter+=1;key=f'h{self.heading_counter}';level=0 if flowable.style.name=='h1' else 1
            self.canv.bookmarkPage(key)
            # Avoid outline level jumps across historical embedded sections.
            self.canv.addOutlineEntry(flowable.getPlainText()[:110],key,level=0,closed=True)
            self.notify('TOCEntry',(level,flowable.getPlainText(),self.page,key))

def export(source):
    title=source.stem.replace('_2026-11-20','')
    destination=OUT/(source.stem+'.pdf')
    markdown=source.read_text(encoding='utf-8')
    markdown=re.sub(r'''(?m)^\s*<a\s+id=['"][^'"]+['"]\s*></a>\s*$''','',markdown)
    tokens=MarkdownIt('default',{'html':False}).parse(markdown)
    story=[Spacer(1,65),Paragraph(title,styles['h1']),Paragraph('AI应用开发工程师 / AI全栈开发工程师 / 全栈工程师',styles['body']),Paragraph('完整正文、架构图、源码链接与学习验收 | 2026-11-20',styles['body']),PageBreak(),Paragraph('目录',styles['h1'])]
    toc=TableOfContents();toc.levelStyles=[styles['toc'],ParagraphStyle('toc2',parent=styles['toc'],leftIndent=12)]
    story += [toc,PageBreak()]
    i=0;lists=[];item_prefix=None
    while i<len(tokens):
        t=tokens[i]
        if t.type=='heading_open':
            content=tokens[i+1];level=min(int(t.tag[1:]),3)
            story.append(Paragraph(inline(content.children,source.parent),styles[f'h{level}']));i+=3;continue
        if t.type=='paragraph_open':
            content=tokens[i+1]
            images=[a for a in content.children or [] if a.type=='image']
            if images:
                for image in images:
                    image_path=(source.parent/unquote(image.attrGet('src'))).resolve()
                    pic=Image(str(image_path));ratio=min(CW/pic.imageWidth,570/pic.imageHeight)
                    pic.drawWidth=pic.imageWidth*ratio;pic.drawHeight=pic.imageHeight*ratio
                    story += [pic,Spacer(1,8)]
            else:
                text=inline(content.children,source.parent)
                if item_prefix is not None:
                    text=item_prefix+text;item_prefix=None
                story.append(Paragraph(text,styles['body']))
            i+=3;continue
        if t.type in ['bullet_list_open','ordered_list_open']:
            lists.append({'ordered':t.type=='ordered_list_open','next':int(t.attrGet('start') or 1)})
        elif t.type in ['bullet_list_close','ordered_list_close']:lists.pop()
        elif t.type=='list_item_open':
            item=lists[-1];item_prefix=f'{item["next"]}. ' if item['ordered'] else '• ';item['next']+=1
        elif t.type=='list_item_close':item_prefix=None
        elif t.type in ['fence','code_block']:
            # Wrap by measured glyph widths; preserve every source character.
            for line in t.content.rstrip('\n').split('\n'):
                for part in wrapped(line,CW-16,7.1):
                    story.append(Paragraph(html.escape(part).replace(' ','&#160;') or '&#160;',styles['code']))
            story.append(Spacer(1,9))
        elif t.type=='table_open':
            rows=[];row=[];j=i+1
            while tokens[j].type!='table_close':
                if tokens[j].type=='tr_open':row=[]
                elif tokens[j].type=='inline':row.append(Paragraph(inline(tokens[j].children,source.parent),styles['cell']))
                elif tokens[j].type=='tr_close':rows.append(row)
                j+=1
            cols=max(map(len,rows));widths=[CW/cols]*cols
            table=LongTable(rows,colWidths=widths,repeatRows=1,hAlign='LEFT')
            table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),PALE),('VALIGN',(0,0),(-1,-1),'TOP'),('GRID',(0,0),(-1,-1),.3,colors.HexColor('#cadbd2')),('LEFTPADDING',(0,0),(-1,-1),5),('RIGHTPADDING',(0,0),(-1,-1),5),('TOPPADDING',(0,0),(-1,-1),5),('BOTTOMPADDING',(0,0),(-1,-1),5)]))
            story.extend([table,Spacer(1,9)]);i=j
        i+=1
    Book(destination,title).multiBuild(story)
    pdf=fitz.open(destination);all_text='\n'.join(p.get_text() for p in pdf)
    # Normalize whitespace introduced by layout for completeness checks.
    normalized=re.sub(r'\s+','',all_text)
    source_text=source.read_text(encoding='utf-8')
    headings=re.findall(r'^### Q(\d+)',source_text,re.M)
    missing=[n for n in headings if not re.search(r'Q0*'+str(int(n))+r'(?!\d)',normalized)]
    assert not missing,missing
    checks={'file':destination.relative_to(ROOT).as_posix(),'source':source.relative_to(ROOT).as_posix(),'pages':len(pdf),'bytes':destination.stat().st_size,'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'sourceSha256LF':hashlib.sha256(source_text.replace('\r\n','\n').encode()).hexdigest(),'questionHeadings':len(headings),'allQuestionIdsFound':not missing,'emptyPages':[i+1 for i,p in enumerate(pdf) if len(p.get_text().strip())<30]}
    assert not checks['emptyPages'],checks
    for index in sorted({0,1,2,min(5,len(pdf)-1),len(pdf)//2,len(pdf)-1}):
        pdf[index].get_pixmap(matrix=fitz.Matrix(1.15,1.15)).save(TMP/(source.stem+f'-p{index+1}.png'))
    pdf.close();return checks

diagrams()
if not args.diagrams_only:
    checks=[export(DOC/(name+'_2026-11-20.md')) for name in ['项目架构详解','三方向完整学习文档','三方向完整面试文档']]
    (OUT/'三方向PDF校验_2026-11-20.json').write_text(json.dumps({'status':'STRUCTURAL_CHECKS_PASS','visualReview':'PENDING','renderer':'ReportLab + Microsoft YaHei; PyMuPDF preview','documents':checks},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(checks,ensure_ascii=True))
