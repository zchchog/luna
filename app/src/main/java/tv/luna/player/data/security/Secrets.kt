package tv.luna.player.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Playlist URL, username and password at rest: AES-256-GCM under a key that lives in the Android Keystore (it never
 * leaves the keystore, so copying the database off the box gives only ciphertext). Not hashed: the app must send the
 * real login to the provider. Values without the [PREFIX] are old plain text and read as-is until re-saved.
 */
object Secrets {
    private const val PREFIX = "enc1:"
    private const val ALIAS = "iptv_credentials"
    private const val TAG = "IptvSecrets"
    // Keystore calls are slow on TV boxes; the provider rows are read on every change, so remember results.
    private val plain = ConcurrentHashMap<String, String>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun isEncrypted(s: String?) = s?.startsWith(PREFIX) == true

    // shortcut: with no AndroidKeyStore (JVM unit tests only; every Android TV box has one) values stay plain.
    private val available by lazy { runCatching { KeyStore.getInstance("AndroidKeyStore") }.isSuccess }

    fun encrypt(s: String?): String? {
        if (s.isNullOrEmpty() || isEncrypted(s) || !available) return s
        // A box with a faulty keystore keeps the value plain rather than failing to save the playlist.
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
        }.onSuccess { plain[it] = s }.onFailure { Log.e(TAG, "Encrypt failed; stored unencrypted", it) }.getOrDefault(s)
    }

    /** Null when [s] can't be decrypted (e.g. the keystore was wiped): the login reads as empty, to be entered again. */
    fun decrypt(s: String?): String? {
        if (s == null || !isEncrypted(s)) return s
        plain[s]?.let { return it }
        return runCatching {
            val bytes = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            String(c.doFinal(bytes, 12, bytes.size - 12))
        }.onSuccess { plain[s] = it }.onFailure { Log.e(TAG, "Decrypt failed; the login must be entered again", it) }.getOrNull()
    }
}

/** Stand-ins for the Xtream login in saved stream and guide links; [withLogin] puts the real one back at use time. */
const val USER_TOKEN = "~iptvu~"
const val PASS_TOKEN = "~iptvp~"

data class Login(val username: String, val password: String)

/** [url] with the login placeholders (path segments or username/password query values) replaced; others unchanged. */
fun withLogin(url: String, login: Login?): String {
    // Query values come back encoded (%7E for ~), so look for the bare names.
    if (login == null || ("iptvu" !in url && "iptvp" !in url)) return url
    val u = url.toHttpUrlOrNull() ?: return url
    val b = u.newBuilder()
    u.pathSegments.forEachIndexed { i, s ->
        when (s) { USER_TOKEN -> b.setPathSegment(i, login.username); PASS_TOKEN -> b.setPathSegment(i, login.password) }
    }
    b.mapQuery(u) { name, value ->
        when {
            name == "username" && value == USER_TOKEN -> login.username
            name == "password" && value == PASS_TOKEN -> login.password
            else -> value
        }
    }
    return b.build().toString()
}

/** Rewrites the query values in their original order (setQueryParameter would move them to the end). */
private fun okhttp3.HttpUrl.Builder.mapQuery(u: okhttp3.HttpUrl, f: (String, String?) -> String?) {
    if (u.querySize == 0) return
    query(null)
    for (i in 0 until u.querySize) addQueryParameter(u.queryParameterName(i), f(u.queryParameterName(i), u.queryParameterValue(i)))
}

/** The reverse, for links saved before placeholders: the login's path segments / query values become placeholders. */
fun withoutLogin(url: String, login: Login): String {
    val u = url.toHttpUrlOrNull() ?: return url
    val segs = u.pathSegments
    val b = u.newBuilder()
    for (i in 0 until segs.size - 1) if (segs[i] == login.username && segs[i + 1] == login.password) {
        b.setPathSegment(i, USER_TOKEN); b.setPathSegment(i + 1, PASS_TOKEN); break
    }
    b.mapQuery(u) { name, value ->
        when {
            name == "username" && value == login.username -> USER_TOKEN
            name == "password" && value == login.password -> PASS_TOKEN
            else -> value
        }
    }
    return b.build().toString()
}

/** An M3U address's login when it uses the common `?username=…&password=…` form; null otherwise. */
fun m3uLogin(url: String): Login? {
    val u = url.toHttpUrlOrNull() ?: return null
    val name = u.queryParameter("username"); val pass = u.queryParameter("password")
    return if (name.isNullOrEmpty() || pass.isNullOrEmpty() || name == USER_TOKEN) null else Login(name, pass)
}

/** The M3U address as a backup stores it: its login replaced by placeholders, asked for again on restore. */
fun m3uBackupUrl(url: String): String = m3uLogin(url)?.let { withoutLogin(url, it) } ?: url

/**
 * True when one of [secrets] is in [url]: a whole (decoded) path segment, query value or user-info part, or — for secrets
 * of 5+ characters — anywhere in the decoded URL ("pass.xml", "user:pass"). Percent-encoding can't hide it, and a short
 * username doesn't match inside unrelated words. Unparsable → plain substring check.
 */
fun urlHoldsAny(url: String, secrets: List<String>): Boolean {
    val u = url.toHttpUrlOrNull() ?: return secrets.any { it in url }
    val parts = u.pathSegments + (0 until u.querySize).mapNotNull { u.queryParameterValue(it) } + listOf(u.username, u.password)
    val decoded = parts.joinToString("/")
    return secrets.any { it in parts || (it.length >= 5 && it in decoded) }
}

/** True when [url] holds login placeholders that need a login to be usable. */
fun hasLoginPlaceholders(url: String) = "iptvu" in url || "iptvp" in url
