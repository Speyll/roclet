package com.roclet.mic

import org.rocstreaming.roctoolkit.ChannelSet
import org.rocstreaming.roctoolkit.ClockSource
import org.rocstreaming.roctoolkit.Endpoint
import org.rocstreaming.roctoolkit.FrameEncoding
import org.rocstreaming.roctoolkit.Interface
import org.rocstreaming.roctoolkit.Protocol
import org.rocstreaming.roctoolkit.ResamplerProfile
import org.rocstreaming.roctoolkit.RocContext
import org.rocstreaming.roctoolkit.RocReceiver
import org.rocstreaming.roctoolkit.RocReceiverConfig
import org.rocstreaming.roctoolkit.Slot

class RocAudioReceiver(
    private val sourcePort: Int = DEFAULT_SOURCE_PORT,
    private val repairPort: Int = DEFAULT_REPAIR_PORT,
    private val sampleRate: Int = SAMPLE_RATE,
    private val targetLatencyNanos: Long = DEFAULT_TARGET_LATENCY_NANOS
) : AutoCloseable {

    private var context: RocContext? = null
    private var receiver: RocReceiver? = null

    fun start() {
        check(receiver == null) { "ROC receiver has already been started" }

        val ctx = RocContext()
        var openedReceiver: RocReceiver? = null
        try {
            val config = RocReceiverConfig.builder()
                .frameSampleRate(sampleRate)
                .frameChannels(ChannelSet.STEREO)
                .frameEncoding(FrameEncoding.PCM_FLOAT)
                .clockSource(ClockSource.EXTERNAL)
                .resamplerProfile(ResamplerProfile.MEDIUM)
                .targetLatency(targetLatencyNanos)
                .build()

            val rcv = RocReceiver(ctx, config)
            openedReceiver = rcv

            val sourceEndpoint = Endpoint.builder()
                .protocol(Protocol.RTP_RS8M_SOURCE)
                .host(BIND_HOST)
                .port(sourcePort)
                .build()

            val repairEndpoint = Endpoint.builder()
                .protocol(Protocol.RS8M_REPAIR)
                .host(BIND_HOST)
                .port(repairPort)
                .build()

            rcv.bind(Slot.DEFAULT, Interface.AUDIO_SOURCE, sourceEndpoint)
            rcv.bind(Slot.DEFAULT, Interface.AUDIO_REPAIR, repairEndpoint)

            context = ctx
            receiver = rcv
        } catch (t: Throwable) {
            try {
                openedReceiver?.close()
            } catch (_: Throwable) {
            }
            try {
                ctx.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    fun readFloats(samples: FloatArray) {
        receiver?.read(samples)
    }

    override fun close() {
        try {
            receiver?.close()
        } catch (_: Throwable) {
        }
        receiver = null

        try {
            context?.close()
        } catch (_: Throwable) {
        }
        context = null
    }

    companion object {
        const val DEFAULT_SOURCE_PORT = 10001
        const val DEFAULT_REPAIR_PORT = 10002
        const val SAMPLE_RATE = 48000

        /** 10 ms at [SAMPLE_RATE], in interleaved stereo floats. */
        const val FRAME_FLOATS = 480 * 2

        /** Playout target, including network jitter and FEC block buffering. */
        const val DEFAULT_TARGET_LATENCY_NANOS = 120_000_000L

        private const val BIND_HOST = "0.0.0.0"
    }
}
