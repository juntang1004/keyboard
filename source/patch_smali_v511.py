"""
원본 도록키보드 apk를 apktool로 디컴파일한 폴더에 수정판 연결 코드를 넣는 스크립트.
사용: python patch_smali.py <apktool로 풀린 폴더>
"""
import os, re, sys

root = sys.argv[1]
K = os.path.join(root, 'smali_classes3', 'com', 'thock', 'keyboard')

def rd(p): return open(p, encoding='utf-8').read()
def wr(p, s): open(p, 'w', encoding='utf-8').write(s)

# 1) 터치 처리: 손가락마다 따로 추적 (빠르게 칠 때 씹힘 수정)
p = os.path.join(K, 'KeyboardService$RootInterceptorLayout.smali')
s = rd(p)
new = '''.method public dispatchTouchEvent(Landroid/view/MotionEvent;)Z
    .locals 1

    invoke-static {p0, p1}, Lcom/thock/keyboard/Ext;->dispatch(Landroid/widget/FrameLayout;Landroid/view/MotionEvent;)Z

    move-result v0

    return v0
.end method'''
s, n = re.subn(r'\.method public dispatchTouchEvent\(Landroid/view/MotionEvent;\)Z.*?\.end method', new, s, flags=re.S)
assert n == 1; wr(p, s)

# 2) 키보드 화면: 툴바·천지인·편집·문구 패널 감싸기 + 훅
p = os.path.join(K, 'KeyboardService.smali')
s = rd(p)
old = '''    invoke-direct {p0}, Lcom/thock/keyboard/KeyboardService;->buildRoot()Landroid/view/View;

    move-result-object v0
'''
assert s.count(old) == 1
s = s.replace(old, old + '''
    invoke-static {p0, v0}, Lcom/thock/keyboard/Ext;->wrap(Landroid/inputmethodservice/InputMethodService;Landroid/view/View;)Landroid/view/View;

    move-result-object v0
''')

def hook(src, sig, code):
    i = src.index('.method ' + sig); j = src.index('.end method', i)
    lines = src[i:j].split('\n')
    for n, ln in enumerate(lines):
        t = ln.strip()
        if n > 0 and t and not t.startswith(('.', ':', '#')):
            lines.insert(n, code); break
    return src[:i] + '\n'.join(lines) + src[j:]

s = hook(s, 'private final renderRows()V',
         '    invoke-static/range {p0 .. p0}, Lcom/thock/keyboard/Ext;->onRender(Landroid/inputmethodservice/InputMethodService;)V\n')
s = hook(s, 'public onFinishInput()V',
         '    invoke-static/range {p0 .. p0}, Lcom/thock/keyboard/Ext;->onFinish(Landroid/inputmethodservice/InputMethodService;)V\n')
s = hook(s, 'public onUpdateSelection(IIIIII)V',
         '    invoke-static/range {p0 .. p6}, Lcom/thock/keyboard/Ext;->onSel(Landroid/inputmethodservice/InputMethodService;IIIIII)V\n')
wr(p, s)

# 3) 설정 화면 교체
p = os.path.join(K, 'MainActivity.smali')
s = rd(p)
o = 'invoke-direct {p0}, Lcom/thock/keyboard/MainActivity;->buildUi()Landroid/widget/ScrollView;'
assert s.count(o) == 1
wr(p, s.replace(o, 'invoke-static {p0}, Lcom/thock/keyboard/SettingsUi;->build(Landroid/app/Activity;)Landroid/view/View;'))

# 4) 매니페스트: 뒤로가기 처리 + 삼성 키보드 폰트 읽기 허용
p = os.path.join(root, 'AndroidManifest.xml')
s = rd(p)
if 'enableOnBackInvokedCallback' not in s:
    s = s.replace('<application ', '<application android:enableOnBackInvokedCallback="true" ', 1)
if '<queries>' not in s:
    s = s.replace('<uses-permission', '<queries><package android:name="com.samsung.android.honeyboard"/></queries>\n    <uses-permission', 1)
wr(p, s)
print('patched OK')
