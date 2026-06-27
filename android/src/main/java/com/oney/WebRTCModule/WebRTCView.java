package com.oney.WebRTCModule;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.core.view.ViewCompat;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.MediaStream;
import org.webrtc.RendererCommon;
import org.webrtc.RendererCommon.RendererEvents;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

public class WebRTCView extends ViewGroup {
    private enum RendererType {
        SURFACE,
        TEXTURE
    }

    /**
     * The scaling type to be utilized by default.
     *
     * The default value is in accord with
     * https://www.w3.org/TR/html5/embedded-content-0.html#the-video-element:
     *
     * In the absence of style rules to the contrary, video content should be
     * rendered inside the element's playback area such that the video content
     * is shown centered in the playback area at the largest possible size that
     * fits completely within it, with the video content's aspect ratio being
     * preserved. Thus, if the aspect ratio of the playback area does not match
     * the aspect ratio of the video, the video will be shown letterboxed or
     * pillarboxed. Areas of the element's playback area that do not contain the
     * video represent nothing.
     */
    private static final ScalingType DEFAULT_SCALING_TYPE = ScalingType.SCALE_ASPECT_FIT;

    private static final int TEXTURE_STARTUP_STABILIZATION_FRAMES = 1;
    private static final int TEXTURE_RESIZE_STABILIZATION_FRAMES = 1;
    private static final long TEXTURE_FADE_IN_MS = 160;
    private static final long TEXTURE_FADE_HARD_TIMEOUT_MS = 1000;
    private static final String FIRST_FRAME_RENDERED_EVENT = "topFirstFrameRendered";
    private static final int LAST_FRAME_CACHE_KB = 16 * 1024;

    private static final String TAG = WebRTCModule.TAG;
    private static final LruCache<String, Bitmap> lastFrameCache = new LruCache<String, Bitmap>(LAST_FRAME_CACHE_KB) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };

    /**
     * The number of instances for {@link SurfaceViewRenderer}, used for logging.
     * When the renderer is initialized, it creates a new {@link javax.microedition.khronos.egl.EGLContext}
     * which can throw an exception, probably due to memory limitations. We log the number of instances that can
     * be created before the exception is thrown.
     */
    private static int surfaceViewRendererInstances;

    /**
     * The height of the last video frame rendered by
     * {@link #surfaceViewRenderer}.
     */
    private int frameHeight;

    /**
     * The rotation (degree) of the last video frame rendered by
     * {@link #surfaceViewRenderer}.
     */
    private int frameRotation;

    /**
     * The width of the last video frame rendered by
     * {@link #surfaceViewRenderer}.
     */
    private int frameWidth;

    /**
     * The {@code Object} which synchronizes the access to the layout-related
     * state of this instance such as {@link #frameHeight},
     * {@link #frameRotation}, {@link #frameWidth}, and {@link #scalingType}.
     */
    private final Object layoutSyncRoot = new Object();

    /**
     * The indicator which determines whether this {@code WebRTCView} is to
     * mirror the video represented by {@link #videoTrack} during its rendering.
     */
    private boolean mirror;

    /**
     * Indicates if the {@link SurfaceViewRenderer} is attached to the video
     * track.
     */
    private boolean rendererAttached;

    /**
     * The {@code RendererEvents} which listens to rendering events reported by
     * {@link #surfaceViewRenderer}.
     */
    private final RendererEvents rendererEvents = new RendererEvents() {
        @Override
        public void onFirstFrameRendered() {
            WebRTCView.this.onFirstFrameRendered();
        }

        @Override
        public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
            WebRTCView.this.onFrameResolutionChanged(videoWidth, videoHeight, rotation);
        }
    };

    /**
     * The {@code Runnable} representation of
     * {@link #requestSurfaceViewRendererLayout()}. Explicitly defined in order
     * to allow the use of the latter with {@link #post(Runnable)} without
     * initializing new instances on every (method) call.
     */
    private final Runnable requestSurfaceViewRendererLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            requestSurfaceViewRendererLayout();
        }
    };

    /**
     * The scaling type this {@code WebRTCView} is to apply to the video
     * represented by {@link #videoTrack} during its rendering. An expression of
     * the CSS property {@code object-fit} in the terms of WebRTC.
     */
    private ScalingType scalingType;

    /**
     * The URL, if any, of the {@link MediaStream} (to be) rendered by this
     * {@code WebRTCView}. The value of {@link #videoTrack} is derived from it.
     */
    private String streamURL;

    /**
     * The {@link View} and {@link VideoSink} implementation which
     * actually renders {@link #videoTrack} on behalf of this instance.
     */
    private RendererType rendererType = RendererType.SURFACE;

    /**
     * zOrder only applies to the SurfaceView renderer. TextureView participates
     * in normal Android view composition and should use regular view ordering.
     */
    private int zOrder;

    private View rendererView;
    private VideoSink rendererSink;
    private SurfaceViewRenderer surfaceViewRenderer;
    private VideoTextureViewRenderer textureViewRenderer;
    private VideoTextureViewRenderer textureResizeSwapRenderer;
    private VideoTrack textureResizeSwapTrack;
    private boolean noanimation;
    private boolean preserveLastFrame;
    private ImageView lastFrameOverlayView;
    private ImageView resizeSnapshotOverlayView;
    private boolean textureResizeSnapshotPending;
    private int textureResizeTargetGeneration;
    private boolean textureResizeLayoutPending;
    private int textureResizeLayoutBottom;
    private int textureResizeLayoutLeft;
    private int textureResizeLayoutRight;
    private int textureResizeLayoutTop;
    private boolean textureResizeFadePending;
    private int textureResizeFramesUntilFade;
    private boolean textureStartupFadePending;
    private boolean textureStartupOverlayActive;
    private boolean textureStartupOverlayFading;
    private boolean textureFirstFrameWaitingForStartup;
    private int textureStartupFramesUntilFade;
    private int textureResizeSnapshotGeneration;
    private int textureStartupBottom;
    private int textureStartupLeft;
    private int textureStartupRight;
    private int textureStartupTop;
    private int textureResizeFadeGeneration;
    private int textureStartupFadeGeneration;
    private int textureResizeSwapBottom;
    private int textureResizeSwapGeneration;
    private int textureResizeSwapLeft;
    private int textureResizeSwapRight;
    private int textureResizeSwapTop;

    /**
     * The {@code VideoTrack}, if any, rendered by this {@code WebRTCView}.
     */
    private VideoTrack videoTrack;
    private boolean firstFrameRendered;

    public WebRTCView(Context context) {
        super(context);

        createRenderer(RendererType.TEXTURE);

        setMirror(false);
        setScalingType(DEFAULT_SCALING_TYPE);
    }

    private void createRenderer(RendererType rendererType) {
        this.rendererType = rendererType;
        surfaceViewRenderer = null;
        textureViewRenderer = null;

        if (rendererType == RendererType.TEXTURE) {
            textureViewRenderer = createTextureRenderer();
            textureViewRenderer.setTextureUpdatedListener(() -> WebRTCView.this.onTextureUpdated());
            rendererView = textureViewRenderer;
            rendererSink = textureViewRenderer;
        } else {
            surfaceViewRenderer = new SurfaceViewRenderer(getContext());
            rendererView = surfaceViewRenderer;
            rendererSink = surfaceViewRenderer;
            applyZOrder();
        }

        addView(rendererView);
        applyMirror();
        if (scalingType != null) {
            applyScalingType();
        }
    }

    private void replaceRenderer(RendererType nextRendererType) {
        if (rendererType == nextRendererType) return;

        removeRendererFromVideoTrack();
        if (rendererView != null) {
            removeView(rendererView);
        }
        removeLastFrameOverlay();
        resetTextureResizeSnapshot();
        resetTextureResizeFade();
        resetTextureStartupFade(true);

        createRenderer(nextRendererType);
        tryAddRendererToVideoTrack();
        requestSurfaceViewRendererLayout();
    }

    private void applyMirror() {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.setMirror(mirror);
        } else if (textureViewRenderer != null) {
            textureViewRenderer.setMirror(mirror);
        }
    }

    private void applyScalingType() {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.setScalingType(scalingType);
        } else if (textureViewRenderer != null) {
            textureViewRenderer.setScalingType(scalingType);
        }
    }

    private void applyZOrder() {
        if (surfaceViewRenderer == null) return;

        switch (zOrder) {
            case 0:
                surfaceViewRenderer.setZOrderOnTop(false);
                surfaceViewRenderer.setZOrderMediaOverlay(false);
                break;
            case 1:
                surfaceViewRenderer.setZOrderOnTop(false);
                surfaceViewRenderer.setZOrderMediaOverlay(true);
                break;
            case 2:
                surfaceViewRenderer.setZOrderOnTop(true);
                break;
        }
    }

    private void initRenderer(EglBase.Context sharedContext) {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.init(sharedContext, rendererEvents);
            surfaceViewRendererInstances++;
        } else if (textureViewRenderer != null) {
            textureViewRenderer.init(sharedContext, rendererEvents);
            surfaceViewRendererInstances++;
        }
    }

    private void releaseRenderer() {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.release();
            surfaceViewRendererInstances--;
        } else if (textureViewRenderer != null) {
            textureViewRenderer.release();
            surfaceViewRendererInstances--;
        }
    }

    private void setSurfaceRendererBackgroundColor(int color) {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.setBackgroundColor(color);
        }
    }

    /**
     * "Cleans" the {@code SurfaceViewRenderer} by setting the view part to
     * opaque black and the surface part to transparent.
     */
    private void cleanSurfaceViewRenderer() {
        setSurfaceRendererBackgroundColor(Color.BLACK);
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.clearImage();
        } else if (textureViewRenderer != null) {
            textureViewRenderer.clearImage();
        }
    }

    private VideoTrack getVideoTrackForStreamURL(String streamURL) {
        VideoTrack videoTrack = null;

        if (streamURL != null) {
            ReactContext reactContext = (ReactContext) getContext();
            WebRTCModule module = reactContext.getNativeModule(WebRTCModule.class);
            MediaStream stream = module.getStreamForReactTag(streamURL);

            if (stream != null) {
                List<VideoTrack> videoTracks = stream.videoTracks;

                if (!videoTracks.isEmpty()) {
                    videoTrack = videoTracks.get(0);
                }
            }

            if (videoTrack == null) {
                Log.w(TAG, "No video stream for react tag: " + streamURL);
            }
        }

        return videoTrack;
    }

    @Override
    protected void onAttachedToWindow() {
        try {
            // Generally, OpenGL is only necessary while this View is attached
            // to a window so there is no point in having the whole rendering
            // infrastructure hooked up while this View is not attached to a
            // window. Additionally, a memory leak was solved in a similar way
            // on iOS.
            tryAddRendererToVideoTrack();
        } finally {
            super.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            // Generally, OpenGL is only necessary while this View is attached
            // to a window so there is no point in having the whole rendering
            // infrastructure hooked up while this View is not attached to a
            // window. Additionally, a memory leak was solved in a similar way
            // on iOS.
            removeRendererFromVideoTrack();
        } finally {
            super.onDetachedFromWindow();
        }
    }

    /**
     * Callback fired by {@link #surfaceViewRenderer} when the first frame is
     * rendered. Here we will set the background of the view part of the
     * SurfaceView to transparent, so the surface (where video is actually
     * rendered) shines through.
     */
    private void onFirstFrameRendered() {
        post(() -> {
            Log.d(TAG, "First frame rendered.");
            setSurfaceRendererBackgroundColor(Color.TRANSPARENT);
            if (noanimation || textureViewRenderer == null || !isTextureStartupOverlayOwned()) {
                textureFirstFrameWaitingForStartup = false;
                if (noanimation) {
                    removeLastFrameOverlay();
                }
                emitFirstFrameRenderedIfNeeded();
            } else {
                textureFirstFrameWaitingForStartup = true;
            }
        });
    }

    private void resetFirstFrameRendered() {
        synchronized (this) {
            firstFrameRendered = false;
        }
    }

    private void emitFirstFrameRenderedIfNeeded() {
        boolean shouldEmit = false;

        synchronized (this) {
            if (!firstFrameRendered) {
                firstFrameRendered = true;
                shouldEmit = true;
            }
        }

        if (!shouldEmit) {
            return;
        }

        ReactContext reactContext = (ReactContext) getContext();
        reactContext
                .getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), FIRST_FRAME_RENDERED_EVENT, null);
    }

    /**
     * Callback fired by {@link #surfaceViewRenderer} when the resolution or
     * rotation of the frame it renders has changed.
     *
     * @param videoWidth The new width of the rendered video frame.
     * @param videoHeight The new height of the rendered video frame.
     * @param rotation The new rotation of the rendered video frame.
     */
    private void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
        boolean changed = false;

        synchronized (layoutSyncRoot) {
            if (this.frameHeight != videoHeight) {
                this.frameHeight = videoHeight;
                changed = true;
            }
            if (this.frameRotation != rotation) {
                this.frameRotation = rotation;
                changed = true;
            }
            if (this.frameWidth != videoWidth) {
                this.frameWidth = videoWidth;
                changed = true;
            }
        }
        if (changed) {
            // The onFrameResolutionChanged method call executes on the
            // surfaceViewRenderer's render Thread.
            post(requestSurfaceViewRendererLayoutRunnable);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int height = b - t;
        int width = r - l;

        if (height == 0 || width == 0) {
            l = t = r = b = 0;
        } else {
            int frameHeight;
            int frameRotation;
            int frameWidth;
            ScalingType scalingType;

            synchronized (layoutSyncRoot) {
                frameHeight = this.frameHeight;
                frameRotation = this.frameRotation;
                frameWidth = this.frameWidth;
                scalingType = this.scalingType;
            }

            switch (scalingType) {
                case SCALE_ASPECT_FILL:
                    // Fill this ViewGroup with surfaceViewRenderer and the latter
                    // will take care of filling itself with the video similarly to
                    // the cover value the CSS property object-fit.
                    r = width;
                    l = 0;
                    b = height;
                    t = 0;
                    break;
                case SCALE_ASPECT_FIT:
                default:
                    // Lay surfaceViewRenderer out inside this ViewGroup in accord
                    // with the contain value of the CSS property object-fit.
                    // SurfaceViewRenderer will fill itself with the video similarly
                    // to the cover or contain value of the CSS property object-fit
                    // (which will not matter, eventually).
                    if (frameHeight == 0 || frameWidth == 0) {
                        l = t = r = b = 0;
                    } else {
                        float frameAspectRatio = (frameRotation % 180 == 0) ? frameWidth / (float) frameHeight
                                                                            : frameHeight / (float) frameWidth;
                        Point frameDisplaySize =
                                RendererCommon.getDisplaySize(scalingType, frameAspectRatio, width, height);

                        l = (width - frameDisplaySize.x) / 2;
                        t = (height - frameDisplaySize.y) / 2;
                        r = l + frameDisplaySize.x;
                        b = t + frameDisplaySize.y;
                    }
                    break;
            }
        }
        layoutRendererView(l, t, r, b);
    }

    private void layoutRendererView(int left, int top, int right, int bottom) {
        if (rendererView == null) return;

        if (textureViewRenderer == null) {
            rendererView.layout(left, top, right, bottom);
            layoutLastFrameOverlay(left, top, right, bottom);
            return;
        }

        layoutTextureRendererView(left, top, right, bottom);
        layoutLastFrameOverlay(left, top, right, bottom);
        layoutResizeSnapshotOverlay(left, top, right, bottom);
    }

    private void layoutTextureRendererView(int targetLeft, int targetTop, int targetRight, int targetBottom) {
        int targetWidth = targetRight - targetLeft;
        int targetHeight = targetBottom - targetTop;

        if (targetWidth <= 0 || targetHeight <= 0) {
            resetTextureResizeSnapshot();
            resetTextureResizeFade();
            clearPendingTextureResizeLayout();
            rendererView.layout(targetLeft, targetTop, targetRight, targetBottom);
            textureViewRenderer.setLayoutAspectRatio(0f);
            markTextureStartupLayoutReady();
            return;
        }

        float targetAspectRatio = targetWidth / (float) targetHeight;
        int currentWidth = rendererView.getWidth();
        int currentHeight = rendererView.getHeight();
        boolean hasCurrentLayout = currentWidth > 0 && currentHeight > 0;
        boolean isResizing = hasCurrentLayout && (targetWidth != currentWidth || targetHeight != currentHeight);

        if (!isResizing) {
            clearPendingTextureResizeLayout();
            textureViewRenderer.prepareForLayoutSize(targetWidth, targetHeight);
            rendererView.layout(targetLeft, targetTop, targetRight, targetBottom);
            textureViewRenderer.setLayoutAspectRatio(targetAspectRatio);
            markTextureStartupLayoutReady();
            return;
        }

        resetTextureStartupFade(false);
        if (startTextureResizeSwap(targetLeft, targetTop, targetRight, targetBottom, targetAspectRatio)) {
            resetTextureResizeSnapshot();
            resetTextureResizeFade();
            return;
        }

        if (noanimation) {
            resetTextureResizeFade();
            boolean snapshotArmed = armTextureResizeSnapshot(targetLeft, targetTop, targetRight, targetBottom);
            int targetGeneration = textureViewRenderer.prepareForLayoutSize(targetWidth, targetHeight);
            textureViewRenderer.setLayoutAspectRatio(targetAspectRatio);
            setPendingTextureResizeLayout(targetLeft, targetTop, targetRight, targetBottom);
            if (snapshotArmed) {
                textureResizeTargetGeneration = targetGeneration;
            } else {
                armTextureResizeWaitOnly(targetGeneration);
            }
            return;
        } else {
            resetTextureResizeSnapshot();
            armTextureResizeFade();
        }
        textureResizeTargetGeneration = textureViewRenderer.prepareForLayoutSize(targetWidth, targetHeight);
        textureViewRenderer.setLayoutAspectRatio(targetAspectRatio);
        setPendingTextureResizeLayout(targetLeft, targetTop, targetRight, targetBottom);
    }

    private void onTextureUpdated() {
        if (textureResizeSnapshotPending) {
            if (!isTextureResizeTargetUpdated()) {
                return;
            }
            applyPendingTextureResizeLayout();
            finishTextureResizeSnapshot();
            return;
        }

        if (textureResizeFadePending && textureResizeFramesUntilFade > 0) {
            if (!isTextureResizeTargetUpdated()) {
                return;
            }
            applyPendingTextureResizeLayout();
            textureResizeFramesUntilFade--;
            if (textureResizeFramesUntilFade <= 0) {
                finishTextureResizeFade();
            }
            return;
        }

        if (!textureStartupFadePending
                || !textureStartupOverlayActive
                || textureStartupFramesUntilFade <= 0) {
            return;
        }

        textureStartupFramesUntilFade--;
        if (textureStartupFramesUntilFade > 0) {
            return;
        }

        finishTextureStartupFade();
    }

    private boolean isTextureResizeTargetUpdated() {
        return textureViewRenderer == null
                || textureResizeTargetGeneration <= 0
                || textureViewRenderer.isGenerationDrawn(textureResizeTargetGeneration);
    }

    private void setPendingTextureResizeLayout(int left, int top, int right, int bottom) {
        textureResizeLayoutPending = true;
        textureResizeLayoutLeft = left;
        textureResizeLayoutTop = top;
        textureResizeLayoutRight = right;
        textureResizeLayoutBottom = bottom;
    }

    private void applyPendingTextureResizeLayout() {
        if (!textureResizeLayoutPending || textureViewRenderer == null) return;

        int width = textureResizeLayoutRight - textureResizeLayoutLeft;
        int height = textureResizeLayoutBottom - textureResizeLayoutTop;
        if (width <= 0 || height <= 0) {
            clearPendingTextureResizeLayout();
            return;
        }

        textureResizeLayoutPending = false;
        textureViewRenderer.layoutWithReadyBuffer(
                textureResizeLayoutLeft,
                textureResizeLayoutTop,
                textureResizeLayoutRight,
                textureResizeLayoutBottom,
                width / (float) height);
    }

    private void clearPendingTextureResizeLayout() {
        textureResizeLayoutPending = false;
        textureResizeLayoutLeft = 0;
        textureResizeLayoutTop = 0;
        textureResizeLayoutRight = 0;
        textureResizeLayoutBottom = 0;
    }

    private boolean startTextureResizeSwap(
            int left,
            int top,
            int right,
            int bottom,
            float aspectRatio) {
        if (textureViewRenderer == null || videoTrack == null || !rendererAttached) {
            return false;
        }

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return false;
        }

        if (textureResizeSwapRenderer != null
                && textureResizeSwapLeft == left
                && textureResizeSwapTop == top
                && textureResizeSwapRight == right
                && textureResizeSwapBottom == bottom) {
            return true;
        }

        EglBase.Context sharedContext = EglUtils.getRootEglBaseContext();
        if (sharedContext == null) {
            return false;
        }

        cancelTextureResizeSwap();

        final int generation = ++textureResizeSwapGeneration;
        final VideoTextureViewRenderer nextRenderer = createTextureRenderer();
        nextRenderer.setAlpha(0f);
        nextRenderer.setTextureUpdatedListener(() -> onTextureResizeSwapUpdated(nextRenderer, generation));

        try {
            nextRenderer.init(sharedContext, rendererEvents);
            surfaceViewRendererInstances++;
        } catch (Exception e) {
            Logging.e(TAG, "Failed to initialize resize renderer on instance " + surfaceViewRendererInstances, e);
            nextRenderer.release();
            return false;
        }

        textureResizeSwapRenderer = nextRenderer;
        textureResizeSwapTrack = videoTrack;
        textureResizeSwapLeft = left;
        textureResizeSwapTop = top;
        textureResizeSwapRight = right;
        textureResizeSwapBottom = bottom;

        addView(nextRenderer);
        nextRenderer.layoutWithReadyBuffer(left, top, right, bottom, aspectRatio);
        nextRenderer.prepareForLayoutSize(width, height);
        nextRenderer.bringToFront();
        addSinkToVideoTrack(textureResizeSwapTrack, nextRenderer);
        return true;
    }

    private void onTextureResizeSwapUpdated(VideoTextureViewRenderer renderer, int generation) {
        if (renderer != textureResizeSwapRenderer || generation != textureResizeSwapGeneration) {
            return;
        }
        if (!renderer.hasDrawnFrame()) {
            return;
        }

        finishTextureResizeSwap(renderer, generation);
    }

    private void finishTextureResizeSwap(VideoTextureViewRenderer nextRenderer, int generation) {
        if (nextRenderer != textureResizeSwapRenderer || generation != textureResizeSwapGeneration) {
            return;
        }

        final VideoTextureViewRenderer previousRenderer = textureViewRenderer;
        final VideoSink previousSink = rendererSink;
        final VideoTrack previousTrack = videoTrack;

        textureResizeSwapRenderer = null;
        textureResizeSwapTrack = null;
        textureResizeSwapLeft = 0;
        textureResizeSwapTop = 0;
        textureResizeSwapRight = 0;
        textureResizeSwapBottom = 0;

        textureViewRenderer = nextRenderer;
        rendererView = nextRenderer;
        rendererSink = nextRenderer;
        nextRenderer.setTextureUpdatedListener(() -> WebRTCView.this.onTextureUpdated());
        nextRenderer.animate().cancel();
        nextRenderer.animate().setListener(null);
        nextRenderer.setAlpha(1f);

        if (previousTrack != null && previousSink != null) {
            removeSinkFromVideoTrack(previousTrack, previousSink);
        }
        if (previousRenderer != null) {
            previousRenderer.setTextureUpdatedListener(null);
            previousRenderer.release();
            surfaceViewRendererInstances--;
            removeView(previousRenderer);
        }

        resetTextureResizeSnapshot();
        resetTextureResizeFade();
        clearPendingTextureResizeLayout();
        requestSurfaceViewRendererLayout();
    }

    private void cancelTextureResizeSwap() {
        textureResizeSwapGeneration++;
        final VideoTextureViewRenderer renderer = textureResizeSwapRenderer;
        final VideoTrack track = textureResizeSwapTrack;

        textureResizeSwapRenderer = null;
        textureResizeSwapTrack = null;
        textureResizeSwapLeft = 0;
        textureResizeSwapTop = 0;
        textureResizeSwapRight = 0;
        textureResizeSwapBottom = 0;

        if (renderer == null) return;

        if (track != null) {
            removeSinkFromVideoTrack(track, renderer);
        }
        renderer.setTextureUpdatedListener(null);
        renderer.release();
        surfaceViewRendererInstances--;
        removeView(renderer);
    }

    /**
     * Stops rendering {@link #videoTrack} and releases the associated acquired
     * resources (if rendering is in progress).
     */
    private void removeRendererFromVideoTrack() {
        cacheLastTextureFrame();
        cancelTextureResizeSwap();
        resetTextureStartupFade(true);
        resetTextureResizeSnapshot();
        resetTextureResizeFade();

        if (rendererAttached) {
            if (videoTrack != null) {
                final VideoSink sink = rendererSink;
                final VideoTrack track = videoTrack;
                removeSinkFromVideoTrack(track, sink);
            }

            releaseRenderer();
            rendererAttached = false;

            // Since this WebRTCView is no longer rendering anything, make sure
            // surfaceViewRenderer displays nothing as well.
            synchronized (layoutSyncRoot) {
                frameHeight = 0;
                frameRotation = 0;
                frameWidth = 0;
            }
            requestSurfaceViewRendererLayout();
        }
    }

    private void removeSinkFromVideoTrack(VideoTrack track, VideoSink sink) {
        if (track == null || sink == null) return;

        ThreadUtils.runOnExecutor(() -> {
            try {
                track.removeSink(sink);
            } catch (Throwable tr) {
                // XXX If WebRTCModule#mediaStreamTrackRelease has already been
                // invoked on videoTrack, then it is no longer safe to call removeSink
                // on the instance, it will throw IllegalStateException.
            }
        });
    }

    private void addSinkToVideoTrack(VideoTrack track, VideoSink sink) {
        if (track == null || sink == null) return;

        ThreadUtils.runOnExecutor(() -> {
            try {
                track.addSink(sink);
            } catch (Throwable tr) {
                // XXX If WebRTCModule#mediaStreamTrackRelease has already been
                // invoked on videoTrack, then it is no longer safe to call addSink
                // on the instance, it will throw IllegalStateException.

                Log.e(TAG, "Failed to add renderer", tr);
            }
        });
    }

    private void cacheLastTextureFrame() {
        if (!preserveLastFrame || streamURL == null || textureViewRenderer == null) return;
        if (textureViewRenderer.getWidth() <= 0 || textureViewRenderer.getHeight() <= 0) return;

        try {
            Bitmap bitmap = textureViewRenderer.getBitmap();
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                return;
            }
            synchronized (lastFrameCache) {
                lastFrameCache.put(streamURL, bitmap);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "Failed to cache last TextureView frame.", tr);
        }
    }

    private void maybeShowLastFrameOverlay() {
        if (!preserveLastFrame || streamURL == null || lastFrameOverlayView != null) return;

        Bitmap bitmap;
        synchronized (lastFrameCache) {
            bitmap = lastFrameCache.get(streamURL);
        }
        if (bitmap == null || bitmap.isRecycled()) return;

        ImageView overlay = new ImageView(getContext());
        overlay.setImageBitmap(bitmap);
        overlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
        overlay.setAlpha(1f);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        lastFrameOverlayView = overlay;
        addView(overlay);
        overlay.bringToFront();
        requestSurfaceViewRendererLayout();
    }

    private void layoutLastFrameOverlay(int left, int top, int right, int bottom) {
        if (lastFrameOverlayView == null) return;
        lastFrameOverlayView.layout(left, top, right, bottom);
        lastFrameOverlayView.bringToFront();
    }

    private void fadeOutLastFrameOverlay() {
        if (lastFrameOverlayView == null) return;

        final ImageView overlay = lastFrameOverlayView;
        overlay.animate().cancel();
        overlay.animate()
                .alpha(0f)
                .setDuration(TEXTURE_FADE_IN_MS)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        overlay.animate().setListener(null);
                        if (!canceled && lastFrameOverlayView == overlay) {
                            removeLastFrameOverlay();
                        }
                    }
                })
                .start();
    }

    private void removeLastFrameOverlay() {
        if (lastFrameOverlayView == null) return;

        lastFrameOverlayView.animate().cancel();
        lastFrameOverlayView.animate().setListener(null);
        removeView(lastFrameOverlayView);
        lastFrameOverlayView = null;
    }

    private ImageView.ScaleType getTextureOverlayScaleType() {
        return scalingType == ScalingType.SCALE_ASPECT_FIT
                ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.CENTER_CROP;
    }

    private boolean armTextureResizeSnapshot(int left, int top, int right, int bottom) {
        textureResizeTargetGeneration = 0;
        clearPendingTextureResizeLayout();
        if (textureViewRenderer == null || right <= left || bottom <= top) return false;
        if (textureViewRenderer.getWidth() <= 0 || textureViewRenderer.getHeight() <= 0) return false;

        Bitmap bitmap;
        try {
            bitmap = textureViewRenderer.getBitmap();
        } catch (Throwable tr) {
            Log.w(TAG, "Failed to capture TextureView resize snapshot.", tr);
            return false;
        }

        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return false;
        }

        removeResizeSnapshotOverlay();

        ImageView overlay = new ImageView(getContext());
        overlay.setImageBitmap(bitmap);
        overlay.setScaleType(getTextureOverlayScaleType());
        overlay.setAlpha(1f);
        overlay.setClickable(false);
        overlay.setFocusable(false);

        resizeSnapshotOverlayView = overlay;
        textureResizeSnapshotPending = true;
        textureResizeSnapshotGeneration++;
        addView(overlay);
        layoutResizeSnapshotOverlay(left, top, right, bottom);

        textureViewRenderer.animate().cancel();
        textureViewRenderer.animate().setListener(null);
        textureViewRenderer.setAlpha(0f);
        scheduleTextureResizeSnapshotHardTimeout();
        return true;
    }

    private void armTextureResizeWaitOnly(int targetGeneration) {
        textureResizeTargetGeneration = targetGeneration;
        textureResizeSnapshotPending = true;
        textureResizeSnapshotGeneration++;
        scheduleTextureResizeSnapshotHardTimeout();
    }

    private void scheduleTextureResizeSnapshotHardTimeout() {
        final int generation = textureResizeSnapshotGeneration;

        postDelayed(() -> {
            if (generation != textureResizeSnapshotGeneration || !textureResizeSnapshotPending) {
                return;
            }

            if (textureResizeLayoutPending && !isTextureResizeTargetUpdated()) {
                scheduleTextureResizeSnapshotHardTimeout();
                return;
            }

            finishTextureResizeSnapshot();
        }, TEXTURE_FADE_HARD_TIMEOUT_MS);
    }

    private void finishTextureResizeSnapshot() {
        textureResizeSnapshotGeneration++;
        textureResizeSnapshotPending = false;
        textureResizeTargetGeneration = 0;
        applyPendingTextureResizeLayout();
        removeResizeSnapshotOverlay();

        if (textureViewRenderer != null && noanimation) {
            textureViewRenderer.animate().cancel();
            textureViewRenderer.animate().setListener(null);
            textureViewRenderer.setAlpha(1f);
        }
    }

    private void resetTextureResizeSnapshot() {
        textureResizeSnapshotGeneration++;
        textureResizeSnapshotPending = false;
        textureResizeTargetGeneration = 0;
        clearPendingTextureResizeLayout();
        removeResizeSnapshotOverlay();

        if (textureViewRenderer != null && noanimation) {
            textureViewRenderer.animate().cancel();
            textureViewRenderer.animate().setListener(null);
            textureViewRenderer.setAlpha(1f);
        }
    }

    private void removeResizeSnapshotOverlay() {
        if (resizeSnapshotOverlayView == null) return;

        resizeSnapshotOverlayView.animate().cancel();
        resizeSnapshotOverlayView.animate().setListener(null);
        removeView(resizeSnapshotOverlayView);
        resizeSnapshotOverlayView = null;
    }

    private void layoutResizeSnapshotOverlay(int left, int top, int right, int bottom) {
        if (resizeSnapshotOverlayView == null) return;
        resizeSnapshotOverlayView.layout(left, top, right, bottom);
        resizeSnapshotOverlayView.bringToFront();
    }

    private void armTextureResizeFade() {
        if (textureViewRenderer == null) return;
        if (noanimation) {
            resetTextureResizeFade();
            return;
        }

        textureResizeFadeGeneration++;
        textureResizeFadePending = true;
        textureResizeFramesUntilFade = TEXTURE_RESIZE_STABILIZATION_FRAMES;
        textureViewRenderer.animate().cancel();
        textureViewRenderer.animate().setListener(null);
        textureViewRenderer.setAlpha(0f);
        scheduleTextureResizeFadeHardTimeout();
    }

    private void scheduleTextureResizeFadeHardTimeout() {
        final int generation = textureResizeFadeGeneration;

        postDelayed(() -> {
            if (generation != textureResizeFadeGeneration || !textureResizeFadePending) {
                return;
            }

            if (textureResizeLayoutPending && !isTextureResizeTargetUpdated()) {
                scheduleTextureResizeFadeHardTimeout();
                return;
            }

            finishTextureResizeFade();
        }, TEXTURE_FADE_HARD_TIMEOUT_MS);
    }

    private void finishTextureResizeFade() {
        if (textureViewRenderer == null) {
            resetTextureResizeFade();
            return;
        }

        textureResizeFadePending = false;
        textureResizeTargetGeneration = 0;
        textureResizeFramesUntilFade = 0;
        applyPendingTextureResizeLayout();
        fadeInTextureRenderer(null);
    }

    private void resetTextureResizeFade() {
        textureResizeFadeGeneration++;
        textureResizeFadePending = false;
        textureResizeTargetGeneration = 0;
        textureResizeFramesUntilFade = 0;
        clearPendingTextureResizeLayout();
    }

    private boolean isTextureStartupOverlayOwned() {
        return textureStartupFadePending || textureStartupOverlayActive || textureStartupOverlayFading;
    }

    private void armTextureStartupFade() {
        if (textureViewRenderer == null) return;
        if (noanimation) {
            resetTextureStartupFade(true);
            return;
        }

        textureStartupFadePending = true;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = false;
        textureFirstFrameWaitingForStartup = false;
        textureStartupFramesUntilFade = TEXTURE_STARTUP_STABILIZATION_FRAMES;
        textureStartupLeft = 0;
        textureStartupTop = 0;
        textureStartupRight = 0;
        textureStartupBottom = 0;
        textureViewRenderer.animate().cancel();
        textureViewRenderer.animate().setListener(null);
        textureViewRenderer.setAlpha(0f);
        scheduleTextureStartupFadeHardTimeout();
        markTextureStartupLayoutReady();
    }

    private void scheduleTextureStartupFadeHardTimeout() {
        final int generation = ++textureStartupFadeGeneration;

        postDelayed(() -> {
            if (generation != textureStartupFadeGeneration
                    || textureViewRenderer == null
                    || !textureStartupFadePending) {
                return;
            }

            finishTextureStartupFade();
        }, TEXTURE_FADE_HARD_TIMEOUT_MS);
    }

    private void markTextureStartupLayoutReady() {
        if (!textureStartupFadePending || textureViewRenderer == null) return;

        int left = 0;
        int top = 0;
        int right = getWidth();
        int bottom = getHeight();

        if (right <= left || bottom <= top) return;

        boolean targetChanged = !textureStartupOverlayActive
                || textureStartupLeft != left
                || textureStartupTop != top
                || textureStartupRight != right
                || textureStartupBottom != bottom;
        if (targetChanged || textureStartupFramesUntilFade == 0) {
            textureStartupFramesUntilFade = TEXTURE_STARTUP_STABILIZATION_FRAMES;
        }

        textureStartupOverlayActive = true;
        textureStartupLeft = left;
        textureStartupTop = top;
        textureStartupRight = right;
        textureStartupBottom = bottom;
    }

    private void finishTextureStartupFade() {
        if (textureViewRenderer == null) {
            resetTextureStartupFade(false);
            emitFirstFrameRenderedIfNeeded();
            return;
        }

        textureStartupFadePending = false;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = true;
        fadeInTextureRenderer(() -> {
            textureStartupOverlayFading = false;
            textureFirstFrameWaitingForStartup = false;
            emitFirstFrameRenderedIfNeeded();
        });
    }

    private void fadeInTextureRenderer(Runnable onEnd) {
        if (textureViewRenderer == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        if (noanimation) {
            textureViewRenderer.animate().cancel();
            textureViewRenderer.setAlpha(1f);
            removeLastFrameOverlay();
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        final VideoTextureViewRenderer renderer = textureViewRenderer;
        renderer.animate().cancel();
        fadeOutLastFrameOverlay();
        renderer.animate()
                .alpha(1f)
                .setDuration(TEXTURE_FADE_IN_MS)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        renderer.animate().setListener(null);
                        if (!canceled && textureViewRenderer == renderer) {
                            renderer.setAlpha(1f);
                        }
                        if (!canceled && onEnd != null) {
                            onEnd.run();
                        }
                    }
                })
                .start();
    }

    private void resetTextureStartupFade(boolean removeOverlay) {
        textureStartupFadeGeneration++;
        textureStartupFadePending = false;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = false;
        textureFirstFrameWaitingForStartup = false;
        textureStartupFramesUntilFade = 0;
        textureStartupLeft = 0;
        textureStartupTop = 0;
        textureStartupRight = 0;
        textureStartupBottom = 0;
        if (textureViewRenderer != null) {
            textureViewRenderer.animate().cancel();
            textureViewRenderer.animate().setListener(null);
            textureViewRenderer.setAlpha(1f);
        }
        if (removeOverlay && !preserveLastFrame) {
            removeLastFrameOverlay();
        }
    }

    private VideoTextureViewRenderer createTextureRenderer() {
        VideoTextureViewRenderer renderer = new VideoTextureViewRenderer(getContext());
        if (!noanimation) {
            renderer.setAlpha(0f);
        }
        renderer.setMirror(mirror);
        if (scalingType != null) {
            renderer.setScalingType(scalingType);
        }
        return renderer;
    }

    /**
     * Request that {@link #surfaceViewRenderer} be laid out (as soon as
     * possible) because layout-related state either of this instance or of
     * {@code surfaceViewRenderer} has changed.
     */
    @SuppressLint("WrongCall")
    private void requestSurfaceViewRendererLayout() {
        // Google/WebRTC just call requestLayout() on surfaceViewRenderer when
        // they change the value of its mirror or surfaceType property.
        if (rendererView != null) {
            rendererView.requestLayout();
        }
        // The above is not enough though when the video frame's dimensions or
        // rotation change. The following will suffice.
        if (!ViewCompat.isInLayout(this)) {
            onLayout(
                    /* changed */ false, getLeft(), getTop(), getRight(), getBottom());
        }
    }

    /**
     * Sets the indicator which determines whether this {@code WebRTCView} is to
     * mirror the video represented by {@link #videoTrack} during its rendering.
     *
     * @param mirror If this {@code WebRTCView} is to mirror the video
     * represented by {@code videoTrack} during its rendering, {@code true};
     * otherwise, {@code false}.
     */
    public void setMirror(boolean mirror) {
        if (this.mirror != mirror) {
            this.mirror = mirror;
            applyMirror();
            // SurfaceViewRenderer takes the value of its mirror property into
            // account upon its layout.
            requestSurfaceViewRendererLayout();
        }
    }

    /**
     * In the fashion of
     * https://www.w3.org/TR/html5/embedded-content-0.html#dom-video-videowidth
     * and https://www.w3.org/TR/html5/rendering.html#video-object-fit,
     * resembles the CSS style {@code object-fit}.
     *
     * @param objectFit For details, refer to the documentation of the
     * {@code objectFit} property of the JavaScript counterpart of
     * {@code WebRTCView} i.e. {@code RTCView}.
     */
    public void setObjectFit(String objectFit) {
        ScalingType scalingType =
                "cover".equals(objectFit) ? ScalingType.SCALE_ASPECT_FILL : ScalingType.SCALE_ASPECT_FIT;

        setScalingType(scalingType);
    }

    private void setScalingType(ScalingType scalingType) {
        synchronized (layoutSyncRoot) {
            if (this.scalingType == scalingType) {
                return;
            }
            this.scalingType = scalingType;
            applyScalingType();
        }
        // Both this instance ant its SurfaceViewRenderer take the value of
        // their scalingType properties into account upon their layouts.
        requestSurfaceViewRendererLayout();
    }

    public void setAndroidRenderer(String androidRenderer) {
        RendererType nextRendererType = "texture".equals(androidRenderer)
                ? RendererType.TEXTURE
                : RendererType.SURFACE;

        replaceRenderer(nextRendererType);
    }

    public void setNoanimation(boolean noanimation) {
        this.noanimation = noanimation;
        if (noanimation) {
            boolean shouldEmitFirstFrame = textureFirstFrameWaitingForStartup;
            resetTextureResizeSnapshot();
            resetTextureResizeFade();
            resetTextureStartupFade(true);
            if (!preserveLastFrame) {
                removeLastFrameOverlay();
            }
            if (shouldEmitFirstFrame) {
                emitFirstFrameRenderedIfNeeded();
            }
        }
    }

    public void setPreserveLastFrame(boolean preserveLastFrame) {
        this.preserveLastFrame = preserveLastFrame;
        if (preserveLastFrame) {
            maybeShowLastFrameOverlay();
        } else {
            removeLastFrameOverlay();
        }
    }

    /**
     * Sets the {@code MediaStream} to be rendered by this {@code WebRTCView}.
     * The implementation renders the first {@link VideoTrack}, if any, of the
     * specified {@code mediaStream}.
     *
     * @param streamURL The URL of the {@code MediaStream} to be rendered by
     * this {@code WebRTCView} or {@code null}.
     */
    void setStreamURL(String streamURL) {
        // Is the value of this.streamURL really changing?
        if (!Objects.equals(streamURL, this.streamURL)) {
            // XXX The value of this.streamURL is really changing. Before
            // realizing/applying the change, let go of the old videoTrack. Of
            // course, that is only necessary if the value of videoTrack will
            // really change. Please note though that letting go of the old
            // videoTrack before assigning to this.streamURL is vital;
            // otherwise, removeRendererFromVideoTrack will fail to remove the
            // old videoTrack from the associated videoRenderer, two
            // VideoTracks (the old and the new) may start rendering and, most
            // importantly the videoRender may eventually crash when the old
            // videoTrack is disposed.
            VideoTrack videoTrack = getVideoTrackForStreamURL(streamURL);

            if (this.videoTrack != videoTrack) {
                setVideoTrack(null);
            }

            this.streamURL = streamURL;
            if (streamURL == null) {
                removeLastFrameOverlay();
            } else {
                maybeShowLastFrameOverlay();
            }

            // After realizing/applying the change in the value of
            // this.streamURL, reflect it on the value of videoTrack.
            setVideoTrack(videoTrack);
        }
    }

    /**
     * Sets the {@code VideoTrack} to be rendered by this {@code WebRTCView}.
     *
     * @param videoTrack The {@code VideoTrack} to be rendered by this
     * {@code WebRTCView} or {@code null}.
     */
    private void setVideoTrack(VideoTrack videoTrack) {
        VideoTrack oldVideoTrack = this.videoTrack;

        if (oldVideoTrack != videoTrack) {
            resetFirstFrameRendered();

            if (oldVideoTrack != null) {
                if (videoTrack == null && !preserveLastFrame) {
                    // If we are not going to render any stream, clean the
                    // surface.
                    cleanSurfaceViewRenderer();
                }
                removeRendererFromVideoTrack();
            }

            this.videoTrack = videoTrack;

            if (videoTrack != null) {
                tryAddRendererToVideoTrack();
                if (oldVideoTrack == null && !preserveLastFrame) {
                    // If there was no old track, clean the surface so we start
                    // with black.
                    cleanSurfaceViewRenderer();
                }
            }
        }
    }

    /**
     * Sets the z-order of this {@link WebRTCView} in the stacking space of all
     * {@code WebRTCView}s. For more details, refer to the documentation of the
     * {@code zOrder} property of the JavaScript counterpart of
     * {@code WebRTCView} i.e. {@code RTCView}.
     *
     * @param zOrder The z-order to set on this {@code WebRTCView}.
     */
    public void setZOrder(int zOrder) {
        this.zOrder = zOrder;
        applyZOrder();
    }

    /**
     * Starts rendering {@link #videoTrack} if rendering is not in progress and
     * all preconditions for the start of rendering are met.
     */
    private void tryAddRendererToVideoTrack() {
        if (!rendererAttached && videoTrack != null && ViewCompat.isAttachedToWindow(this)) {
            EglBase.Context sharedContext = EglUtils.getRootEglBaseContext();

            if (sharedContext == null) {
                // If SurfaceViewRenderer#init() is invoked, it will throw a
                // RuntimeException which will very likely kill the application.
                Log.e(TAG, "Failed to render a VideoTrack!");
                return;
            }

            try {
                initRenderer(sharedContext);
            } catch (Exception e) {
                Logging.e(
                        TAG, "Failed to initialize renderer on instance " + surfaceViewRendererInstances, e);
                return;
            }

            if (textureViewRenderer != null) {
                armTextureStartupFade();
            }

            final VideoSink sink = rendererSink;
            final VideoTrack track = videoTrack;
            addSinkToVideoTrack(track, sink);

            rendererAttached = true;
        }
    }
}
