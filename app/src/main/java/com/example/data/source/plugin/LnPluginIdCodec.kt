package com.example.data.source.plugin

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object LnPluginIdCodec {
    private val BASE64_URL_SAFE_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun isPathLengthSafe(pluginId: String, path: String): Boolean {
        val prefixLen = 3 + pluginId.length + 1 // "ln_" + pluginId + "_"
        val base64Len = Math.ceil(4.0 * path.toByteArray(Charsets.UTF_8).size / 3.0).toInt()
        return prefixLen + base64Len + 5 <= 255
    }

    fun encodeNovelId(pluginId: String, path: String): String {
        val encoded = Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "ln_${pluginId}_$encoded"
    }

    fun decodeNovelPath(pluginId: String, novelId: String): String {
        val prefix = "ln_${pluginId}_"
        if (!novelId.startsWith(prefix)) {
            throw IllegalArgumentException("Plugin [$pluginId] cannot decode novelId not starting with '$prefix': $novelId")
        }
        val encoded = novelId.substring(prefix.length)
        if (encoded.isEmpty() || !BASE64_URL_SAFE_REGEX.matches(encoded)) {
            throw IllegalArgumentException("Plugin [$pluginId] malformed novelId base64 (invalid chars or empty): $novelId")
        }
        val bytes = try {
            Base64.decode(encoded, Base64.URL_SAFE)
        } catch (e: Exception) {
            throw IllegalArgumentException("Plugin [$pluginId] malformed novelId base64: $novelId", e)
        }
        if (bytes == null || bytes.isEmpty()) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded empty novel path from: $novelId")
        }
        val reencoded = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if (reencoded != encoded) {
            throw IllegalArgumentException("Plugin [$pluginId] novelId failed base64 round-trip verification: $novelId")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val path = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded bytes are not valid UTF-8: $novelId", e)
        }
        if (path.isEmpty()) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded empty novel path from: $novelId")
        }
        return path
    }
}
