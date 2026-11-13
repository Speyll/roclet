package com.roclet.mic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SpeakerReceiverService : Service() {

    /** Lifecycle state of the receiving service, published to bound UI listeners. */
    enum class State { STOPPED, STARTING, LISTENING, PAUSED, STOPPING, ERROR }

    /** Callback delivered on the main thread with the current state and last error. */
    fun interface StateListener {
        fun onStateChanged(state: State, error: String?)
    }

    private val binder = LocalBinder()

    @Volatile
    private var activeRun: PlaybackRun? = null

    @Volatile
    private var state: State = State.STOPPED

    @Volatile
    private var lastError: String? = null

    private val stateListeners = CopyOnWriteArrayList<StateListener>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainExecutor.execute { handleAudioFocusChange(change) }
    }

    inner class LocalBinder : Binder() {
        val isRunning: Boolean
            get() = activeRun != null

        val state: State
            get() = this@SpeakerReceiverService.state

        val lastError: String?
            get() = this@SpeakerReceiverService.lastError

        fun addStateListener(listener: StateListener) {
            if (!stateListeners.contains(listener)) {
                stateListeners.add(listener)
            }
            listener.onStateChanged(this@SpeakerReceiverService.state, this@SpeakerReceiverService.lastError)
        }

        fun removeStateListener(listener: StateListener) {
            stateListeners.remove(listener)
        }
    }

    private fun setState(newState: State, error: String? = null) {
        state = newState
        lastError = error
        for (listener in stateListeners) {
            try {
                listener.onStateChanged(newState, error)
            } catch (t: Throwable) {
                Log.e(TAG, "State listener failed", t)
            }
        }
    }

    /**
     * Owns the resources of a single receiving run. The audio worker is the only
     * component allowed to release the track or close the ROC receiver, and it does
     * so in its own `finally` path after the playback loop has exited.
     */
    private class PlaybackRun(
        val receiver: RocAudioReceiver,
        val track: AudioTrack,
        val buffer: FloatArray
    ) {
        @Volatile
        var cancelled = false
            private set

        /** When set, received audio is drained but silence is written to the device. */
        @Volatile
        var paused = false

        /** Human-readable reason set by the worker when playback fails; null on a normal stop. */
        @Volatile
        var failureMessage: String? = null

        var thread: Thread? = null

        private val released = AtomicBoolean(false)

        /** Signals this run to stop and unblocks a blocking [AudioTrack.write]. */
        fun requestStop() {
            cancelled = true
            try {
                track.stop()
            } catch (_: Throwable) {
            }
        }

        /** Releases native resources. Safe to call more than once; only the first call acts. */
        fun releaseResources() {
            if (!released.compareAndSet(false, true)) return
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
            try {
                receiver.close()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        val sourcePort = intent?.getIntExtra(
            EXTRA_SOURCE_PORT,
            RocAudioReceiver.DEFAULT_SOURCE_PORT
        ) ?: RocAudioReceiver.DEFAULT_SOURCE_PORT
        val repairPort = intent?.getIntExtra(
            EXTRA_REPAIR_PORT,
            RocAudioReceiver.DEFAULT_REPAIR_PORT
        ) ?: RocAudioReceiver.DEFAULT_REPAIR_PORT

        when (action) {
            ACTION_START -> {
                if (activeRun == null && state != State.STARTING) {
                    startReceiving(sourcePort, repairPort)
                }
            }
            ACTION_STOP -> {
                stopReceiving()
            }
        }
        return START_NOT_STICKY
    }

    private fun startReceiving(sourcePort: Int, repairPort: Int) {
        if (activeRun != null || state == State.STARTING) return

        setState(State.STARTING)

        var receiver: RocAudioReceiver? = null
        var track: AudioTrack? = null
        var run: PlaybackRun? = null
        var startError: String? = null
        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            acquireLocks()

            if (!requestAudioFocus()) {
                startError = getString(R.string.error_audio_focus)
                throw IllegalStateException("audio focus unavailable")
            }

            val createdReceiver = RocAudioReceiver(
                sourcePort = sourcePort,
                repairPort = repairPort
            )
            receiver = createdReceiver
            createdReceiver.start()

            val createdTrack = createAudioTrack()
            track = createdTrack
            createdTrack.play()

            val startedRun = PlaybackRun(
                createdReceiver,
                createdTrack,
                FloatArray(RocAudioReceiver.FRAME_FLOATS)
            )
            run = startedRun
            startedRun.thread = Thread { runWorker(startedRun) }.apply {
                name = "SpeakerReceiverAudioThread"
                priority = Thread.MAX_PRIORITY
            }

            activeRun = startedRun
            startedRun.thread?.start()
            setState(State.LISTENING)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start receiving", t)
            val partiallyStartedRun = run
            if (partiallyStartedRun != null) {
                activeRun = null
                partiallyStartedRun.releaseResources()
            } else {
                try {
                    track?.stop()
                } catch (_: Throwable) {
                }
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
                try {
                    receiver?.close()
                } catch (_: Throwable) {
                }
            }
            releaseLocks()
            abandonAudioFocus()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            setState(
                State.ERROR,
                startError ?: getString(R.string.error_receive_start_failed)
            )
            stopSelf()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            RocAudioReceiver.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("invalid AudioTrack buffer size: $minBufferSize")
        }

        val frameBytes = RocAudioReceiver.FRAME_FLOATS * Float.SIZE_BYTES
        val bufferBytes = maxOf(minBufferSize, frameBytes * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RocAudioReceiver.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun runWorker(run: PlaybackRun) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val samples = run.buffer
        val silence = FloatArray(samples.size)
        try {
            while (!run.cancelled) {
                run.receiver.readFloats(samples)

                val output = if (run.paused) silence else samples
                var offset = 0
                while (offset < output.size && !run.cancelled) {
                    val written = run.track.write(
                        output,
                        offset,
                        output.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written < 0) {
                        if (!run.cancelled) {
                            Log.e(TAG, "AudioTrack.write() failed with error $written")
                            run.failureMessage = getString(R.string.error_playback_failed)
                        }
                        return
                    }
                    if (written == 0) {
                        Thread.yield()
                    } else {
                        offset += written
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Playback worker failed", t)
            if (!run.cancelled) {
                run.failureMessage = getString(R.string.error_receive_failed)
            }
        } finally {
            run.releaseResources()
            mainExecutor.execute { finishRun(run) }
        }
    }

    private fun finishRun(run: PlaybackRun) {
        if (activeRun !== run) return
        activeRun = null
        releaseLocks()
        abandonAudioFocus()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        val failure = run.failureMessage
        if (failure != null) {
            setState(State.ERROR, failure)
        } else {
            setState(State.STOPPED)
        }
        stopSelf()
    }

    private fun stopReceiving() {
        val run = activeRun
        if (run != null) {
            setState(State.STOPPING)
            run.requestStop()
        } else {
            releaseLocks()
            abandonAudioFocus()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            setState(State.STOPPED)
            stopSelf()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()

        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest
        audioFocusRequest = null
        if (hasAudioFocus && request != null) {
            try {
                audioManager.abandonAudioFocusRequest(request)
            } catch (_: Throwable) {
            }
        }
        hasAudioFocus = false
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                setTrackVolume(1f)
                val run = activeRun
                if (run != null && state == State.PAUSED) {
                    run.paused = false
                    setState(State.LISTENING)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                stopReceiving()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val run = activeRun
                if (run != null && state == State.LISTENING) {
                    run.paused = true
                    setState(State.PAUSED)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                setTrackVolume(DUCK_VOLUME)
            }
        }
    }

    private fun setTrackVolume(volume: Float) {
        try {
            activeRun?.track?.setVolume(volume)
        } catch (_: Throwable) {
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RocletSpeaker::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "RocletSpeaker::WifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_speaker_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_speaker_text)
            enableLights(false)
            enableVibration(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SpeakerReceiverService::class.java).apply {
            putExtra(EXTRA_ACTION, ACTION_STOP)
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_speaker_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop),
                stopPending
            )
            .build()
    }

    override fun onDestroy() {
        stopReceiving()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTION = "EXTRA_ACTION"
        const val EXTRA_SOURCE_PORT = "EXTRA_SOURCE_PORT"
        const val EXTRA_REPAIR_PORT = "EXTRA_REPAIR_PORT"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "roc_speaker_channel"
        private const val TAG = "SpeakerReceiverService"
        private const val DUCK_VOLUME = 0.2f
    }
}
