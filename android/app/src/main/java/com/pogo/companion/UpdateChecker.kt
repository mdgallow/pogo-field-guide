package com.pogo.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from the published site. On launch it fetches version.json, and when that
 * lists a newer versionCode it offers the update, downloads the APK to the cache folder and
 * hands it to Android's package installer (the user still confirms the install).
 *
 * The APK must be signed with the same key as the installed app or Android refuses it, so
 * this only works once CI signs with the permanent key (tools/setup-signing.sh).
 * When the app moves to the Play Store this class goes away; Play handles updates.
 */
object UpdateChecker {
    private const val TAG = "PogoUpdate"
    private const val BASE_URL = "https://mdgallow.github.io/pogo-field-guide/"
    private const val MANIFEST = "version.json"
    private const val PREFS = "pogo_update"
    private const val PREF_SKIPPED = "skipped_version"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var checkedThisLaunch = false

    /** Quiet check once per process; call from onCreate. */
    fun checkOnLaunch(activity: Activity) {
        if (checkedThisLaunch) return
        checkedThisLaunch = true
        check(activity, manual = false)
    }

    fun check(activity: Activity, manual: Boolean) {
        io.execute {
            try {
                val json = JSONObject(URL(BASE_URL + MANIFEST + "?t=" + System.currentTimeMillis()).readText(8000))
                val code = json.getInt("versionCode")
                val name = json.optString("versionName", code.toString())
                val apk = json.optString("apk", "pogo-companion.apk")
                val notes = json.optString("notes", "")
                val skipped = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getInt(PREF_SKIPPED, -1)
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) { checkedThisLaunch = false; return@post }
                    when {
                        code > BuildConfig.VERSION_CODE && (manual || code != skipped) ->
                            offer(activity, code, name, BASE_URL + apk + "?v=" + code, notes, code)
                        manual -> Toast.makeText(activity, "You have the latest version (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: $e")
                if (manual) main.post { Toast.makeText(activity, "Could not reach the update site", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun offer(activity: Activity, code: Int, name: String, apkUrl: String, notes: String, expectedCode: Int) {
        AlertDialog.Builder(activity)
            .setTitle("Update to v$name")
            .setMessage((if (notes.isBlank()) "" else notes + "\n\n") +
                "You have v${BuildConfig.VERSION_NAME}. The download page opens in your browser; install the APK from there. Your log and settings are kept.")
            .setPositiveButton("Open download page") { _, _ ->
                // The app never downloads or installs packages itself (that capability is one of the
                // traits Play Protect flags); the browser handles the file like any other download.
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Could not open the browser: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ ->
                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putInt(PREF_SKIPPED, code).apply()
            }
            .show()
    }

    private fun URL.readText(timeoutMs: Int): String {
        val conn = openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
