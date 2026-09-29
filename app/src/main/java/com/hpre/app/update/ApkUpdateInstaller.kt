package com.hpre.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a release APK into the app cache and hands it to the system package installer.
 *
 * Android itself rejects an update signed with a different key, so the digest check only guards
 * against truncated or corrupted downloads.
 */
class ApkUpdateInstaller(
    context: Context,
    client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    // The shared client caps whole calls at 30s, which a slow mobile link cannot meet for an APK.
    private val client = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val updatesDir: File
        get() = File(appContext.cacheDir, UPDATES_DIR)

    /** Returns the verified APK, or null when the download fails or does not match [apk]. */
    suspend fun download(apk: ReleaseApk, onProgress: (Float) -> Unit): File? = withContext(ioDispatcher) {
        val dir = updatesDir
        dir.deleteRecursively()
        if (!dir.mkdirs()) return@withContext null
        val target = File(dir, APK_FILE_NAME)
        val request = Request.Builder()
            .url(apk.downloadUrl)
            .header("User-Agent", "HPre-Android-Updater")
            .build()
        val verified = try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                response.isSuccessful && body != null &&
                    body.byteStream().use { input -> copyVerified(input, target, apk, onProgress) }
            }
        } catch (_: IOException) {
            false
        }
        if (verified) target else null.also { target.delete() }
    }

    private suspend fun copyVerified(
        input: InputStream,
        target: File,
        apk: ReleaseApk,
        onProgress: (Float) -> Unit
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lastPercent = -1
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                if (written > apk.sizeBytes) return false
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                val percent = (written * 100 / apk.sizeBytes).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent / 100f)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return written == apk.sizeBytes && (apk.sha256 == null || hex == apk.sha256)
    }

    /** Opens the system install confirmation; returns false if no installer could be launched. */
    fun install(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}$AUTHORITY_SUFFIX", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }.isSuccess

    fun clearDownloads() {
        updatesDir.deleteRecursively()
    }

    private companion object {
        const val UPDATES_DIR = "updates"
        const val APK_FILE_NAME = "HPre-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = ".updates"
    }
}
