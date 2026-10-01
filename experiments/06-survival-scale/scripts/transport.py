#!/usr/bin/env python3
from common import *
import http.server, socket, urllib.parse
class Backend(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    totals={'request_body':0,'response_body':0,'requests':0}
    def log_message(self,*a):pass
    def do_GET(self):self.handle_git()
    def do_POST(self):self.handle_git()
    def handle_git(self):
        body=b''
        if self.headers.get('Transfer-Encoding','').lower()=='chunked':
            chunks=[]
            while True:
                n=int(self.rfile.readline().strip().split(b';')[0],16)
                if not n:
                    while self.rfile.readline().strip():pass
                    break
                chunks.append(self.rfile.read(n));self.rfile.read(2)
            body=b''.join(chunks)
        elif self.headers.get('Content-Length'):body=self.rfile.read(int(self.headers['Content-Length']))
        parsed=urllib.parse.urlsplit(self.path)
        env={**os.environ,'GIT_PROJECT_ROOT':str(WORK/'transport/remotes'),'GIT_HTTP_EXPORT_ALL':'1','REQUEST_METHOD':self.command,'PATH_INFO':parsed.path,'QUERY_STRING':parsed.query,'CONTENT_TYPE':self.headers.get('Content-Type',''),'CONTENT_LENGTH':str(len(body)),'REMOTE_ADDR':'127.0.0.1','SERVER_PROTOCOL':'HTTP/1.1','GIT_CONFIG_COUNT':'1','GIT_CONFIG_KEY_0':'pack.threads','GIT_CONFIG_VALUE_0':'1'}
        if self.headers.get('Content-Encoding'):env['HTTP_CONTENT_ENCODING']=self.headers['Content-Encoding']
        if self.headers.get('Git-Protocol'):env['HTTP_GIT_PROTOCOL']=self.headers['Git-Protocol']
        out=subprocess.run(['git','http-backend'],input=body,env=env,capture_output=True)
        if out.returncode:
            sys.stderr.write('BACKEND-ERR '+out.stderr.decode(errors='replace')+'\n');self.send_error(500,out.stderr.decode(errors='replace').encode('ascii','replace').decode()[:200].replace('\n',' '));return
        header,data=out.stdout.split(b'\r\n\r\n',1);hs=[];status=200
        for h in header.decode().splitlines():
            k,v=h.split(':',1)
            if k.lower()=='status':status=int(v.strip().split()[0])
            else:hs.append((k,v.strip()))
        self.send_response(status)
        for k,v in hs:self.send_header(k,v)
        self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers()
        self.totals['request_body']+=len(body);self.totals['response_body']+=len(data);self.totals['requests']+=1
        self.wfile.write(data);self.close_connection=True

def git(*args):
    return subprocess.check_output(['git',*map(str,args)],text=True).strip()
def main():
    dest=WORK/'transport';dest.mkdir(exist_ok=True);remotes=dest/'remotes';remotes.mkdir(exist_ok=True)
    source=WORK/'large/stream.git';large=json.loads((WORK/'large/results.json').read_text());head=git('--git-dir='+str(source),'rev-parse','main')
    rows=[]
    with bench_lock():
      server=http.server.ThreadingHTTPServer(('127.0.0.1',25643),Backend);threading.Thread(target=server.serve_forever,daemon=True).start()
      try:
       for client in ('c','jgit'):
        repo=dest/('source-'+client+'.git');git('clone','--bare','--no-hardlinks',source,repo);git('--git-dir='+str(repo),'config','pack.threads','1')
        remote=remotes/(client+'.git');git('init','--bare','--initial-branch=main',remote);git('--git-dir='+str(remote),'config','http.receivepack','true');git('--git-dir='+str(remote),'config','pack.threads','1')
        url='http://127.0.0.1:25643/'+remote.name
        def measure(label,args,jargs=None):
            before=dict(Backend.totals);start=time.monotonic()
            if jargs is not None:out,t=tool(['transport',*jargs],f'transport-{client}-{label}',xmx='1G');peak=t['rss_bytes']
            else:
                out=subprocess.run(['/usr/bin/time','-v','-o',str(dest/(client+'-'+label+'.time')),'git','-c','pack.threads=1',*map(str,args)],text=True,capture_output=True)
                (dest/(client+'-'+label+'.out')).write_text(out.stdout+out.stderr)
                if out.returncode:raise RuntimeError('git operation failed '+label+out.stderr)
                peak=0
                for l in (dest/(client+'-'+label+'.time')).read_text().splitlines():
                    if 'Maximum resident set size' in l:peak=int(l.split(':')[-1])*1024
            # 所有 response bytes 在客戶端 return 前已寫完。
            row={'client':client,'operation':label,'wall_s':time.monotonic()-start,'rss_bytes':peak,'locked':True,**{k:Backend.totals[k]-before[k] for k in before}}
            rows.append(row);write_json(dest/'results.json',rows);emit(event='transport',result=row);guard()
        git('--git-dir='+str(repo),'update-ref','refs/heads/main',large['init_head'])
        measure('first-push',['--git-dir='+str(repo),'push',url,'refs/heads/main:refs/heads/main'],['push',repo,url] if client=='jgit' else None)
        baseclone=dest/(client+'-baseline.git')
        measure('clone-initial',['clone','--bare',url,baseclone],['clone',url,baseclone] if client=='jgit' else None)
        git('--git-dir='+str(repo),'update-ref','refs/heads/main',head)
        measure('incremental-push',['--git-dir='+str(repo),'push',url,'refs/heads/main:refs/heads/main'],['push',repo,url] if client=='jgit' else None)
        measure('incremental-fetch',['--git-dir='+str(baseclone),'fetch',url,'+refs/heads/main:refs/heads/main'],['fetch',baseclone,url] if client=='jgit' else None)
        if git('--git-dir='+str(baseclone),'rev-parse','main')!=head:raise RuntimeError('fetch head mismatch')
        for label,depth in [('clone-full',None),('clone-depth1',1)]:
            clone=dest/(client+'-'+label+'.git');args=['clone','--bare',*(['--depth','1'] if depth else []),url,clone]
            jargs=['clone',url,clone,*(['1'] if depth else [])] if client=='jgit' else None
            measure(label,args,jargs)
            rows[-1]['repo_disk_bytes']=disk_bytes(clone);rows[-1]['commit_count']=int(git('--git-dir='+str(clone),'rev-list','--count','main'))
            rows[-1]['pack_bytes']=sum(p.stat().st_size for p in (clone/'objects/pack').glob('*.pack'))
            if git('--git-dir='+str(clone),'rev-parse','main')!=head:raise RuntimeError('clone head mismatch')
            git('--git-dir='+str(clone),'fsck','--connectivity-only')
            # 不留六份大型 clone；驗證後只保存數據與 remote。
            shutil.rmtree(clone)
        shutil.rmtree(repo);shutil.rmtree(baseclone);write_json(dest/'results.json',rows)
      finally:server.shutdown();server.server_close()
    emit(event='transport_complete',disk=guard())
if __name__=='__main__':main()
