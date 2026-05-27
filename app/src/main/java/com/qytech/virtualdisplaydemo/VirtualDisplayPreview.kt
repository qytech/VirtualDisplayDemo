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
                setOnTouchListener { v, event ->
                    // 必须通过 obtain 复制一份事件，因为原始 event 会被 View 系统立刻回收
                    // 这里的 obtain 会在 ProjectionService.injectEvent 中被 recycle
                    onTouch(MotionEvent.obtain(event))
                    v.performClick()
                    true
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
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
