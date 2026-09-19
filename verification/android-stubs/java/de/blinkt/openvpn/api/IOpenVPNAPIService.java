package de.blinkt.openvpn.api;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
/**
 * Mirrors the AIDL-generated interface from
 * ics-openvpn v0.7.65 aidl/de/blinkt/openvpn/api/IOpenVPNAPIService.aidl.
 * Only the members EDUVPN uses are declared.
 */
public interface IOpenVPNAPIService {
    void startVPN(String inlineconfig) throws RemoteException;
    Intent prepare(String packagename) throws RemoteException;
    Intent prepareVPNService() throws RemoteException;
    void disconnect() throws RemoteException;
    void pause() throws RemoteException;
    void resume() throws RemoteException;
    void registerStatusCallback(IOpenVPNStatusCallback cb) throws RemoteException;
    void unregisterStatusCallback(IOpenVPNStatusCallback cb) throws RemoteException;
    abstract class Stub {
        public static IOpenVPNAPIService asInterface(IBinder binder) { return null; }
    }
}
