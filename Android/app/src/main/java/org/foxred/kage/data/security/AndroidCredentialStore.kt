package org.foxred.kage.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.foxred.kage.core.account.*
import org.json.JSONObject

/**
 * AES-GCM ciphertext in noBackupFilesDir; each account/protocol key remains in Android Keystore.
 */
class AndroidCredentialStore(context: Context, private val namespace: String = "mail-credentials") :
    CredentialStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, namespace)
    private val prefix = "org.foxred.kage.$namespace."

    init {
        require(namespace.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
    }

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun name(accountId: String, protocol: ServerProtocol): String {
        require(accountId.isNotBlank())
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(accountId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "$hash.${protocol.name}"
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val existing = keys().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw CredentialFailure(CredentialFailureReason.UNREADABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    override fun save(accountId: String, protocol: ServerProtocol, authorization: Authorization) =
        synchronized(lock) {
            val identifier = name(accountId, protocol)
            val raw =
                JSONObject()
                    .put("kind", authorization.kind.name)
                    .put("secret", authorization.secret)
                    .put("expiresAt", authorization.expiresAt?.toString() ?: JSONObject.NULL)
                    .put("refreshToken", authorization.refreshToken ?: JSONObject.NULL)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            try {
                require(raw.size <= MAX_PLAINTEXT) { "Credential exceeds storage limit" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(prefix + identifier, true))
                cipher.updateAAD(identifier.toByteArray(Charsets.UTF_8))
                val encrypted = cipher.doFinal(raw)
                check(directory.isDirectory || directory.mkdirs())
                val file = AtomicFile(File(directory, identifier))
                var stream: FileOutputStream? = null
                try {
                    stream = file.startWrite()
                    val output = DataOutputStream(stream)
                    output.writeInt(1)
                    output.writeByte(cipher.iv.size)
                    output.write(cipher.iv)
                    output.write(encrypted)
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
            } catch (_: Exception) {
                throw CredentialFailure(CredentialFailureReason.STORAGE_UNAVAILABLE)
            } finally {
                raw.fill(0)
            }
        }

    override fun authorization(accountId: String, protocol: ServerProtocol): Authorization? =
        synchronized(lock) {
            val identifier = name(accountId, protocol)
            val file = AtomicFile(File(directory, identifier))
            val input =
                try {
                    file.openRead()
                } catch (_: FileNotFoundException) {
                    return@synchronized null
                }
            try {
                input.use {
                    require(it.channel.size() in 33..MAX_FILE.toLong())
                    val data = DataInputStream(it)
                    require(data.readInt() == 1)
                    val ivSize = data.readUnsignedByte()
                    require(ivSize == 12)
                    val iv = ByteArray(ivSize).also(data::readFully)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key(prefix + identifier, false),
                        GCMParameterSpec(128, iv),
                    )
                    cipher.updateAAD(identifier.toByteArray(Charsets.UTF_8))
                    val plain = cipher.doFinal(data.readBytes())
                    try {
                        require(plain.size <= MAX_PLAINTEXT)
                        val value = JSONObject(plain.toString(Charsets.UTF_8))
                        Authorization(
                            value.getString("secret"),
                            Authorization.Kind.valueOf(value.getString("kind")),
                            if (value.isNull("expiresAt")) null
                            else Instant.parse(value.getString("expiresAt")),
                            if (value.isNull("refreshToken")) null
                            else value.getString("refreshToken"),
                        )
                    } finally {
                        plain.fill(0)
                    }
                }
            } catch (_: Exception) {
                throw CredentialFailure(CredentialFailureReason.UNREADABLE)
            }
        }

    override fun removeAccount(accountId: String) =
        synchronized(lock) {
            try {
                val store = keys()
                ServerProtocol.entries.forEach { protocol ->
                    val identifier = name(accountId, protocol)
                    // Revoke decryptability first, even if filesystem cleanup is interrupted.
                    store.deleteEntry(prefix + identifier)
                    AtomicFile(File(directory, identifier)).delete()
                }
            } catch (_: Exception) {
                throw CredentialFailure(CredentialFailureReason.STORAGE_UNAVAILABLE)
            }
        }

    override fun clear() =
        synchronized(lock) {
            try {
                val store = keys()
                store
                    .aliases()
                    .toList()
                    .filter { it.startsWith(prefix) }
                    .forEach(store::deleteEntry)
                if (directory.exists() && !directory.deleteRecursively())
                    throw IOException("Credential cleanup failed")
                Unit
            } catch (_: Exception) {
                throw CredentialFailure(CredentialFailureReason.STORAGE_UNAVAILABLE)
            }
        }

    private companion object {
        val lock = Any()
        const val MAX_PLAINTEXT = 64 * 1024
        const val MAX_FILE = 128 * 1024
    }
}
