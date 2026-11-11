package com.gitco.smartcast.qa

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PushToTalkController(
    private val hostIp: String,
    private val pttPort: Int,
    private val encodeOpus: (ByteArray) -> ByteArray? // Uses your existing Opus C++ wrapper
) {
    private var isTransmitActive = false
    private var pttJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun startTransmitting() {
        if (isTransmitActive) return
        isTransmitActive = true

        pttJob = scope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
            )

            val socket = DatagramSocket()
            val address = InetAddress.getByName(hostIp)
            val buffer = ByteArray(minBufferSize)

            recorder.startRecording()
            try {
                while (isTransmitActive && isActive) {
                    val readBytes = recorder.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        // Compress PCM audio chunk using your Opus encoder
                        val pcmChunk = buffer.copyOfRange(0, readBytes)
                        val opusPayload = encodeOpus(pcmChunk)
                        
                        if (opusPayload != null && opusPayload.isNotEmpty()) {
                            val packet = DatagramPacket(opusPayload, opusPayload.size, address, pttPort)
                            socket.send(packet)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                recorder.stop()
                recorder.release()
                socket.close()
            }
        }
    }

    fun stopTransmitting() {
        isTransmitActive = false
        pttJob?.cancel()
        pttJob = null
    }
}
