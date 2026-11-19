import java.io.*;
public class T3 { public static void main(String[] a) throws Exception { PrintStream o=new PrintStream(new FileOutputStream("t3.txt"),true,"UTF-8"); for(String k:a) o.println(k+" -> "+CjTest.run(k)); } }
