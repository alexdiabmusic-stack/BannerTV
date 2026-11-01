package com.alexdiab.stadiatv.live

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Xtream usernames and passwords, AES-GCM encrypted with a key that never leaves the Android Keystore.
 * Credentials are kept per playlist id and are never written to the playlist JSON.
 */
class CredentialStore(context: Context) {
    private val addonFile = android.util.AtomicFile(java.io.File(context.noBackupFilesDir, "addons.v1"))
    private val metadataFile = android.util.AtomicFile(java.io.File(context.noBackupFilesDir, "metadata.v1"))
    fun addonVault() = encryptedFile(addonFile)
    fun metadataVault() = encryptedFile(metadataFile)
    private fun encryptedFile(file: android.util.AtomicFile) = object : com.alexdiab.stadiatv.addons.AddonVault {
        override fun read(): String? = try { decrypt(file.openRead().bufferedReader().use { it.readText() }).also { require(it.isNotEmpty()) } }
            catch (_: java.io.FileNotFoundException) { if (file.baseFile.exists()) throw IllegalStateException("Vault unavailable") else null }
        override fun replace(value: String) {
            val bytes = encrypt(value).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(playlistId: String, credentials: XtreamCredentials) {
        val encrypted = runCatching { encrypt(credentials.username + "\n" + credentials.password) }.getOrNull() ?: return
        prefs.edit().putString(playlistId, encrypted).apply()
    }

    fun load(playlistId: String): XtreamCredentials? {
        val stored = prefs.getString(playlistId, null) ?: return null
        val plain = runCatching { decrypt(stored) }.getOrNull() ?: return null
        val parts = plain.split('\n', limit = 2)
        return XtreamCredentials(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    fun remove(playlistId: String) = prefs.edit().remove(playlistId).apply()

    /** An encrypted free-form secret (such as ESPN session cookies), stored under its own id. */
    fun saveSecret(id: String, value: String) {
        val encrypted = runCatching { encrypt(value) }.getOrNull() ?: return
        prefs.edit().putString(id, encrypted).apply()
    }

    fun loadSecret(id: String): String? =
        prefs.getString(id, null)?.let { stored -> runCatching { decrypt(stored) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    /** Decrypts a value written by the original Android build (same key alias and format). */
    fun decryptLegacy(value: String): XtreamCredentials? {
        val plain = runCatching { decrypt(value) }.getOrNull() ?: return null
        val parts = plain.split('\n', limit = 2)
        return XtreamCredentials(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "stadiatv.credentials.v2"
        const val STORE = "AndroidKeyStore"
        // Same alias as the original build, so existing Xtream logins keep decrypting after the update.
        const val ALIAS = "stadiatv.xtream.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
