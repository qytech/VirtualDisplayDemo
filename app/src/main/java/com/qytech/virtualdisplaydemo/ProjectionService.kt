package com.qytech.virtualdisplaydemo

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat

class ProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: PortraitPresentation? = null
    private val binder = ProjectionBinder()

    var isProjecting by mutableStateOf(false)
        private set

    var isDashboardShowing by mutableStateOf(false)
        private set

    inner class ProjectionBinder : Binder() {
        fun getService(): ProjectionService = this@ProjectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            startForegroundService(resultCode, resultData)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(resultCode: Int, resultData: Intent) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "projection_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Projection",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Virtual Display Demo")
            .setContentText("Screen projection is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        isProjecting = true

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                isProjecting = false
                mediaProjection = null
                stopSelf()
            }
        }, null)
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun stopProjection() {
        isProjecting = false
        hideDashboard()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun createVirtualDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        if (mediaProjection == null) return

        // Fix: Don't recreate VirtualDisplay if it already exists, just update surface
        if (virtualDisplay != null) {
            Log.d("ProjectionService", "VirtualDisplay already exists, updating surface")
            virtualDisplay?.surface = surface
            // Re-apply rotation lock on new surface if needed
            virtualDisplay?.display?.let { freezeRotation(it.displayId) }
            return
        }

        Log.d("ProjectionService", "Creating new VirtualDisplay: ${width}x${height} @ $densityDpi")

        // FLAG_OWN_ORIENTATION = 1 << 11 (2048)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                (1 shl 11)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VirtualDisplayDemo",
            width,
            height,
            densityDpi,
            flags,
            surface,
            null,
            null
        )

        virtualDisplay?.display?.let { display ->
            Log.d("ProjectionService", "VirtualDisplay created. DisplayId: ${display.displayId}")
            freezeRotation(display.displayId)
            showDashboard()
        }
    }

    private fun freezeRotation(displayId: Int) {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val windowManagerBinder = getService.invoke(null, "window") as IBinder

            val iWindowManagerStub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = iWindowManagerStub.getMethod("asInterface", IBinder::class.java)
            val iWindowManager = asInterface.invoke(null, windowManagerBinder)

            // Strengthen Portrait Lock: Set Fixed to User Rotation
            // FIXED_TO_USER_ROTATION_ENABLED = 2
            try {
                val setFixedToUserRotation = iWindowManager.javaClass.getMethod(
                    "setFixedToUserRotation",
                    Int::class.java,
                    Int::class.java
                )
                setFixedToUserRotation.invoke(iWindowManager, displayId, 2)
                Log.d("ProjectionService", "Set FixedToUserRotation ENABLED for display $displayId")
            } catch (e: Exception) {
                Log.w("ProjectionService", "setFixedToUserRotation not available or failed", e)
            }

            val freezeDisplayRotation = iWindowManager.javaClass.getMethod(
                "freezeDisplayRotation",
                Int::class.java,
                Int::class.java
            )
            freezeDisplayRotation.invoke(iWindowManager, displayId, 0) // Surface.ROTATION_0

            Log.d(
                "ProjectionService",
                "Successfully frozen display $displayId in Portrait (ROTATION_0)"
            )
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to freeze display rotation via reflection", e)
        }
    }

    fun showDashboard() {
        val display = virtualDisplay?.display ?: return
        if (presentation == null) {
            freezeRotation(display.displayId) // Ensure rotation is still frozen
            presentation = PortraitPresentation(this, display)
            presentation?.show()
            isDashboardShowing = true
        }
    }

    fun hideDashboard() {
        presentation?.dismiss()
        presentation = null
        isDashboardShowing = false
    }

    fun launchAppOnVirtualDisplay(packageName: String) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return

        freezeRotation(displayId) // Ensure rotation is frozen before launching
        hideDashboard()

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic().apply {
            // This API requires system signature or special permissions
            launchDisplayId = displayId
        }

        try {
            startActivity(launchIntent, options.toBundle())
            Log.d("ProjectionService", "Launched $packageName on display $displayId")
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to launch app on virtual display", e)
        }
    }

    fun injectEvent(event: MotionEvent) {
        val displayId = virtualDisplay?.display?.displayId ?: return

        try {
            // MotionEvent.setDisplayId is a hidden API. We use reflection.
            val setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.java)
            setDisplayIdMethod.invoke(event, displayId)

            // InputManager.injectInputEvent is a system API.
            val im = getSystemService(Context.INPUT_SERVICE) as InputManager
            val injectInputEventMethod = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)

            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            injectInputEventMethod.invoke(im, event, 0)
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to inject event into display $displayId", e)
        }
    }

    override fun onDestroy() {
        hideDashboard()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
