"""Collect independent gym answers via an isolated, already-running local backend.
The credential is read only from the process environment and is never persisted.
This reports observations, not an automatic semantic quality pass.
"""
import argparse, hashlib, json, os, time, urllib.request, urllib.error, uuid
from pathlib import Path

def collect(base, credential, suite_file, output):
    if base not in ('http://127.0.0.1:18147/api', 'http://127.0.0.1:18148/api'):
        raise ValueError('Use an isolated loopback evaluation server')
    output=Path(output)
    output.mkdir(parents=True, exist_ok=False)
    raw=Path(suite_file).read_bytes(); suite=json.loads(raw)
    def api(path, body=None):
        headers={'X-API-Key':credential,'Content-Type':'application/json'}
        request=urllib.request.Request(base+path, headers=headers, data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(request, timeout=40) as response:return json.load(response)
    report={'suiteId':suite['suiteId'],'suiteSha256':hashlib.sha256(raw).hexdigest(),'startedAt':time.strftime('%Y-%m-%dT%H:%M:%S%z'),
            'mode':'real-model-via-local-agent-http','semanticReview':'PENDING','records':[]}
    jar=Path('target/vio-ai-agent-gym-v2.jar')
    if jar.is_file():report['artifactSha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
    sources=['tools/pokemon-engine/gym-battle.cjs','tools/pokemon-engine/package-lock.json',
             'src/main/resources/pokemon-gym/sv-initial-v2.json','src/main/resources/pokemon-gym/sv-rematch-v2.json']
    report['supportingFilesSha256']={name:hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in sources}
    runtime=api('/chat/runtime')
    report['runtime']=runtime
    (output/'observations.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    for item in suite['cases']:
        record={'id':item['id'],'prompt':item['prompt'],'rubric':item['rubric'],'status':'NOT_RUN','answer':'','toolNames':[]}
        report['records'].append(record);start=time.monotonic();run=None;events=[];seen=set();cursor='0'
        try:
            run=api('/chat/runs',{'prompt':item['prompt'],'conversationId':'gym-eval-'+uuid.uuid4().hex,'gameReleaseId':'pokemon-scarlet-violet',
                  'battleFormat':'casual-singles','language':'zh-CN','dataReleaseId':'pokeapi-reference-v1','timeoutSeconds':120,'idempotencyKey':uuid.uuid4().hex})
            record['runId']=run['runId']
            while time.monotonic()-start<145:
                page=api('/chat/runs/'+run['runId']+'/events?afterEventId='+cursor)
                for event in page.get('events',[]):
                    eid=str(event.get('eventId',event.get('id')))
                    if eid in seen:continue
                    seen.add(eid);cursor=eid;events.append(event)
                    kind=event.get('type','');payload=event.get('payload',{})
                    if kind=='answer.delta':record['answer']+=payload.get('text','')
                    if kind in ('answer.completed','run.completed') and payload.get('answer'):record['answer']=payload['answer']
                view=api('/chat/runs/'+run['runId']);record['status']=view['status']
                if view['status'] in ('COMPLETED','FAILED','TIMED_OUT','CANCELLED','WAITING_APPROVAL'):
                    # Drain once after terminal status so the final streamed event is included.
                    if record.get('_terminal'):break
                    record['_terminal']=True
                time.sleep(.5)
            record.pop('_terminal',None)
            names=[]
            for e in events:
                if str(e.get('type','')).startswith('tool.'):
                    p=e.get('payload',{});name=p.get('toolName') or p.get('name')
                    if name and name not in names:names.append(name)
            record['toolNames']=names
            record['hasAnswer']=bool(record['answer'].strip())
        except Exception as error:
            # HTTP bodies and exception request strings are not persisted.
            record['status']='COLLECTION_FAILED';record['errorType']=type(error).__name__
        finally:
            if run and record['status']!='COMPLETED':
                try:api('/chat/runs/'+run['runId']+'/cancel',{'reason':'gym_evaluation_cleanup'})
                except Exception:pass
            record['elapsedMs']=round((time.monotonic()-start)*1000)
            event_bytes=json.dumps(events,ensure_ascii=False,indent=2).encode()
            (output/(item['id']+'-events.json')).write_bytes(event_bytes)
            record['eventsSha256']=hashlib.sha256(event_bytes).hexdigest()
            (output/'observations.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            print(item['id'],record['status'],'answer chars',len(record['answer']),flush=True)
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base',default='http://127.0.0.1:18147/api');parser.add_argument('--suite',default='evaluation/gym-v2/heldout.json');parser.add_argument('--output',required=True)
    args=parser.parse_args();credential=os.environ.get('GYM_EVAL_TOKEN')
    if not credential:raise SystemExit('Configure GYM_EVAL_TOKEN in the local process; never pass it on the command line')
    collect(args.base,credential,args.suite,args.output)
