package com.oney.WebRTCModule

import android.content.Context
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
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

/**
 * TextureView-based WebRTC renderer for layouts that need Android clipping,
 * transforms, opacity, or rounded masks. SurfaceViewRenderer remains the default
 * because it is faster and more battle-tested for full-screen video.
 */
class VideoTextureViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), VideoSink, SurfaceTextureListener {
    private val resourceName: String = getResourceName()
    private val videoLayoutMeasure = VideoLayoutMeasure()
    private val eglRenderer: EglRenderer = EglRenderer(resourceName)
    private val uiThreadHandler = Handler(Looper.getMainLooper())
    private val textureTransform = Matrix()

    private var rendererEvents: RendererEvents? = null
    private var isFirstFrameRendered = false
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0
    private var released = true
    private var hasEglSurface = false
    private var scalingType: ScalingType? = ScalingType.SCALE_ASPECT_FIT
    private var renderedLayoutAspectRatio = 0f
    private var targetLayoutAspectRatio = 0f
    private var textureUpdatesBeforeReset = 0

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
        if (isAvailable) {
            createEglSurfaceIfNeeded(surfaceTexture)
        }
    }

    fun release() {
        ThreadUtils.checkIsOnMainThread()
        if (released) return
        released = true
        hasEglSurface = false
        isFirstFrameRendered = false
        rotatedFrameWidth = 0
        rotatedFrameHeight = 0
        frameRotation = 0
        rendererEvents = null
        eglRenderer.release()
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
        updateResizeTransform()
        requestLayout()
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
        if (height > 0) {
            val nextAspectRatio = width / height.toFloat()
            targetLayoutAspectRatio = nextAspectRatio
            eglRenderer.setLayoutAspectRatio(nextAspectRatio)
            updateResizeTransform()
        } else {
            targetLayoutAspectRatio = 0f
            eglRenderer.setLayoutAspectRatio(0f)
            resetResizeTransform()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        createEglSurfaceIfNeeded(surfaceTexture)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (!released && hasEglSurface) {
            val completionLatch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { completionLatch.countDown() }
            ThreadUtils.awaitUninterruptibly(completionLatch)
            hasEglSurface = false
        }
        return true
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (height > 0) {
            targetLayoutAspectRatio = width / height.toFloat()
            eglRenderer.setLayoutAspectRatio(targetLayoutAspectRatio)
            updateResizeTransform()
        }
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        if (textureUpdatesBeforeReset > 0) {
            textureUpdatesBeforeReset -= 1
            if (textureUpdatesBeforeReset == 0) {
                renderedLayoutAspectRatio = targetLayoutAspectRatio
                resetResizeTransform()
            }
        } else if (targetLayoutAspectRatio > 0f) {
            renderedLayoutAspectRatio = targetLayoutAspectRatio
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun createEglSurfaceIfNeeded(surfaceTexture: SurfaceTexture?) {
        if (released || hasEglSurface || surfaceTexture == null) return
        eglRenderer.createEglSurface(surfaceTexture)
        hasEglSurface = true
    }

    private fun updateResizeTransform() {
        val viewWidth = width
        val viewHeight = height
        val previousAspectRatio = renderedLayoutAspectRatio
        val nextAspectRatio = targetLayoutAspectRatio

        if (
            viewWidth <= 0 ||
            viewHeight <= 0 ||
            previousAspectRatio <= 0f ||
            nextAspectRatio <= 0f ||
            aspectRatioEquals(previousAspectRatio, nextAspectRatio)
        ) {
            if (textureUpdatesBeforeReset == 0) {
                resetResizeTransform()
            }
            if (previousAspectRatio <= 0f && nextAspectRatio > 0f) {
                renderedLayoutAspectRatio = nextAspectRatio
            }
            return
        }

        applyResizeTransform(previousAspectRatio, nextAspectRatio, viewWidth, viewHeight)
        textureUpdatesBeforeReset = RESIZE_TRANSFORM_TEXTURE_UPDATES
    }

    private fun applyResizeTransform(
        contentAspectRatio: Float,
        viewAspectRatio: Float,
        width: Int,
        height: Int,
    ) {
        val scale = contentAspectRatio / viewAspectRatio
        val scaleX: Float
        val scaleY: Float

        if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
            if (scale > 1f) {
                scaleX = scale
                scaleY = 1f
            } else {
                scaleX = 1f
                scaleY = 1f / scale
            }
        } else {
            if (scale > 1f) {
                scaleX = 1f
                scaleY = 1f / scale
            } else {
                scaleX = scale
                scaleY = 1f
            }
        }

        textureTransform.reset()
        textureTransform.setScale(scaleX, scaleY, width / 2f, height / 2f)
        setTransform(textureTransform)
    }

    private fun resetResizeTransform() {
        textureTransform.reset()
        setTransform(textureTransform)
    }

    private fun aspectRatioEquals(first: Float, second: Float): Boolean {
        return kotlin.math.abs(first - second) < ASPECT_RATIO_EPSILON
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

    private companion object {
        private const val ASPECT_RATIO_EPSILON = 0.001f
        private const val RESIZE_TRANSFORM_TEXTURE_UPDATES = 2
    }
}
