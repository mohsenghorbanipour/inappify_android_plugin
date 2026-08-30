@file:Suppress("DEPRECATION")

package com.inappify.sdk.internal.storage

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Session persistence backed by AES-GCM and Android Keystore.
 *
 * File locking serializes access between client instances and application
 * processes. Values from previous Inappify SDK installations are removed only
 * after an encrypted write succeeds.
 */
internal class EncryptedSessionStateStore private constructor(
    private val applicationContext: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : SessionStateStore {

    private val sessionFile = AtomicFile(
        File(applicationContext.noBackupFilesDir, SESSION_FILE_NAME),
    )
    private val migrationMarker = File(
        applicationContext.noBackupFilesDir,
        MIGRATION_MARKER_FILE_NAME,
    )
    private val storageLockFile = File(
        applicationContext.noBackupFilesDir,
        STORAGE_LOCK_FILE_NAME,
    )
    private val cipher = SessionCipher(applicationContext)

    override suspend fun load(): PersistedSession? = withStorageLock {
        val restored = if (sessionFile.baseFile.exists()) {
            readSession()
        } else {
            null
        }
        if (restored != null) {
            completeLegacyCleanupIfNeeded()
            restored
        } else {
            if (sessionFile.baseFile.exists()) deleteCorruptedSession()
            migrateLegacySession()
        }
    }

    override suspend fun save(session: PersistedSession): Boolean =
        withStorageLock {
            val stored = writeSession(session)
            if (stored) completeLegacyCleanupIfNeeded()
            stored
        }

    override suspend fun clear(): Boolean = withStorageLock {
        try {
            sessionFile.delete()
        } catch (_: Exception) {
            return@withStorageLock false
        }
        !sessionFile.baseFile.exists()
    }

    private suspend fun <T> withStorageLock(block: () -> T): T =
        withContext(ioDispatcher) {
            RandomAccessFile(storageLockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.use { channel ->
                    val lock = acquireStorageLock(channel)
                    try {
                        block()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

    private suspend fun acquireStorageLock(channel: FileChannel): FileLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            delay(STORAGE_LOCK_RETRY_MILLIS)
        }
    }

    private fun readSession(): PersistedSession? = try {
        val encrypted = sessionFile.openRead().use {
            it.readBytesWithLimit(MAX_SESSION_FILE_BYTES)
        }
        decode(cipher.decrypt(encrypted).decodeToString())
    } catch (_: Exception) {
        null
    }

    private fun writeSession(session: PersistedSession): Boolean = try {
        val plainText = encode(session).encodeToByteArray()
        require(plainText.size <= MAX_SESSION_PLAINTEXT_BYTES) {
            "Session payload exceeds the storage limit."
        }
        val encrypted = cipher.encrypt(plainText)
        require(encrypted.size <= MAX_SESSION_FILE_BYTES) {
            "Encrypted session exceeds the storage limit."
        }
        val output = sessionFile.startWrite()
        try {
            output.write(encrypted)
            output.fd.sync()
            sessionFile.finishWrite(output)
            true
        } catch (exception: Exception) {
            sessionFile.failWrite(output)
            throw exception
        }
    } catch (_: Exception) {
        false
    }

    private fun migrateLegacySession(): PersistedSession? {
        if (migrationMarker.exists()) return null

        val preferences = try {
            applicationContext.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        } catch (_: Exception) {
            return null
        }
        val values = try {
            preferences.all
        } catch (_: Exception) {
            return null
        }
        val migrated = legacySessionFrom(values)
        if (migrated == null) {
            completeLegacyCleanupIfNeeded()
            return null
        }

        if (!writeSession(migrated)) return migrated
        completeLegacyCleanupIfNeeded()
        return migrated
    }

    @SuppressLint("ApplySharedPref")
    private fun completeLegacyCleanupIfNeeded() {
        if (migrationMarker.exists()) return

        val preferences = try {
            applicationContext.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        } catch (_: Exception) {
            return
        }
        val removed = try {
            val editor = preferences.edit()
            LEGACY_PREFERENCE_KEYS.forEach { key -> editor.remove(key) }
            // Synchronous confirmation is required before marking migration complete.
            editor.commit()
        } catch (_: Exception) {
            false
        }
        if (!removed) return

        try {
            if (!migrationMarker.exists()) migrationMarker.createNewFile()
        } catch (_: Exception) {
            // Cleanup is idempotent and will be retried on the next load/save.
        }
    }

    private fun deleteCorruptedSession() {
        try {
            sessionFile.delete()
        } catch (_: Exception) {
            // A later atomic write may still replace the invalid file.
        }
    }

    private fun encode(session: PersistedSession): String =
        JsonObject().apply {
            addNullableString("token", session.token)
            addNullableString("appUserIdentifier", session.appUserIdentifier)
            addNullableNumber("forceVersion", session.forceVersion)
            addNullableNumber("appId", session.appId)
            addNullableString("storeInfo", session.storeInfo)
            addNullableString("apiKeyFingerprint", session.apiKeyFingerprint)
            addNullableString(
                "cacheContextFingerprint",
                session.cacheContextFingerprint,
            )
            addNullableString("purchaseRecoveryId", session.purchaseRecoveryId)
            addNullableString("customerInfoJson", session.customerInfoJson)
            addNullableString("offeringsJson", session.offeringsJson)
            addNullableString(
                "customerInfoUpdatedAt",
                session.customerInfoUpdatedAt,
            )
        }.toString()

    private fun decode(raw: String): PersistedSession {
        val root = JsonParser.parseString(raw)
        require(root.isJsonObject) { "Session payload must be a JSON object." }
        val json = root.asJsonObject
        return PersistedSession(
            token = json.stringValue("token"),
            appUserIdentifier = json.stringValue("appUserIdentifier"),
            forceVersion = json.longValue("forceVersion"),
            appId = json.longValue("appId"),
            storeInfo = json.stringValue("storeInfo"),
            apiKeyFingerprint = json.stringValue("apiKeyFingerprint"),
            cacheContextFingerprint = json.stringValue(
                "cacheContextFingerprint",
            ),
            purchaseRecoveryId = json.stringValue("purchaseRecoveryId"),
            customerInfoJson = json.stringValue("customerInfoJson"),
            offeringsJson = json.stringValue("offeringsJson"),
            customerInfoUpdatedAt = json.stringValue("customerInfoUpdatedAt"),
        )
    }

    private fun JsonObject.addNullableString(name: String, value: String?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addNullableNumber(name: String, value: Number?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.stringValue(name: String): String? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString

    private fun JsonObject.longValue(name: String): Long? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isNumber }
            ?.let { value ->
                try {
                    BigDecimal(value.asString).longValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
            }

    internal companion object {
        private const val SESSION_FILE_NAME = "inappify_session_v1.bin"
        private const val STORAGE_LOCK_FILE_NAME = "inappify_session_v1.lock"
        private const val MIGRATION_MARKER_FILE_NAME =
            "inappify_flutter_session_migration_v1"
        private const val LEGACY_PREFERENCES_NAME = "FlutterSharedPreferences"
        private const val LEGACY_TOKEN_KEY = "flutter.token"
        private const val LEGACY_USER_IDENTIFIER_KEY =
            "flutter.appUserIdentifier"
        private const val LEGACY_FORCE_VERSION_KEY = "flutter.forceVersion"
        private const val LEGACY_APP_ID_KEY = "flutter.appId"
        private const val LEGACY_CUSTOMER_INFO_KEY = "flutter.customerInfo"
        private const val LEGACY_OFFERINGS_KEY = "flutter.offerings"
        private const val LEGACY_CUSTOMER_INFO_DATE_KEY =
            "flutter.dateTimeCustomerInfo"
        private const val MAX_SESSION_PLAINTEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_SESSION_FILE_BYTES =
            MAX_SESSION_PLAINTEXT_BYTES + 1024
        private const val STORAGE_LOCK_RETRY_MILLIS = 10L

        internal val LEGACY_PREFERENCE_KEYS: Set<String>
            get() = setOf(
                LEGACY_TOKEN_KEY,
                LEGACY_USER_IDENTIFIER_KEY,
                LEGACY_FORCE_VERSION_KEY,
                LEGACY_APP_ID_KEY,
                LEGACY_CUSTOMER_INFO_KEY,
                LEGACY_OFFERINGS_KEY,
                LEGACY_CUSTOMER_INFO_DATE_KEY,
            )

        internal fun legacySessionFrom(values: Map<String, *>): PersistedSession? =
            PersistedSession(
                token = values[LEGACY_TOKEN_KEY] as? String,
                appUserIdentifier = values[LEGACY_USER_IDENTIFIER_KEY] as? String,
                forceVersion = values.numberValue(LEGACY_FORCE_VERSION_KEY),
                appId = values.numberValue(LEGACY_APP_ID_KEY),
                storeInfo = null,
                apiKeyFingerprint = null,
                customerInfoJson = values[LEGACY_CUSTOMER_INFO_KEY] as? String,
                offeringsJson = values[LEGACY_OFFERINGS_KEY] as? String,
                customerInfoUpdatedAt =
                    values[LEGACY_CUSTOMER_INFO_DATE_KEY] as? String,
            ).takeIf { session -> session.hasData() }

        internal fun create(context: Context): EncryptedSessionStateStore =
            EncryptedSessionStateStore(
                applicationContext = context.applicationContext,
                ioDispatcher = Dispatchers.IO,
            )
    }
}

private fun PersistedSession.hasData(): Boolean =
    !token.isNullOrBlank() ||
        !appUserIdentifier.isNullOrBlank() ||
        forceVersion != null ||
        appId != null ||
        customerInfoJson != null ||
        offeringsJson != null

private fun Map<String, *>.numberValue(key: String): Long? =
    (get(key) as? Number)?.let { value ->
        try {
            BigDecimal(value.toString()).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

private class SessionCipher(
    private val context: Context,
) {
    private val secureRandom = SecureRandom()

    fun encrypt(plainText: ByteArray): ByteArray = try {
        encryptWith(currentSecretKey(), plainText)
    } catch (_: Exception) {
        resetKeyMaterial()
        encryptWith(currentSecretKey(), plainText)
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > HEADER_SIZE) { "Encrypted session is truncated." }
        val buffer = ByteBuffer.wrap(payload)
        val formatVersion = buffer.get().toInt()
        require(formatVersion == FORMAT_VERSION) {
            "Unsupported encrypted session format."
        }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 1..buffer.remaining()) { "Invalid session IV." }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val decryptor = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                currentSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
        }
        return decryptor.doFinal(encrypted)
    }

    private fun encryptWith(key: SecretKey, plainText: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val encryptor = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
        }
        val encrypted = encryptor.doFinal(plainText)
        return ByteBuffer.allocate(HEADER_SIZE + iv.size + encrypted.size)
            .put(FORMAT_VERSION.toByte())
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
    }

    private fun currentSecretKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            api23SecretKey()
        } else {
            legacyWrappedSecretKey()
        }

    @TargetApi(Build.VERSION_CODES.M)
    private fun api23SecretKey(): SecretKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(AES_KEY_ALIAS)) {
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE,
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        AES_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or
                            KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
        return keyStore.getKey(AES_KEY_ALIAS, null) as SecretKey
    }

    @Suppress("DEPRECATION")
    private fun legacyWrappedSecretKey(): SecretKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(RSA_KEY_ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val specification = KeyPairGeneratorSpec.Builder(context)
                .setAlias(RSA_KEY_ALIAS)
                .setSubject(X500Principal("CN=Inappify Session Key"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE).apply {
                initialize(specification)
            }.generateKeyPair()
        }

        val wrappedKeyFile = AtomicFile(
            File(context.noBackupFilesDir, WRAPPED_AES_KEY_FILE_NAME),
        )
        val entry = keyStore.getEntry(RSA_KEY_ALIAS, null)
            as KeyStore.PrivateKeyEntry
        val rawKey = if (wrappedKeyFile.baseFile.exists()) {
            val wrapped = wrappedKeyFile.openRead().use {
                it.readBytesWithLimit(MAX_WRAPPED_KEY_FILE_BYTES)
            }
            Cipher.getInstance(RSA_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, entry.privateKey)
            }.doFinal(wrapped)
        } else {
            val generated = KeyGenerator.getInstance("AES").apply {
                init(LEGACY_AES_KEY_SIZE_BITS)
            }.generateKey().encoded
            val wrapped = Cipher.getInstance(RSA_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
            }.doFinal(generated)
            require(wrapped.size <= MAX_WRAPPED_KEY_FILE_BYTES) {
                "Wrapped session key exceeds the storage limit."
            }
            val output = wrappedKeyFile.startWrite()
            try {
                output.write(wrapped)
                output.fd.sync()
                wrappedKeyFile.finishWrite(output)
            } catch (exception: Exception) {
                wrappedKeyFile.failWrite(output)
                throw exception
            }
            generated
        }
        return SecretKeySpec(rawKey, "AES")
    }

    private fun resetKeyMaterial() {
        try {
            androidKeyStore().apply {
                if (containsAlias(AES_KEY_ALIAS)) deleteEntry(AES_KEY_ALIAS)
                if (containsAlias(RSA_KEY_ALIAS)) deleteEntry(RSA_KEY_ALIAS)
            }
        } catch (_: Exception) {
            // The next key request surfaces any unrecoverable Keystore error.
        }
        try {
            AtomicFile(
                File(context.noBackupFilesDir, WRAPPED_AES_KEY_FILE_NAME),
            ).delete()
        } catch (_: Exception) {
            // The next unwrap or write reports an unusable wrapped-key file.
        }
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        private const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 2
        private const val GCM_IV_SIZE_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val LEGACY_AES_KEY_SIZE_BITS = 128
        private const val MAX_WRAPPED_KEY_FILE_BYTES = 16 * 1024
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val AES_KEY_ALIAS = "com.inappify.sdk.session.aes.v1"
        private const val RSA_KEY_ALIAS = "com.inappify.sdk.session.rsa.v1"
        private const val WRAPPED_AES_KEY_FILE_NAME =
            "inappify_wrapped_session_key_v1.bin"
    }
}

internal fun InputStream.readBytesWithLimit(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "File-size limit must be positive." }
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        total += count
        require(total <= maxBytes) { "Stored file exceeds the size limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
