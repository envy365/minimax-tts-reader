package com.minimax.ttsreader.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioUtils {

    /**
     * MiniMax 接口返回的 audio 字段为 hex 编码，需解码为字节
     */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "").replace("\r", "")
        val len = clean.length
        if (len % 2 != 0) return ByteArray(0)
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high < 0 || low < 0) return ByteArray(0)
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    fun pcm16ToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        buffer.putInt(totalSize)
        buffer.put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        buffer.put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        buffer.putInt(dataSize)
        buffer.put(pcmData)

        return buffer.array()
    }

    /**
     * 校验音频：若已是完整 wav 直接返回；若是裸 pcm 则补 wav 头
     */
    fun validateAndFixWav(audioData: ByteArray, sampleRate: Int = 32000, channels: Int = 1): ByteArray {
        if (audioData.size < 44) return audioData
        val riff = String(audioData, 0, 4)
        if (riff != "RIFF") {
            return pcm16ToWav(audioData, sampleRate, channels, 16)
        }
        return audioData
    }
}
