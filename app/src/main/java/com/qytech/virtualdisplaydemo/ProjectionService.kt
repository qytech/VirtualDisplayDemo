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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class ProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: VirtualPresentation? = null
    private val binder = ProjectionBinder()
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存所有反射对象
    private var inputManager: InputManager? = null
    private var iWindowManager: Any? = null
    private var setDisplayIdMethod: Method? = null
    private var injectInputEventMethod: Method? = null
    private var setFixedToUserRotationMethod: Method? = null
    private var freezeDisplayRotationMethod: Method? = null
    private var setShouldShowSystemDecorsMethod: Method? = null
    private var setShouldShowImeMethod: Method? = null
    private var setDisplayImePolicyMethod: Method? = null
    private var setFocusedDisplayMethod: Method? = null

    var isProjecting by mutableStateOf(false)
        private set

    var isDashboardShowing by mutableStateOf(false)
        private set

    inner class ProjectionBinder : Binder() {
        fun getService(): ProjectionService = this@ProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        prepareReflection()
    }

    private fun prepareReflection() {
        try {
            // 1. MotionEvent
            setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.java)
            
            // 2. InputManager
            inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
            injectInputEventMethod = inputManager?.javaClass?.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )

            // 3. IWindowManager
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val windowManagerBinder = getService.invoke(null, "window") as IBinder
            val iWindowManagerStub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = iWindowManagerStub.getMethod("asInterface", IBinder::class.java)
            iWindowManager = asInterface.invoke(null, windowManagerBinder)

            val iwmClass = iWindowManager?.javaClass
            setFixedToUserRotationMethod = try { iwmClass?.getMethod("setFixedToUserRotation", Int::class.java, Int::class.java) } catch (e: Exception) { null }
            freezeDisplayRotationMethod = iwmClass?.getMethod("freezeDisplayRotation", Int::class.java, Int::class.java)
            setShouldShowSystemDecorsMethod = try { iwmClass?.getMethod("setShouldShowSystemDecors", Int::class.java, Boolean::class.java) } catch (e: Exception) { null }
            setShouldShowImeMethod = try { iwmClass?.getMethod("setShouldShowIme", Int::class.java, Boolean::class.java) } catch (e: Exception) { null }
            setDisplayImePolicyMethod = try { iwmClass?.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java) } catch (e: Exception) { null }
            setFocusedDisplayMethod = try { iwmClass?.getMethod("setFocusedDisplay", Int::class.java) } catch (e: Exception) { null }

            Log.d("ProjectionService", "Reflection methods cached successfully")
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to prepare reflection", e)
        }
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
            val channel = NotificationChannel(channelId, "Screen Projection", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Virtual Display Demo")
            .setContentText("Screen projection is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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

        if (virtualDisplay != null) {
            val currentDisplay = virtualDisplay?.display
            @Suppress("DEPRECATION")
            if (currentDisplay?.width != width || currentDisplay?.height != height) {
                presentation?.dismiss()
                presentation = null
                virtualDisplay?.release()
                virtualDisplay = null
            } else {
                virtualDisplay?.surface = surface
                return
            }
        }

        // 2 (PRESENTATION) | 1 (PUBLIC) | 64 (SUPPORTS_TOUCH) | 512 (SYSTEM_DECORATIONS) | 1024 (TRUSTED) | 2048 (OWN_ORIENTATION)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                (1 shl 6) or (1 shl 9) or (1 shl 10) or (1 shl 11)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VirtualDisplayDemo", width, height, densityDpi, flags, surface, null, null
        )

        virtualDisplay?.display?.let { display ->
            configureDisplay(display.displayId)
            showDashboard()
        }
    }

    private fun configureDisplay(displayId: Int) {
        // 在后台线程配置系统属性，防止阻塞主线程导致的 ANR
        serviceScope.launch {
            try {
                // 1. 强制设置旋转锁定
                setFixedToUserRotationMethod?.invoke(iWindowManager, displayId, 2)
                freezeDisplayRotationMethod?.invoke(iWindowManager, displayId, 0)

                // 2. 开启系统装饰
                setShouldShowSystemDecorsMethod?.invoke(iWindowManager, displayId, true)

                // 3. 启用 IME
                if (setShouldShowImeMethod != null) {
                    setShouldShowImeMethod?.invoke(iWindowManager, displayId, true)
                } else {
                    setDisplayImePolicyMethod?.invoke(iWindowManager, displayId, 0)
                }
                
                // 4. 显式请求焦点
                setFocusedDisplayMethod?.invoke(iWindowManager, displayId)
                
                Log.d("ProjectionService", "Display $displayId configured successfully")
            } catch (e: Exception) {
                Log.e("ProjectionService", "Failed to configure display $displayId", e)
            }
        }
    }

    fun showDashboard() {
        val display = virtualDisplay?.display ?: return
        if (presentation == null) {
            presentation = VirtualPresentation(this, display)
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

        hideDashboard()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        try {
            startActivity(launchIntent, options.toBundle())
            // 每次启动 APP 重新强制一次焦点
            serviceScope.launch { setFocusedDisplayMethod?.invoke(iWindowManager, displayId) }
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to launch app", e)
        }
    }

    fun injectEvent(event: MotionEvent) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        val im = inputManager ?: return
        val injectMethod = injectInputEventMethod ?: return
        val setIdMethod = setDisplayIdMethod ?: return

        serviceScope.launch {
            try {
                setIdMethod.invoke(event, displayId)
                injectMethod.invoke(im, event, 0)
            } catch (e: Exception) {
                Log.e("ProjectionService", "Injection failed", e)
            } finally {
                event.recycle()
            }
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
