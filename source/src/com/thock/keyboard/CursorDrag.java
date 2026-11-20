package com.thock.keyboard;

/**
 * 끌어서 커서 이동: 손가락이 움직인 거리만큼 커서를 한 칸씩 옮긴다 (삼성 키보드 조작감).
 *
 * 수치는 삼성 키보드 화면 녹화(1080px 폭, 42fps)에서 손가락 위치와 커서 위치를 프레임마다 재서 맞췄다
 * (source/test/samsung_drag_trace.txt, CursorDragTest). 키보드 폭 대비 비율:
 *  - 한 칸 = 4.4% (1080px 에서 약 48px)
 *  - 글자 키 위에서 시작: 21.5% (약 232px) 밀면 바로 시작, 또는 0.7초 넘게 누른 채 10% (약 108px, 키 하나) 밀면 시작.
 *    시작하는 순간 한 칸 움직이고, 그 뒤로는 시작한 곳부터 한 칸 거리마다 한 칸.
 *  - 방향을 바꾸면 8% (약 86px) 더 밀어야 반대로 움직이기 시작 (손 떨림으로 왔다갔다 하지 않게)
 * 안드로이드 의존 없음 (JVM 테스트용).
 */
public final class CursorDrag {
    public static final float STEP = 0.044f, START_FAST = 0.215f, START_SLOW = 0.10f, REVERSE = 0.08f;
    public static final long SLOW_MS = 700;

    private float origin, anchor;
    private long t0;
    private int dir;            // 마지막으로 움직인 방향 (-1, 0, 1)
    private boolean active;

    /** 손가락이 닿은 곳과 시각(ms) */
    public void start(float x, long t) { origin = x; anchor = x; t0 = t; dir = 0; active = false; }

    public boolean active() { return active; }
    public float origin() { return origin; }

    /** 커서 모드 시작 (스페이스 길게 누름 등): 지금 위치부터 잰다, 움직이지는 않음 */
    public void activate(float x) { active = true; anchor = x; dir = 0; }

    /** 글자 키 위에서 밀기: w = 키보드 폭. @return 움직일 칸 수 (+ 오른쪽, - 왼쪽) */
    public int moveKey(float x, long t, float w, float speed) {
        if (!active) {
            float d = Math.abs(x - origin);
            if (d < w * START_FAST && !(t - t0 >= SLOW_MS && d >= w * START_SLOW)) return 0;
            active = true;
            anchor = x;
            dir = x > origin ? 1 : -1;
            return dir;                     // 시작하면서 한 칸
        }
        return steps(x, w * STEP / speed, w * REVERSE);
    }

    /** 이미 시작된 상태(스페이스바)에서 밀기 */
    public int moveActive(float x, float w, float speed) {
        if (!active) return 0;
        return steps(x, w * STEP / speed, w * REVERSE);
    }

    private int steps(float x, float step, float reverse) {
        int n = 0;
        if (dir == 0) {
            if (Math.abs(x - anchor) < step) return 0;
            dir = x > anchor ? 1 : -1;
        } else if ((anchor - x) * dir >= reverse + step) {
            // 방향 바꿈: 여유 거리 + 한 칸
            dir = -dir;
            anchor += dir * (reverse + step);
            n += dir;
        }
        while ((x - anchor) * dir >= step) { anchor += dir * step; n += dir; }
        return n;
    }
}
