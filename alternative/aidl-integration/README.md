# Alternative integration: drive an installed "OpenVPN for Android" over AIDL

The default build of this project vendors the ics-openvpn sources as a library
module. That gives full control but requires an NDK + CMake + swig toolchain and
adds the OpenVPN native libraries to your APK.

If you would rather not build native code, ics-openvpn exposes a **remote control
AIDL interface** that you can bind to instead. The whole
fetch → parse → rank → decode pipeline in this project is reused untouched —
`IOpenVPNAPIService.startVPN(String inlineconfig)` takes the `.ovpn` text as an
inline string, which is precisely what `OvpnConfigDecoder` produces.

## Trade-offs

| | Vendored module (default) | AIDL remote (this folder) |
|---|---|---|
| Extra install for the user | none | must install *OpenVPN for Android* |
| Native build (NDK/CMake/swig) | required | not required |
| APK size | +~10 MB of `libopenvpn` | unchanged |
| Licence | GPL for the vendored code | the remote-control API is documented by the project as usable from an external app |
| Control | in-process callbacks, pause/resume, log buffer | connect / disconnect / pause / resume / status |

## Wiring it in

1. Copy the three AIDL files into your app, keeping the package path:

   ```
   app/src/main/aidl/de/blinkt/openvpn/api/IOpenVPNAPIService.aidl
   app/src/main/aidl/de/blinkt/openvpn/api/IOpenVPNStatusCallback.aidl
   app/src/main/aidl/de/blinkt/openvpn/api/APIVpnProfile.aidl
   ```

2. Copy `RemoteOpenVpnController.kt` to
   `app/src/main/java/com/eduvpn/onetap/vpn/`.

3. In `app/build.gradle.kts`, enable AIDL and drop the module dependency:

   ```kotlin
   android {
       buildFeatures { aidl = true }
   }
   dependencies {
       // implementation(project(":openvpn"))   // <- remove
   }
   ```

4. Add a `<queries>` block to `AndroidManifest.xml` — without it, Android 11+
   hides the other app and `bindService` returns false:

   ```xml
   <queries>
       <package android:name="de.blinkt.openvpn" />
   </queries>
   ```

   You can then delete the `INTERNET`/`FOREGROUND_SERVICE*` permissions from
   your own manifest; the tunnel runs inside the other app's process.

5. Delete `IcsOpenVpnController.kt` and `EdUvpnApplication.kt`, and point the
   ViewModel at the new controller:

   ```kotlin
   private val controller: VpnController = RemoteOpenVpnController(application)
   ```

## One extra handshake

Before the first `startVPN`, the remote app wants *your* package to be on its
allow-list, and it also owns the `VpnService.prepare` consent. Both are handled
by methods on the same binder:

```kotlin
val api = IOpenVPNAPIService.Stub.asInterface(binder)
val appConsent = api.prepare(packageName)   // null == already allowed
val vpnConsent = api.prepareVPNService()    // null == already allowed
```

Launch whichever is non-null, then call `startVPN`. `RemoteOpenVpnController`
leaves that to the Activity because it needs an `ActivityResultLauncher`.
