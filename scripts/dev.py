#!/usr/bin/env python3
"""Owns only processes started by this checkout. Data survives stop/restart."""
import argparse,json,os,shutil,signal,socket,subprocess,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
RUNTIME=ROOT/'.runtime'
RUNTIME.mkdir(exist_ok=True)
REGISTRY=RUNTIME/'processes.json'
def tools():
    env=os.environ.copy()
    java=ROOT/'.tools/amazon-corretto-21.jdk/Contents/Home'
    if java.exists():env['JAVA_HOME']=str(java)
    node=Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin'
    fallback=Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/bin/fallback'
    env['PATH']=str(java/'bin')+os.pathsep+str(node)+os.pathsep+str(fallback)+os.pathsep+env.get('PATH','')
    maven=ROOT/'.tools/apache-maven-3.9.11/bin/mvn'
    mvn=str(maven) if maven.exists() else shutil.which('mvn',path=env['PATH'])
    pnpm=shutil.which('pnpm',path=env['PATH'])
    java_bin=shutil.which('java',path=env['PATH'])
    if not mvn or not pnpm or not java_bin:raise SystemExit('请先运行 scripts/bootstrap.py 并准备 Node 22 / pnpm。')
    return env,mvn,pnpm,java_bin
def records():
    return json.loads(REGISTRY.read_text()) if REGISTRY.exists() else {}
def stamp(pid):
    result=subprocess.run(['ps','-p',str(pid),'-o','lstart='],capture_output=True,text=True,env={**os.environ,'TZ':'UTC'})
    return result.stdout.strip()
def alive(record):
    return bool(record and record.get('started') and stamp(record['pid'])==record['started'])
def open_port(port):
    with socket.socket() as sock:
        sock.settimeout(.2)
        return sock.connect_ex(('127.0.0.1',port))==0
def wait_http(url):
    for attempt in range(160):
        try:
            with urllib.request.urlopen(url,timeout=2) as r:
                if r.status==200:return
        except Exception:pass
        time.sleep(.25)
    raise SystemExit('服务启动未完成，请查看 .runtime 下的日志：'+url)
def launch(name,args,cwd,env):
    state=records()
    if alive(state.get(name)):return
    log=open(RUNTIME/(name+'.log'),'a')
    process=subprocess.Popen(args,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,stdin=subprocess.DEVNULL)
    log.close()
    state[name]={'pid':process.pid,'started':stamp(process.pid),'root':str(ROOT),'group':True}
    REGISTRY.write_text(json.dumps(state,indent=2))
def build():
    env,mvn,pnpm,java=tools()
    subprocess.run([pnpm,'install','--frozen-lockfile'],cwd=ROOT,env=env,check=True)
    subprocess.run([mvn,'-B','verify'],cwd=ROOT/'backend',env=env,check=True)
    subprocess.run([pnpm,'build'],cwd=ROOT,env=env,check=True)
def start():
    env,mvn,pnpm,java=tools()
    mysql=ROOT/'.tools/mysql-8.4.13-macos15-arm64'
    if not mysql.exists():raise SystemExit('请运行 scripts/bootstrap.py 准备独立 MySQL，或使用 docker compose up --build。')
    if not open_port(13306):
        data=RUNTIME/'mysql'
        if not (data/'mysql').exists():
            data.mkdir(exist_ok=True)
            subprocess.run([str(mysql/'bin/mysqld'),'--no-defaults','--initialize-insecure','--basedir='+str(mysql),'--datadir='+str(data)],check=True)
        launch('mysql',[str(mysql/'bin/mysqld'),'--no-defaults','--basedir='+str(mysql),'--datadir='+str(data),'--bind-address=127.0.0.1','--port=13306','--mysqlx=0','--socket='+str(RUNTIME/'mysql.sock'),'--pid-file='+str(RUNTIME/'mysql.pid'),'--log-error='+str(RUNTIME/'mysql-server.log')],ROOT,env)
        for attempt in range(120):
            if open_port(13306):break
            time.sleep(.25)
    elif not alive(records().get('mysql')) and not (RUNTIME/'mysql.pid').exists():
        raise SystemExit('13306端口被其他服务占用，未修改其数据。')
    sql="CREATE DATABASE IF NOT EXISTS chuyu_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'chuyu'@'localhost' IDENTIFIED BY 'chuyu_local_demo'; CREATE USER IF NOT EXISTS 'chuyu'@'127.0.0.1' IDENTIFIED BY 'chuyu_local_demo'; GRANT ALL ON chuyu_demo.* TO 'chuyu'@'localhost'; GRANT ALL ON chuyu_demo.* TO 'chuyu'@'127.0.0.1';"
    subprocess.run([str(mysql/'bin/mysql'),'--no-defaults','--socket='+str(RUNTIME/'mysql.sock'),'-uroot'],input=sql,text=True,check=True,capture_output=True)
    jar=ROOT/'backend/target/chuyu-platform-0.1.0.jar'
    if not jar.exists():raise SystemExit('请先运行 python3 scripts/dev.py build。')
    for name,port in [('backend',18080),('admin',15173),('h5',15174)]:
        if open_port(port) and not alive(records().get(name)):raise SystemExit(str(port)+'端口已有非本脚本启动的进程，请先正常关闭原预览。')
    launch('backend',[java,'-jar',str(jar)],ROOT/'backend',env);wait_http('http://127.0.0.1:18080/actuator/health')
    launch('admin',[pnpm,'dev:admin'],ROOT,env);launch('h5',[pnpm,'dev:h5'],ROOT,env)
    wait_http('http://127.0.0.1:15173/');wait_http('http://127.0.0.1:15174/')
    print('管理后台：http://127.0.0.1:15173/\n会员端：http://127.0.0.1:15174/\n所有数据保存在当前项目 .runtime/mysql。')
def stop():
    state=records()
    for name in ['h5','admin','backend','mysql']:
        record=state.get(name)
        if alive(record) and record.get('root')==str(ROOT):
            if record.get('group',True):os.killpg(record['pid'],signal.SIGTERM)
            else:os.kill(record['pid'],signal.SIGTERM)
            for attempt in range(80):
                if not alive(record):break
                time.sleep(.1)
            print('已停止 '+name)
    REGISTRY.write_text('{}\n')
def status():
    state=records()
    for name,port in [('mysql',13306),('backend',18080),('admin',15173),('h5',15174)]:print(name, '运行中' if open_port(port) else '已停止',state.get(name,{}).get('pid',''))
def reload_backend():
    env,mvn,pnpm,java=tools();record=records().get('backend')
    if open_port(18080) and not alive(record):raise SystemExit('后端进程不属于本启动记录。')
    if alive(record):
        if record.get('group',True):os.killpg(record['pid'],signal.SIGTERM)
        else:os.kill(record['pid'],signal.SIGTERM)
        for attempt in range(120):
            if not open_port(18080):break
            time.sleep(.1)
    launch('backend',[java,'-jar',str(ROOT/'backend/target/chuyu-platform-0.1.0.jar')],ROOT/'backend',env)
    wait_http('http://127.0.0.1:18080/actuator/health')
    print('后端已更新，前端预览保持运行。')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['start','stop','restart','status','build','reload_backend']);action=parser.parse_args().action
    if action=='restart':stop();start()
    else:globals()[action]()
