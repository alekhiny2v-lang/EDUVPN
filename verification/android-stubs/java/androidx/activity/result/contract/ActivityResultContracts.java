package androidx.activity.result.contract;
import android.content.Intent;
public final class ActivityResultContracts {
    public static final class StartActivityForResult extends ActivityResultContract<Intent, ActivityResult> { }
    public static final class RequestPermission extends ActivityResultContract<String, Boolean> { }
}
