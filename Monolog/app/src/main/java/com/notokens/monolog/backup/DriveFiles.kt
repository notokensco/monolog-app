package com.notokens.monolog.backup

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DriveConsentRequired : IOException("Reconnect Drive to continue.")
internal data class DriveBackup(val id: String, val year: Int, val created: String, val name: String,
    val source: String = "", val selection: String = "")
internal interface DriveStorage {
    suspend fun list(): List<DriveBackup>
    suspend fun upload(year: Int, bytes: ByteArray)
    suspend fun download(file: DriveBackup): ByteArray
    suspend fun recoverySeeds(): List<ByteArray> = emptyList()
    suspend fun linkRecovery(seed: ByteArray) = Unit
}

internal class DriveFiles(private val token: String, private val source: String, private val selection: String) : DriveStorage {
    override suspend fun list(): List<DriveBackup> {
        val files = mutableListOf<DriveBackup>()
        var page = ""
        do {
            val url = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder")
                .appendQueryParameter("q", "trashed = false and appProperties has { key='monologBackup' and value='1' }")
                .appendQueryParameter("fields", "nextPageToken,files(id,name,createdTime,appProperties)")
                .appendQueryParameter("pageSize", "100")
                .appendQueryParameter("orderBy", "createdTime desc")
            if (page.isNotEmpty()) url.appendQueryParameter("pageToken", page)
            val json = JSONObject(request(url.build().toString()).toString(Charsets.UTF_8))
            val rows = json.optJSONArray("files")
            if (rows != null) for (i in 0 until rows.length()) {
                val file = rows.getJSONObject(i)
                val year = file.optJSONObject("appProperties")?.optString("year")?.toIntOrNull() ?: continue
                if (year in 1970..9999) files.add(DriveBackup(file.getString("id"), year,
                    file.optString("createdTime"), file.getString("name"),
                    file.optJSONObject("appProperties")?.optString("source").orEmpty(),
                    file.optJSONObject("appProperties")?.optString("selection").orEmpty()))
            }
            page = json.optString("nextPageToken")
            require(files.size <= 10_000) { "Too many backups to load at once." }
        } while (page.isNotEmpty())
        // Each device uploads a complete yearly snapshot for its selection.
        return files.distinctBy { Triple(it.year, it.source.ifBlank { it.id }, it.selection) }
    }

    override suspend fun recoverySeeds(): List<ByteArray> {
        val files = mutableListOf<ByteArray>()
        var page = ""
        do {
            val url = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder")
                .appendQueryParameter("q", "trashed = false and appProperties has { key='monologRecovery' and value='1' }")
                .appendQueryParameter("fields", "nextPageToken,files(id,size)")
            if (page.isNotBlank()) url.appendQueryParameter("pageToken", page)
            val response = JSONObject(request(url.build().toString()).toString(Charsets.UTF_8))
            val rows = response.getJSONArray("files")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                if (row.optLong("size") == 32L && id.matches(Regex("[A-Za-z0-9_-]+"))) {
                    val seed = request("https://www.googleapis.com/drive/v3/files/$id?alt=media")
                    if (seed.size == 32) files.add(seed)
                }
                check(files.size <= 100) { "This account has too many linked backup keys." }
            }
            page = response.optString("nextPageToken")
        } while (page.isNotBlank())
        return files
    }

    override suspend fun linkRecovery(seed: ByteArray) {
        require(seed.size == 32)
        val fingerprint = BackupCrypto.digest(seed)
        val query = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
            .appendQueryParameter("spaces", "appDataFolder")
            .appendQueryParameter("q", "trashed = false and appProperties has { key='recoveryId' and value='$fingerprint' }")
            .appendQueryParameter("fields", "files(id)").build()
        if (JSONObject(request(query.toString()).toString(Charsets.UTF_8)).getJSONArray("files").length() > 0) return
        val metadata = JSONObject().put("name", "monolog-linked-recovery.key")
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("monologRecovery", "1").put("recoveryId", fingerprint))
        uploadFile(metadata, seed)
    }

    override suspend fun upload(year: Int, bytes: ByteArray) {
        val metadata = JSONObject().put("name", "monolog-v1-$year-${UUID.randomUUID()}.monolog")
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("monologBackup", "1").put("year", year.toString())
                .put("source", source).put("selection", selection))
        uploadFile(metadata, bytes)
    }

    private suspend fun uploadFile(metadata: JSONObject, bytes: ByteArray) {
        val boundary = "monolog-" + UUID.randomUUID()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val json = JSONObject(request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,md5Checksum",
            body, "multipart/related; boundary=$boundary").toString(Charsets.UTF_8))
        val checksum = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        check(json.optString("id").isNotBlank() && json.optString("md5Checksum") == checksum) {
            "Drive did not confirm the uploaded backup."
        }
    }

    override suspend fun download(file: DriveBackup): ByteArray {
        require(file.id.matches(Regex("[A-Za-z0-9_-]+")))
        return request("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media")
    }

    private suspend fun request(url: String, body: ByteArray? = null, contentType: String = ""): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>()
            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = URL(url).openConnection() as HttpsURLConnection
                    active.set(connection)
                    try {
                        if (!continuation.isActive) return@launch
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 30_000
                        connection.useCaches = false
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        if (body != null) {
                            connection.requestMethod = "POST"
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", contentType)
                            connection.setFixedLengthStreamingMode(body.size)
                            connection.outputStream.use { it.write(body) }
                        }
                        val status = connection.responseCode
                        if (status == 401 || status == 403) throw DriveConsentRequired()
                        if (status !in 200..299) throw IOException("Drive request failed ($status). Try again.")
                        val bytes = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16_384)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (!continuation.isActive) throw CancellationException()
                                require(output.size().toLong() + count <= BackupCrypto.MAX_BYTES + 256L) {
                                    "Backup is too large."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } finally {
                        active.set(null)
                        connection.disconnect()
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.disconnect()
                job.cancel()
            }
        }
}
