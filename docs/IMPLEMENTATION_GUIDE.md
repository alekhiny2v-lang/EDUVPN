# EDUVPN — implementation guide

A step-by-step build of the one-tap client, including the parts that are easy to
get wrong. Every fact about the VPN Gate feed and the ics-openvpn API in here was
checked against the live endpoint and against ics-openvpn v0.7.65 source on
2026-09-19.

---

## Step 0 — toolchain

| Tool | Version | Why |
|---|---|---|
| Android Studio | latest stable | AGP 9.4 needs it |
| Gradle | 9.7.1 | pinned in `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.0 | matches ics-openvpn v0.7.65 |
| Kotlin | 2.4.10 | ditto |
| JDK | 17 | ics-openvpn compiles to Java 17 bytecode |
| NDK | 30.0.14904198 | pinned by ics-openvpn's `ndkVersion` |
| CMake | from the SDK manager | native `libopenvpn` build |
| swig | 3.0+ | generates the OpenVPN 3 JNI wrapper |

macOS: `brew install swig`. Linux: `apt install swig`. The ics-openvpn build
script looks for `swig` on `PATH`, or at `/opt/homebrew/bin/swig` and
`/usr/local/bin/swig` on macOS.

---

## Step 1 — create the project

The repository already contains the full module. If you are starting from
scratch instead:

- New Project → Empty Views Activity, language Kotlin, min SDK 24, build config Kotlin DSL.
- Application ID `com.eduvpn.onetap`.
- Replace the generated files with the ones in `app/`.

---

## Step 2 — vendor ics-openvpn as a library module

This is the part the brief assumed away. There is no Maven artifact; the sources
are the distribution.

```bash
cd <parent of EDUVPN>
git clone --depth 1 --branch v0.7.65 https://github.com/schwabe/ics-openvpn
cd ics-openvpn
git submodule update --init --recursive     # pulls OpenVPN and openvpn3 sources
```

`settings.gradle.kts` in this repo already does:

```kotlin
include(":openvpn")
project(":openvpn").projectDir = File(rootDir.parentFile, "ics-openvpn/main")
```

Now convert `ics-openvpn/main/build.gradle.kts` from an application to a library.
Four edits, all in that one file:

**2.1 — the plugin**

```kotlin
plugins {
    alias(libs.plugins.android.library)   // was: android.application
}
```

`libs` resolves against *this* repository's `gradle/libs.versions.toml`, which
already defines `android-library` plus every other alias their build script
references. If you add a new alias there and forget to add it here, Gradle fails
during configuration with `Unresolved reference` — that is the symptom to look
for.

**2.2 — delete the `splits` block.** ABI splits are illegal in a library module:

```kotlin
// remove entirely
splits {
    abi { isEnable = true; reset(); include("x86", "x86_64", "armeabi-v7a", "arm64-v8a"); isUniversalApk = true }
}
```

**2.3 — delete the `bundle { codeTransparency { signing { … } } }` block.**
Bundle signing is an application concern.

**2.4 — delete `testBuildType = obtainTestBuildType()`.** Also an
application-only property (and the `obtainTestBuildType()` helper above it).

Leave the product flavours alone. `implementation = {ui, skeleton}` and
`ovpnimpl = {ovpn23, ovpn2}` stay, and `app/build.gradle.kts` picks one from each
dimension:

```kotlin
missingDimensionStrategy("implementation", "skeleton")  // the no-UI build
missingDimensionStrategy("ovpnimpl", "ovpn23")          // OpenVPN 2.x native
```

`skeleton` is exactly what an embedding app wants: it excludes the whole `ui`
source set (profile editor, log window, MPAndroidChart, preference screens) and
with it their AndroidX/UI dependencies.

First sync will take several minutes — it builds `libopenvpn` from C sources.
That is normal and only happens once per ABI.

---

## Step 3 — manifest and permissions

`app/src/main/AndroidManifest.xml` declares only what the *app* needs:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

`OpenVPNService` is **not** declared here. The library's own manifest already
declares it with `android:permission="android.permission.BIND_VPN_SERVICE"` and
`android:foregroundServiceType="specialUse"`, and the merger folds that in.
Declaring it twice invites the two definitions drifting apart.

There is no runtime permission for a VPN. The gate is `VpnService.prepare()`,
which returns an `Intent` you must start; see step 6.

`android:name=".EdUvpnApplication"` subclasses ics-openvpn's
`ICSOpenVPNApplication`, which initialises the log cache the engine writes to. An
app has one Application class, so subclassing is the only way to keep both.

---

## Step 4 — cleartext HTTP

The HTTP fallbacks in `VpnGateApi` need cleartext allowed, which Android blocks by
default from API 28. Rather than the blunt `android:usesCleartextTraffic="true"`,
`res/xml/network_security_config.xml` allow-lists two domains and denies
everything else:

```xml
<base-config cleartextTrafficPermitted="false"> … </base-config>
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">vpngate.net</domain>
    <domain includeSubdomains="true">openvpn.jp</domain>
</domain-config>
```

---

## Step 5 — the pipeline

### 5.1 Fetch — `data/remote/VpnGateApi.kt`

OkHttp on `Dispatchers.IO`, wrapped in the `ServerListSource` interface so the
rest of the app never sees OkHttp:

- **Endpoint list, walked in order.** HTTPS → HTTP → an hourly mirror of the CSV.
  Only endpoints verified to answer are defaults; the `vgateapi*.openvpn.jp`
  mirrors the site advertises were unreachable when this was written, so they are
  left as a documented extension point rather than a default that times out.
- **Retries with backoff** (`0ms, 750ms, 2000ms`) per endpoint.
- **Three timeouts**: connect 15 s, read 45 s, whole-call 90 s. A single call
  timeout matters because the response is ~1.3 MB.
- **A byte cap**, enforced while streaming. A hostile or broken mirror cannot
  make the app allocate an unbounded buffer.
- **A shape check** before parsing. This endpoint is known to answer HTTP 200
  with an HTML error page; failing here gives "response is not a server list"
  instead of a confusing parse error two layers down.

### 5.2 Parse — `data/parser/VpnGateCsvParser.kt`

```kotlin
val report = VpnGateCsvParser.parse(rawCsv)
// report.servers, report.skippedLines, report.headerRecognised
```

Five properties of the real feed drive the implementation:

1. **Marker lines.** The body is framed by `*vpn_servers` and a closing `*`.
2. **CRLF line endings.** Splitting on `\n` leaves a trailing `\r` on the last
   column, which silently corrupts the Base64. `lineSequence()` handles `\r\n`,
   `\n` and `\r`, and each line is trimmed of `\r` again defensively. *This was
   verified the hard way: reading the file in a text mode that normalises
   newlines produces a parser that passes every test and fails in production.*
3. **Column order is not stable**, so columns are located by header name and
   fixed indices are only a fallback.
4. **`Operator` and `Message` may contain commas**, so fields go through an
   RFC 4180 splitter with quote and `""`-escape handling.
5. **The feed can be truncated**, so a bad line is skipped and counted rather
   than aborting the parse.

`InvalidFeedException` is reserved for a response that contains *no* usable rows.

### 5.3 Rank — `domain/ServerSelector.kt`

Dead-server filter: `pingMs > 0 && ip.isNotBlank() && configBase64.isNotBlank()`.
`Ping == 0` means the VPN Gate probe never got a reply and is the strongest
signal available; `Score` is a tie-break, not a filter (a brand-new relay scores
0 but can still work).

Two strategies:

- `LOWEST_PING` (default) — `pingMs` ascending, `score` descending as tie-break.
  The literal answer to "select the lowest ping".
- `BEST_QUALITY` — normalised `0.55·ping + 0.30·(1−score) + 0.15·(1−speed)`.
  Avoids landing on a fast-but-saturated relay.

Both return a **queue** of up to 5 candidates, not a single winner. Relays die
between the CSV being generated and the user tapping; the ViewModel walks down
the queue.

### 5.4 Decode and harden — `domain/OvpnConfigDecoder.kt`

Base64 → UTF-8 text, then:

- **Validate.** Must contain a `remote` directive and an inline `<ca>`. A
  profile with no CA cannot work inline because nothing is ever written to disk.
- **Normalise** CRLF → LF.
- **Strip hostile directives.** `up`, `down`, `route-up`, `tls-verify`, `plugin`,
  `script-security`, `management*`, `auth-user-pass`, `daemon`, `log`, `tmp-dir`,
  `dev-node`, `setenv` and friends. The config comes from a third party, an
  `.ovpn` file can execute arbitrary programs, and OpenVPN on Android cannot run
  scripts anyway. `auth-user-pass` in particular would hang the connect waiting
  for credentials that do not exist.
- **Harden.** Append `remote-cert-tls server` (VPN Gate profiles do not ship it,
  and without it a malicious relay can present any certificate),
  `connect-retry 2 4` and `server-poll-timeout 20`.

Real profiles, for reference — all 100 rows of a live snapshot decode cleanly:

```
dev tun
proto tcp                    # 88 of 100 relays; the other 12 are udp
remote 219.100.37.9 443      # ports range from 443/995/1194 to random high ports
cipher AES-128-CBC
data-ciphers AES-128-CBC
auth SHA1
client
verb 3
#auth-user-pass
<ca> … </ca>
<cert> … </cert>
<key> … </key>
```

The Base64 decoder is hand-written (`domain/Base64Compat.kt`) so the module stays
free of `android.util.Base64` and testable on a JVM, and free of
`java.util.Base64` so it works below API 26. Its test asserts byte-for-byte
equality with `java.util.Base64` across every real payload and every padding
length.

### 5.5 Orchestrate — `pipeline/OneTapPipeline.kt`

`prepareConnection()` chains the four steps and reports failures as
`PipelineStage.{FETCH, PARSE, SELECT, DECODE}` so the UI can say *why*. It walks
the candidate queue at the decode stage too: a relay whose profile will not
decode is skipped rather than fatal.

Because this class is pure Kotlin, the whole pipeline is covered by a JVM test
that runs it over a real snapshot.

---

## Step 6 — the Activity, the consent dialog and the binding

`MainActivity` does three things only.

**Consent.** `VpnService.prepare(this)` returns null when permission was already
granted and a launchable `Intent` otherwise:

```kotlin
val consentIntent = VpnService.prepare(this)
if (consentIntent == null) viewModel.connect() else vpnConsentLauncher.launch(consentIntent)
```

Do this in the Activity, *before* handing a profile to the engine. If you skip
it, ics-openvpn's own fallback for a missing permission is `checkVPNPermission()`
in `OpenVPNService`, which just posts a notification pointing at `LaunchVPN` — a
class in the `ui` source set that the `skeleton` flavor excludes. The user would
see a notification they can't act on.

**Rendering.** One `StateFlow<UiState>` collected under `repeatOnLifecycle(STARTED)`.
`UiState` is immutable and carries everything: phase, status line, country line,
detail line, button-enabled, busy.

**Forwarding the tap** to `OneTapViewModel`, which owns the pipeline coroutine and
the engine listener and therefore survives rotation.

### Engine callbacks

`IcsOpenVpnController` implements `VpnStatus.StateListener`:

```kotlin
override fun updateState(
    state: String, logmessage: String, localizedResId: Int,
    level: ConnectionStatus, intent: Intent?,   // <- nullable, see below
)
```

Two details worth knowing:

- **`intent` must be nullable.** The Java declaration is `Intent Intent`, but
  `VpnStatus.updateStateString(state, msg, resid, level)` forwards `null`. Kotlin
  infers a platform type, so declaring it non-null compiles and then throws on
  every state change that carries no intent.
- **The `when` must be exhaustive.** `ConnectionStatus` has ten values;
  `LEVEL_WAITING_FOR_USER_INPUT` is easy to forget and its absence is a compile
  error.

### Starting and stopping

Start, in the order the library requires:

```kotlin
ConfigParser().parseConfig(StringReader(ovpn))   // -> may throw ConfigParseError
val profile = parser.convertProfile().apply { mName = "EDUVPN Japan (JP) · 10 ms" }
ProfileManager.setTemporaryProfile(context, profile)   // persists + bumps mVersion
VPNLaunchHelper.startOpenVpn(profile, context, reason, true)
```

`setTemporaryProfile` **must** come first: `VpnProfile.getStartServiceIntent`
stamps the current `mVersion` into the intent and `OpenVPNService.fetchVPNProfile`
then waits up to 10 s for a profile of that version.

Stop by binding for the control interface. Note the action — `onBind` returns the
binder only when the intent carries `START_SERVICE`, and returns null otherwise:

```kotlin
val intent = Intent(context, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
bindService(intent, object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
    }
    …
}, 0)
```

---

## Step 7 — build and run

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest     # the JVM test suite
./gradlew :app:installDebug
```

First launch: tap Connect → system VPN consent dialog → status walks
*Fetching best server… → Picking the fastest relay… → Connecting… → Connected*,
and the country and ping lines appear.

---

## Verification without an Android SDK

Two scripts cover everything that can be covered without a device. See the
README for what each one does and does not prove.

```bash
verification/run_tests.sh                     # executes the real parsing/selection code
verification/typecheck_android_sources.sh     # type-checks the Android-coupled code
```

`tools/make_fixtures.py <snapshot.csv>` regenerates the fixtures from a fresh
capture of the live feed, so the tests can be re-pointed at today's data:

```bash
curl -o /tmp/vpngate.csv https://www.vpngate.net/api/iphone/
python3 tools/make_fixtures.py /tmp/vpngate.csv --rows 5
```

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Unresolved reference` in `ics-openvpn/main/build.gradle.kts` | A version-catalog alias their build script uses is missing from `gradle/libs.versions.toml`. |
| `ABI splits are not supported for library projects` | Step 2.2 not done. |
| `swig: command not found` | Install swig; it is not bundled with the SDK. |
| `Property 'testBuildType' does not exist` on a library | Step 2.4 not done. |
| `unresolved reference: de.blinkt.openvpn` | `../ics-openvpn` missing; `settings.gradle.kts` logs a warning when it is. |
| Connect hangs on "Connecting…" | The relay accepted TCP then went quiet. The 25 s watchdog moves to the next candidate; check the log for `previous relay failed`. |
| Notification never appears | `POST_NOTIFICATIONS` denied (API 33+). The tunnel still works. |
| Cleartext blocked | A mirror host was added to `VpnGateApi` but not to `network_security_config.xml`. |

## Licensing

ics-openvpn is **GPL v2 with additional terms**. Vendoring it makes your app a
derivative work. The project's own FAQ is explicit that a closed-source custom UI
built on it needs a separate paid licence. `alternative/aidl-integration/` uses
the remote-control AIDL surface instead, which the project documents as usable
from an external app.
