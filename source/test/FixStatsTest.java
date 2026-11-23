import com.thock.keyboard.FixStats;

/** 교정 횟수 기록: 최근 30일 · 전체 · 마지막 날 · 90일 지난 기록 정리 · 저장/읽기 · 999+ · 1,000어절당 요약 */
public class FixStatsTest {
    static int fail;
    static void eq(String w, Object got, Object want) { boolean ok = want.equals(got); if (!ok) fail++; System.out.println((ok ? "ok   " : "FAIL ") + w + ": " + got + (ok ? "" : "  (기대 " + want + ")")); }

    public static void main(String[] a) {
        FixStats s = new FixStats();
        int today = 20000;
        for (int i = 0; i < 5; i++) s.hit("고ㅓ", today - 100, 202607);   // 100일 전 5번 (날짜별 기록은 정리되지만 전체에는 남음)
        for (int i = 0; i < 3; i++) s.hit("고ㅓ", today - 40, 202609);
        for (int i = 0; i < 4; i++) s.hit("고ㅓ", today - 2, 202611);
        s.hit("되요", today, 202611);
        eq("전체", s.total("고ㅓ"), 12);
        eq("최근 30일", s.recent("고ㅓ", today, 30), 4);
        eq("최근 90일", s.recent("고ㅓ", today, 90), 7);
        eq("마지막 날", s.last("고ㅓ"), today - 2);
        eq("줄", s.line("고ㅓ", today), "최근 30일 4번 · 전체 12번 · 2일 전");
        eq("오늘", s.line("되요", today), "최근 30일 1번 · 전체 1번 · 오늘");
        eq("기록 없음", s.line("없음", today), "아직 고친 적 없음");
        eq("달별", s.month(202611), 5);
        FixStats t = new FixStats();
        t.decode(s.encode());
        eq("저장 후 읽기", t.encode(), s.encode());
        eq("읽은 뒤 최근 30일", t.recent("고ㅓ", today, 30), 4);
        t.seedTotal("예전", 1500);
        eq("예전 누적 + 999+", t.line("예전", today), "최근 30일 0번 · 전체 999+번 · 날짜 기록 전");
        eq("오래 안 쓴 순: 안 쓴 것 > 날짜 모름 > 오래됨", t.unusedDays("없음", today) > t.unusedDays("예전", today) && t.unusedDays("예전", today) > t.unusedDays("고ㅓ", today), true);
        eq("요약", FixStats.rateLine("자동 교정", 31, 10000, 54, 10000), "자동 교정: 이번 달 1,000어절당 3.1번 (지난달 5.4번 ↓)");
        eq("요약 적음", FixStats.rateLine("자동 교정", 2, 50, 0, 0), "자동 교정: 이번 달 기록이 아직 적어요 (어절 50개 · 2번)");
        eq("지난달", FixStats.prevMonth(202601), 202512);
        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
