/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Root build file. Plugins are declared here with `apply false` and applied per
 * module, which is what keeps the vendored :openvpn module's own build file
 * working unchanged.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
