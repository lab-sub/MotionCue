package app.motioncue.recovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

interface VoiceGuide {
    fun speak(text: String)
    fun close()
}

class AndroidTtsVoiceGuide(context: Context, private val log: (String) -> Unit) : VoiceGuide {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    private var engine: TextToSpeech? = null
    private var pendingText: String? = null
    init {
        onMain { initialize(context.applicationContext) }
    }

    private fun initialize(context: Context) {
        if (closed) return
        try {
            engine = TextToSpeech(context) { status ->
                // Posting also handles an immediate callback before engine assignment completes.
                main.post {
                    if (closed) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        pendingText = null
                        log("Android 음성 안내 초기화 실패: $status · 화면 안내 사용")
                        return@post
                    }
                    try {
                        val result = engine?.setLanguage(Locale.KOREA)
                        ready = result != null && result >= TextToSpeech.LANG_AVAILABLE
                        if (ready) {
                            log("Android 음성 안내 초기화 성공 · ko-KR")
                            val pending = pendingText
                            pendingText = null
                            pending?.let(::speak)
                        } else {
                            pendingText = null
                            log("한국어 음성 데이터 미지원: $result · 화면 안내 사용")
                        }
                    } catch (e: Exception) { log("Android 음성 언어 설정 실패: ${e.message}") }
                }
            }
        } catch (e: Exception) { log("음성 안내 사용 불가: ${e.message}") }
    }

    override fun speak(text: String) = onMain {
        if (closed || text.isBlank()) return@onMain
        if (!ready) {
            pendingText = text
            return@onMain
        }
        try {
            if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "motioncue-recovery") == TextToSpeech.ERROR) {
                log("음성 안내 재생 실패")
            }
        } catch (e: Exception) { log("음성 안내 실패: ${e.message}") }
    }

    override fun close() = onMain {
        closed = true
        ready = false
        pendingText = null
        try { engine?.stop() } catch (e: Exception) { log("음성 재생 중지 실패: ${e.message}") }
        try { engine?.shutdown() } catch (e: Exception) { log("음성 엔진 종료 실패: ${e.message}") }
        engine = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
