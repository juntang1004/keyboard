#!/usr/bin/env python3
"""AndroidManifest.xml(바이너리)에 <queries><package android:name="..."/> 항목을 추가한다.
사용: axml_add_queries.py in.xml out.xml pkg1 [pkg2 ...]"""
import struct, sys

def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]

def parse_pool(ch):
    hs = u16(ch, 2); n = u32(ch, 8); styles = u32(ch, 12); flags = u32(ch, 16); sstart = u32(ch, 20)
    assert styles == 0, 'styled string pool not supported'
    utf8 = bool(flags & 0x100)
    offs = [u32(ch, hs + 4 * i) for i in range(n)]
    out = []
    for o in offs:
        p = sstart + o
        if utf8:
            def ln(p):
                a = ch[p]
                if a & 0x80: return ((a & 0x7f) << 8) | ch[p + 1], p + 2
                return a, p + 1
            _, p = ln(p); bl, p = ln(p)
            out.append(ch[p:p + bl].decode('utf-8', 'surrogatepass'))
        else:
            l = u16(ch, p); p += 2
            if l & 0x8000: l = ((l & 0x7fff) << 16) | u16(ch, p); p += 2
            out.append(ch[p:p + 2 * l].decode('utf-16-le', 'surrogatepass'))
    return out, flags, utf8

def build_pool(strings, flags, utf8):
    data = b''; offs = []
    for s in strings:
        offs.append(len(data))
        if utf8:
            e = s.encode('utf-8', 'surrogatepass'); cl = len(s)
            def ln(v): return bytes([v]) if v < 0x80 else bytes([0x80 | (v >> 8), v & 0xff])
            data += ln(cl) + ln(len(e)) + e + b'\0'
        else:
            e = s.encode('utf-16-le', 'surrogatepass'); l = len(e) // 2
            data += (struct.pack('<H', l) if l < 0x8000 else struct.pack('<HH', 0x8000 | (l >> 16), l & 0xffff)) + e + b'\0\0'
    while len(data) % 4: data += b'\0'
    hs = 28; sstart = hs + 4 * len(strings)
    body = b''.join(struct.pack('<I', o) for o in offs) + data
    return struct.pack('<HHIIIIII', 1, hs, hs + len(body), len(strings), 0, flags, sstart, 0) + body

def main(src, dst, pkgs):
    b = open(src, 'rb').read()
    assert u16(b, 0) == 3
    pos = u16(b, 2); chunks = []
    while pos < len(b):
        t = u16(b, pos); sz = u32(b, pos + 4); chunks.append(bytearray(b[pos:pos + sz])); pos += sz
    assert u16(chunks[0], 0) == 1
    strings, flags, utf8 = parse_pool(bytes(chunks[0]))
    pk = strings.index('package')
    # find <package> start/end pair that sits inside <queries>
    depth = []; si = ei = None
    for i, ch in enumerate(chunks):
        t = u16(ch, 0)
        if t == 0x0102:
            name = strings[u32(ch, 20)]
            if name == 'package' and depth and depth[-1] == 'queries' and si is None: si = i
            depth.append(name)
        elif t == 0x0103:
            name = depth.pop()
            if name == 'package' and si is not None and ei is None: ei = i
    assert si is not None and ei == si + 1, 'no <queries><package/> to copy'
    st = chunks[si]
    attr_start = 16 + u16(st, 24); attr_size = u16(st, 26); assert u16(st, 28) == 1
    existing = strings[u32(st, attr_start + 8)]
    new = []
    for p in pkgs:
        if p == existing: continue
        idx = len(strings); strings.append(p)
        c = bytearray(st)
        struct.pack_into('<I', c, attr_start + 8, idx)       # rawValue
        struct.pack_into('<I', c, attr_start + 16, idx)      # typedValue.data (TYPE_STRING)
        assert c[attr_start + 15] == 0x03
        new += [c, bytearray(chunks[ei])]
    chunks[ei + 1:ei + 1] = new
    chunks[0] = bytearray(build_pool(strings, flags, utf8))
    body = b''.join(bytes(c) for c in chunks)
    open(dst, 'wb').write(struct.pack('<HHI', 3, 8, 8 + len(body)) + body)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
