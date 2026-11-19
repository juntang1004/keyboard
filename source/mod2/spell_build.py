#!/usr/bin/env python3
"""맞춤법 목록(spell_list.tsv, 사람이 읽는 원본) → 앱용 assets/spell.tsv

원본 열: 틀린형태 <TAB> 바른형태 <TAB> 방식 <TAB> 표시 <TAB> 메모
 {가|나} 묶음은 틀린형태와 바른형태에서 같은 순서로 짝지어 펼친다 (묶음이 여럿이면 모든 조합).
출력 (한 줄 = 한 항목): id <TAB> 표시 <TAB> 방식 <TAB> 틀린=바른|틀린=바른...
 id = 원본의 틀린형태 그대로 (설정에서 끈 항목을 기억하는 열쇠라 바꾸지 않는다)
사용: spell_build.py spell_list.tsv out/spell.tsv
"""
import itertools, re, sys

MODES = set('sepxw')


def groups(s):
    """'{a|b}c{|d}' → (['', 'c', ''], [['a','b'], ['', 'd']])"""
    parts, alts, pos = [], [], 0
    for m in re.finditer(r'\{([^{}]*)\}', s):
        parts.append(s[pos:m.start()])
        alts.append(m.group(1).split('|'))
        pos = m.end()
    parts.append(s[pos:])
    return parts, alts


def expand(wrong, right):
    pw, aw = groups(wrong)
    pr, ar = groups(right)
    if len(aw) != len(ar) or any(len(a) != len(b) for a, b in zip(aw, ar)):
        raise ValueError('묶음 짝이 안 맞음: %s / %s' % (wrong, right))
    out = []
    for idx in itertools.product(*[range(len(a)) for a in aw]):
        w = pw[0] + ''.join(aw[g][i] + pw[g + 1] for g, i in enumerate(idx))
        r = pr[0] + ''.join(ar[g][i] + pr[g + 1] for g, i in enumerate(idx))
        out.append((w, r))
    return out


def load(path):
    items = []
    for n, line in enumerate(open(path, encoding='utf-8'), 1):
        line = line.rstrip('\n')
        if not line.strip() or line.startswith('#'):
            continue
        f = line.split('\t')
        if len(f) < 3 or f[2] not in MODES:
            raise ValueError('%d번째 줄 형식 오류: %r' % (n, line))
        pairs = expand(f[0], f[1])
        disp = f[3] if len(f) > 3 and f[3] else '%s → %s' % pairs[0]
        items.append((f[0], disp, f[2], pairs, f[4] if len(f) > 4 else ''))
    return items


def check(items):
    """틀린 형태 중복 없음, 틀린≠바른, 바른 형태 안에 다른 틀린 형태가 없음(순환 없음)"""
    seen, errs = {}, []
    for it in items:
        for w, r in it[3]:
            if w == r: errs.append('같음: ' + w)
            if (w, it[2]) in seen: errs.append('중복: %s (%s, %s)' % (w, seen[(w, it[2])], it[0]))
            seen[(w, it[2])] = it[0]
    return errs


def main(src, dst):
    items = load(src)
    errs = check(items)
    if errs:
        print('\n'.join(errs)); sys.exit(1)
    with open(dst, 'w', encoding='utf-8', newline='\n') as f:
        for it in items:
            f.write('%s\t%s\t%s\t%s\n' % (it[0], it[1], it[2], '|'.join('%s=%s' % p for p in it[3])))
    print('items', len(items), 'forms', sum(len(it[3]) for it in items))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
