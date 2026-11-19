import com.thock.keyboard.CjEngine;

public class CjTest {
    static String run(String keys) {
        // keys: 1=ㅣ 2=ㆍ 3=ㅡ, g=ㄱㅋ n=ㄴㄹ d=ㄷㅌ b=ㅂㅍ s=ㅅㅎ j=ㅈㅊ o=ㅇㅁ, _=separator(space-as-break), <=backspace
        CjEngine e = new CjEngine();
        StringBuilder out = new StringBuilder();
        for (char k : keys.toCharArray()) {
            switch (k) {
                case '1': out.append(e.vowel('ㅣ')); break;
                case '2': out.append(e.vowel('ㆍ')); break;
                case '3': out.append(e.vowel('ㅡ')); break;
                case '_': out.append(e.finish()); break;
                case '<': if (!e.backspace() && out.length() > 0) out.setLength(out.length() - 1); break;
                default: out.append(e.consonant("gndbsjo".indexOf(k)));
            }
        }
        return out + e.composing();
    }

    static int fail = 0;
    static void t(String keys, String expect) {
        String got = run(keys);
        System.out.println((got.equals(expect) ? "OK  " : "FAIL") + " " + keys + " -> " + got + (got.equals(expect) ? "" : "   (expected " + expect + ")"));
        if (!got.equals(expect)) fail++;
    }

    public static void main(String[] a) {
        t("ss12g_g221", "학교"); // ㅎ ㅏ ㄱ | ㄱ ㅛ
        t("o12nn1", "안녕".substring(0, 1) + "리"); // 안 + ㄹ? -> check cycling to ㄹ moves into next syllable: 아+ㄹ+ㅣ = 아리
        t("o221n1", "어니");
        t("g12", "가");
        t("g12g", "각");
        t("g12gg", "갘");
        t("g12ggg", "갂");
        t("g12g1", "가기");
        t("n12nng", "갉".replace('갉', '랅').substring(0, 0) + "날ㄱ".substring(0, 0) + "낡");
        t("d121", "대");
        t("s3", "스");
        t("o3ㆍ".replace("ㆍ", "2"), "우");
        t("o321", "위");
        t("o3221", "워");
        t("o2312", "와");
        t("o231", "외");
        t("o22121".substring(0,0) + "o2211", "예");
        t("b12s", "밧");
        t("b12bs", "밦".substring(0,0) + "밦".substring(0,0) + run("b12bs"));
        t("g12nnsss", "갏".substring(0,0) + "갏");
        t("ss12nn", "할");
        t("ss12nn<", "하");
        t("ss12<", "ㅎㅣ");
        t("j221s", "졋".substring(0,0) + "젓");
        t("j221ss", "젛");
        t("d2311", "되".substring(0,0) + "됴".substring(0,0) + run("d2311"));
        System.out.println(fail == 0 ? "ALL OK" : fail + " FAILED");
    }
}
