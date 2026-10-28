package org.evsyukov.hermes.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.hermes.data.remote.errorOf
import org.evsyukov.hermes.data.server.ServerSession
import java.io.File
import java.security.MessageDigest

/**
 * Files of the server kept on the phone: an attachment downloads once into the cache and opens
 * from there, in the viewer or as a picture in the chat.
 */
class AttachmentCache(private val root: File) {
    init {
        root.mkdirs()
    }

    fun fileFor(serverId: String, path: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$serverId:$path".toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
        val name = path.substringAfterLast('/').replace(Regex("""[^\w.\-]"""), "_").takeLast(80).ifEmpty { "file" }
        return File(root, "${hash}_$name")
    }

    /**
     * Downloads [path] from the server unless it is in the cache already. A relative path, as
     * `@file:` references are, resolves in the working directory of [chatId].
     */
    suspend fun get(session: ServerSession, path: String, chatId: String? = null): File = withContext(Dispatchers.IO) {
        val relative = !path.startsWith("/")
        val target = fileFor(session.profile.id, if (relative) "$chatId/$path" else path)
        if (target.isFile && target.length() > 0) return@withContext target
        val part = File(target.parentFile, target.name + ".part")
        session.http.execute {
            val url = session.http.endpoint("api/fs/download").addQueryParameter("path", path)
            if (relative && chatId != null) url.addQueryParameter("session_id", chatId)
            url(url.build())
        }.use { response ->
            if (!response.isSuccessful) throw errorOf(response)
            part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        part.renameTo(target)
        target
    }

    /** Keeps a picked file in the cache, so the bubble of a sent message can show it. */
    fun put(name: String, bytes: ByteArray): File {
        val file = File(root, "local_${System.nanoTime()}_${name.replace(Regex("""[^\w.\-]"""), "_")}")
        file.writeBytes(bytes)
        return file
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    fun size(): Long = root.listFiles()?.sumOf { it.length() } ?: 0
}
