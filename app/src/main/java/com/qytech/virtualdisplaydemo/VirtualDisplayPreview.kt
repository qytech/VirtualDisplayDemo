package com.qytech.virtualdisplaydemo

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VirtualDisplayPreview(
    onSurfaceCreated: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    onTouch: (MotionEvent) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                setOnTouchListener { _, event ->
                    // Capture local touch event and pass to handler
                    onTouch(MotionEvent.obtain(event))
                    true
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        // 关键修复：强制设置缓冲区的宽高为虚拟分辨率，防止画面偏移或截断
                        surfaceTexture.setDefaultBufferSize(720, 1280)
                        onSurfaceCreated(Surface(surfaceTexture))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
