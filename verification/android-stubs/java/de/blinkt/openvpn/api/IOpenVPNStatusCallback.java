package de.blinkt.openvpn.api;
import android.os.IBinder;
import android.os.RemoteException;
/**
 * Mirrors the AIDL-generated callback from
 * ics-openvpn v0.7.65 aidl/de/blinkt/openvpn/api/IOpenVPNStatusCallback.aidl.
 */
public interface IOpenVPNStatusCallback {
    void newStatus(String uuid, String state, String message, String level) throws RemoteException;
    abstract class Stub implements IOpenVPNStatusCallback {
        public void newStatus(String uuid, String state, String message, String level) { }
        public IBinder asBinder() { return null; }
    }
}
