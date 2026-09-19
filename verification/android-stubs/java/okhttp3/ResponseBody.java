package okhttp3;
import java.io.InputStream;
public abstract class ResponseBody implements java.io.Closeable {
    public abstract InputStream byteStream();
}
