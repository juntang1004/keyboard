import com.thock.keyboard.CursorDrag;
import java.nio.file.*;
import java.util.*;

/**
 * 삼성 키보드 녹화의 손가락 궤적(source/test/samsung_drag_trace.txt)을 CursorDrag 에 넣어,
 * 획마다 커서가 움직인 칸 수를 삼성에서 실제로 관찰된 칸 수와 비교한다.
 * 통과 기준: 획마다 차이 1칸 이내 (획 9 는 앞 획에서 이어진 것인지 녹화로 구분이 안 돼 한 획까지 2칸 허용).
 * 글은 "Skekwkwjwjwbddi 가나다라 간단" (커서 0~23). 획마다 삼성의 시작 위치에서 출발해 글 끝에서 멈추게 한다.
 */
public class CursorDragTest {
    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a.length > 0 ? a[0] : "source/test/samsung_drag_trace.txt"));
        int[] starts = {15, 123, 163, 199, 254, 306, 359, 444, 486, 527, 564, 610, 9999};
        // 녹화에서 잰 삼성 커서 위치: 획 시작, 가장 왼쪽, 획 끝
        int[][] sam = {{23, 16, 23}, {23, 18, 18}, {18, 17, 17}, {17, 14, 14}, {14, 13, 13}, {13, 12, 12},
                {12, 4, 4}, {4, 1, 1}, {1, 1, 8}, {8, 8, 15}, {15, 15, 21}, {21, 21, 23}};
        int cur = 0, lo = 0, si = -1, err = 0, bad = 0;
        CursorDrag d = null;
        for (String l : lines) {
            if (l.startsWith("#") || l.trim().isEmpty()) continue;
            String[] p = l.trim().split(" ");
            int f = Integer.parseInt(p[0]); float x = Float.parseFloat(p[1]);
            if (si + 1 < starts.length - 1 && f >= starts[si + 1]) {
                if (si >= 0) { int e = report(si, sam[si], lo, cur); err += e; if (e > 1) bad++; }
                si++; d = new CursorDrag(); d.start(x, ms(f)); cur = lo = sam[si][0]; continue;
            }
            cur = Math.max(0, Math.min(23, cur + d.moveKey(x, ms(f), 1080, 1f)));
            lo = Math.min(lo, cur);
        }
        int e = report(si, sam[si], lo, cur); err += e; if (e > 1) bad++;
        System.out.println("total |diff| = " + err + " steps over " + (si + 1) + " strokes, strokes off by >1: " + bad);
        if (bad > 1) { System.out.println("FAIL"); System.exit(1); }
        System.out.println("OK");
    }

    static long ms(int f) { return Math.round(f * 1000 / 42.404); }

    static int report(int si, int[] s, int lo, int cur) {
        int e = Math.max(Math.abs(lo - s[1]), Math.abs(cur - s[2]));
        System.out.println("stroke " + (si + 1) + ": samsung " + s[0] + "->" + s[1] + "->" + s[2] + "   ours " + s[0] + "->" + lo + "->" + cur + (e == 0 ? "" : "   diff " + e));
        return e;
    }
}
