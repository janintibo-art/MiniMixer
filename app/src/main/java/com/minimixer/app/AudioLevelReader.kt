package com.minimixer.app

import android.media.audiofx.Visualizer
import kotlin.math.sqrt

/**
 * Lit le niveau réel de la sortie audio globale (RMS) via l'API Visualizer.
 * Le signal est analysé en direct, jamais enregistré ni stocké.
 */
class AudioLevelReader {

    @Volatile
    var level = 0f
        private set

    private var vis: Visualizer? = null

    fun start(): Boolean {
        stop()
        return try {
            vis = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform == null || waveform.isEmpty()) return
                        var sum = 0.0
                        for (b in waveform) {
                            val d = (b.toInt() and 0xFF) - 128
                            sum += (d * d).toDouble()
                        }
                        val rms = sqrt(sum / waveform.size) / 128.0
                        level = (rms * 2.4).toFloat().coerceIn(0f, 1f)
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        runCatching {
            vis?.enabled = false
            vis?.release()
        }
        vis = null
        level = 0f
    }
}
