/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eduvpn.onetap.databinding.ActivityMainBinding
import com.eduvpn.onetap.ui.UiState
import kotlinx.coroutines.launch

/**
 * The single screen: one round button, one status line, one country/ping line.
 *
 * This class deliberately owns only three responsibilities:
 *  1. the system VPN consent flow ([VpnService.prepare]),
 *  2. rendering [UiState],
 *  3. forwarding the tap to [OneTapViewModel], which runs the
 *     fetch -> parse -> rank -> decode -> connect pipeline.
 *
 * Nothing here parses CSV or touches OpenVPN types.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: OneTapViewModel by viewModels()

    /**
     * The system VPN consent dialog. Android requires this before any
     * [android.service.quicksettings.TileService] or `VpnService` subclass can
     * open a tunnel; the returned intent is null when consent was already given,
     * which is why [onConnectClicked] branches on it.
     */
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    /** Android 13+: without it the VPN's foreground notification is invisible. */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The tunnel works either way; the notification is just hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { onConnectClicked() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    /**
     * Tap handler. Resolves the VPN permission first, because handing the engine
     * a profile before consent is granted leaves the user staring at a
     * notification instead of a dialog.
     */
    private fun onConnectClicked() {
        if (viewModel.uiState.value.isConnected) {
            viewModel.disconnect()
            return
        }
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            // Already granted - connect immediately, no dialog.
            viewModel.connect()
        } else {
            vpnConsentLauncher.launch(consentIntent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(state: UiState) {
        with(binding) {
            statusText.text = state.statusText
            countryText.text = state.countryLine
            detailText.text = state.detailLine

            countryText.visibility =
                if (state.countryLine.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            detailText.visibility =
                if (state.detailLine.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

            connectButton.isEnabled = state.buttonEnabled
            connectButton.setText(
                when (state.phase) {
                    UiState.Phase.CONNECTED -> R.string.action_disconnect
                    UiState.Phase.CONNECTING -> R.string.action_connecting
                    UiState.Phase.FETCHING -> R.string.action_fetching
                    else -> R.string.action_connect
                },
            )
            connectButton.backgroundTintList =
                androidx.core.content.ContextCompat.getColorStateList(
                    this@MainActivity,
                    when (state.phase) {
                        UiState.Phase.CONNECTED -> R.color.button_connected
                        UiState.Phase.CONNECTING,
                        UiState.Phase.FETCHING,
                        -> R.color.button_busy
                        UiState.Phase.ERROR -> R.color.button_error
                        UiState.Phase.IDLE -> R.color.button_idle
                    },
                )

            progressIndicator.visibility =
                if (state.busy) android.view.View.VISIBLE else android.view.View.INVISIBLE
            if (state.busy) progressIndicator.show() else progressIndicator.hide()
        }
    }
}
