package android.content;
public class Intent {
    public Intent() { }
    public Intent(android.content.Context c, Class<?> cls) { }
    public Intent setAction(String a) { return this; }
    public String getAction() { return null; }
    public Intent setComponent(ComponentName c) { return this; }
    public Intent putExtra(String k, String v) { return this; }
}
