#!/usr/bin/env python3
"""Task-local toolchain. Never edits system profiles or global Java installations."""
import hashlib,json,os,platform,tarfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
TOOLS=ROOT/'.tools'
TOOLS.mkdir(exist_ok=True)
if platform.system()!='Darwin' or platform.machine()!='arm64':
    raise SystemExit('原生自动准备适用于 Apple Silicon Mac。其他平台请使用 compose.yml，或安装 Java 21、Maven 3.9、Node 22 与 pnpm 11。')
lock=json.loads((ROOT/'scripts/toolchain-lock.json').read_text())
for name,info in lock.items():
    destination={'jdk21.tar.gz':'amazon-corretto-21.jdk','maven.tar.gz':'apache-maven-3.9.11','mysql.tar.gz':'mysql-8.4.13-macos15-arm64'}[name]
    archive=TOOLS/name
    if not archive.exists():
        print('下载 '+name,flush=True)
        temporary=archive.with_suffix('.download')
        urllib.request.urlretrieve(info['url'],temporary)
        temporary.rename(archive)
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest!=info['sha256']:raise SystemExit('下载文件校验失败：'+name)
    if not (TOOLS/destination).exists():
        with tarfile.open(archive) as contents:
            for member in contents.getmembers():
                if not os.path.realpath(TOOLS/member.name).startswith(str(TOOLS)+'/'):raise SystemExit('压缩包路径无效')
            contents.extractall(TOOLS)
    print('已准备 '+destination,flush=True)
print('准备完成。运行 python3 scripts/dev.py build，然后运行 python3 scripts/dev.py start。')
