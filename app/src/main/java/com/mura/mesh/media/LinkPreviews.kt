package com.mura.mesh.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.mura.mesh.core.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Link previews, privacy-first: only the SENDER's phone fetches the page (when it has
 * internet and the setting is on). The title, description and a small image travel inside
 * the encrypted message, so the person receiving it never contacts the website.
 */
object LinkPreviews {
    private val URL_RE = Regex("""\bhttps?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        try {
            val (html, finalUrl) = get(url, 512 * 1024, "text/html") ?: return@withContext null
            val page = String(html, Charsets.UTF_8)
            val title = meta(page, "og:title") ?: meta(page, "twitter:title")
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(page)?.groupValues?.get(1)
            val desc = meta(page, "og:description") ?: meta(page, "description") ?: meta(page, "twitter:description") ?: ""
            val imgUrl = meta(page, "og:image") ?: meta(page, "twitter:image")
            if (title.isNullOrBlank() && imgUrl == null) return@withContext null
            val image = imgUrl?.let { resolve(finalUrl, it) }?.let { fetchImage(it) } ?: ByteArray(0)
            LinkPreview(url, clean(title ?: "").take(160), clean(desc).take(300), image)
        } catch (e: Exception) { null }
    }

    private fun meta(html: String, key: String): String? {
        val k = Regex.escape(key)
        val a = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$k["'][^>]*content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
        val b = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']$k["']""", RegexOption.IGNORE_CASE).find(html)
        return (a ?: b)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun resolve(base: String, ref: String): String? = try { URL(URL(base), ref.replace("&amp;", "&")).toString() } catch (e: Exception) { null }

    /** Fetched only through Tor (see TorNet): the site never sees your IP address, and nothing is fetched before Tor connects. */
    private fun get(url: String, max: Int, accept: String): Pair<ByteArray, String>? {
        var current = url
        repeat(5) {
            val u = URL(current)
            if (u.protocol != "https") return null
            val r = com.mura.mesh.mesh.TorNet.request("GET", current, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0",   // same as Tor Browser: blends in
                "Accept" to accept,
            ), isolation = "preview", timeoutMs = 30_000)
            r.use {
                if (r.code in 300..399) { current = URL(u, r.header("location") ?: return null).toString(); return@repeat }
                if (!r.ok) return null
                return readLimited(r.body, max) to current
            }
        }
        return null
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        input.use {
            while (out.size() < max) {
                val n = it.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun fetchImage(url: String): ByteArray? {
        val (bytes, _) = get(url, 3 * 1024 * 1024, "image/*") ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 480f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 60, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return out.toByteArray()
    }
}
