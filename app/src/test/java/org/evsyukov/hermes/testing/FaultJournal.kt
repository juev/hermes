package org.evsyukov.hermes.testing

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic private test file only. Failure occurs in DataStore's serializer after edit transform. */
internal class FaultJournal(@Suppress("UNUSED_PARAMETER") suppliedScope: CoroutineScope) {
    private val root = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(root + kotlinx.coroutines.Dispatchers.IO)
    suspend fun close() { release.countDown(); readRelease.countDown(); root.cancel(); root.join(); check(!directory.exists() || directory.deleteRecursively()) { "Synthetic journal cleanup failed" } }
    @Volatile var holdRead = false
    val readEntered = CountDownLatch(1)
    val readRelease = CountDownLatch(1)
    fun prefillEmpty() { file.parentFile?.mkdirs(); file.writeText("{}") }
    fun committedRows(): List<org.evsyukov.hermes.chat.LocalObservation> = if (!file.exists()) emptyList() else Json.parseToJsonElement(file.readText()).jsonObject.filterKeys { it.startsWith("observation:") }.values.map { Json.decodeFromString(org.evsyukov.hermes.chat.LocalObservation.serializer(), it.jsonPrimitive.content) }
    @Volatile var fail = false
    @Volatile var hold = false
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val writes = AtomicInteger()
    val events = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val directory = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "synthetic-journal-${java.util.UUID.randomUUID()}")
    private val file = File(directory, "journal.json")
    val data = DataStoreFactory.create(serializer = object : Serializer<Preferences> {
        override val defaultValue: Preferences = emptyPreferences()
        override suspend fun readFrom(input: InputStream): Preferences {
            readEntered.countDown()
            if (holdRead) check(readRelease.await(10, TimeUnit.SECONDS)) { "Journal read gate not released" }
            val obj = Json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
            val prefs = mutablePreferencesOf()
            obj.forEach { (key, value) -> val item = value.jsonPrimitive; if (item.isString) prefs[stringPreferencesKey(key)] = item.content else prefs[booleanPreferencesKey(key)] = checkNotNull(item.booleanOrNull) }
            return prefs
        }
        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            events.trySend(writes.incrementAndGet()); entered.countDown()
            if (hold) check(release.await(10, TimeUnit.SECONDS)) { "Journal serializer gate not released" }
            output.write(buildJsonObject { t.asMap().forEach { (key, value) -> when(value) { is String -> put(key.name, value); is Boolean -> put(key.name, value); else -> error("Unsupported synthetic preference") } } }.toString().encodeToByteArray())
            if (fail) throw IOException("synthetic post-transform storage write failed")
        }
    }, scope = scope, produceFile = { file.parentFile?.mkdirs(); file })
}
