# EDUVPN — one-tap OpenVPN client for Android

Tap one button. The app fetches the public VPN Gate relay list, throws away the
dead relays, picks the one with the lowest measured round-trip time, decodes its
profile, hardens it and opens the tunnel. No IP addresses, no config files, no
manual input.

```
tap ──▶ fetch CSV ──▶ parse ──▶ filter dead ──▶ rank by ping ──▶ base64 → .ovpn
                                                                        │
                                            UI ◀── VpnStatus ◀── OpenVPN tunnel
```

| Layer | Where | Needs the Android SDK? |
|---|---|---|
| Data model | `app/src/main/java/.../data/model/` | no |
| CSV parser | `app/src/main/java/.../data/parser/` | no |
| Server ranking | `app/src/main/java/.../domain/ServerSelector.kt` | no |
| Config decode + hardening | `app/src/main/java/.../domain/OvpnConfigDecoder.kt` | no |
| Pipeline orchestration | `app/src/main/java/.../pipeline/OneTapPipeline.kt` | no |
| HTTP fetch (OkHttp) | `app/src/main/java/.../data/remote/VpnGateApi.kt` | yes |
| OpenVPN engine adapter | `app/src/main/java/.../vpn/` | yes |
| UI | `MainActivity.kt`, `res/layout/activity_main.xml` | yes |

The five Android-free layers are the risky logic, so they are unit-tested on a
plain JVM — no emulator, no Gradle, no Android SDK. See **Verification** below.

---

## ⚠️ Two corrections to the brief before you start

Both were verified on 2026-09-19 and both will cost you hours if you code to the
original wording.

### 1. `de.blinkt.openvpn:ics-openvpn:0.7.26` does not exist

A Maven Central query for the group `de.blinkt.openvpn` returns **zero
artifacts**. ics-openvpn ("OpenVPN for Android" by Arne Schwabe) is not published
as an AAR — its own README tells you to build it from source, and the module is a
`com.android.application` with an NDK/CMake/swig native build.

This project therefore vendors it as a **library module** (`settings.gradle.kts`
wires `../ics-openvpn/main` in as `:openvpn`). Step 2 of the guide walks through
the conversion.

The current release is **v0.7.65** (2026-09-05), not 0.7.26.

If you would rather not build native code at all, `alternative/aidl-integration/`
contains a working second path that drives an *installed* OpenVPN for Android
over its AIDL remote API.

### 2. The API URL is not `http://vpngate.net`

The CSV feed lives at:

```
https://www.vpngate.net/api/iphone/
```

`http://vpngate.net` serves an HTML page. Hitting it gives you a web page, which
is why `VpnGateApi` validates the response shape before handing it to the parser
— several deployments return an HTML error page with HTTP 200.

The real payload (verified live):

```
*vpn_servers
#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
public-vpn-45,219.100.37.9,2927964,10,243266034,Japan,JP,70,...,IyMjIyMj…
…
*
```

- 15 columns, CRLF line endings, `*vpn_servers` and `*` marker lines
- `OpenVPN_ConfigData_Base64` is column **14** — but the parser locates columns by
  *header name*, because the order has changed over the life of this API
- `Ping` is milliseconds; `0` means "probe never replied", which is the main
  dead-server signal

---

## Quick start

```bash
# 1. Get OpenVPN for Android as a library module, next to this repo.
git clone --depth 1 --branch v0.7.65 https://github.com/schwabe/ics-openvpn ../ics-openvpn

# 2. Convert its main module from application to library.
#    → docs/IMPLEMENTATION_GUIDE.md, step 2 (four small edits + swig)

# 3. Open this directory in Android Studio, sync, run on a device.
```

Full walkthrough: **[`docs/IMPLEMENTATION_GUIDE.md`](docs/IMPLEMENTATION_GUIDE.md)**.

---

## Verification

Two scripts, both runnable without an Android SDK.

```bash
# 1. Executes the real parser / selector / decoder / pipeline on the JVM,
#    against a byte-faithful extract of a live VPN Gate response.
verification/run_tests.sh

# 2. Type-checks every Android-coupled source against transcribed ics-openvpn
#    signatures and the real kotlinx-coroutines artifact.
verification/typecheck_android_sources.sh
```

The Gradle equivalent of (1) is `./gradlew :app:testDebugUnitTest`.

**What is and is not covered.** (1) *runs* `VpnGateCsvParser.parse`,
`ServerSelector.select`, `OvpnConfigDecoder.decode` and
`OneTapPipeline.prepareConnection` — the shipped classes, not copies. It found a
real bug: the hand-written Base64 decoder dropped the trailing partial group, so
every decoded profile was 1–3 bytes short. (2) compiles `MainActivity`,
`OneTapViewModel`, `IcsOpenVpnController`, `RemoteOpenVpnController`,
`VpnGateApi` and `EdUvpnApplication`; it caught a non-exhaustive `when` over
`ConnectionStatus` and a use of the internal `kotlin.Result.Success` type.

Neither script runs Gradle, AGP, AAPT or a device. **Resource references, the
manifest merge and the native build are not verified here.** A clean
`./gradlew assembleDebug` on a machine with the Android SDK is still required.

---

## Known limitations

- **VPN Gate relays are volunteer-run and keep logs** (`LogType` column says how
  long). This is a learning tool, not a privacy product. The UI says so.
- **Relays die constantly.** The pipeline returns a ranked queue, not one winner,
  and the ViewModel walks down it when a relay fails — but a tap can still end in
  "no relay could be reached".
- **Cleartext HTTP.** The HTTP fallbacks are allow-listed per-host in
  `res/xml/network_security_config.xml` rather than via a global
  `usesCleartextTraffic="true"`.
- **GPL.** Vendoring ics-openvpn puts its GPL terms on your app. The AIDL path in
  `alternative/` avoids that.

## Repository layout

```
app/                        Android application module
  src/main/java/…           production code
  src/main/res/             layout, colours, strings, network security config
  src/test/java/…           JUnit tests (./gradlew :app:testDebugUnitTest)
  src/test/resources/fixtures/   real + edge-case CSV fixtures
alternative/aidl-integration/    optional no-NDK integration path
docs/IMPLEMENTATION_GUIDE.md     step-by-step build guide
tools/make_fixtures.py      regenerates the fixtures from a live snapshot
verification/               JVM harness + API stubs (not part of the APK)
```
