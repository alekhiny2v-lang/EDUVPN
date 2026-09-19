package androidx.activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
public class ComponentActivity extends android.content.Context implements LifecycleOwner {
    public static final int RESULT_OK = -1;
    public static final int RESULT_CANCELED = 0;
    protected void onCreate(Bundle savedInstanceState) { }
    public void setContentView(android.view.View view) { }
    public LayoutInflater getLayoutInflater() { return null; }
    public <I, O> ActivityResultLauncher<I> registerForActivityResult(
            ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback) { return null; }
    public Lifecycle getLifecycle() { return null; }
}
