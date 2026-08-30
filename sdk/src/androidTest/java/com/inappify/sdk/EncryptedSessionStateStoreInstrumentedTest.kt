package com.inappify.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.PersistedSession
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore and filesystem implementation. */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStateStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var store: EncryptedSessionStateStore

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = EncryptedSessionStateStore.create(context)
        store.clear()
        storageLockFile().delete()
        migrationMarker().delete()
        legacyPreferences().edit().clear().commit()
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
        storageLockFile().delete()
        migrationMarker().delete()
        legacyPreferences().edit().clear().commit()
    }

    @Test
    fun encryptedSession_roundTripsWithoutPlaintextSecrets() = runBlocking {
        val session = persistedSession()

        assertTrue(store.save(session))
        val restored = store.load()

        assertNotNull(restored)
        assertEquals(session.token, restored?.token)
        assertEquals(
            session.appUserIdentifier,
            restored?.appUserIdentifier,
        )
        assertEquals(session.apiKeyFingerprint, restored?.apiKeyFingerprint)
        assertEquals(
            session.cacheContextFingerprint,
            restored?.cacheContextFingerprint,
        )
        assertEquals(session.purchaseRecoveryId, restored?.purchaseRecoveryId)
        val encrypted = sessionFile().readBytes()
        assertFalse(encrypted.containsSubsequence(session.token!!.encodeToByteArray()))
        assertFalse(
            encrypted.containsSubsequence(
                session.appUserIdentifier!!.encodeToByteArray(),
            ),
        )
        assertFalse(
            encrypted.containsSubsequence(
                session.purchaseRecoveryId!!.encodeToByteArray(),
            ),
        )
    }

    @Test
    fun corruptedCiphertext_isDiscardedSafely() = runBlocking {
        assertTrue(store.save(persistedSession()))
        sessionFile().writeBytes(byteArrayOf(1, 2, 3, 4))

        val restored = store.load()

        assertNull(restored)
        assertFalse(sessionFile().exists())
    }

    @Test
    fun independentStores_serializeConcurrentWrites() = runBlocking {
        val secondStore = EncryptedSessionStateStore.create(context)
        val firstSession = persistedSession("first")
        val secondSession = persistedSession("second")
        val start = CompletableDeferred<Unit>()

        val writes = listOf(
            async(Dispatchers.Default) {
                start.await()
                store.save(firstSession)
            },
            async(Dispatchers.Default) {
                start.await()
                secondStore.save(secondSession)
            },
        )
        start.complete(Unit)

        assertTrue(writes.awaitAll().all { it })
        val restoredToken = store.load()?.token
        assertTrue(
            restoredToken == firstSession.token ||
                restoredToken == secondSession.token,
        )
    }

    @Test
    fun flutterMigration_encryptsOwnedValuesAndPreservesUnrelatedValue() =
        runBlocking {
            val preferences = legacyPreferences()
            assertTrue(
                preferences.edit()
                    .putString("flutter.token", "legacy-token")
                    .putString(
                        "flutter.appUserIdentifier",
                        "legacy-customer",
                    )
                    .putLong("flutter.forceVersion", 2_147_483_648L)
                    .putString("flutter.unrelated", "keep-me")
                    .commit(),
            )

            val restored = store.load()

            assertEquals("legacy-token", restored?.token)
            assertEquals("legacy-customer", restored?.appUserIdentifier)
            assertFalse(preferences.contains("flutter.token"))
            assertFalse(preferences.contains("flutter.appUserIdentifier"))
            assertEquals("keep-me", preferences.getString("flutter.unrelated", null))
            assertTrue(sessionFile().exists())
        }

    private fun persistedSession(suffix: String = "default"): PersistedSession = PersistedSession(
        token = "customer-token-$suffix",
        appUserIdentifier = "customer-identifier-$suffix",
        forceVersion = 2_147_483_648L,
        appId = 4_294_967_296L,
        storeInfo = "bazar",
        apiKeyFingerprint = "api-key-fingerprint",
        cacheContextFingerprint = "cache-context-fingerprint",
        purchaseRecoveryId = "purchase-recovery-id-$suffix",
        customerInfoJson =
            "{\"originalAppUserId\":\"customer-identifier-$suffix\"}",
        offeringsJson = "{\"offerings\":[]}",
        customerInfoUpdatedAt = "2026-08-26T12:00:00.000Z",
    )

    private fun sessionFile(): File =
        File(context.noBackupFilesDir, "inappify_session_v1.bin")

    private fun storageLockFile(): File =
        File(context.noBackupFilesDir, "inappify_session_v1.lock")

    private fun migrationMarker(): File = File(
        context.noBackupFilesDir,
        "inappify_flutter_session_migration_v1",
    )

    private fun legacyPreferences() = context.getSharedPreferences(
        "FlutterSharedPreferences",
        Context.MODE_PRIVATE,
    )
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
}
