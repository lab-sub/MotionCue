package app.motioncue.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MotionCueApp(model: MainViewModel, onPlayPause: () -> Unit = model.pipeline::playPause) {
    val state by model.state.collectAsStateWithLifecycle()
    val pipeline = model.pipeline
    val actions = remember(pipeline, onPlayPause) {
        MotionCueActions(
            onSelectMode = pipeline::selectMode,
            onPlayPause = onPlayPause,
            onRestart = pipeline::restart,
            onApplySettings = pipeline::configure,
            onToggleRecovery = pipeline::toggleRecovery,
        )
    }
    MotionCueScreen(state, actions)
}
