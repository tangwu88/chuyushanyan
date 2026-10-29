#!/usr/bin/env python3
"""200k temporary orders and 200k zero-value ledger entries in the local demo only."""
import datetime,io,json,subprocess,time,urllib.request,zipfile,xml.etree.ElementTree as E
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
MYSQL=ROOT/'.tools/mysql-8.4.13-macos15-arm64/bin/mysql'
SOCKET=ROOT/'.runtime/mysql.sock'
BASE='http://127.0.0.1:18080/api/v1'
def sql(s):
    return subprocess.check_output([str(MYSQL),'--no-defaults','--socket='+str(SOCKET),'-uroot','-N','-B','chuyu_demo'],input=s.encode()).decode().strip()
def request(path,token='',data=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path,None if data is None else json.dumps(data).encode(),headers)
    return urllib.request.urlopen(req,timeout=180)
token=json.load(request('/auth/login',data={'username':'admin','password':'ChuyuDemo!2026'}))['token']
assert json.load(request('/config',token))['demo'] is True
prefix='PF'+str(int(time.time()*1000))
point=prefix+'P';tx=prefix+'T';count=200000
result={'dataset':{'orders':count,'ledgerEntries':count},'database':'MySQL 8.4.13','verifiedAt':datetime.datetime.now().isoformat(),'queries':{}}
try:
    sql("INSERT INTO member_points(id,owner_id,phone,nickname,is_main,sequence_no,active,activated) VALUES('"+point+"','"+prefix+"','0000999988','规模验证临时点位',TRUE,1,FALSE,FALSE);INSERT INTO wallets(point_id,kind) VALUES('"+point+"','REWARD'),('"+point+"','RECHARGE');INSERT INTO ledger_transactions(id,business_key,kind,actor_id,description) VALUES('"+tx+"','"+prefix+"','SCALE_TEST','PERFORMANCE','规模验证临时记录');")
    for start in range(0,count,1000):
        orders=[];entries=[]
        for i in range(start,min(start+1000,count)):
            identity=prefix+str(i).zfill(8)
            orders.append("('"+identity+"','"+point+"','CY9001','"+point+"','PR001','FIRST',1,1,1,0,1,'UNPAID','PENDING','UNFULFILLED','DEMO-V2','{}','2023-01-01 00:00:00')")
            entries.append("('"+identity+"','"+tx+"','"+point+"','REWARD',0,0,0,'规模验证临时记录','2023-01-01 00:00:00')")
        sql("INSERT INTO shop_orders(id,point_id,shop_id,payer_id,product_id,order_type,quantity,unit_price,total,reward_paid,cash_paid,payment_status,activation_status,fulfilment_status,rule_id,rule_snapshot,created_at) VALUES"+','.join(orders)+";INSERT INTO ledger_entries(id,transaction_id,point_id,wallet_kind,delta,balance_after,frozen_after,description,created_at) VALUES"+','.join(entries)+";")
    for name,path in {
        'orderGlobalPage':'/lists/orders?size=20&page=1',
        'orderDeepPage':'/lists/orders?pointId='+point+'&size=200&page=1000',
        'orderExactSearch':'/lists/orders?search='+prefix+'00001999',
        'dateFilter':'/lists/orders?start=2023-01-01&end=2023-01-01&size=20',
        'ledgerDeepPage':'/lists/ledger?pointId='+point+'&size=200&page=1000'
    }.items():
        timings=[];data={}
        for i in range(3):
            began=time.perf_counter();data=json.load(request(path,token));timings.append(round((time.perf_counter()-began)*1000,2))
        if name in ('orderDeepPage','ledgerDeepPage','dateFilter'):assert data['total']==count
        if name=='orderExactSearch':assert data['total']==1
        result['queries'][name]={'milliseconds':timings,'total':data['total'],'returned':len(data['items'])}
    began=time.perf_counter()
    content=request('/exports/ledger?pointId='+point,token).read()
    result['exportMilliseconds']=round((time.perf_counter()-began)*1000,2);result['exportBytes']=len(content)
    with zipfile.ZipFile(io.BytesIO(content)) as book:
        rows=0
        with book.open('xl/worksheets/sheet1.xml') as sheet:
            for event,node in E.iterparse(sheet,events=['end']):
                if node.tag.endswith('}row'):rows+=1;node.clear()
        assert rows==count+1
        result['exportDataRows']=rows-1
finally:
    sql("DELETE FROM ledger_entries WHERE point_id='"+point+"';DELETE FROM ledger_transactions WHERE id='"+tx+"';DELETE FROM shop_orders WHERE point_id='"+point+"';DELETE FROM wallets WHERE point_id='"+point+"';DELETE FROM member_history WHERE point_id='"+point+"';DELETE FROM member_points WHERE id='"+point+"';")
    result['temporaryDataRemoved']=True
result['balancedAfterCleanup']=json.load(request('/reconciliation',token))['balanced']
assert result['balancedAfterCleanup']
proof=ROOT/'docs/verification/performance.json';proof.parent.mkdir(parents=True,exist_ok=True);proof.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
