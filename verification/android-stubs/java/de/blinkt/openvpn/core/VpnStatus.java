package de.blinkt.openvpn.core;
import android.content.Context;
import android.content.Intent;
/** Transcribed from ics-openvpn v0.7.65 core/VpnStatus.java */
public class VpnStatus {
    public interface StateListener {
        void updateState(String state, String logmessage, int localizedResId, ConnectionStatus level, Intent Intent);
        void setConnectedVPN(String uuid);
    }
    public static synchronized void addStateListener(StateListener sl) { }
    public static synchronized void removeStateListener(StateListener sl) { }
    public static boolean isVPNActive() { return false; }
    public static String getLastCleanLogMessage(Context c) { return ""; }
}
