package app.box.suggest

import android.content.Context
import android.net.Uri

data class Sniff(
    val kind: Kind,
    val mime: String,
    val ext: String,
)

fun sniff(bytes: ByteArray): Sniff? {
    if (bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a")) {
        return Sniff(Kind.Gif, "image/gif", "gif")
    }
    if (bytes.startsWith(PNG)) {
        return Sniff(Kind.Image, "image/png", "png")
    }
    if (bytes.startsWith(JPEG)) {
        return Sniff(Kind.Image, "image/jpeg", "jpg")
    }
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals(RIFF) &&
        bytes.copyOfRange(8, 12).contentEquals(WEBP)
    ) {
        return Sniff(Kind.Image, "image/webp", "webp")
    }
    return null
}

sealed class ReadResult {
    class Ok(val kind: Kind, val mime: String, val ext: String, val bytes: ByteArray) : ReadResult()
    data object Unreadable : ReadResult()
    data object TooBig : ReadResult()
    data object Unsupported : ReadResult()
}

object MediaReader {
    fun read(context: Context, uri: Uri): ReadResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return ReadResult.Unreadable
        if (bytes.size > Limits.MAX_MEDIA_BYTES) return ReadResult.TooBig
        val sniffed = sniff(bytes) ?: return ReadResult.Unsupported
        return ReadResult.Ok(sniffed.kind, sniffed.mime, sniffed.ext, bytes)
    }
}

private val PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)
private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

private fun ByteArray.startsWithAscii(prefix: String): Boolean =
    startsWith(prefix.toByteArray(Charsets.US_ASCII))
