package androidx.lifecycle;
import android.app.Application;
public abstract class AndroidViewModel extends ViewModel {
    public AndroidViewModel(Application application) { }
    public Application getApplication() { return null; }
}
