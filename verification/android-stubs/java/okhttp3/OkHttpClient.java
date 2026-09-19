package okhttp3;
import java.util.concurrent.TimeUnit;
public class OkHttpClient {
    public Call newCall(Request request) { return null; }
    public static class Builder {
        public Builder connectTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder readTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder callTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder retryOnConnectionFailure(boolean b) { return this; }
        public OkHttpClient build() { return null; }
    }
}
