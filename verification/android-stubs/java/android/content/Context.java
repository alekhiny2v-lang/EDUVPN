package android.content;
public class Context {
    public static final int BIND_AUTO_CREATE = 1;
    public boolean bindService(Intent i, ServiceConnection c, int flags) { return false; }
    public void unbindService(ServiceConnection c) { }
}
