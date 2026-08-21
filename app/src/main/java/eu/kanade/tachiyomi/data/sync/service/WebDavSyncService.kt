package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.http.HttpStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WebDavSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
) : SyncService(
    context,
    json,
    syncPreferences,
) {
    constructor(context: Context) : this(
        context,
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        },
        Injekt.get<SyncPreferences>(),
    )

    enum class DeleteSyncDataStatus {
        NOT_CONFIGURED,
        NO_FILES,
        SUCCESS,
        ERROR,
    }

    private val appName = context.stringResource(MR.strings.app_name)

    private val remoteFileName = "${appName}_sync.proto.gz"
    private val deviceFileName = "${appName}_sync.device"

    private val protoBuf: ProtoBuf = Injekt.get()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doSync(syncData: SyncData): Backup? {
        beforeSync()

        try {
            val remoteSData = pullSyncData()

            if (remoteSData != null) {
                // Get local unique device ID
                val localDeviceId = syncPreferences.uniqueDeviceID()
                val lastSyncDeviceId = remoteSData.deviceId

                // Log the device IDs
                logcat(LogPriority.DEBUG, "SyncService") {
                    "Local device ID: $localDeviceId, Last sync device ID: $lastSyncDeviceId"
                }

                // check if the last sync was done by the same device if so overwrite the remote data with the local data
                return if (lastSyncDeviceId == localDeviceId) {
                    pushSyncData(syncData)
                    syncData.backup
                } else {
                    // Merge the local and remote sync data
                    val mergedSyncData = mergeSyncData(syncData, remoteSData)
                    pushSyncData(mergedSyncData)
                    mergedSyncData.backup
                }
            }

            pushSyncData(syncData)
            return syncData.backup
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, "SyncService") { "Error syncing: ${e.message}" }
            return null
        }
    }

    private fun beforeSync() {
        if (syncPreferences.webDavUrl.get().isBlank()) {
            throw Exception(context.stringResource(SYMR.strings.webdav_not_configured))
        }
    }

    private fun baseUrl(): String = syncPreferences.webDavUrl.get().trim().trimEnd('/')

    private fun fileUrl(fileName: String): String = "${baseUrl()}/$fileName"

    private fun buildHeaders(): Headers {
        val username = syncPreferences.webDavUsername.get()
        return if (username.isNotEmpty()) {
            Headers.Builder()
                .add("Authorization", Credentials.basic(username, syncPreferences.webDavPassword.get()))
                .build()
        } else {
            Headers.Builder().build()
        }
    }

    private suspend fun pullSyncData(): SyncData? {
        val response = client.newCall(GET(fileUrl(remoteFileName), buildHeaders())).await()

        if (response.code == HttpStatus.SC_NOT_FOUND) {
            response.close()
            logcat(LogPriority.INFO) { "No sync data found on WebDAV server" }
            return null
        }

        if (!response.isSuccessful) {
            val body = response.body.string()
            response.close()
            throw Exception("Failed to download sync data: ${response.code} $body")
        }

        val backup = try {
            response.use {
                it.body.byteStream().use { inputStream ->
                    GZIPInputStream(inputStream).use { gzipInputStream ->
                        protoBuf.decodeFromByteArray(Backup.serializer(), gzipInputStream.readBytes())
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e) { "Error downloading file" }
            throw Exception("Failed to download sync data: ${e.message}", e)
        }

        return SyncData(deviceId = pullDeviceId(), backup = backup)
    }

    private suspend fun pullDeviceId(): String {
        val response = client.newCall(GET(fileUrl(deviceFileName), buildHeaders())).await()
        return response.use {
            if (it.isSuccessful) {
                it.body.string().trim()
            } else {
                ""
            }
        }
    }

    private suspend fun pushSyncData(syncData: SyncData) = withIOContext {
        val backup = syncData.backup ?: return@withIOContext

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }

        val gzipped = ByteArrayOutputStream().use { outputStream ->
            GZIPOutputStream(outputStream).use { gzipOutputStream ->
                gzipOutputStream.write(byteArray)
            }
            outputStream.toByteArray()
        }

        uploadFile(remoteFileName, gzipped, "application/octet-stream")
        uploadFile(deviceFileName, syncData.deviceId.toByteArray(Charsets.UTF_8), "text/plain")
    }

    private suspend fun uploadFile(fileName: String, bytes: ByteArray, mediaType: String) {
        val body = bytes.toRequestBody(mediaType.toMediaType())
        val response = client.newCall(PUT(fileUrl(fileName), buildHeaders(), body)).await()
        response.use {
            if (!it.isSuccessful) {
                val responseBody = it.body.string()
                throw Exception("Failed to upload sync data: ${it.code} $responseBody")
            }
        }
    }

    suspend fun deleteSyncDataFromWebDav(): DeleteSyncDataStatus {
        if (syncPreferences.webDavUrl.get().isBlank()) {
            return DeleteSyncDataStatus.NOT_CONFIGURED
        }

        return withIOContext {
            try {
                var anyDeleted = false
                var anyError = false

                for (fileName in listOf(remoteFileName, deviceFileName)) {
                    val response = client.newCall(DELETE(fileUrl(fileName), buildHeaders())).await()
                    when {
                        response.code == HttpStatus.SC_NOT_FOUND -> {
                            // Nothing to delete
                        }
                        response.isSuccessful -> {
                            anyDeleted = true
                        }
                        else -> {
                            anyError = true
                        }
                    }
                    response.close()
                }

                when {
                    anyError -> DeleteSyncDataStatus.ERROR
                    anyDeleted -> DeleteSyncDataStatus.SUCCESS
                    else -> DeleteSyncDataStatus.NO_FILES
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, throwable = e) {
                    "Error occurred while interacting with WebDAV server"
                }
                DeleteSyncDataStatus.ERROR
            }
        }
    }
}