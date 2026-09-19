package android.view;
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public void setVisibility(int v) { }
    public int getVisibility() { return VISIBLE; }
    public void setEnabled(boolean e) { }
    public boolean isEnabled() { return true; }
    public void setOnClickListener(OnClickListener l) { }
    public interface OnClickListener { void onClick(View v); }
}
