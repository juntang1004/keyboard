"""원작자 v5.5.0 디컴파일 폴더 준비: 원작자 apk에 들어 있는 예전 수정판(v5.8.1) 클래스를 빼고,
'신버전 사용' 기본값을 켜짐으로 바꿈. (v5.5.0은 연결 코드(훅)가 이미 들어 있어서 smali 패치는 필요 없음)
사용: python prep_v550.py <디컴파일 폴더>"""
import glob, os, sys
d = os.path.join(sys.argv[1], 'smali_classes3', 'com', 'thock', 'keyboard')
for c in ['Ext', 'SettingsUi', 'CjEngine', 'NaraEngine', 'LineIcon', 'SwitchList']:
    for f in glob.glob(os.path.join(d, c + '.smali')) + glob.glob(os.path.join(d, c + '$*.smali')):
        os.remove(f)
p = os.path.join(d, 'Prefs.smali')
s = open(p, encoding='utf-8').read()
a = '    const-string v1, "newVersion"

    const/4 v2, 0x0
'
assert s.count(a) == 1, 'Prefs.newVersion 기본값 위치를 못 찾음'
open(p, 'w', encoding='utf-8').write(s.replace(a, a.replace('0x0', '0x1')))
print('prep OK')
