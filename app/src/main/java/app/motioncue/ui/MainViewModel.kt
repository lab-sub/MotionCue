package app.motioncue.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.motioncue.session.CuePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // This scope survives onCleared long enough to finish bounded SDK restoration.
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val pipeline = CuePipeline(application, sessionScope)
    val state = pipeline.state

    override fun onCleared() {
        sessionScope.launch {
            try { pipeline.close() } finally { sessionScope.cancel() }
        }
        super.onCleared()
    }
}
