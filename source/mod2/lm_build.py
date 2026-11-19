#!/usr/bin/env python3
"""음절 연결 통계(오타 제안용) 만들기.
 입력: wiki40b ko tfrecord (문어체), FrequencyWords ko_full.txt (자막 어절 빈도, 구어체)
 출력: typo_lm.bin  = 'TLM1' | nTok(int) | tok(char16)*n | uni(float32 count)*n | nBi(int) | key(int32: a*nTok+b, 오름차순)*nBi | val(uint8: round(log2(c)*8))*nBi
"""
import sys, struct, math, collections, re
sys.path.insert(0, '/home/user/kbd/build')
import tfr

def toks(s):
    out = []
    for ch in s:
        o = ord(ch)
        if 0xAC00 <= o <= 0xD7A3 or 0x3131 <= o <= 0x3163: out.append(ch)
        elif ch.isspace(): 
            if out and out[-1] != ' ': out.append(' ')
        else:
            if not out or out[-1] != '#': out.append('#')
    return out

def build(wiki, subs, out, sub_weight=4.0, max_par=None):
    uni = collections.Counter(); bi = collections.Counter()
    n = 0
    for p in tfr.texts(wiki):
        t = [' '] + toks(p) + [' ']
        for a, b in zip(t, t[1:]):
            if a == ' ' and b == ' ': continue
            bi[(a, b)] += 1; uni[a] += 1
        n += 1
        if max_par and n >= max_par: break
    for line in open(subs, encoding='utf-8'):
        f = line.rsplit(' ', 1)
        if len(f) != 2: continue
        w, c = f[0], int(f[1]) * sub_weight
        if c < 2 * sub_weight: continue
        t = [' '] + toks(w) + [' ']
        for a, b in zip(t, t[1:]):
            if a == ' ' and b == ' ': continue
            bi[(a, b)] += c; uni[a] += c
    # 채팅에서 흔한 낱자 (ㅋㅋ ㅎㅎ ㅠㅠ ㅜㅜ): 말뭉치에 거의 없어서 조금 넣어 준다
    for j, c in (('ㅋ', 30000), ('ㅎ', 15000), ('ㅠ', 12000), ('ㅜ', 6000), ('ㅡ', 1500)):
        bi[(j, j)] += c * 2; bi[(' ', j)] += c; bi[(j, ' ')] += c; uni[j] += c * 3; uni[' '] += c
        for a in '요다네어지나': bi[(a, j)] += c * 0.15; uni[a] += c * 0.15
    tok = sorted(uni)
    idx = {t: i for i, t in enumerate(tok)}
    items = sorted((idx[a] * len(tok) + idx[b], c) for (a, b), c in bi.items() if c >= 3 and a in idx and b in idx)
    with open(out, 'wb') as f:
        f.write(b'TLM1'); f.write(struct.pack('>i', len(tok)))
        for t in tok: f.write(struct.pack('>H', ord(t)))
        for t in tok: f.write(struct.pack('>f', float(uni[t])))
        f.write(struct.pack('>i', len(items)))
        for k, _ in items: f.write(struct.pack('>i', k))
        f.write(bytes(min(255, max(0, round(math.log2(c) * 8))) for _, c in items))
    print('tokens', len(tok), 'bigrams', len(items), 'paragraphs', n)

if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2], sys.argv[3])
