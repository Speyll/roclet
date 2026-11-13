package com.roclet.mic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.roclet.mic.databinding.ActivityMainBinding
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    private enum class Mode { MIC, SPEAKER }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var currentMode = Mode.MIC
    private var settingsExpanded = false
    private var pendingAction = false
    private var pendingPermissionMode: Mode? = null

    private var serviceBound = false
    private var serviceReady = false
    private var micBinder: MicSenderService.LocalBinder? = null
    private var speakerBinder: SpeakerReceiverService.LocalBinder? = null

    private val micStateListener = MicSenderService.StateListener { state, error ->
        if (currentMode == Mode.MIC) {
            renderMic(state, error)
        }
    }

    private val speakerStateListener = SpeakerReceiverService.StateListener { state, error ->
        if (currentMode == Mode.SPEAKER) {
            renderSpeaker(state, error)
        }
    }

    private val micConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (currentMode != Mode.MIC) return
            val localBinder = binder as MicSenderService.LocalBinder
            micBinder?.removeStateListener(micStateListener)
            micBinder = localBinder
            serviceReady = true
            localBinder.addStateListener(micStateListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (currentMode != Mode.MIC) return
            handleServiceDisconnected()
        }
    }

    private val speakerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (currentMode != Mode.SPEAKER) return
            val localBinder = binder as SpeakerReceiverService.LocalBinder
            speakerBinder?.removeStateListener(speakerStateListener)
            speakerBinder = localBinder
            serviceReady = true
            localBinder.addStateListener(speakerStateListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (currentMode != Mode.SPEAKER) return
            handleServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureEdgeToEdge()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentMode = if (prefs.getString(PREF_MODE, MODE_MIC) == MODE_SPEAKER) {
            Mode.SPEAKER
        } else {
            Mode.MIC
        }

        loadSettingsForMode()

        settingsExpanded = savedInstanceState?.getBoolean(STATE_SETTINGS_EXPANDED) ?: false
        applySettingsExpanded()

        binding.ivSettings.setOnClickListener {
            settingsExpanded = !settingsExpanded
            applySettingsExpanded()
            updateDestSummary()
        }
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            onModeChecked(checkedId)
        }
        binding.btnMic.setOnClickListener { onControlClicked() }
        binding.btnNotices.setOnClickListener { showThirdPartyNotices() }

        applyModeUi()
        renderIdle()
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSystemBarColors()
        applySystemBarIconAppearance()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBarColors() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun applySystemBarIconAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    override fun onStart() {
        super.onStart()
        bindSelectedService()
    }

    override fun onStop() {
        super.onStop()
        pendingAction = false
        pendingPermissionMode = null
        unbindSelectedService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS_EXPANDED, settingsExpanded)
    }

    private fun bindSelectedService() {
        if (serviceBound) return

        val intent = Intent(
            this,
            if (currentMode == Mode.MIC) {
                MicSenderService::class.java
            } else {
                SpeakerReceiverService::class.java
            }
        )
        val connection = if (currentMode == Mode.MIC) micConnection else speakerConnection
        serviceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        serviceReady = false
        updateModeControlEnabled()
    }

    private fun unbindSelectedService() {
        if (!serviceBound) {
            serviceReady = false
            return
        }

        when (currentMode) {
            Mode.MIC -> {
                micBinder?.removeStateListener(micStateListener)
                micBinder = null
                try {
                    unbindService(micConnection)
                } catch (_: IllegalArgumentException) {
                }
            }
            Mode.SPEAKER -> {
                speakerBinder?.removeStateListener(speakerStateListener)
                speakerBinder = null
                try {
                    unbindService(speakerConnection)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        serviceBound = false
        serviceReady = false
    }

    private fun handleServiceDisconnected() {
        unbindSelectedService()
        renderIdle()
        bindSelectedService()
    }

    private fun onModeChecked(checkedId: Int) {
        val newMode = when (checkedId) {
            R.id.rbMic -> Mode.MIC
            R.id.rbSpeaker -> Mode.SPEAKER
            else -> return
        }
        if (newMode == currentMode) return

        if (pendingAction || isBusy()) {
            applyModeSelection()
            return
        }

        unbindSelectedService()
        currentMode = newMode
        prefs.edit()
            .putString(PREF_MODE, if (currentMode == Mode.MIC) MODE_MIC else MODE_SPEAKER)
            .apply()
        loadSettingsForMode()
        applyModeUi()
        renderIdle()
        bindSelectedService()
    }

    private fun applyModeSelection() {
        binding.modeGroup.check(
            if (currentMode == Mode.MIC) R.id.rbMic else R.id.rbSpeaker
        )
    }

    private fun applyModeUi() {
        val isMic = currentMode == Mode.MIC

        applyModeSelection()
        binding.tvIpLabel.visibility = if (isMic) View.VISIBLE else View.GONE
        binding.etIp.visibility = if (isMic) View.VISIBLE else View.GONE
        binding.tvPortsLabel.setText(
            if (isMic) R.string.network_ports else R.string.listen_ports
        )
        binding.tvSpeakerHint.visibility = if (isMic) View.GONE else View.VISIBLE
        binding.btnMic.setImageResource(
            if (isMic) R.drawable.ic_mic else R.drawable.ic_speaker
        )
        binding.btnMic.contentDescription = getString(
            if (isMic) R.string.cd_start_stream else R.string.cd_start_listening
        )

        updateModeControlEnabled()
        updateDestSummary()
    }

    private fun onControlClicked() {
        if (pendingAction || (serviceBound && !serviceReady)) return

        when (currentMode) {
            Mode.MIC -> when (micBinder?.state ?: MicSenderService.State.STOPPED) {
                MicSenderService.State.STOPPED,
                MicSenderService.State.ERROR -> requestStart()

                MicSenderService.State.STREAMING -> requestStop()

                MicSenderService.State.STARTING,
                MicSenderService.State.STOPPING -> Unit
            }
            Mode.SPEAKER -> when (
                speakerBinder?.state ?: SpeakerReceiverService.State.STOPPED
            ) {
                SpeakerReceiverService.State.STOPPED,
                SpeakerReceiverService.State.ERROR -> requestStart()

                SpeakerReceiverService.State.LISTENING,
                SpeakerReceiverService.State.PAUSED -> requestStop()

                SpeakerReceiverService.State.STARTING,
                SpeakerReceiverService.State.STOPPING -> Unit
            }
        }
    }

    private fun requestStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (currentMode == Mode.MIC) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            pendingAction = true
            pendingPermissionMode = currentMode
            updateModeControlEnabled()
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST_CODE)
            return
        }

        startSelectedService()
    }

    private fun startSelectedService() {
        val intent = when (currentMode) {
            Mode.MIC -> startMicrophoneService()
            Mode.SPEAKER -> startSpeakerService()
        } ?: run {
            updateModeControlEnabled()
            return
        }

        pendingAction = true
        binding.btnMic.isEnabled = false
        binding.btnMic.alpha = DISABLED_ALPHA
        updateModeControlEnabled()
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            pendingAction = false
            binding.btnMic.isEnabled = true
            binding.btnMic.alpha = 1f
            showError(getString(R.string.error_service_start_failed))
            updateModeControlEnabled()
        }
    }

    private fun startMicrophoneService(): Intent? {
        val ip = binding.etIp.text.toString().trim()
        if (ip.isEmpty()) {
            showError(getString(R.string.status_enter_ip))
            return null
        }

        val ports = parsePorts() ?: return null

        prefs.edit()
            .putString(PREF_DEST_IP, ip)
            .putString(PREF_SOURCE_PORT, ports.first.toString())
            .putString(PREF_REPAIR_PORT, ports.second.toString())
            .apply()

        stopOtherModeService()

        return Intent(this, MicSenderService::class.java).apply {
            putExtra(MicSenderService.EXTRA_DEST_IP, ip)
            putExtra(MicSenderService.EXTRA_SOURCE_PORT, ports.first)
            putExtra(MicSenderService.EXTRA_REPAIR_PORT, ports.second)
            putExtra(MicSenderService.EXTRA_ACTION, MicSenderService.ACTION_START)
        }
    }

    private fun startSpeakerService(): Intent? {
        val ports = parsePorts() ?: return null

        prefs.edit()
            .putString(PREF_SPEAKER_SOURCE_PORT, ports.first.toString())
            .putString(PREF_SPEAKER_REPAIR_PORT, ports.second.toString())
            .apply()

        stopOtherModeService()

        return Intent(this, SpeakerReceiverService::class.java).apply {
            putExtra(SpeakerReceiverService.EXTRA_SOURCE_PORT, ports.first)
            putExtra(SpeakerReceiverService.EXTRA_REPAIR_PORT, ports.second)
            putExtra(SpeakerReceiverService.EXTRA_ACTION, SpeakerReceiverService.ACTION_START)
        }
    }

    private fun parsePorts(): Pair<Int, Int>? {
        binding.etSourcePort.error = null
        binding.etRepairPort.error = null

        val sourcePort = parsePort(binding.etSourcePort) ?: return null
        val repairPort = parsePort(binding.etRepairPort) ?: return null
        if (sourcePort == repairPort) {
            binding.etRepairPort.error = getString(R.string.error_ports_must_differ)
            return null
        }
        return Pair(sourcePort, repairPort)
    }

    private fun stopOtherModeService() {
        val otherService = if (currentMode == Mode.MIC) {
            SpeakerReceiverService::class.java
        } else {
            MicSenderService::class.java
        }
        stopService(Intent(this, otherService))
    }

    private fun requestStop() {
        val intent = when (currentMode) {
            Mode.MIC -> Intent(this, MicSenderService::class.java).apply {
                putExtra(MicSenderService.EXTRA_ACTION, MicSenderService.ACTION_STOP)
            }
            Mode.SPEAKER -> Intent(this, SpeakerReceiverService::class.java).apply {
                putExtra(SpeakerReceiverService.EXTRA_ACTION, SpeakerReceiverService.ACTION_STOP)
            }
        }
        pendingAction = true
        binding.btnMic.isEnabled = false
        binding.btnMic.alpha = DISABLED_ALPHA
        updateModeControlEnabled()
        try {
            startService(intent)
        } catch (t: Throwable) {
            pendingAction = false
            binding.btnMic.isEnabled = true
            binding.btnMic.alpha = 1f
            showError(getString(R.string.error_service_start_failed))
            updateModeControlEnabled()
        }
    }

    private fun renderIdle() {
        pendingAction = false
        setFieldsEnabled(true)
        binding.btnMic.isEnabled = true
        tintMic(R.color.mic_ready)
        binding.btnMic.contentDescription = getString(
            if (currentMode == Mode.MIC) R.string.cd_start_stream
            else R.string.cd_start_listening
        )
        setStatus(R.string.state_stopped, R.color.text_primary)
        binding.btnMic.alpha = 1f
        updateModeControlEnabled()
        updateDestSummary()
    }

    private fun renderMic(state: MicSenderService.State, error: String?) {
        pendingAction = false

        setFieldsEnabled(
            state == MicSenderService.State.STOPPED || state == MicSenderService.State.ERROR
        )

        when (state) {
            MicSenderService.State.STOPPED -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_ready)
                binding.btnMic.contentDescription = getString(R.string.cd_start_stream)
                setStatus(R.string.state_stopped, R.color.text_primary)
            }
            MicSenderService.State.STARTING -> {
                binding.btnMic.isEnabled = false
                tintMic(R.color.mic_transitional)
                binding.btnMic.contentDescription = getString(R.string.cd_starting)
                setStatus(R.string.state_starting, R.color.text_secondary)
            }
            MicSenderService.State.STREAMING -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_streaming)
                binding.btnMic.contentDescription = getString(R.string.cd_stop_stream)
                setStatus(R.string.state_streaming, R.color.status_streaming)
            }
            MicSenderService.State.STOPPING -> {
                binding.btnMic.isEnabled = false
                tintMic(R.color.mic_transitional)
                binding.btnMic.contentDescription = getString(R.string.cd_stopping)
                setStatus(R.string.state_stopping, R.color.text_secondary)
            }
            MicSenderService.State.ERROR -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_ready)
                binding.btnMic.contentDescription = getString(R.string.cd_start_stream)
                binding.tvStatus.text = error ?: getString(R.string.error_start_failed)
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_error)
                )
            }
        }

        binding.btnMic.alpha = if (binding.btnMic.isEnabled) 1f else DISABLED_ALPHA
        updateModeControlEnabled()
        updateDestSummary()
    }

    private fun renderSpeaker(state: SpeakerReceiverService.State, error: String?) {
        pendingAction = false

        setFieldsEnabled(
            state == SpeakerReceiverService.State.STOPPED ||
                state == SpeakerReceiverService.State.ERROR
        )

        when (state) {
            SpeakerReceiverService.State.STOPPED -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_ready)
                binding.btnMic.contentDescription = getString(R.string.cd_start_listening)
                setStatus(R.string.state_stopped, R.color.text_primary)
            }
            SpeakerReceiverService.State.STARTING -> {
                binding.btnMic.isEnabled = false
                tintMic(R.color.mic_transitional)
                binding.btnMic.contentDescription =
                    getString(R.string.cd_starting_listening)
                setStatus(R.string.state_starting, R.color.text_secondary)
            }
            SpeakerReceiverService.State.LISTENING -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_streaming)
                binding.btnMic.contentDescription = getString(R.string.cd_stop_listening)
                setStatus(R.string.state_listening, R.color.status_streaming)
            }
            SpeakerReceiverService.State.PAUSED -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_transitional)
                binding.btnMic.contentDescription = getString(R.string.cd_stop_listening)
                setStatus(R.string.state_paused, R.color.text_secondary)
            }
            SpeakerReceiverService.State.STOPPING -> {
                binding.btnMic.isEnabled = false
                tintMic(R.color.mic_transitional)
                binding.btnMic.contentDescription =
                    getString(R.string.cd_stopping_listening)
                setStatus(R.string.state_stopping, R.color.text_secondary)
            }
            SpeakerReceiverService.State.ERROR -> {
                binding.btnMic.isEnabled = true
                tintMic(R.color.mic_ready)
                binding.btnMic.contentDescription = getString(R.string.cd_start_listening)
                binding.tvStatus.text = error ?: getString(R.string.error_receive_start_failed)
                binding.tvStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_error)
                )
            }
        }

        binding.btnMic.alpha = if (binding.btnMic.isEnabled) 1f else DISABLED_ALPHA
        updateModeControlEnabled()
        updateDestSummary()
    }

    private fun isBusy(): Boolean {
        if (serviceBound && !serviceReady) return true

        return when (currentMode) {
            Mode.MIC -> when (micBinder?.state) {
                null,
                MicSenderService.State.STOPPED,
                MicSenderService.State.ERROR -> false

                MicSenderService.State.STARTING,
                MicSenderService.State.STREAMING,
                MicSenderService.State.STOPPING -> true
            }
            Mode.SPEAKER -> when (speakerBinder?.state) {
                null,
                SpeakerReceiverService.State.STOPPED,
                SpeakerReceiverService.State.ERROR -> false

                SpeakerReceiverService.State.STARTING,
                SpeakerReceiverService.State.LISTENING,
                SpeakerReceiverService.State.PAUSED,
                SpeakerReceiverService.State.STOPPING -> true
            }
        }
    }

    private fun updateModeControlEnabled() {
        val enabled = !pendingAction && !isBusy()
        binding.modeGroup.isEnabled = enabled
        binding.rbMic.isEnabled = enabled
        binding.rbSpeaker.isEnabled = enabled
        binding.modeGroup.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun tintMic(colorRes: Int) {
        binding.btnMic.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun setStatus(textRes: Int, colorRes: Int) {
        binding.tvStatus.setText(textRes)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showError(message: String) {
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
    }

    private fun showThirdPartyNotices() {
        val notices = resources.openRawResource(R.raw.third_party_notices)
            .bufferedReader()
            .use { it.readText() }
        val view = layoutInflater.inflate(R.layout.dialog_third_party_notices, null)
        view.findViewById<TextView>(R.id.tvNotices).text = notices

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.third_party_notices)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.etIp.isEnabled = enabled && currentMode == Mode.MIC
        binding.etSourcePort.isEnabled = enabled
        binding.etRepairPort.isEnabled = enabled
    }

    private fun loadSettingsForMode() {
        when (currentMode) {
            Mode.MIC -> {
                binding.etIp.setText(prefs.getString(PREF_DEST_IP, ""))
                binding.etSourcePort.setText(
                    prefs.getString(
                        PREF_SOURCE_PORT,
                        RocAudioSender.DEFAULT_SOURCE_PORT.toString()
                    )
                )
                binding.etRepairPort.setText(
                    prefs.getString(
                        PREF_REPAIR_PORT,
                        RocAudioSender.DEFAULT_REPAIR_PORT.toString()
                    )
                )
            }
            Mode.SPEAKER -> {
                binding.etSourcePort.setText(
                    prefs.getString(
                        PREF_SPEAKER_SOURCE_PORT,
                        RocAudioReceiver.DEFAULT_SOURCE_PORT.toString()
                    )
                )
                binding.etRepairPort.setText(
                    prefs.getString(
                        PREF_SPEAKER_REPAIR_PORT,
                        RocAudioReceiver.DEFAULT_REPAIR_PORT.toString()
                    )
                )
            }
        }

        binding.etIp.error = null
        binding.etSourcePort.error = null
        binding.etRepairPort.error = null
    }

    private fun applySettingsExpanded() {
        binding.settingsContainer.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        binding.ivSettings.contentDescription = getString(
            if (settingsExpanded) R.string.settings_expanded else R.string.settings_collapsed
        )
    }

    private fun updateDestSummary() {
        if (currentMode == Mode.SPEAKER) {
            val localIp = localWifiIpv4()
            binding.tvDestSummary.text = localIp ?: getString(R.string.speaker_no_wifi)

            val source = binding.etSourcePort.text.toString().trim()
            val repair = binding.etRepairPort.text.toString().trim()
            binding.tvPortSummary.text = getString(R.string.dest_ports_format, source, repair)
            return
        }

        val ip = binding.etIp.text.toString().trim()
        val source = binding.etSourcePort.text.toString().trim()
        val repair = binding.etRepairPort.text.toString().trim()
        binding.tvDestSummary.text = if (ip.isEmpty()) {
            getString(R.string.dest_summary_none)
        } else {
            ip
        }
        binding.tvPortSummary.text = if (ip.isEmpty()) {
            ""
        } else {
            getString(R.string.dest_ports_format, source, repair)
        }
    }

    private fun localWifiIpv4(): String? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
            ?.substringBefore('%')
    }

    private fun parsePort(field: EditText): Int? {
        val port = field.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            field.error = getString(R.string.error_invalid_port)
            return null
        }
        return port
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            val requestedMode = pendingPermissionMode
            pendingPermissionMode = null
            pendingAction = false

            if (requestedMode == null || requestedMode != currentMode) {
                updateModeControlEnabled()
                return
            }

            if (requestedMode == Mode.MIC) {
                val audioGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (audioGranted) {
                    startSelectedService()
                } else {
                    showError(getString(R.string.status_mic_permission_required))
                    updateModeControlEnabled()
                }
            } else {
                startSelectedService()
            }
        }
    }

    companion object {
        private const val PERM_REQUEST_CODE = 2001
        private const val PREFS_NAME = "roc_mic_prefs"
        private const val PREF_MODE = "mode"
        private const val PREF_DEST_IP = "dest_ip"
        private const val PREF_SOURCE_PORT = "source_port"
        private const val PREF_REPAIR_PORT = "repair_port"
        private const val PREF_SPEAKER_SOURCE_PORT = "speaker_source_port"
        private const val PREF_SPEAKER_REPAIR_PORT = "speaker_repair_port"
        private const val MODE_MIC = "mic"
        private const val MODE_SPEAKER = "speaker"
        private const val STATE_SETTINGS_EXPANDED = "settings_expanded"
        private const val DISABLED_ALPHA = 0.6f
    }
}
