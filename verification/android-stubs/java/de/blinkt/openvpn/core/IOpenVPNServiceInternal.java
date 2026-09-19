package de.blinkt.openvpn.core;
import android.os.IBinder;
import android.os.RemoteException;
/** Transcribed from ics-openvpn v0.7.65 aidl/de/blinkt/openvpn/core/IOpenVPNServiceInternal.aidl */
public interface IOpenVPNServiceInternal {
    boolean protect(int fd) throws RemoteException;
    void userPause(boolean b) throws RemoteException;
    boolean stopVPN(boolean replaceConnection) throws RemoteException;
    abstract class Stub implements IOpenVPNServiceInternal {
        public static IOpenVPNServiceInternal asInterface(IBinder binder) { return null; }
    }
}
