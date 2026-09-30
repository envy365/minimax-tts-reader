package com.minimax.ttsreader.util

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 响度处理工具（v0.6.x，针对 MiniMax 后端 RMS 不归一化的已知问题）。
 *
 * 提供两种模式，可由用户在 App 设置页切换对比：
 *
 * - **[MODE_RMS] RMS 归一化**：每段 wav 整体 gain 到目标 dBFS（-18 dBFS，speech 行业基准）。
 *   所有段响度完全一致，但**自然抑扬顿挫会被压平**（类似过度压缩的播客）。
 *
 * - **[MODE_DRC] 动态范围压缩**：参数由 [ConfigManager.DrcConfig] 提供，v0.7.4 起用户可在「响度归一化」下拉框
 *   下展开高级面板调整（默认参数见 [DEFAULT_DRC_CONFIG]，针对 MiniMax Speech-2.8-HD 调优）。
 *   保留自然抑扬曲线，只把过响/过轻的极值拉近（专业音频处理器做法）。
 *
 * 输入输出均为完整 wav 字节（含 44-byte header + PCM data chunk）。
 * 失败场景（header 异常 / PCM 空）→ 原样返回，不影响主流程。
 *
 * 实现注意：
 * - 假设 MiniMax 返回 32kHz / 16-bit / mono PCM（默认配置）
 * - 解析 WAV 时扫描所有 chunk 找 "data"（MiniMax 返回的 wav 头部可能有 LIST/INFO 等额外 chunk）
 * - 数据量 O(N)，32kHz 单声道几秒 wav 微秒级完成
 */
object AudioNormalizer {

    private const val TAG = "AudioNormalizer"

    /** 关闭归一化（默认） */
    const val MODE_OFF = "off"

    /** RMS 归一化（粗暴拉齐响度） */
    const val MODE_RMS = "rms"

    /** 动态范围压缩（保留抑扬但拉近极值） */
    const val MODE_DRC = "drc"

    /** 所有合法模式（前端下拉框用） */
    val ALL_MODES = listOf(MODE_OFF, MODE_RMS, MODE_DRC)

    /** speech loudness 行业基准（speech mono target ~-19 LUFS，audiobook ~-18 dBFS） */
    private const val TARGET_DBFS = -18.0

    /** 防止放大底噪：最大正向 gain（放宽到 ±9 适应 MiniMax 2.8 HD 偏中性输出） */
    private const val MAX_GAIN_DB = 9.0

    /** 防止过度衰减：最大负向 gain */
    private const val MIN_GAIN_DB = -9.0

    /** DRC 默认参数（v0.7.4，针对 MiniMax Speech-2.8-HD 调优） */
    val DEFAULT_DRC_CONFIG = com.minimax.ttsreader.util.ConfigManager.DrcConfig()

    /**
     * 入口：按 mode 处理 wav 字节。
     * 未知模式或 OFF → 原样返回。
     *
     * [drcConfig] 仅在 MODE_DRC 下生效；其他模式忽略。
     */
    fun process(wav: ByteArray, mode: String, drcConfig: ConfigManager.DrcConfig = ConfigManager.DrcConfig()): ByteArray {
        if (mode == MODE_OFF || wav.size < 44) return wav
        return try {
            when (mode) {
                MODE_RMS -> normalizeRMS(wav)
                MODE_DRC -> applyDRC(wav, drcConfig)
                else -> wav
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$mode] processing failed, return original", e)
            wav
        }
    }

    /**
     * RMS 归一化：计算当前 RMS → gain 到 target → 限幅 ±6dB → 应用。
     *
     * 限幅的目的：避免放大底噪（如果某段 wav 整体很轻，可能是静音段，放大后只剩噪声）。
     */
    private fun normalizeRMS(wav: ByteArray): ByteArray {
        val pcm = extractPCM(wav)
        val params = parseWavHeader(wav) ?: return wav
        if (pcm.isEmpty()) return wav

        val sampleCount = pcm.size / 2
        val currentRms = computeRMS(pcm, sampleCount)
        if (currentRms <= 0) return wav

        val peakValue = (1 shl (params.bitsPerSample - 1)).toDouble()  // 32768 for 16-bit
        val currentDbfs = 20.0 * log10(currentRms / peakValue)
        val gainDb = (TARGET_DBFS - currentDbfs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val gainLinear = 10.0.pow(gainDb / 20.0)

        Log.d(TAG, "[rms] current=${"%.1f".format(currentDbfs)}dBFS → gain=${"%.1f".format(gainDb)}dB → target=${"%.1f".format(TARGET_DBFS)}dBFS")

        return applyGain(wav, pcm, params, gainLinear)
    }

    /**
     * DRC 动态范围压缩：envelope follower + soft-knee compression + makeup gain。
     *
     * 参数（threshold / ratio / makeup / attack / release）由 [ConfigManager.DrcConfig] 提供，v0.7.4 起可由用户在
     * App 设置页高级面板调整。
     */
    private fun applyDRC(wav: ByteArray, config: ConfigManager.DrcConfig): ByteArray {
        val pcm = extractPCM(wav)
        val params = parseWavHeader(wav) ?: return wav
        if (pcm.isEmpty()) return wav

        val sampleRate = params.sampleRate.toDouble()
        val attackCoef = exp(-1.0 / (config.attackMs / 1000.0 * sampleRate))
        val releaseCoef = exp(-1.0 / (config.releaseMs / 1000.0 * sampleRate))
        val peakValue = (1 shl (params.bitsPerSample - 1)).toDouble()
        val threshold = peakValue * 10.0.pow(config.thresholdDb / 20.0)  // threshold dBFS 对应的线性值
        val thresholdDb = config.thresholdDb
        val makeupLinear = 10.0.pow(config.makeupGainDb / 20.0)  // makeup gain 一次性线性系数

        val sampleCount = pcm.size / 2
        val output = ShortArray(sampleCount)
        var envelope = 0.0
        var compressedCount = 0

        for (i in 0 until sampleCount) {
            // 读取 16-bit signed little-endian
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = if (hi and 0x80 != 0) (hi shl 8) or lo or 0xFFFF0000.toInt() else (hi shl 8) or lo
            val sampleAbs = abs(sample).toDouble()

            // Envelope follower: 上沿用 attackCoef（短），下沿用 releaseCoef（长）
            val coef = if (sampleAbs > envelope) attackCoef else releaseCoef
            envelope = sampleAbs + (envelope - sampleAbs) * coef

            // 计算 gain reduction
            var gain = 1.0
            if (envelope > threshold) {
                val envDb = 20.0 * log10(envelope / peakValue)
                val outputDb = thresholdDb + (envDb - thresholdDb) / config.ratio
                val targetEnv = peakValue * 10.0.pow(outputDb / 20.0)
                gain = targetEnv / envelope
                compressedCount++
            }

            // 应用 gain（压缩 + makeup），clip 到 16-bit
            // makeupLinear 对未压缩样本（gain=1.0）也生效，等价于整体抬升；压缩样本同时获得 makeup + 压缩
            val totalGain = gain * makeupLinear
            val newSample = (sample * totalGain).toInt().coerceIn(-32768, 32767)
            output[i] = newSample.toShort()
        }

        val compressRatio = compressedCount.toDouble() / sampleCount
        Log.d(TAG, "[drc] samples=$sampleCount compressed=${"%.1f".format(compressRatio * 100)}% threshold=${"%.1f".format(thresholdDb)}dBFS ratio=${config.ratio}:1 makeup=${"%.1f".format(config.makeupGainDb)}dB attack=${config.attackMs.toInt()}ms release=${config.releaseMs.toInt()}ms")

        return writePCM(wav, params, output)
    }

    /**
     * 应用固定 gain 到 PCM samples，重写 wav 的 data chunk。
     */
    private fun applyGain(wav: ByteArray, pcm: ByteArray, params: WavParams, gain: Double): ByteArray {
        val sampleCount = pcm.size / 2
        val output = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = if (hi and 0x80 != 0) (hi shl 8) or lo or 0xFFFF0000.toInt() else (hi shl 8) or lo
            val newSample = (sample * gain).toInt().coerceIn(-32768, 32767)
            output[i] = newSample.toShort()
        }
        return writePCM(wav, params, output)
    }

    /**
     * 计算 16-bit signed PCM 的 RMS（linear，非 dB）。
     * 返回 0 表示全静音或空数据。
     */
    private fun computeRMS(pcm: ByteArray, sampleCount: Int): Double {
        if (sampleCount == 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = if (hi and 0x80 != 0) (hi shl 8) or lo or 0xFFFF0000.toInt() else (hi shl 8) or lo
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        return kotlin.math.sqrt(sumSquares / sampleCount)
    }

    /**
     * 解析 WAV header，返回关键参数。失败返回 null。
     * 扫描所有 chunk 找 "data"，容忍 LIST/INFO 等额外 chunk。
     */
    private data class WavParams(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,    // "data" 标签所在位置
        val dataSize: Int       // data chunk 声明的大小
    )

    private fun parseWavHeader(wav: ByteArray): WavParams? {
        if (wav.size < 44) return null
        if (String(wav, 0, 4) != "RIFF") return null
        if (String(wav, 8, 4) != "WAVE") return null

        // fmt chunk 必须在 12-44 之间（标准 PCM）
        if (String(wav, 12, 4) != "fmt ") return null
        val channels = ByteBuffer.wrap(wav, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val sampleRate = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val bitsPerSample = ByteBuffer.wrap(wav, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        // 扫描 chunks 找 "data"
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4)
            val chunkSize = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") {
                return WavParams(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    dataOffset = offset,
                    dataSize = chunkSize
                )
            }
            offset += 8 + chunkSize
        }
        return null
    }

    /**
     * 提取 PCM data chunk 的字节（不包含 "data" 标签和 size 字段）。
     */
    private fun extractPCM(wav: ByteArray): ByteArray {
        val params = parseWavHeader(wav) ?: return ByteArray(0)
        val dataStart = params.dataOffset + 8
        val dataEnd = Math.min(wav.size, dataStart + params.dataSize)
        if (dataStart >= dataEnd) return ByteArray(0)
        return wav.copyOfRange(dataStart, dataEnd)
    }

    /**
     * 把新的 PCM samples 写回 wav，更新 data chunk size。
     * RIFF 总大小也需要同步更新（wav.size = RIFF + 8 + data chunk size）。
     */
    private fun writePCM(wav: ByteArray, params: WavParams, samples: ShortArray): ByteArray {
        // 新 PCM 字节数
        val newDataSize = samples.size * 2
        // 新 wav 总大小 = data offset + 8 + newDataSize
        val newSize = params.dataOffset + 8 + newDataSize

        val output = ByteArray(newSize)
        // 拷贝 header（含 "data" 标签 + size 字段的位置）
        System.arraycopy(wav, 0, output, 0, params.dataOffset + 8)
        // 更新 RIFF 总大小（位于 offset 4）
        ByteBuffer.wrap(output, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newSize - 8)
        // 更新 data chunk size（位于 params.dataOffset + 4）
        ByteBuffer.wrap(output, params.dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newDataSize)
        // 写 PCM
        for (i in samples.indices) {
            val s = samples[i].toInt()
            output[params.dataOffset + 8 + i * 2] = (s and 0xFF).toByte()
            output[params.dataOffset + 8 + i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return output
    }
}