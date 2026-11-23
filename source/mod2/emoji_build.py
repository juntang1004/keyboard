#!/usr/bin/env python3
"""이모지 목록(assets/emoji.tsv) 만들기.

자료 (둘 다 Unicode License, 자유 사용):
  emoji-test.txt  https://unicode.org/Public/emoji/15.1/emoji-test.txt   (이모지·분류·순서)
  ko.xml          https://github.com/unicode-org/cldr  common/annotations/ko.xml        (한국어 이름·검색어)
  ko_derived.xml  같은 저장소 common/annotationsDerived/ko.xml  (국기·가족 등 조합 이모지 이름)
출력 (한 줄 = 이모지 하나): 분류번호 <TAB> 이모지 <TAB> 한국어 이름|검색어|...
 분류: 1 스마일 2 사람 3 동물·자연 4 음식 5 여행·장소 6 활동 7 사물 8 기호 9 깃발
 피부색 바꾼 것(🏻~🏿)과 구성 요소(Component)는 뺀다.
사용: emoji_build.py emoji-test.txt ko.xml ko_derived.xml 출력.tsv
"""
import re, sys, xml.etree.ElementTree as ET

GROUPS = {'Smileys & Emotion': 1, 'People & Body': 2, 'Animals & Nature': 3, 'Food & Drink': 4,
          'Travel & Places': 5, 'Activities': 6, 'Objects': 7, 'Symbols': 8, 'Flags': 9}
SKIN = set(range(0x1F3FB, 0x1F400))
# 한국에서 흔히 쓰는 검색어 몇 개 (CLDR 에 없는 것)
EXTRA = {'🇰🇷': ['태극기', '한국'], '😂': ['ㅋㅋ'], '🤣': ['ㅋㅋ'], '😭': ['ㅠㅠ'], '😢': ['ㅠㅠ'], '👍': ['좋아요', '최고']}


def annotations(paths):
    names, terms = {}, {}
    for p in paths:
        for a in ET.parse(p).getroot().iter('annotation'):
            cp = a.get('cp').replace('️', '')
            txt = (a.text or '').strip()
            if not txt or txt == '↑↑↑':
                continue
            if a.get('type') == 'tts':
                names.setdefault(cp, txt)
            else:
                terms.setdefault(cp, [t.strip() for t in txt.split('|') if t.strip()])
    return names, terms


def main(test, ko, kod, out):
    names, terms = annotations([ko, kod])
    group, rows = None, []
    for line in open(test, encoding='utf-8'):
        m = re.match(r'# group: (.*)', line)
        if m:
            group = GROUPS.get(m.group(1).strip())
            continue
        if not line.strip() or line.startswith('#') or group is None:
            continue
        code, rest = line.split(';', 1)
        if 'fully-qualified' not in rest:
            continue
        cps = [int(x, 16) for x in code.split()]
        if any(c in SKIN for c in cps):
            continue
        e = ''.join(chr(c) for c in cps)
        k = e.replace('️', '')
        nm = names.get(k, '')
        ts = [t for t in terms.get(k, []) if t != nm]
        if nm.startswith('깃발: '):                     # 국기: 나라 이름 + "국기" 로도 찾게
            country = nm[4:]
            ts = [country, country + ' 국기', '국기'] + [t for t in ts if t not in (country, '국기')]
        ts += [t for t in EXTRA.get(e, []) if t not in ts]
        rows.append('%d\t%s\t%s' % (group, e, '|'.join([nm] + ts) if nm else '|'.join(ts)))
    open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(rows) + '\n')
    print('emoji', len(rows), 'no-name', sum(1 for r in rows if r.endswith('\t')))


if __name__ == '__main__':
    main(*sys.argv[1:5])
