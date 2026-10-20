package android.content;
public abstract class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final int MODE_PRIVATE = 0;
    public abstract Context getApplicationContext();
    public abstract Object getSystemService(String name);
    public abstract ContentResolver getContentResolver();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
