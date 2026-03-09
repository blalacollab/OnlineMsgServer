package com.onlinemsg.client.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File

data class RecordedAudio(
    val base64: String,
    val durationMillis: Long
)

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMillis: Long = 0L

    fun start(): Boolean {
        if (mediaRecorder != null) return false
        val file = runCatching {
            File.createTempFile("oms_record_", ".m4a", context.cacheDir)
        }.getOrNull() ?: return false

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        val started = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(24_000)
            recorder.setAudioSamplingRate(16_000)
            recorder.setMaxDuration(60_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            true
        }.getOrElse {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            file.delete()
            false
        }

        if (!started) return false

        mediaRecorder = recorder
        outputFile = file
        startedAtMillis = System.currentTimeMillis()
        return true
    }

    fun stopAndEncode(send: Boolean): RecordedAudio? {
        val recorder = mediaRecorder ?: return null
        mediaRecorder = null
        val file = outputFile
        outputFile = null

        runCatching { recorder.stop() }
        runCatching { recorder.reset() }
        runCatching { recorder.release() }

        if (!send || file == null) {
            file?.delete()
            return null
        }

        val duration = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()

        if (bytes == null || bytes.isEmpty()) return null
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return RecordedAudio(base64 = base64, durationMillis = duration)
    }

    fun cancel() {
        stopAndEncode(send = false)
    }

    fun release() {
        cancel()
    }
}
