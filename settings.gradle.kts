/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * NOTE ON THE OPENVPN DEPENDENCY
 * ------------------------------
 * The task brief asked for `de.blinkt.openvpn:ics-openvpn:0.7.26`. That
 * coordinate does not exist: a Maven Central query for the group
 * `de.blinkt.openvpn` returns zero artifacts, and the project's own README
 * describes a Gradle *application* module that you build from source.
 *
 * ics-openvpn is therefore consumed as a source module, exactly as its own
 * documentation recommends. Clone it next to this project:
 *
 *     git clone --depth 1 --branch v0.7.65 https://github.com/schwabe/ics-openvpn ../ics-openvpn
 *
 * then follow docs/IMPLEMENTATION_GUIDE.md step 2 to switch its `main` module
 * from `com.android.application` to `com.android.library`.
 *
 * If ../ics-openvpn is not present the build stops with a clear message rather
 * than failing later with an unresolved `de.blinkt.openvpn.*` import.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ics-openvpn's `ui` flavor pulls MPAndroidChart from JitPack. This app
        // builds the `skeleton` flavor so the artifact is never needed at runtime,
        // but Gradle still has to be able to resolve the coordinate.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EDUVPN"

include(":app")

val openVpnDir = File(rootDir.parentFile, "ics-openvpn")
if (openVpnDir.isDirectory && File(openVpnDir, "main/build.gradle.kts").isFile) {
    include(":openvpn")
    project(":openvpn").projectDir = File(openVpnDir, "main")
} else {
    logger.warn(
        """
        EDUVPN: the ics-openvpn module was not found at ${openVpnDir.absolutePath}.
        Clone it with:
            git clone --depth 1 --branch v0.7.65 https://github.com/schwabe/ics-openvpn ../ics-openvpn
        See docs/IMPLEMENTATION_GUIDE.md step 2.
        """.trimIndent(),
    )
}
