import http.client, http.server, json, threading, time, uuid, pathlib

base_host, base_port = '127.0.0.1', 8080
client_id = 'codex-integration-' + uuid.uuid4().hex[:10]
user_id = 'integration-user'
callbacks = []

class Tools(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        payload = request['payload']
        callbacks.append(payload)
        result = {'v':'0.1','id':request['id'],'type':'tool_result','payload':{
            'chatId':payload['chatId'],'callId':payload['callId'],'status':'ok',
            'result':{'token':'网关工具调用成功'}}}
        body = json.dumps(result).encode()
        self.send_response(200); self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)

server = http.server.ThreadingHTTPServer(('127.0.0.1',0),Tools)
threading.Thread(target=server.serve_forever,daemon=True).start()

def call(method,path,payload=None):
    connection = http.client.HTTPConnection(base_host,base_port,timeout=180)
    body = None if payload is None else json.dumps(payload,ensure_ascii=False).encode()
    connection.request(method,path,body,{'Content-Type':'application/json'})
    response=connection.getresponse(); data=response.read(); connection.close()
    assert response.status == 200, (path,response.status)
    return json.loads(data) if data else None

def chat(agent,prompt,session=None):
    payload={'clientId':client_id,'endUserId':user_id,'agent':agent,'prompt':prompt,
             'clientPrompt':'你是只读集成测试助手。当前客户端工具位于client-tools MCP服务器，按需可搜索目录。只允许执行read_probe这一个业务工具；禁止shell命令和读写文件。'}
    if session: payload['sessionId']=session
    envelope={'v':'0.1','id':uuid.uuid4().hex,'type':'chat','payload':payload}
    connection=http.client.HTTPConnection(base_host,base_port,timeout=180)
    started=time.monotonic()
    connection.request('POST','/chat',json.dumps(envelope,ensure_ascii=False).encode(),
                       {'Content-Type':'application/json','Accept':'text/event-stream','Accept-Encoding':'identity'})
    response=connection.getresponse()
    assert response.status == 200, ('chat',agent,response.status)
    buffer=b''; deltas=[]; first=None; final=None
    while True:
        data=response.read1(65536)
        if not data: break
        buffer+=data
        while b'\n\n' in buffer:
            frame,buffer=buffer.split(b'\n\n',1)
            lines=[line[5:].lstrip() for line in frame.splitlines() if line.startswith(b'data:')]
            if not lines: continue
            event=json.loads(b'\n'.join(lines))
            if event['type']=='delta':
                first=first if first is not None else round((time.monotonic()-started)*1000)
                deltas.append(event['payload']['text'])
            elif event['type']=='error':
                raise AssertionError(('Agent error',agent,event['payload'].get('code')))
            elif event['type']=='final': final=event['payload']
    connection.close()
    assert final and final['agent']==agent, ('final missing or wrong agent',agent)
    assert deltas, ('no deltas',agent)
    summary={'agent':agent,'deltaCount':len(deltas),'firstDeltaMs':first,
             'totalMs':round((time.monotonic()-started)*1000),'sessionId':final['sessionId']}
    print(json.dumps(summary),flush=True)
    return final,summary

report={}
try:
    call('POST','/clients/register',{'v':'0.1','id':'register-probe','type':'register_tools','payload':{
        'clientId':client_id,'clientType':'SERVER','baseCallbackUrl':f'http://127.0.0.1:{server.server_port}',
        'defaultCallbackPath':'/tools','tools':[{'name':'read_probe','description':'只读连通性测试，返回固定token。',
        'inputSchema':{'type':'object','properties':{},'additionalProperties':False}}]}})
    final,report['codexTool']=chat('codex','请调用client-tools MCP服务器的read_probe工具一次，并原样回复工具返回的token。记住本轮口令是青山十七。不要使用其他业务工具。')
    assert '网关工具调用成功' in final['output'], 'tool result was not used'
    assert callbacks and all(p['clientId']==client_id and p['endUserId']==user_id and p['sessionId']==final['sessionId'] and p['chatId'] for p in callbacks), 'tool routing fields lost'
    resumed,report['codexResume']=chat('codex','上一轮让我记住的口令是什么？只输出口令，不要调用工具。',final['sessionId'])
    assert '青山十七' in resumed['output'], 'Codex did not remember previous turn'
    assert resumed['sessionId']==final['sessionId'], 'session changed on resume'
    qwen,report['qwen']=chat('cli','只输出 通义连接成功，不要使用任何工具，不要读写文件或执行命令。')
    assert '通义连接成功' in qwen['output'], 'Qwen route failed'
    report['toolCallbackCount']=len(callbacks)
    report['passed']=True
finally:
    try: call('DELETE','/clients/'+client_id)
    finally: server.shutdown(); server.server_close()
    report_path=pathlib.Path(__file__).resolve().parents[1]/'target'/'codex-integration-report.json'
    report_path.parent.mkdir(parents=True,exist_ok=True)
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: Codex SSE, client MCP tool, thread resume and Qwen compatibility',flush=True)
