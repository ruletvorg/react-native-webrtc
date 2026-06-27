package com.oney.WebRTCModule

import android.content.Context
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import androidx.core.view.ViewCompat
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon.RendererEvents
import org.webrtc.RendererCommon.ScalingType
import org.webrtc.RendererCommon.VideoLayoutMeasure
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * TextureView-based WebRTC renderer for layouts that need Android clipping,
 * transforms, opacity, or rounded masks. SurfaceViewRenderer remains the default
 * because it is faster and more battle-tested for full-screen video.
 */
class VideoTextureViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), VideoSink, SurfaceTextureListener {
    companion object {
        private const val TAG = "VideoTextureViewRenderer"
    }

    private val resourceName: String = getResourceName()
    private val videoLayoutMeasure = VideoLayoutMeasure()
    private val eglRenderer: EglRenderer = EglRenderer(resourceName)
    private val uiThreadHandler = Handler(Looper.getMainLooper())
    private val activeEglSurfaceGeneration = AtomicInteger(0)
    private val lastRenderedGeneration = AtomicInteger(0)
    private val lastTextureUpdateGeneration = AtomicInteger(0)
    private val renderListener = EglRenderer.RenderListener {
        val generation = activeEglSurfaceGeneration.get()
        if (generation > 0) {
            lastRenderedGeneration.set(generation)
            notifyTextureUpdated()
        }
    }

    private var rendererEvents: RendererEvents? = null
    private var textureUpdatedListener: Runnable? = null
    private var isFirstFrameRendered = false
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var released = true
    private var hasEglSurface = false
    private var eglSurfaceWidth = 0
    private var eglSurfaceHeight = 0
    private var surfaceGeneration = 0
    private var renderListenerAdded = false
    private var scalingType: ScalingType? = null
    private var resizeTransformPending = false
    private var resizeTransformTargetGeneration = 0
    private var suppressNextResizeTransform = false
    private val textureTransform = Matrix()

    init {
        surfaceTextureListener = this
    }

    fun init(
        sharedContext: EglBase.Context,
        rendererEvents: RendererEvents,
    ) {
        ThreadUtils.checkIsOnMainThread()
        if (!released) {
            release()
        }
        released = false
        this.rendererEvents = rendererEvents
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        if (!renderListenerAdded) {
            eglRenderer.addRenderListener(renderListener)
            renderListenerAdded = true
        }
        if (isAvailable) {
            scheduleCreateEglSurface(surfaceTexture)
        }
    }

    fun release() {
        ThreadUtils.checkIsOnMainThread()
        if (released) return
        surfaceGeneration++
        releaseEglSurface()
        released = true
        hasEglSurface = false
        eglSurfaceWidth = 0
        eglSurfaceHeight = 0
        activeEglSurfaceGeneration.set(0)
        lastRenderedGeneration.set(0)
        lastTextureUpdateGeneration.set(0)
        isFirstFrameRendered = false
        rotatedFrameWidth = 0
        rotatedFrameHeight = 0
        frameRotation = 0
        layoutWidth = 0
        layoutHeight = 0
        clearResizeTransform()
        rendererEvents = null
        eglRenderer.release()
        renderListenerAdded = false
    }

    fun clearImage() {
        if (!released) {
            eglRenderer.clearImage()
        }
    }

    fun setMirror(mirror: Boolean) {
        eglRenderer.setMirror(mirror)
    }

    fun setScalingType(scalingType: ScalingType?) {
        ThreadUtils.checkIsOnMainThread()
        this.scalingType = scalingType
        videoLayoutMeasure.setScalingType(scalingType)
        requestLayout()
    }

    fun setTextureUpdatedListener(listener: Runnable?) {
        ThreadUtils.checkIsOnMainThread()
        textureUpdatedListener = listener
    }

    fun setLayoutAspectRatio(aspectRatio: Float) {
        eglRenderer.setLayoutAspectRatio(aspectRatio)
    }

    fun isGenerationDrawn(generation: Int): Boolean {
        if (generation <= 0) return true

        return lastRenderedGeneration.get() >= generation &&
            lastTextureUpdateGeneration.get() >= generation
    }

    fun hasDrawnFrame(): Boolean {
        val generation = lastRenderedGeneration.get()
        return generation > 0 && lastTextureUpdateGeneration.get() >= generation
    }

    fun layoutWithReadyBuffer(left: Int, top: Int, right: Int, bottom: Int, aspectRatio: Float) {
        ThreadUtils.checkIsOnMainThread()
        suppressNextResizeTransform = true
        layout(left, top, right, bottom)
        setLayoutAspectRatio(aspectRatio)
    }

    fun prepareForLayoutSize(width: Int, height: Int): Int {
        ThreadUtils.checkIsOnMainThread()
        if (width <= 0 || height <= 0) return activeEglSurfaceGeneration.get()

        val currentSurfaceTexture = surfaceTexture ?: return activeEglSurfaceGeneration.get()
        configureSurfaceTextureSize(currentSurfaceTexture, width, height)

        if (released) {
            eglSurfaceWidth = width
            eglSurfaceHeight = height
            return activeEglSurfaceGeneration.get()
        }

        val sizeChanged = eglSurfaceWidth > 0 &&
            eglSurfaceHeight > 0 &&
            (eglSurfaceWidth != width || eglSurfaceHeight != height)

        eglSurfaceWidth = width
        eglSurfaceHeight = height

        if (!hasEglSurface) {
            return scheduleCreateEglSurface(currentSurfaceTexture)
        }

        if (!sizeChanged) return activeEglSurfaceGeneration.get()

        surfaceGeneration++
        releaseEglSurface()
        val targetGeneration = scheduleCreateEglSurface(currentSurfaceTexture)
        resizeTransformTargetGeneration = targetGeneration
        return targetGeneration
    }

    override fun onFrame(videoFrame: VideoFrame) {
        if (released) return
        eglRenderer.onFrame(videoFrame)
        updateFrameData(videoFrame)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        ThreadUtils.checkIsOnMainThread()
        val size: Point = videoLayoutMeasure.measure(
            widthSpec,
            heightSpec,
            rotatedFrameWidth,
            rotatedFrameHeight,
        )
        setMeasuredDimension(size.x, size.y)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        ThreadUtils.checkIsOnMainThread()
        val width = right - left
        val height = bottom - top
        val previousWidth = layoutWidth
        val previousHeight = layoutHeight
        val sizeChanged = previousWidth > 0 &&
            previousHeight > 0 &&
            width > 0 &&
            height > 0 &&
            (previousWidth != width || previousHeight != height)

        if (sizeChanged && !suppressNextResizeTransform) {
            holdPreviousTextureAspect(previousWidth, previousHeight, width, height)
        } else if (width <= 0 || height <= 0) {
            clearResizeTransform()
        } else if (suppressNextResizeTransform) {
            clearResizeTransform()
        }
        suppressNextResizeTransform = false

        layoutWidth = width
        layoutHeight = height

        if (height > 0) {
            eglRenderer.setLayoutAspectRatio(width / height.toFloat())
        } else {
            eglRenderer.setLayoutAspectRatio(0f)
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            eglSurfaceWidth = width
            eglSurfaceHeight = height
            configureSurfaceTextureSize(surfaceTexture, width, height)
        }
        scheduleCreateEglSurface(surfaceTexture)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        surfaceGeneration++
        releaseEglSurface()
        return true
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        prepareForLayoutSize(width, height)
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        val generation = activeEglSurfaceGeneration.get()
        if (generation > 0) {
            lastTextureUpdateGeneration.set(generation)
        }
        notifyTextureUpdated()
    }

    override fun onDetachedFromWindow() {
        surfaceGeneration++
        release()
        super.onDetachedFromWindow()
    }

    private fun scheduleCreateEglSurface(surfaceTexture: SurfaceTexture?): Int {
        if (released || hasEglSurface || surfaceTexture == null) return activeEglSurfaceGeneration.get()

        val generation = ++surfaceGeneration
        uiThreadHandler.post {
            createEglSurfaceIfCurrent(surfaceTexture, generation)
        }
        return generation
    }

    private fun createEglSurfaceIfCurrent(surfaceTexture: SurfaceTexture, generation: Int) {
        if (
            released ||
            hasEglSurface ||
            generation != surfaceGeneration ||
            !isAvailable ||
            !ViewCompat.isAttachedToWindow(this) ||
            this.surfaceTexture !== surfaceTexture
        ) {
            return
        }

        eglRenderer.createEglSurface(surfaceTexture)
        activeEglSurfaceGeneration.set(generation)
        hasEglSurface = true
    }

    private fun configureSurfaceTextureSize(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        try {
            surfaceTexture.setDefaultBufferSize(width, height)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set TextureView buffer size.", t)
        }
    }

    private fun releaseEglSurface() {
        if (released) return

        try {
            val completionLatch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { completionLatch.countDown() }
            ThreadUtils.awaitUninterruptibly(completionLatch)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release EGL surface.", t)
        } finally {
            hasEglSurface = false
            activeEglSurfaceGeneration.set(0)
        }
    }

    private fun notifyTextureUpdated() {
        uiThreadHandler.post {
            if (!released) {
                if (
                    !resizeTransformPending ||
                    resizeTransformTargetGeneration <= 0 ||
                    isGenerationDrawn(resizeTransformTargetGeneration)
                ) {
                    clearResizeTransform()
                }
                textureUpdatedListener?.run()
            }
        }
    }

    private fun holdPreviousTextureAspect(
        previousWidth: Int,
        previousHeight: Int,
        width: Int,
        height: Int,
    ) {
        val widthScale = width / previousWidth.toFloat()
        val heightScale = height / previousHeight.toFloat()
        val scale = if (scalingType == ScalingType.SCALE_ASPECT_FIT) {
            minOf(widthScale, heightScale)
        } else {
            maxOf(widthScale, heightScale)
        }

        textureTransform.reset()
        textureTransform.setScale(
            scale / widthScale,
            scale / heightScale,
            width / 2f,
            height / 2f,
        )
        resizeTransformPending = true
        setTransform(textureTransform)
    }

    private fun clearResizeTransform() {
        if (!resizeTransformPending) return

        textureTransform.reset()
        resizeTransformPending = false
        resizeTransformTargetGeneration = 0
        setTransform(textureTransform)
    }

    private fun updateFrameData(videoFrame: VideoFrame) {
        if (!isFirstFrameRendered) {
            rendererEvents?.onFirstFrameRendered()
            isFirstFrameRendered = true
        }

        if (
            videoFrame.rotatedWidth != rotatedFrameWidth ||
            videoFrame.rotatedHeight != rotatedFrameHeight ||
            videoFrame.rotation != frameRotation
        ) {
            rotatedFrameWidth = videoFrame.rotatedWidth
            rotatedFrameHeight = videoFrame.rotatedHeight
            frameRotation = videoFrame.rotation

            post { requestLayout() }
            uiThreadHandler.post {
                rendererEvents?.onFrameResolutionChanged(
                    rotatedFrameWidth,
                    rotatedFrameHeight,
                    frameRotation,
                )
            }
        }
    }

    private fun getResourceName(): String {
        return try {
            resources.getResourceEntryName(id) + ": "
        } catch (e: Resources.NotFoundException) {
            ""
        }
    }

}
