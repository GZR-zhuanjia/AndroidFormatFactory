package com.zhuanformat.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Standard RIFF/WAVE, signed little-endian 16-bit PCM, without silent 32-bit size overflow. */
object WavHeader {
    const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36

    fun create(dataSize: Long, sampleRate: Int, channelCount: Int): ByteArray {
        require(dataSize in 0..MAX_DATA_BYTES) { "WAV 超过标准 RIFF 的 4GB 限制，请改用 M4A" }
        require(sampleRate in 1..768_000 && channelCount in 1..8) { "不支持的音频采样参数" }
        require(dataSize % (channelCount * 2) == 0L) { "音频采样数据不完整" }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((dataSize + 36).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1); putShort(channelCount.toShort())
            putInt(sampleRate); putInt(sampleRate * channelCount * 2)
            putShort((channelCount * 2).toShort()); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize.toInt())
        }.array()
    }
}
