package app.motioncue.recovery

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Pleos 3.3.1 on-device synthesis; server credentials are neither read nor required. */
class PleosVoiceGuide(context: Context, private val log: (String) -> Unit) : VoiceGuide {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var fallback: VoiceGuide? = null
    private var ready = false
    private var closed = false
    private var usingFallback = false
    private var listenerAdded = false
    private var pendingText: String? = null
    private var activeText: String? = null
    private var playbackStarted = false

    private val initializeDeadline = Runnable {
        if (!closed && !ready && !usingFallback) fail("초기화 시간 초과(10초)")
    }
    private val speechDeadline = Runnable {
        if (!closed && !usingFallback && activeText != null) fail("재생 완료 콜백 시간 초과(20초)")
    }
    private val events = object : EventListener {
        override fun onReady() = onEvent { log("Pleos TTS 준비 · ON_DEVICE") }
        override fun onStart() = onEvent {
            playbackStarted = true
            log("Pleos TTS 재생 시작")
        }
        override fun onDone() = onEvent {
            log("Pleos TTS 재생 완료")
            finishSpeech()
        }
        override fun onStop() = onEvent {
            log("Pleos TTS 재생 중지")
            finishSpeech()
        }
        override fun onError(errorType: ErrorType) = onEvent {
            fail("합성 실패: ${errorType.name}")
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        onMain { initialize() }
    }

    override fun speak(text: String) = onMain {
        if (closed || text.isBlank()) return@onMain
        if (usingFallback) {
            fallback?.speak(text)
        } else if (text != activeText) {
            // Keep only the latest queued guidance and never overlap SDK requests.
            pendingText = text
            drain()
        }
    }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        ready = false
        pendingText = null
        activeText = null
        main.removeCallbacks(initializeDeadline)
        main.removeCallbacks(speechDeadline)
        releaseEngine()
        fallback?.close()
        fallback = null
    }

    private fun initialize() {
        if (closed) return
        if (context.checkSelfPermission(TTS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            fail("권한 없음: $TTS_PERMISSION")
            return
        }
        try {
            val candidate = TextToSpeech(context, Mode.ON_DEVICE)
            engine = candidate
            main.postDelayed(initializeDeadline, INITIALIZE_TIMEOUT_MS)
            candidate.initialize(object : OnInitializeListener {
                override fun onSuccess() = onEvent {
                    try {
                        candidate.addEventListener(events)
                        listenerAdded = true
                        candidate.setLocale(Locale.KOREA)
                        ready = true
                        main.removeCallbacks(initializeDeadline)
                        log("Pleos TTS 초기화 성공 · ON_DEVICE · ko-KR")
                        drain()
                    } catch (e: Exception) {
                        fail("설정 실패: ${e.message}")
                    }
                }
                override fun onFailure(errorType: ErrorType) = onEvent {
                    fail("초기화 실패: ${errorType.name}")
                }
            })
        } catch (e: Exception) {
            fail("초기화 예외: ${e.message}")
        }
    }

    private fun drain() {
        if (!ready || closed || usingFallback || activeText != null) return
        val text = pendingText ?: return
        val current = engine ?: return
        pendingText = null
        activeText = text
        playbackStarted = false
        main.postDelayed(speechDeadline, SPEECH_TIMEOUT_MS)
        try {
            current.speak(text, true)
            log("Pleos TTS 합성 요청")
        } catch (e: Exception) {
            fail("합성 요청 예외: ${e.message}")
        }
    }

    private fun finishSpeech() {
        main.removeCallbacks(speechDeadline)
        activeText = null
        playbackStarted = false
        drain()
    }

    private fun fail(reason: String) {
        if (closed || usingFallback) return
        log("Pleos TTS $reason · Android 음성 안내 시도")
        // Do not replay speech that already started; the fallback may repeat audible guidance.
        val retryText = pendingText ?: activeText?.takeUnless { playbackStarted }
        usingFallback = true
        ready = false
        pendingText = null
        activeText = null
        main.removeCallbacks(initializeDeadline)
        main.removeCallbacks(speechDeadline)
        releaseEngine()
        fallback = AndroidTtsVoiceGuide(context, log).also { retryText?.let(it::speak) }
    }

    private fun releaseEngine() {
        val current = engine ?: return
        engine = null
        if (listenerAdded) {
            try { current.removeEventListener(events) }
            catch (e: Exception) { log("Pleos TTS 리스너 해제 실패: ${e.message}") }
        }
        listenerAdded = false
        try { current.stop() }
        catch (e: Exception) { log("Pleos TTS 중지 실패: ${e.message}") }
        try { current.release() }
        catch (e: Exception) { log("Pleos TTS 해제 실패: ${e.message}") }
    }

    // Always post callbacks, including synchronous SDK callbacks, until initialization returns.
    private fun onEvent(block: () -> Unit) {
        main.post { if (!closed && !usingFallback) block() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
        const val INITIALIZE_TIMEOUT_MS = 10_000L
        const val SPEECH_TIMEOUT_MS = 20_000L
    }
}
