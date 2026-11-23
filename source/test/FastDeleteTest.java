import com.thock.keyboard.*;
public class FastDeleteTest {
  static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
  public static void main(String[] args) {
    for (int i = 1; i <= 6; i++) {
      check(FastDelete.stage(i * 2000L - 1) == i - 1, "before stage " + i);
      check(FastDelete.stage(i * 2000L) == i, "start stage " + i);
    }
    check(FastDelete.stage(60000) == 6, "all stage stays final");
    check(FastDelete.delayMillis(0) == FastDelete.delayMillis(3) && FastDelete.delayMillis(1) == FastDelete.delayMillis(4) && FastDelete.delayMillis(2) == FastDelete.delayMillis(5), "word speed equals corresponding character speed");
    check(FastDelete.delayMillis(0) > FastDelete.delayMillis(1) && FastDelete.delayMillis(1) > FastDelete.delayMillis(2), "speeds increase");
    check(FastDelete.precedingWordLength("밥 먹자  ")==4, "word with spaces");
    check(FastDelete.precedingWordLength("abc") == 3, "single word");
    CjEngine c = new CjEngine(); check(c.reopen('고'), "cj reopen"); c.vowel(CjEngine.I); c.vowel(CjEngine.A); check(c.composing().equals("과"), "cj go+a");
    NaraEngine n = new NaraEngine(); check(n.reopen('고'), "nara reopen"); n.consonant('ㄹ'); check(n.composing().equals("골"), "nara gol"); check(n.backspace() && n.composing().equals("고"), "nara backspace");
    n.consonant('ㄹ'); n.key(NaraEngine.AE); check(n.composing().equals("고라"), "nara split");
    NaraEngine n2 = new NaraEngine(); n2.reopen('밥'); n2.consonant('ㅅ'); n2.key(NaraEngine.AE); String committed = n2.consonant('ㅇ'); check((committed + n2.composing()).equals("밥상"), "babsang");
    CjEngine loneC = new CjEngine(); check(loneC.reopen('ㄱ'), "cj reopen consonant"); loneC.vowel(CjEngine.A); loneC.vowel(CjEngine.A); loneC.vowel(CjEngine.EU); check(loneC.composing().equals("교"), "cj consonant yo");
    NaraEngine loneN = new NaraEngine(); check(loneN.reopen('ㄱ'), "nara reopen consonant"); loneN.key(NaraEngine.OU); loneN.key(NaraEngine.STROKE); check(loneN.composing().equals("교"), "nara consonant yo");
    check(!new CjEngine().reopen('ㄳ') && !new NaraEngine().reopen('ㅏ'), "unusable jamo are not reopened");
    System.out.println("PASS");
  }
}
