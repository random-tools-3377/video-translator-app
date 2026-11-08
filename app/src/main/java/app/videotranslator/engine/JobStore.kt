package app.videotranslator.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import app.videotranslator.whisper.Segment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The translation in progress (or just finished), kept in the app's private no-backup storage so
 * that a run Android stops (its 6-hour limit for background work, low memory, a crash) can carry
 * on where it stopped, and a finished result is still there after the app was closed. Only the
 * one job: it is replaced when another video is started and deleted when the user closes the
 * result. Nothing else can read this storage, and app backup is off.
 */
object JobStore {
    private const val TAG = "VTJobStore"
    private const val FILE = "job.json"
    private const val VERSION = 1

    @Synchronized
    fun save(context: Context, s: JobState) {
        val uri = s.videoUri ?: return
        if (s.phase == Phase.Idle) return
        try {
            val json = JSONObject()
                .put("v", VERSION)
                .put("uri", uri.toString())
                .putOpt("name", s.videoName)
                .putOpt("durationMs", s.durationMs)
                .putOpt("languageCode", s.languageCode)
                .putOpt("languageName", s.languageName)
                .putOpt("languageModel", s.languageModel)
                .put("translatedByLanguageModel", s.translatedByLanguageModel)
                .put("translated", s.translated)
                .put("whisperDone", s.whisperDone)
                .put("complete", s.phase == Phase.Done)
                .put("elapsedMs", ((s.finishedAtMs ?: s.startedAtMs) - s.startedAtMs).coerceAtLeast(0))
                .put("lines", segments(s.lines))
                .put("spoken", segments(s.spoken))
            s.timings?.let {
                json.put(
                    "timings",
                    JSONObject()
                        .put("readAudioMs", it.readAudioMs).put("loadModelMs", it.loadModelMs).put("whisperMs", it.whisperMs)
                        .put("loadLanguageModelMs", it.loadLanguageModelMs).put("languageModelMs", it.languageModelMs),
                )
            }
            val dir = context.noBackupFilesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(File(dir, FILE))) throw IllegalStateException("rename failed")
        } catch (e: Exception) {
            // Never stop a translation over this: it only means a stop could not be recovered.
            Log.w(TAG, "could not keep the work in progress", e)
        }
    }

    /** The kept job, as the screen shows it after the app was closed; null if there is none. */
    @Synchronized
    fun load(context: Context): JobState? {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.isFile) return null
        return try {
            val j = JSONObject(file.readText(Charsets.UTF_8))
            if (j.optInt("v") != VERSION) return null
            val complete = j.optBoolean("complete")
            val elapsed = j.optLong("elapsedMs")
            val timings = j.optJSONObject("timings")?.let {
                Timings(
                    it.optLong("readAudioMs"), it.optLong("loadModelMs"), it.optLong("whisperMs"),
                    it.optLong("loadLanguageModelMs"), it.optLong("languageModelMs"),
                )
            }
            JobState(
                phase = if (complete) Phase.Done else Phase.Cancelled,
                videoUri = Uri.parse(j.getString("uri")),
                videoName = j.optString("name").takeIf { j.has("name") },
                durationMs = if (j.has("durationMs")) j.getLong("durationMs") else null,
                progress = if (complete) 1f else 0f,
                languageCode = j.optString("languageCode").takeIf { j.has("languageCode") },
                languageName = j.optString("languageName").takeIf { j.has("languageName") },
                lines = segments(j.optJSONArray("lines")),
                spoken = segments(j.optJSONArray("spoken")),
                languageModel = j.optString("languageModel").takeIf { j.has("languageModel") },
                translatedByLanguageModel = j.optBoolean("translatedByLanguageModel"),
                translated = j.optInt("translated"),
                whisperDone = j.optBoolean("whisperDone"),
                startedAtMs = 0L,
                finishedAtMs = elapsed,
                timings = if (complete) timings else null,
                restored = true,
            )
        } catch (e: Exception) {
            Log.w(TAG, "the kept work could not be read; removing it", e)
            file.delete()
            null
        }
    }

    @Synchronized
    fun delete(context: Context) {
        File(context.noBackupFilesDir, FILE).delete()
        File(context.noBackupFilesDir, "$FILE.tmp").delete()
    }

    private fun segments(list: List<Segment>): JSONArray {
        val a = JSONArray()
        for (s in list) a.put(JSONArray().put(s.startMs).put(s.endMs).put(s.text))
        return a
    }

    private fun segments(a: JSONArray?): List<Segment> {
        if (a == null) return emptyList()
        val out = ArrayList<Segment>(a.length())
        for (i in 0 until a.length()) {
            val s = a.getJSONArray(i)
            out += Segment(s.getLong(0), s.getLong(1), s.getString(2))
        }
        return out
    }
}
