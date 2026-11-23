package com.thock.keyboard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 교정 횟수 기록 (자동 교정 규칙 / 맞춤법 항목 각각 하나씩).
 *  - 항목마다: 전체 횟수(끝까지 셈, 화면에서만 999+), 마지막으로 고친 날, 최근 90일의 날짜별 횟수
 *  - 달마다: 고친 횟수 합계 (위 요약 카드: 1,000어절당 교정 횟수 = 달별 고친 횟수 ÷ 달별 어절 수)
 * 날짜는 "1970-01-01 부터 며칠째"(지역 시간), 달은 yyyymm. 안드로이드 의존 없음 (JVM 테스트용).
 */
public final class FixStats {
    public static final int KEEP_DAYS = 90, KEEP_MONTHS = 24;

    static final class Rec {
        int total, last;                                   // last = 0 이면 아직 기록 없음
        final TreeMap<Integer, Integer> days = new TreeMap<>();
    }

    final Map<String, Rec> recs = new HashMap<>();
    final TreeMap<Integer, Integer> months = new TreeMap<>();

    /** 한 번 고침 */
    public void hit(String key, int day, int month) {
        Rec r = recs.get(key);
        if (r == null) { r = new Rec(); recs.put(key, r); }
        r.total++;
        r.last = day;
        Integer n = r.days.get(day);
        r.days.put(day, n == null ? 1 : n + 1);
        Integer m = months.get(month);
        months.put(month, m == null ? 1 : m + 1);
        prune(day);
    }

    public int total(String key) { Rec r = recs.get(key); return r == null ? 0 : r.total; }

    public int last(String key) { Rec r = recs.get(key); return r == null ? 0 : r.last; }

    /** 오늘을 포함한 최근 span 일 동안의 횟수 */
    public int recent(String key, int today, int span) {
        Rec r = recs.get(key);
        if (r == null) return 0;
        int n = 0;
        for (Map.Entry<Integer, Integer> e : r.days.tailMap(today - span + 1).entrySet()) if (e.getKey() <= today) n += e.getValue();
        return n;
    }

    public int month(int yyyymm) { Integer n = months.get(yyyymm); return n == null ? 0 : n; }

    public void remove(String key) { recs.remove(key); }

    /** 예전 버전의 누적 횟수만 있을 때 (마지막 날짜·최근 기록은 모름) */
    public void seedTotal(String key, int total) {
        if (total <= 0 || recs.containsKey(key)) return;
        Rec r = new Rec(); r.total = total; recs.put(key, r);
    }

    public void clear() { recs.clear(); months.clear(); }

    void prune(int today) {
        for (Rec r : recs.values()) r.days.headMap(today - KEEP_DAYS + 1).clear();
        while (months.size() > KEEP_MONTHS) months.remove(months.firstKey());
    }

    // ---------------------------------------------------------------- 저장 문자열
    // 한 줄에 항목 하나: key TAB total TAB last TAB day:count,day:count / 마지막 줄: "#M" TAB yyyymm:count,...
    // (설정 파일이 XML 이라 제어 문자는 쓰지 않는다. 오타·항목 id 에는 탭·줄바꿈이 없음)
    public String encode() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Rec> e : recs.entrySet()) {
            Rec r = e.getValue();
            b.append(e.getKey()).append('\t').append(r.total).append('\t').append(r.last).append('\t');
            boolean first = true;
            for (Map.Entry<Integer, Integer> d : r.days.entrySet()) { if (!first) b.append(','); first = false; b.append(d.getKey()).append(':').append(d.getValue()); }
            b.append('\n');
        }
        b.append("#M\t");
        boolean first = true;
        for (Map.Entry<Integer, Integer> m : months.entrySet()) { if (!first) b.append(','); first = false; b.append(m.getKey()).append(':').append(m.getValue()); }
        return b.toString();
    }

    public void decode(String s) {
        clear();
        if (s == null || s.isEmpty()) return;
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && f[0].equals("#M")) {
                if (!f[1].isEmpty()) for (String m : f[1].split(",")) {
                    try { int c = m.indexOf(':'); months.put(Integer.parseInt(m.substring(0, c)), Integer.parseInt(m.substring(c + 1))); } catch (RuntimeException ignored) {}
                }
                continue;
            }
            if (f.length < 4) continue;
            try {
                Rec r = new Rec();
                r.total = Integer.parseInt(f[1]);
                r.last = Integer.parseInt(f[2]);
                if (!f[3].isEmpty()) for (String d : f[3].split(",")) { int c = d.indexOf(':'); r.days.put(Integer.parseInt(d.substring(0, c)), Integer.parseInt(d.substring(c + 1))); }
                recs.put(f[0], r);
            } catch (RuntimeException ignored) {}
        }
    }

    // ---------------------------------------------------------------- 날짜 · 표시
    public static int today() {
        long now = System.currentTimeMillis();
        return (int) ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L);
    }

    public static int thisMonth() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 100 + c.get(java.util.Calendar.MONTH) + 1;
    }

    public static int prevMonth(int yyyymm) { int y = yyyymm / 100, m = yyyymm % 100; return m == 1 ? (y - 1) * 100 + 12 : y * 100 + m - 1; }

    /** 화면 표시: 1000 이상은 999+ */
    public static String fmt(int n) { return n > 999 ? "999+" : String.valueOf(n); }

    /** "최근 30일 4번 · 전체 128번 · 12일 전" */
    public String line(String key, int today) {
        Rec r = recs.get(key);
        if (r == null || r.total == 0) return "아직 고친 적 없음";
        String when = r.last == 0 ? "날짜 기록 전" : r.last >= today ? "오늘" : (today - r.last) + "일 전";
        return "최근 30일 " + fmt(recent(key, today, 30)) + "번 · 전체 " + fmt(r.total) + "번 · " + when;
    }

    /** 정렬용 "오래 안 쓴" 정도: 한 번도 안 쓴 것이 가장 오래된 것 */
    public int unusedDays(String key, int today) {
        Rec r = recs.get(key);
        if (r == null || r.total == 0) return Integer.MAX_VALUE;
        if (r.last == 0) return Integer.MAX_VALUE - 1;
        return today - r.last;
    }

    /** 위 요약: "이번 달 1,000어절당 3.1번 (지난달 5.4번 ↓)" */
    public static String rateLine(String what, int fixesNow, int wordsNow, int fixesPrev, int wordsPrev) {
        if (wordsNow < 200) return what + ": 이번 달 기록이 아직 적어요 (어절 " + wordsNow + "개 · " + fixesNow + "번)";
        double now = fixesNow * 1000.0 / wordsNow;
        String s = what + ": 이번 달 1,000어절당 " + String.format(java.util.Locale.US, "%.1f", now) + "번";
        if (wordsPrev >= 200) {
            double prev = fixesPrev * 1000.0 / wordsPrev;
            s += " (지난달 " + String.format(java.util.Locale.US, "%.1f", prev) + "번 " + (now < prev - 0.05 ? "↓" : now > prev + 0.05 ? "↑" : "→") + ")";
        }
        return s;
    }

    /** 어절 수 같은 달별 숫자 (Typo 가 씀) */
    static String encodeMonths(TreeMap<Integer, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : m.entrySet()) { if (b.length() > 0) b.append(','); b.append(e.getKey()).append(':').append(e.getValue()); }
        return b.toString();
    }

    static void decodeMonths(String s, TreeMap<Integer, Integer> into) {
        into.clear();
        if (s == null || s.isEmpty()) return;
        for (String m : s.split(",")) {
            try { int c = m.indexOf(':'); into.put(Integer.parseInt(m.substring(0, c)), Integer.parseInt(m.substring(c + 1))); } catch (RuntimeException ignored) {}
        }
        for (Iterator<Integer> it = into.keySet().iterator(); into.size() > KEEP_MONTHS && it.hasNext(); ) { it.next(); it.remove(); }
    }
}
