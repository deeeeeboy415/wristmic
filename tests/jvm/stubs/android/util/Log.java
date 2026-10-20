package android.util;
public class Log {
    public static int i(String t, String m) { System.out.println("    [log] " + m); return 0; }
    public static int w(String t, String m, Throwable e) { System.out.println("    [warn] " + m + " " + e); return 0; }
}
