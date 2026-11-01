package app.motioncue.probe

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional independent engine check; a synthesis file does not establish audible playback. */
internal class KoreanTtsProbe(
    private val instrumentation: Instrumentation,
    private val context: Context,
    private val runId: String,
    private val emit: (String, JSONObject) -> Unit,
) {
    fun run() {
        val initialized = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var initializationResult: Int? = null
        var languageResult: Int? = null
        var done = false
        val utteranceId = "motioncue-probe-$runId"
        try {
            instrumentation.runOnMainSync {
                tts = TextToSpeech(context) { result ->
                    initializationResult = result
                    emit("tts_init", JSONObject().put("result", result))
                    initialized.countDown()
                }
            }
            if (!initialized.await(20L, TimeUnit.SECONDS)) {
                emit("tts_result", JSONObject().put("status", "initialization_timeout"))
                return
            }
            if (initializationResult != TextToSpeech.SUCCESS) {
                emit("tts_result", JSONObject().put("status", "initialization_failed").put("result", initializationResult))
                return
            }
            val engine = requireNotNull(tts)
            instrumentation.runOnMainSync {
                languageResult = engine.setLanguage(Locale.KOREAN)
                emit("tts_engine", JSONObject().put("defaultEngine", engine.defaultEngine)
                    .put("koreanAvailability", engine.isLanguageAvailable(Locale.KOREAN))
                    .put("setLanguageResult", languageResult)
                    .put("engines", JSONArray(engine.engines.map {
                        JSONObject().put("name", it.name).put("label", it.label)
                    })))
            }
            if (requireNotNull(languageResult) < TextToSpeech.LANG_AVAILABLE) {
                emit("tts_result", JSONObject().put("status", "korean_unavailable"))
                return
            }
            val directory = File(context.filesDir, "verification")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create TTS verification directory" }
            val output = File(directory, "korean-$runId.wav")
            check(!output.exists()) { "TTS output already exists" }
            instrumentation.runOnMainSync {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        emit("tts_utterance", JSONObject().put("event", "onStart").put("id", id))
                    }
                    override fun onDone(id: String?) {
                        if (id != utteranceId) return
                        done = true
                        emit("tts_utterance", JSONObject().put("event", "onDone").put("id", id))
                        completed.countDown()
                    }
                    @Deprecated("SDK compatibility callback")
                    override fun onError(id: String?) = failed(id, null)
                    override fun onError(id: String?, errorCode: Int) = failed(id, errorCode)
                    override fun onStop(id: String?, interrupted: Boolean) {
                        emit("tts_utterance", JSONObject().put("event", "onStop").put("id", id).put("interrupted", interrupted))
                        if (id == utteranceId) completed.countDown()
                    }
                    private fun failed(id: String?, code: Int?) {
                        emit("tts_utterance", JSONObject().put("event", "onError").put("id", id)
                            .put("code", code ?: JSONObject.NULL))
                        if (id == utteranceId) completed.countDown()
                    }
                })
                val request = engine.synthesizeToFile("편안하게 호흡하고 먼 곳을 바라보세요.", Bundle(), output, utteranceId)
                emit("tts_request", JSONObject().put("returnCode", request).put("path", output.absolutePath))
                if (request != TextToSpeech.SUCCESS) completed.countDown()
            }
            val signaled = completed.await(30L, TimeUnit.SECONDS)
            val data = JSONObject().put("status", if (signaled && done) "synthesis_done" else if (signaled) "synthesis_failed" else "synthesis_timeout")
                .put("path", output.absolutePath).put("exists", output.exists()).put("bytes", output.length())
                .put("audiblePlaybackVerified", false)
            if (output.isFile && signaled && done) {
                val digest = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                data.put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                val prefix = ByteArray(12)
                val read = output.inputStream().use { it.read(prefix) }
                data.put("riffWaveHeader", read == 12 && String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(prefix, 8, 4, Charsets.US_ASCII) == "WAVE")
            }
            emit("tts_result", data)
        } catch (error: Exception) {
            emit("tts_result", JSONObject().put("status", "probe_error").put("message", error.message))
        } finally {
            instrumentation.runOnMainSync { tts?.stop(); tts?.shutdown() }
        }
    }
}
