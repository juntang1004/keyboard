#!/usr/bin/env python3
"""AndroidManifest.xml(바이너리)의 <manifest android:versionName / versionCode> 를 바꾼다.
설치 화면과 설정 맨 아래 "버전 …" 이 빌드마다 바뀌게 (예전에는 원작 그대로 3.33 이었음).
사용: axml_set_version.py in.xml out.xml <versionName> <versionCode>"""
import struct, sys
from axml_add_queries import u16, u32, parse_pool, build_pool

TYPE_STRING, TYPE_INT_DEC = 0x03, 0x10


def main(src, dst, vname, vcode):
    b = open(src, 'rb').read()
    assert u16(b, 0) == 3
    pos = u16(b, 2); chunks = []
    while pos < len(b):
        sz = u32(b, pos + 4); chunks.append(bytearray(b[pos:pos + sz])); pos += sz
    assert u16(chunks[0], 0) == 1
    strings, flags, utf8 = parse_pool(bytes(chunks[0]))
    done = set()
    for ch in chunks:
        if u16(ch, 0) != 0x0102 or strings[u32(ch, 20)] != 'manifest':
            continue
        attr_start = 16 + u16(ch, 24); attr_size = u16(ch, 26); n = u16(ch, 28)
        for k in range(n):
            o = attr_start + k * attr_size
            name = strings[u32(ch, o + 4)]
            if name == 'versionName':
                idx = len(strings); strings.append(vname)
                struct.pack_into('<I', ch, o + 8, idx)          # rawValue
                ch[o + 15] = TYPE_STRING
                struct.pack_into('<I', ch, o + 16, idx)         # typedValue.data
                done.add(name)
            elif name == 'versionCode':
                struct.pack_into('<I', ch, o + 8, 0xFFFFFFFF)   # rawValue 없음
                ch[o + 15] = TYPE_INT_DEC
                struct.pack_into('<I', ch, o + 16, int(vcode))
                done.add(name)
        break
    assert done == {'versionName', 'versionCode'}, done
    chunks[0] = bytearray(build_pool(strings, flags, utf8))
    body = b''.join(bytes(c) for c in chunks)
    open(dst, 'wb').write(struct.pack('<HHI', 3, 8, 8 + len(body)) + body)


if __name__ == '__main__':
    main(*sys.argv[1:5])
