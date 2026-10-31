package com.pixelx.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PixelXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

object Store {
    lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("pixelx", Context.MODE_PRIVATE)
    }

    var termsAccepted: Boolean
        get() = sp.getBoolean("terms", false)
        set(v) { sp.edit().putBoolean("terms", v).apply() }

    var userKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", "")?.ifBlank { null } ?: BuildConfig.GROQ_MODEL
        set(v) { sp.edit().putString("model", v.trim()).apply() }

    fun activeKey(): String = userKey.ifBlank { BuildConfig.GROQ_API_KEY }

    /** Wipes projects, videos, cache and settings. Credit usage is deliberately kept. */
    fun wipe(ctx: Context) {
        File(ctx.filesDir, "videos").deleteRecursively()
        File(ctx.filesDir, "thumbs").deleteRecursively()
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val keep = sp.getString("credit_stamps", "") ?: ""
        sp.edit().clear().putString("credit_stamps", keep).apply()
    }
}

object Credits {
    const val MAX = 5
    private const val WINDOW = 24L * 60 * 60 * 1000

    private fun stamps(): MutableList<Long> {
        val now = System.currentTimeMillis()
        val raw = Store.sp.getString("credit_stamps", "") ?: ""
        // a stamp from the "future" (clock rolled back) still counts as used
        return raw.split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < WINDOW }.toMutableList()
    }

    fun remaining(): Int = (MAX - stamps().size).coerceAtLeast(0)

    /** Milliseconds until the next credit returns (0 if one is available now). */
    fun nextRefillMs(): Long {
        val s = stamps()
        if (s.size < MAX) return 0L
        val oldest = s.minOrNull() ?: return 0L
        return (oldest + WINDOW - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun consume() {
        val s = stamps()
        s.add(System.currentTimeMillis())
        Store.sp.edit().putString("credit_stamps", s.joinToString(",")).apply()
    }

    fun refillText(): String {
        val ms = nextRefillMs()
        val m = ms / 60000
        return "${m / 60}h ${m % 60}m"
    }
}

data class Project(
    val id: String, val createdAt: Long, val prompt: String, val seconds: Int,
    val video: String, val thumb: String, val summary: String, val w: Int, val h: Int
)

object History {
    private fun load(): JSONArray = try { JSONArray(Store.sp.getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    fun all(): List<Project> {
        val a = load()
        val out = ArrayList<Project>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val p = Project(
                o.optString("id"), o.optLong("t"), o.optString("prompt"), o.optInt("s"),
                o.optString("video"), o.optString("thumb"), o.optString("sum"), o.optInt("w", 16), o.optInt("h", 9)
            )
            if (File(p.video).exists()) out.add(p)
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun add(p: Project) {
        val a = load()
        a.put(JSONObject().put("id", p.id).put("t", p.createdAt).put("prompt", p.prompt).put("s", p.seconds)
            .put("video", p.video).put("thumb", p.thumb).put("sum", p.summary).put("w", p.w).put("h", p.h))
        Store.sp.edit().putString("history", a.toString()).apply()
    }

    fun remove(p: Project) {
        val a = load()
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") != p.id) n.put(o)
        }
        Store.sp.edit().putString("history", n.toString()).apply()
        File(p.video).delete()
        File(p.thumb).delete()
    }
}

class JobSpec(val imagePath: String, val prompt: String, val seconds: Int)

object Hub {
    sealed class State {
        object Idle : State()
        class Running(val stage: String, val progress: Float, val etaSec: Int, val detail: String) : State()
        class Done(val project: Project) : State()
        class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    @Volatile var pending: JobSpec? = null
}

object Saver {
    fun saveVideo(ctx: Context, file: File): Boolean {
        val name = "PIXEL-X_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PIXEL-X")
                v.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
                val done = ContentValues()
                done.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, done, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PIXEL-X")
                dir.mkdirs()
                val dst = File(dir, name)
                file.copyTo(dst, true)
                MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
