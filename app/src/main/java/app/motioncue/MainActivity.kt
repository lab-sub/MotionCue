package app.motioncue

import android.os.Bundle
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import app.motioncue.session.SourceMode
import app.motioncue.ui.MainViewModel
import app.motioncue.ui.MotionCueApp

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private var pendingLiveStart = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        model.pipeline.reportPermissionResult(grants.isNotEmpty() && grants.values.all { it })
        val start = pendingLiveStart
        pendingLiveStart = false
        // Registry results may arrive inside super.onStart, before our foreground flag is set.
        window.decorView.post {
            if (start && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                model.state.value.foreground && model.state.value.mode == SourceMode.LIVE && !model.state.value.source.playing) {
                model.pipeline.playPause()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLiveStart = savedInstanceState?.getBoolean("pendingLiveStart") ?: false
        setContent { MotionCueApp(model, onPlayPause = ::playPauseWithPermissions) }
    }
    override fun onStart() { super.onStart(); model.pipeline.setForeground(true) }
    override fun onStop() { model.pipeline.setForeground(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingLiveStart", pendingLiveStart)
        super.onSaveInstanceState(outState)
    }

    private fun playPauseWithPermissions() {
        if (model.state.value.mode != SourceMode.LIVE || model.state.value.source.playing) {
            model.pipeline.playPause()
            return
        }
        // Vehicle permissions are normal on Pleos; FUSED_LOCATION is dangerous on the v2.1 image.
        // Inspect installed definitions instead of assuming all vendor permissions share one level.
        val missing = LIVE_PERMISSIONS.filter { name ->
            val info = try { packageManager.getPermissionInfo(name, 0) } catch (_: PackageManager.NameNotFoundException) { null }
            info != null && info.protection == PermissionInfo.PROTECTION_DANGEROUS &&
                checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) model.pipeline.playPause()
        else {
            pendingLiveStart = true
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private companion object {
        val LIVE_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CONTROL_CAR_LIGHTS", "pleos.car.permission.CAR_CLIMATE",
            "pleos.car.permission.CONTROL_CAR_CLIMATE", "pleos.car.permission.CAR_WINDOWS",
            "pleos.car.permission.CONTROL_CAR_WINDOWS",
        )
    }
}
