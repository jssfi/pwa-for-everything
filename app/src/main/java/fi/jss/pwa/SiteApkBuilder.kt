package fi.jss.pwa

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Builds a standalone app for one site from the template APK in assets (the :site module).
 * The template is copied with its package name, label, icon and site.json replaced, then signed
 * with a key kept in this device's Android Keystore.
 */
object SiteApkBuilder {
    private const val TEMPLATE_ASSET = "site-template.apk"
    private const val PACKAGE_PLACEHOLDER = "fi.jss.pwa.app.t0000000000000000000000000000000"
    private val LABEL_PLACEHOLDER = "_".repeat(64)
    private const val ICON_ENTRY = "res/drawable-nodpi-v4/site_icon.png"
    private const val CONFIG_ENTRY = "assets/site.json"
    private const val KEY_ALIAS = "site-apk-signing"

    /** Same length as the placeholder, so it can be patched in place. Stable per site, so reinstalls update in place. */
    fun packageName(site: Site): String =
        "fi.jss.pwa.app.s" + site.id.replace("-", "").lowercase().take(PACKAGE_PLACEHOLDER.length - 16)

    /** Builds the signed APK into the cache directory. Call off the main thread. */
    fun build(context: Context, site: Site): File {
        val dir = File(context.cacheDir, "site-apks").apply { deleteRecursively(); mkdirs() }
        val template = File(dir, "template.apk")
        context.assets.open(TEMPLATE_ASSET).use { input -> template.outputStream().use { input.copyTo(it) } }

        val config = site.toJson().put("stamp", System.currentTimeMillis()).toString().toByteArray()
        val icon = ByteArrayOutputStream().also {
            SiteIcons.adaptiveBitmap(context, site).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val label = fitLabel(site.name)

        val unsigned = File(dir, "unsigned.apk")
        val replaced = mutableSetOf<String>()
        ZipFile(template).use { zip ->
            ZipOutputStream(unsigned.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (name.startsWith("META-INF/") && isSignatureFile(name)) continue
                    val data = when (name) {
                        "AndroidManifest.xml" -> BinaryXmlStrings.replace(
                            zip.getInputStream(entry).use { it.readBytes() },
                            mapOf(PACKAGE_PLACEHOLDER to packageName(site), LABEL_PLACEHOLDER to label),
                        )
                        ICON_ENTRY -> icon
                        CONFIG_ENTRY -> config
                        else -> zip.getInputStream(entry).use { it.readBytes() }
                    }
                    if (name == "AndroidManifest.xml" || name == ICON_ENTRY || name == CONFIG_ENTRY) replaced += name
                    val copy = ZipEntry(name)
                    if (entry.method == ZipEntry.STORED) {
                        // resources.arsc must stay uncompressed; the signer aligns stored entries.
                        copy.method = ZipEntry.STORED
                        copy.size = data.size.toLong()
                        copy.compressedSize = data.size.toLong()
                        copy.crc = CRC32().apply { update(data) }.value
                    }
                    out.putNextEntry(copy)
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
        check(replaced.size == 3) { "template is missing ${setOf("AndroidManifest.xml", ICON_ENTRY, CONFIG_ENTRY) - replaced}" }

        val signed = File(dir, "${packageName(site)}.apk")
        val (key, cert) = signingKey()
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("pwa", key, listOf(cert)).build()))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(29)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        template.delete()
        unsigned.delete()
        return signed
    }

    /** The label must fit in the placeholder's space in the manifest string pool. */
    private fun fitLabel(name: String): String {
        var label = name.ifBlank { "App" }
        while (label.length > LABEL_PLACEHOLDER.length || label.toByteArray().size > LABEL_PLACEHOLDER.length) {
            label = label.dropLast(1)
        }
        return label
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" || upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    /**
     * One key per device, created on first use. Generated apps can only be updated with the same key,
     * so it lives in the Android Keystore for as long as this app stays installed.
     */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=PWA for Everything"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date(0))
                        .setCertificateNotAfter(Date(4102444800000)) // 2100-01-01
                        .build()
                )
            }.generateKeyPair()
        }
        return (ks.getKey(KEY_ALIAS, null) as PrivateKey) to (ks.getCertificate(KEY_ALIAS) as X509Certificate)
    }
}

/**
 * Replaces strings in the string pool of a compiled (binary) Android XML file, in place.
 * Each replacement must fit in the space of the string it replaces, so the file layout and every
 * offset stay the same; leftover bytes after the new terminator are never read.
 */
object BinaryXmlStrings {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 1 shl 8

    fun replace(xml: ByteArray, replacements: Map<String, String>): ByteArray {
        val out = xml.copyOf()
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val pool = b.getShort(2).toInt() and 0xffff // the pool is the first chunk after the file header
        check((b.getShort(pool).toInt() and 0xffff) == RES_STRING_POOL_TYPE) { "no string pool" }
        val headerSize = b.getShort(pool + 2).toInt() and 0xffff
        val count = b.getInt(pool + 8)
        val utf8 = (b.getInt(pool + 16) and UTF8_FLAG) != 0
        val stringsStart = pool + b.getInt(pool + 20)

        val done = mutableSetOf<String>()
        for (i in 0 until count) {
            val at = stringsStart + b.getInt(pool + headerSize + i * 4)
            val old = if (utf8) readUtf8(b, at) else readUtf16(b, at)
            val new = replacements[old] ?: continue
            if (utf8) writeUtf8(b, at, old, new) else writeUtf16(b, at, old, new)
            done += old
        }
        check(done == replacements.keys) { "placeholders not found: ${replacements.keys - done}" }
        return out
    }

    // Lengths below 0x80 (UTF-8) / 0x8000 (UTF-16) take one length unit; the placeholders are short enough.

    private fun readUtf16(b: ByteBuffer, at: Int): String {
        val len = b.getShort(at).toInt() and 0xffff
        if (len and 0x8000 != 0) return "" // long string, never one of ours
        return String(CharArray(len) { b.getChar(at + 2 + it * 2) })
    }

    private fun writeUtf16(b: ByteBuffer, at: Int, old: String, new: String) {
        require(new.length <= old.length) { "\"$new\" is longer than its placeholder" }
        b.putShort(at, new.length.toShort())
        new.forEachIndexed { i, c -> b.putChar(at + 2 + i * 2, c) }
        b.putShort(at + 2 + new.length * 2, 0)
    }

    private fun readUtf8(b: ByteBuffer, at: Int): String {
        val chars = b.get(at).toInt() and 0xff
        val bytes = b.get(at + 1).toInt() and 0xff
        if (chars and 0x80 != 0 || bytes and 0x80 != 0) return ""
        return String(ByteArray(bytes) { b.get(at + 2 + it) }, Charsets.UTF_8)
    }

    private fun writeUtf8(b: ByteBuffer, at: Int, old: String, new: String) {
        val oldBytes = old.toByteArray(Charsets.UTF_8).size
        val newBytes = new.toByteArray(Charsets.UTF_8)
        require(newBytes.size <= oldBytes && new.length < 0x80) { "\"$new\" is longer than its placeholder" }
        b.put(at, new.length.toByte())
        b.put(at + 1, newBytes.size.toByte())
        newBytes.forEachIndexed { i, v -> b.put(at + 2 + i, v) }
        b.put(at + 2 + newBytes.size, 0)
    }
}
