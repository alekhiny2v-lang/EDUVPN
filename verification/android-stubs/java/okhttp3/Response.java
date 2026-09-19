package okhttp3;
public class Response implements java.io.Closeable {
    public boolean isSuccessful() { return false; }
    public ResponseBody getBody() { return null; }
    public int getCode() { return 0; }
    public void close() { }
}
