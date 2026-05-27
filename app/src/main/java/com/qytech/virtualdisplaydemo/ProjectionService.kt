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
    
    // 异步执行注入，避免阻塞主线程
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存反射方法以提高性能
    private var setDisplayIdMethod: Method? = null
    private var injectInputEventMethod: Method? = null
    private var inputManager: InputManager? = null

    var isProjecting by mutableStateOf(false)
        private set

    var isDashboardShowing by mutableStateOf(false)
        private set

    inner class ProjectionBinder : Binder() {
        fun getService(): ProjectionService = this@ProjectionService
    }

    override fun onCreate() {
        super.onCreate()
        try {
            setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.java)
            inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
            injectInputEventMethod = inputManager?.javaClass?.getMethod(
                "injectInputEvent", 
                InputEvent::class.java, 
                Int::class.java
            )
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to cache reflection methods", e)
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

        // If resolution changed, we must release and recreate the VirtualDisplay
        if (virtualDisplay != null) {
            val currentDisplay = virtualDisplay?.display
            if (currentDisplay?.width != width || currentDisplay?.height != height) {
                Log.d("ProjectionService", "Resolution changed from ${currentDisplay?.width}x${currentDisplay?.height} to ${width}x${height}. Recreating...")
                presentation?.dismiss()
                presentation = null
                virtualDisplay?.release()
                virtualDisplay = null
            } else {
                Log.d("ProjectionService", "Updating VirtualDisplay surface")
                virtualDisplay?.surface = surface
                virtualDisplay?.display?.let { freezeRotation(it.displayId) }
                return
            }
        }

        Log.d("ProjectionService", "Creating VirtualDisplay: ${width}x${height} @ $densityDpi")

        // 2 (PRESENTATION) | 1 (PUBLIC) | 64 (SUPPORTS_TOUCH) | 512 (SYSTEM_DECORATIONS) | 1024 (TRUSTED)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                (1 shl 6) or  // 64 (SUPPORTS_TOUCH)
                (1 shl 9) or  // 512 (SYSTEM_DECORATIONS)
                (1 shl 10) or // 1024 (TRUSTED)
                (1 shl 11)    // 2048 (OWN_ORIENTATION)

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

            // 1. 强制设置竖屏锁定
            try {
                val setFixedToUserRotation = iWindowManager.javaClass.getMethod(
                    "setFixedToUserRotation",
                    Int::class.java,
                    Int::class.java
                )
                setFixedToUserRotation.invoke(iWindowManager, displayId, 2)
            } catch (e: Exception) {
                Log.w("ProjectionService", "setFixedToUserRotation failed", e)
            }

            // 2. 冻结旋转
            val freezeDisplayRotation = iWindowManager.javaClass.getMethod(
                "freezeDisplayRotation",
                Int::class.java,
                Int::class.java
            )
            freezeDisplayRotation.invoke(iWindowManager, displayId, 0)

            // 3. 开启副屏系统装饰
            try {
                val setShouldShowSystemDecors = iWindowManager.javaClass.getMethod(
                    "setShouldShowSystemDecors",
                    Int::class.java,
                    Boolean::class.java
                )
                setShouldShowSystemDecors.invoke(iWindowManager, displayId, true)
            } catch (e: Exception) {
                Log.w("ProjectionService", "setShouldShowSystemDecors failed, ignoring...")
            }

            // 4. 关键修复：兼容新旧版本的 IME 策略
            try {
                // 优先尝试旧版 setShouldShowIme
                val setShouldShowIme = iWindowManager.javaClass.getMethod(
                    "setShouldShowIme",
                    Int::class.java,
                    Boolean::class.java
                )
                setShouldShowIme.invoke(iWindowManager, displayId, true)
                Log.d("ProjectionService", "Enabled IME via setShouldShowIme")
            } catch (e: Exception) {
                try {
                    // 尝试新版 setDisplayImePolicy (0 = SHOW, 1 = HIDE)
                    val setDisplayImePolicy = iWindowManager.javaClass.getMethod(
                        "setDisplayImePolicy",
                        Int::class.java,
                        Int::class.java
                    )
                    setDisplayImePolicy.invoke(iWindowManager, displayId, 0)
                    Log.d("ProjectionService", "Enabled IME via setDisplayImePolicy")
                } catch (e2: Exception) {
                    Log.e("ProjectionService", "All IME activation methods failed", e2)
                }
            }

            // 5. 显式请求副屏获取焦点
            try {
                val setFocusedDisplay = iWindowManager.javaClass.getMethod("setFocusedDisplay", Int::class.java)
                setFocusedDisplay.invoke(iWindowManager, displayId)
                Log.d("ProjectionService", "Set focus to display $displayId")
            } catch (e: Exception) {
                Log.w("ProjectionService", "setFocusedDisplay not available")
            }

        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to configure window manager", e)
        }
    }

    fun showDashboard() {
        val display = virtualDisplay?.display ?: return
        if (presentation == null) {
            freezeRotation(display.displayId)
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

        freezeRotation(displayId)
        hideDashboard()

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }

        try {
            startActivity(launchIntent, options.toBundle())
        } catch (e: Exception) {
            Log.e("ProjectionService", "Failed to launch app", e)
        }
    }

    fun injectEvent(event: MotionEvent) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        val im = inputManager ?: return
        val injectMethod = injectInputEventMethod ?: return
        val setIdMethod = setDisplayIdMethod ?: return

        // 在 IO 线程执行注入，并在完成后回收 event
        serviceScope.launch {
            try {
                setIdMethod.invoke(event, displayId)
                // INJECT_INPUT_EVENT_MODE_ASYNC = 0
                injectMethod.invoke(im, event, 0)
            } catch (e: Exception) {
                Log.e("ProjectionService", "Injection failed", e)
            } finally {
                // 必须回收，否则会造成内存泄漏和事件队列阻塞
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
