package com.minimax.ttsreader.util

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV 拼接器（v0.8.2 — Stage 14）
 *
 * 把多个 WAV 字节流合并成一个连续 WAV 字节流，用于双引擎路由后：
 * - NARRATION 子段走 Android System TTS → wav
 * - DIALOGUE 子段走 MiniMax TTS → wav
 * 拼接回一个 wav 给 Legado（Legado 只接受单个 wav 响应）
 *
 * 实现策略：
 * - 取第一个 wav 的 RIFF/fmt 头作为最终 header（要求所有 wav 采样率/声道/位深一致）
 * - 跳过所有 wav 的 RIFF/fmt/data 等 chunk，只保留第一个 wav 的 header
 * - 把所有 wav 的 PCM data chunk 内容拼起来
 * - 修正 RIFF 总长 + data chunk 长
 *
 * 兼容性约束：
 * - 所有 wav 必须是 PCM（MiniMax / Android System TTS 都返回 PCM WAV）
 * - 所有 wav 采样率 / 声道 / 位深必须一致（不一致 → throw 让上层降级）
 * - 头部可能有 LIST 等额外 chunk（合成引擎/编辑器加的元信息），我们跳过所有非 RIFF/fmt/data
 *
 * 为什么不用 libsndfile/JavaSound：Android 上 JavaSound 不全，PCM-only 自写 70 行最稳。
 */
object WavConcatenator {

    private const val TAG = "WavConcatenator"

    /**
     * 拼接多个 PCM WAV 字节流为单个 WAV 字节流。
     *
     * @param wavs List<ByteArray>，每个元素是一个标准 WAV 文件的字节
     * @return 合并后的 WAV 字节
     * @throws IllegalArgumentException 如果 wavs 为空 / 第一个不是 RIFF / 参数不一致
     */
    fun concat(wavs: List<ByteArray>): ByteArray {
        require(wavs.isNotEmpty()) { "wavs 不能为空" }

        if (wavs.size == 1) return wavs[0]

        // 解析第一个 wav 的 header（fmt + 找到 data chunk 偏移）
        val first = wavs[0]
        require(String(first, 0, 4) == "RIFF") { "第一个 wav 不是 RIFF 格式" }

        val firstFmt = parseFmt(first)
            ?: throw IllegalArgumentException("第一个 wav 缺少 fmt chunk")
        val firstData = parseData(first)
            ?: throw IllegalArgumentException("第一个 wav 缺少 data chunk")

        val totalDataSize = wavs.sumOf { wav ->
            val data = parseData(wav)
                ?: throw IllegalArgumentException("子 wav 缺少 data chunk: ${wav.size} bytes")
            validateAndMatch(wav, firstFmt)  // 校验参数一致
            data.size
        }

        // 构造新 wav：第一个 wav 的 header（裁剪到 data chunk 起点 + 8）+ 拼接所有 data
        val headerEnd = firstData.offset + 8  // header 含 RIFF/fmt/LIST 等直到 data 起始
        val header = first.copyOfRange(0, headerEnd)

        // 写入最终的 RIFF 总长 + data chunk 总长
        // RIFF 总长 = 4(WAVE) + fmt + 8(data chunk header) + data
        //          = (新 wav 总长) - 8
        val newRiffTotal = header.size + totalDataSize - 8
        val newDataSize = totalDataSize

        // 修改 header 中的 RIFF 总长（offset 4, 4 bytes）
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newRiffTotal)
        // 修改 header 中的 data chunk 长（offset = firstData.offset + 4, 4 bytes）
        ByteBuffer.wrap(header, firstData.offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newDataSize)

        // 拼 header + 所有 data 内容
        val out = ByteArray(header.size + totalDataSize)
        System.arraycopy(header, 0, out, 0, header.size)
        var cursor = header.size
        for (wav in wavs) {
            val data = parseData(wav)!!
            val pcm = data.pcm
            System.arraycopy(pcm, 0, out, cursor, pcm.size)
            cursor += pcm.size
        }

        Log.d(TAG, "concat: ${wavs.size} wavs → ${out.size} bytes (data=$totalDataSize, fmt=${firstFmt.sampleRate}Hz/${firstFmt.channels}ch/${firstFmt.bitsPerSample}bit)")
        return out
    }

    // ===== WAV 解析辅助 =====

    /**
     * 解析 fmt chunk 信息
     * @return FmtInfo 或 null（找不到 fmt chunk）
     */
    private fun parseFmt(wav: ByteArray): FmtInfo? {
        var offset = 12  // 跳过 "RIFF<size>WAVE"
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4)
            val chunkSize = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "fmt ") {
                val audioFormat = ByteBuffer.wrap(wav, offset + 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val channels = ByteBuffer.wrap(wav, offset + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val sampleRate = ByteBuffer.wrap(wav, offset + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val bitsPerSample = ByteBuffer.wrap(wav, offset + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                return FmtInfo(audioFormat, channels, sampleRate, bitsPerSample)
            }
            offset += 8 + chunkSize
        }
        return null
    }

    /**
     * 解析 data chunk 位置和 PCM 数据
     * @return DataInfo(offset, pcm) 或 null
     */
    private fun parseData(wav: ByteArray): DataInfo? {
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4)
            val chunkSize = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") {
                val pcm = wav.copyOfRange(offset + 8, offset + 8 + chunkSize)
                return DataInfo(offset, pcm)
            }
            offset += 8 + chunkSize
        }
        return null
    }

    /**
     * 校验 wav 参数与 firstFmt 一致，否则抛异常
     */
    private fun validateAndMatch(wav: ByteArray, firstFmt: FmtInfo) {
        if (String(wav, 0, 4) != "RIFF") {
            throw IllegalArgumentException("子 wav 不是 RIFF 格式 (size=${wav.size})")
        }
        val fmt = parseFmt(wav)
            ?: throw IllegalArgumentException("子 wav 缺少 fmt chunk")
        if (fmt.audioFormat != firstFmt.audioFormat
            || fmt.channels != firstFmt.channels
            || fmt.sampleRate != firstFmt.sampleRate
            || fmt.bitsPerSample != firstFmt.bitsPerSample
        ) {
            throw IllegalArgumentException(
                "子 wav 参数与首个不一致：fmt=${fmt.audioFormat}/${fmt.channels}ch/${fmt.sampleRate}Hz/${fmt.bitsPerSample}bit vs first=${firstFmt}"
            )
        }
    }

    private data class FmtInfo(
        val audioFormat: Int,    // 1 = PCM
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int
    )

    private data class DataInfo(
        val offset: Int,         // data chunk 起始（不含 chunk header）
        val pcm: ByteArray
    ) {
        val size: Int get() = pcm.size
    }
}
