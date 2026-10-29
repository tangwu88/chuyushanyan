#!/usr/bin/env python3
"""Back up demo data and verify restoration in a separate, named database."""
import argparse,datetime,hashlib,json,os,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
BIN=ROOT/'.tools/mysql-8.4.13-macos15-arm64/bin'
RUNTIME=ROOT/'.runtime'
def root_sql(sql,database=None):
    args=[str(BIN/'mysql'),'--no-defaults','--socket='+str(RUNTIME/'mysql.sock'),'-uroot','-N','-B']
    if database:args.append(database)
    return subprocess.check_output(args,input=sql.encode()).decode().strip()
def fingerprint(database):
    tables=['member_points','shop_orders','ledger_transactions','ledger_entries','reward_awards','withdrawals','fulfilment_applications','rule_versions']
    counts={table:int(root_sql('SELECT COUNT(*) FROM '+table+';',database)) for table in tables}
    counts['wallets']=root_sql('SELECT point_id,kind,balance,frozen,restricted FROM wallets ORDER BY point_id,kind;',database)
    return counts
def dump(database,output):
    args=[str(BIN/'mysqldump'),'--no-defaults','--socket='+str(RUNTIME/'mysql.sock'),'-uroot','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF','--routines','--triggers','--skip-comments','--skip-dump-date','--order-by-primary',database]
    with Path(output).open('wb') as f:subprocess.run(args,stdout=f,check=True)
    return hashlib.sha256(Path(output).read_bytes()).hexdigest()
def canonical_hash(file):
    # MySQL may expand an implicit column charset during restore. Normalize only
    # that equivalent DDL spelling; INSERT data bytes remain completely untouched.
    digest=hashlib.sha256()
    with Path(file).open('rb') as source:
        for line in source:
            if not line.startswith(b'INSERT INTO '):
                line=line.replace(b' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',b' COLLATE utf8mb4_unicode_ci')
            digest.update(line)
    return digest.hexdigest()
def backup():
    folder=RUNTIME/'backups';folder.mkdir(parents=True,exist_ok=True)
    output=folder/('chuyu-demo-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.sql')
    digest=dump('chuyu_demo',output)
    output.with_suffix('.sha256').write_text(digest+'\n')
    output.chmod(0o600);return output
def restore_check(file,database):
    if not re.fullmatch(r'chuyu_restore_[a-zA-Z0-9_]+',database):raise SystemExit('恢复验证库名必须以 chuyu_restore_ 开头。')
    if root_sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+database+"';")!='0':raise SystemExit('验证库已存在，请使用新的库名。')
    digest=hashlib.sha256(Path(file).read_bytes()).hexdigest()
    manifest=Path(file).with_suffix('.sha256')
    if manifest.exists() and manifest.read_text().strip()!=digest:raise SystemExit('备份文件SHA256校验失败。')
    root_sql('CREATE DATABASE '+database+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
    subprocess.run([str(BIN/'mysql'),'--no-defaults','--socket='+str(RUNTIME/'mysql.sock'),'-uroot',database],stdin=open(file,'rb'),check=True)
    restored=fingerprint(database)
    redump=RUNTIME/(database+'-verify.sql')
    dump(database,redump)
    if canonical_hash(redump)!=canonical_hash(file):raise SystemExit('恢复库与备份快照不一致，请保留两个SQL文件核对。')
    redump.unlink()
    differences=root_sql("SELECT COUNT(*) FROM wallets w LEFT JOIN (SELECT point_id,wallet_kind,SUM(delta) amount FROM ledger_entries GROUP BY point_id,wallet_kind) e ON e.point_id=w.point_id AND e.wallet_kind=w.kind WHERE w.balance<>COALESCE(e.amount,0);",database)
    if differences!='0':raise SystemExit('恢复后的账本余额存在差异。')
    result={'verifiedAt':datetime.datetime.now().isoformat(),'source':'chuyu_demo','restoredDatabase':database,'counts':{k:v for k,v in restored.items() if k!='wallets'},'snapshotSha256':digest,'restoredSqlHashMatched':True,'allWalletBalancesMatched':True,'backupBytes':Path(file).stat().st_size}
    proof=ROOT/'docs/verification/restore-check.json';proof.parent.mkdir(exist_ok=True,parents=True);proof.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['backup','restore-check']);parser.add_argument('--file');parser.add_argument('--database');args=parser.parse_args()
    if args.action=='backup':print(backup())
    else:
        file=Path(args.file) if args.file else backup()
        database=args.database or 'chuyu_restore_'+datetime.datetime.now().strftime('%Y%m%d_%H%M%S')
        restore_check(file,database)
