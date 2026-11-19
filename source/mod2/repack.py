#!/usr/bin/env python3
"""apk 안의 파일을 바꿔 넣고(서명 파일 제거) zipalign 규칙(저장 항목 4바이트, .so 16KB 정렬)을 맞춘다.
사용: repack.py in.apk out.apk name=path [name=path ...]"""
import sys, zipfile
src, dst, repl = sys.argv[1], sys.argv[2], dict(a.split('=', 1) for a in sys.argv[3:])
zin = zipfile.ZipFile(src)
with open(dst, 'wb') as f:
    zout = zipfile.ZipFile(f, 'w')
    for zi in zin.infolist():
        n = zi.filename
        if n.startswith('META-INF/') and (n.endswith(('.SF', '.RSA', '.DSA', '.EC')) or n == 'META-INF/MANIFEST.MF'): continue
        data = open(repl.pop(n), 'rb').read() if n in repl else zin.read(n)
        ni = zipfile.ZipInfo(n, date_time=zi.date_time)
        ni.compress_type = zi.compress_type; ni.external_attr = zi.external_attr; ni.create_system = zi.create_system
        if zi.compress_type == zipfile.ZIP_STORED:
            align = 16384 if n.endswith('.so') else 4
            off = f.tell() + 30 + len(n.encode('utf-8'))
            ni.extra = b'\0' * ((-off) % align)
        zout.writestr(ni, data)
    for n, path in repl.items():            # apk에 없던 파일은 새로 넣는다 (예: assets/typo_lm.bin)
        zout.writestr(zipfile.ZipInfo(n, date_time=(2026, 1, 1, 0, 0, 0)), open(path, 'rb').read(), zipfile.ZIP_DEFLATED)
    zout.close()
