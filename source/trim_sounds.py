"""소리 파일 꼬리 정리: 최대 음량의 0.56%(약 -45dB) 아래로 떨어진 뒤 10ms만 남기고 자름 (끝 10ms는 페이드아웃).
사용: python trim_sounds.py <디컴파일 폴더>/res/raw"""
import array, glob, os, sys, wave
d = sys.argv[1]
for f in glob.glob(os.path.join(d, '*.wav')):
    w = wave.open(f); p = w.getparams(); n = w.getnframes(); a = array.array('h', w.readframes(n)); w.close()
    m = max((abs(x) for x in a), default=0) or 1
    j = next((k for k in range(len(a) - 1, -1, -1) if abs(a[k]) > m * 0.0056), len(a) - 1)
    keep = min(n, j + 480)
    b = a[:keep]; fade = min(480, keep)
    for i in range(fade):
        k = keep - fade + i; b[k] = int(b[k] * (1 - (i + 1) / fade))
    o = wave.open(f, 'wb'); o.setparams(p); o.writeframes(b.tobytes()); o.close()
