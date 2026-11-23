import com.thock.keyboard.*;
public class FastDeleteTest {
  static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
  public static void main(String[] args) {
    check(FastDelete.stage(999)==0 && FastDelete.stage(1000)==1 && FastDelete.stage(2999)==1 && FastDelete.stage(3000)==2 && FastDelete.stage(4999)==2 && FastDelete.stage(5000)==3, "time stages");
    check(FastDelete.precedingWordLength("밥 먹자  ")==4, "word with spaces");
    check(FastDelete.precedingWordLength("abc") == 3, "single word");
    CjEngine c = new CjEngine(); check(c.reopen('고'), "cj reopen"); c.vowel(CjEngine.I); c.vowel(CjEngine.A); check(c.composing().equals("과"), "cj go+a");
    NaraEngine n = new NaraEngine(); check(n.reopen('고'), "nara reopen"); n.consonant('ㄹ'); check(n.composing().equals("골"), "nara gol"); check(n.backspace() && n.composing().equals("고"), "nara backspace");
    n.consonant('ㄹ'); n.key(NaraEngine.AE); check(n.composing().equals("고라"), "nara split");
    NaraEngine n2 = new NaraEngine(); n2.reopen('밥'); n2.consonant('ㅅ'); n2.key(NaraEngine.AE); String committed = n2.consonant('ㅇ'); check((committed + n2.composing()).equals("밥상"), "babsang");
    System.out.println("PASS");
  }
}
