package com.oney.WebRTCModule;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;

import com.facebook.react.bridge.ReactContext;

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

    private static final int TEXTURE_STARTUP_STABILIZATION_FRAMES = 2;
    private static final int TEXTURE_RESIZE_STABILIZATION_FRAMES = 3;
    private static final long TEXTURE_RESIZE_BLACK_FADE_OUT_MS = 160;
    private static final long TEXTURE_BLACK_OVERLAY_HARD_TIMEOUT_MS = 1000;

    private static final String TAG = WebRTCModule.TAG;

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

    private final RendererEvents noopRendererEvents = new RendererEvents() {
        @Override
        public void onFirstFrameRendered() {
        }

        @Override
        public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
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
    private VideoTextureViewRenderer pendingTextureViewRenderer;
    private boolean pendingTextureRendererAttached;
    private boolean pendingTextureRendererInitialized;
    private boolean hasPendingTextureLayout;
    private int pendingTextureBottom;
    private int pendingTextureLeft;
    private int pendingTextureRight;
    private int pendingTextureTop;
    private View textureResizeBlackOverlayView;
    private int textureResizeFramesUntilCommit;
    private boolean textureStartupFadePending;
    private boolean textureStartupOverlayActive;
    private boolean textureStartupOverlayFading;
    private int textureStartupFramesUntilFade;
    private int textureStartupBottom;
    private int textureStartupLeft;
    private int textureStartupRight;
    private int textureStartupTop;
    private int textureBlackOverlayGeneration;

    /**
     * The {@code VideoTrack}, if any, rendered by this {@code WebRTCView}.
     */
    private VideoTrack videoTrack;

    public WebRTCView(Context context) {
        super(context);

        createRenderer(RendererType.SURFACE);

        setMirror(false);
        setScalingType(DEFAULT_SCALING_TYPE);
    }

    private void createRenderer(RendererType rendererType) {
        this.rendererType = rendererType;
        surfaceViewRenderer = null;
        textureViewRenderer = null;

        if (rendererType == RendererType.TEXTURE) {
            textureViewRenderer = createTextureRenderer();
            textureViewRenderer.setFrameRenderedListener(() -> WebRTCView.this.onTextureFrameRendered());
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
        resetTextureResizeStabilization();
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
        if (pendingTextureViewRenderer != null) {
            pendingTextureViewRenderer.setMirror(mirror);
        }
    }

    private void applyScalingType() {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.setScalingType(scalingType);
        } else if (textureViewRenderer != null) {
            textureViewRenderer.setScalingType(scalingType);
        }
        if (pendingTextureViewRenderer != null) {
            pendingTextureViewRenderer.setScalingType(scalingType);
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
        });
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
            return;
        }

        layoutTextureRendererView(left, top, right, bottom);
    }

    private void layoutTextureRendererView(int targetLeft, int targetTop, int targetRight, int targetBottom) {
        int targetWidth = targetRight - targetLeft;
        int targetHeight = targetBottom - targetTop;

        if (targetWidth <= 0 || targetHeight <= 0) {
            resetTextureResizeStabilization(!isTextureStartupOverlayOwned());
            rendererView.layout(targetLeft, targetTop, targetRight, targetBottom);
            textureViewRenderer.setLayoutAspectRatio(0f);
            maybeShowTextureStartupBlackOverlay();
            return;
        }

        float targetAspectRatio = targetWidth / (float) targetHeight;
        int currentWidth = rendererView.getWidth();
        int currentHeight = rendererView.getHeight();
        boolean hasCurrentLayout = currentWidth > 0 && currentHeight > 0;
        boolean isResizing = hasCurrentLayout && (targetWidth != currentWidth || targetHeight != currentHeight);

        if (!isResizing) {
            resetTextureResizeStabilization(!isTextureStartupOverlayOwned());
            rendererView.layout(targetLeft, targetTop, targetRight, targetBottom);
            textureViewRenderer.setLayoutAspectRatio(targetAspectRatio);
            maybeShowTextureStartupBlackOverlay();
            return;
        }

        resetTextureStartupFade(false);

        boolean targetChanged = !hasPendingTextureLayout
                || pendingTextureLeft != targetLeft
                || pendingTextureTop != targetTop
                || pendingTextureRight != targetRight
                || pendingTextureBottom != targetBottom;
        if (targetChanged || textureResizeFramesUntilCommit == 0) {
            textureResizeFramesUntilCommit = TEXTURE_RESIZE_STABILIZATION_FRAMES;
        }

        hasPendingTextureLayout = true;
        pendingTextureLeft = targetLeft;
        pendingTextureTop = targetTop;
        pendingTextureRight = targetRight;
        pendingTextureBottom = targetBottom;

        int layoutWidth = Math.max(currentWidth, targetWidth);
        int layoutHeight = Math.max(currentHeight, targetHeight);
        int layoutLeft = targetLeft + (targetWidth - layoutWidth) / 2;
        int layoutTop = targetTop + (targetHeight - layoutHeight) / 2;

        rendererView.layout(layoutLeft, layoutTop, layoutLeft + layoutWidth, layoutTop + layoutHeight);
        textureViewRenderer.setLayoutAspectRatio(layoutWidth / (float) layoutHeight);

        if (!ensurePendingTextureRenderer()) {
            return;
        }

        pendingTextureViewRenderer.layout(targetLeft, targetTop, targetRight, targetBottom);
        pendingTextureViewRenderer.setLayoutAspectRatio(targetAspectRatio);
        rendererView.bringToFront();
        showTextureResizeBlackOverlay(targetLeft, targetTop, targetRight, targetBottom);
        tryAddPendingTextureRendererToVideoTrack();
    }

    private void onTextureFrameRendered() {
        if (!textureStartupFadePending
                || !textureStartupOverlayActive
                || textureStartupFramesUntilFade <= 0
                || hasPendingTextureLayout) {
            return;
        }

        textureStartupFramesUntilFade--;
        if (textureStartupFramesUntilFade > 0) {
            return;
        }

        textureStartupFadePending = false;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = true;
        fadeOutTextureResizeBlackOverlay(() -> textureStartupOverlayFading = false);
    }

    private void onPendingTextureFrameRendered() {
        if (!hasPendingTextureLayout || textureResizeFramesUntilCommit <= 0 || pendingTextureViewRenderer == null) {
            return;
        }

        textureResizeFramesUntilCommit--;
        if (textureResizeFramesUntilCommit > 0) {
            return;
        }

        int left = pendingTextureLeft;
        int top = pendingTextureTop;
        int right = pendingTextureRight;
        int bottom = pendingTextureBottom;
        commitPendingTextureRenderer(left, top, right, bottom);
    }

    private void commitPendingTextureRenderer(int left, int top, int right, int bottom) {
        if (pendingTextureViewRenderer == null) return;

        VideoTextureViewRenderer oldTextureViewRenderer = textureViewRenderer;
        View oldRendererView = rendererView;
        VideoSink oldRendererSink = rendererSink;
        boolean oldRendererAttached = rendererAttached;

        textureViewRenderer = pendingTextureViewRenderer;
        rendererView = pendingTextureViewRenderer;
        rendererSink = pendingTextureViewRenderer;
        rendererAttached = pendingTextureRendererAttached;

        pendingTextureViewRenderer = null;
        pendingTextureRendererAttached = false;
        pendingTextureRendererInitialized = false;
        hasPendingTextureLayout = false;
        textureResizeFramesUntilCommit = 0;
        pendingTextureLeft = 0;
        pendingTextureTop = 0;
        pendingTextureRight = 0;
        pendingTextureBottom = 0;

        textureViewRenderer.setFrameRenderedListener(() -> WebRTCView.this.onTextureFrameRendered());
        textureViewRenderer.layout(left, top, right, bottom);
        int height = bottom - top;
        textureViewRenderer.setLayoutAspectRatio(height > 0 ? (right - left) / (float) height : 0f);
        textureViewRenderer.bringToFront();
        fadeOutTextureResizeBlackOverlay();

        if (oldRendererAttached && oldRendererSink != null && videoTrack != null) {
            removeSinkFromVideoTrack(videoTrack, oldRendererSink);
        }
        if (oldTextureViewRenderer != null) {
            oldTextureViewRenderer.setFrameRenderedListener(null);
            oldTextureViewRenderer.release();
            if (oldRendererAttached) {
                surfaceViewRendererInstances--;
            }
        }
        if (oldRendererView != null) {
            removeView(oldRendererView);
        }
    }

    /**
     * Stops rendering {@link #videoTrack} and releases the associated acquired
     * resources (if rendering is in progress).
     */
    private void removeRendererFromVideoTrack() {
        resetTextureStartupFade(true);
        removePendingTextureRenderer();

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

    private void resetTextureResizeStabilization() {
        resetTextureResizeStabilization(true);
    }

    private void resetTextureResizeStabilization(boolean removeOverlay) {
        hasPendingTextureLayout = false;
        textureResizeFramesUntilCommit = 0;
        pendingTextureLeft = 0;
        pendingTextureTop = 0;
        pendingTextureRight = 0;
        pendingTextureBottom = 0;
        removePendingTextureRenderer(removeOverlay);
        if (removeOverlay) {
            removeTextureResizeBlackOverlay();
        }
    }

    private boolean isTextureStartupOverlayOwned() {
        return textureStartupFadePending || textureStartupOverlayActive || textureStartupOverlayFading;
    }

    private void armTextureStartupFade() {
        if (textureViewRenderer == null) return;

        textureStartupFadePending = true;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = false;
        textureStartupFramesUntilFade = TEXTURE_STARTUP_STABILIZATION_FRAMES;
        textureStartupLeft = 0;
        textureStartupTop = 0;
        textureStartupRight = 0;
        textureStartupBottom = 0;
        maybeShowTextureStartupBlackOverlay();
    }

    private void maybeShowTextureStartupBlackOverlay() {
        if (!textureStartupFadePending || textureViewRenderer == null || hasPendingTextureLayout) return;

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
        showTextureResizeBlackOverlay(left, top, right, bottom);
    }

    private void resetTextureStartupFade(boolean removeOverlay) {
        textureStartupFadePending = false;
        textureStartupOverlayActive = false;
        textureStartupOverlayFading = false;
        textureStartupFramesUntilFade = 0;
        textureStartupLeft = 0;
        textureStartupTop = 0;
        textureStartupRight = 0;
        textureStartupBottom = 0;
        if (removeOverlay) {
            removeTextureResizeBlackOverlay();
        }
    }

    private VideoTextureViewRenderer createTextureRenderer() {
        VideoTextureViewRenderer renderer = new VideoTextureViewRenderer(getContext());
        renderer.setMirror(mirror);
        if (scalingType != null) {
            renderer.setScalingType(scalingType);
        }
        return renderer;
    }

    private boolean ensurePendingTextureRenderer() {
        if (pendingTextureViewRenderer != null) return true;

        pendingTextureViewRenderer = createTextureRenderer();
        pendingTextureViewRenderer.setFrameRenderedListener(() -> WebRTCView.this.onPendingTextureFrameRendered());
        addView(pendingTextureViewRenderer, 0);
        return true;
    }

    private void showTextureResizeBlackOverlay(int left, int top, int right, int bottom) {
        boolean createdOverlay = textureResizeBlackOverlayView == null;
        if (textureResizeBlackOverlayView == null) {
            textureResizeBlackOverlayView = new View(getContext());
            textureResizeBlackOverlayView.setBackgroundColor(Color.BLACK);
            textureResizeBlackOverlayView.setClickable(false);
            textureResizeBlackOverlayView.setFocusable(false);
            textureResizeBlackOverlayView.setAlpha(1f);
            addView(textureResizeBlackOverlayView);
        } else {
            textureResizeBlackOverlayView.animate().cancel();
            textureResizeBlackOverlayView.animate().setListener(null);
            textureResizeBlackOverlayView.setAlpha(1f);
        }

        textureResizeBlackOverlayView.layout(left, top, right, bottom);
        textureResizeBlackOverlayView.bringToFront();
        if (createdOverlay) {
            scheduleTextureBlackOverlayHardTimeout();
        }
    }

    private void scheduleTextureBlackOverlayHardTimeout() {
        final int generation = ++textureBlackOverlayGeneration;

        // Some TextureView devices do not report enough rendered frames, so the overlay needs a hard stop.
        postDelayed(() -> {
            if (generation != textureBlackOverlayGeneration || textureResizeBlackOverlayView == null) {
                return;
            }

            if (hasPendingTextureLayout) {
                int left = pendingTextureLeft;
                int top = pendingTextureTop;
                int right = pendingTextureRight;
                int bottom = pendingTextureBottom;

                resetTextureResizeStabilization(false);
                layoutTextureRendererAfterAbortedResize(left, top, right, bottom);
            }

            resetTextureStartupFade(false);
            fadeOutTextureResizeBlackOverlay();
        }, TEXTURE_BLACK_OVERLAY_HARD_TIMEOUT_MS);
    }

    private void layoutTextureRendererAfterAbortedResize(int left, int top, int right, int bottom) {
        if (textureViewRenderer == null || rendererView == null) return;
        if (right <= left || bottom <= top) return;

        rendererView.layout(left, top, right, bottom);
        textureViewRenderer.setLayoutAspectRatio((right - left) / (float) (bottom - top));
    }

    private void fadeOutTextureResizeBlackOverlay() {
        fadeOutTextureResizeBlackOverlay(null);
    }

    private void fadeOutTextureResizeBlackOverlay(Runnable onEnd) {
        if (textureResizeBlackOverlayView == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        final View overlay = textureResizeBlackOverlayView;
        overlay.bringToFront();
        overlay.animate().cancel();
        overlay.animate()
                .alpha(0f)
                .setDuration(TEXTURE_RESIZE_BLACK_FADE_OUT_MS)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        overlay.animate().setListener(null);
                        if (!canceled && textureResizeBlackOverlayView == overlay) {
                            removeTextureResizeBlackOverlay();
                        }
                        if (onEnd != null) {
                            onEnd.run();
                        }
                    }
                })
                .start();
    }

    private void removeTextureResizeBlackOverlay() {
        if (textureResizeBlackOverlayView == null) return;

        textureBlackOverlayGeneration++;
        textureResizeBlackOverlayView.animate().cancel();
        textureResizeBlackOverlayView.animate().setListener(null);
        removeView(textureResizeBlackOverlayView);
        textureResizeBlackOverlayView = null;
    }

    private void tryAddPendingTextureRendererToVideoTrack() {
        if (pendingTextureRendererAttached
                || pendingTextureViewRenderer == null
                || videoTrack == null
                || !ViewCompat.isAttachedToWindow(this)) {
            return;
        }

        EglBase.Context sharedContext = EglUtils.getRootEglBaseContext();
        if (sharedContext == null) {
            Log.e(TAG, "Failed to initialize pending TextureView renderer.");
            removePendingTextureRenderer();
            return;
        }

        try {
            pendingTextureViewRenderer.init(sharedContext, noopRendererEvents);
            pendingTextureRendererInitialized = true;
            surfaceViewRendererInstances++;
        } catch (Exception e) {
            Logging.e(TAG, "Failed to initialize pending TextureView renderer", e);
            removePendingTextureRenderer();
            return;
        }

        final VideoSink sink = pendingTextureViewRenderer;
        final VideoTrack track = videoTrack;
        ThreadUtils.runOnExecutor(() -> {
            try {
                track.addSink(sink);
            } catch (Throwable tr) {
                Log.e(TAG, "Failed to add pending TextureView renderer", tr);
            }
        });

        pendingTextureRendererAttached = true;
    }

    private void removePendingTextureRenderer() {
        removePendingTextureRenderer(true);
    }

    private void removePendingTextureRenderer(boolean removeOverlay) {
        if (pendingTextureViewRenderer == null) return;

        if (pendingTextureRendererAttached && videoTrack != null) {
            removeSinkFromVideoTrack(videoTrack, pendingTextureViewRenderer);
        }

        pendingTextureViewRenderer.setFrameRenderedListener(null);
        if (pendingTextureRendererInitialized) {
            pendingTextureViewRenderer.release();
            surfaceViewRendererInstances--;
        }
        removeView(pendingTextureViewRenderer);

        pendingTextureViewRenderer = null;
        pendingTextureRendererAttached = false;
        pendingTextureRendererInitialized = false;
        hasPendingTextureLayout = false;
        textureResizeFramesUntilCommit = 0;
        pendingTextureLeft = 0;
        pendingTextureTop = 0;
        pendingTextureRight = 0;
        pendingTextureBottom = 0;
        if (removeOverlay) {
            removeTextureResizeBlackOverlay();
        }
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
            if (oldVideoTrack != null) {
                if (videoTrack == null) {
                    // If we are not going to render any stream, clean the
                    // surface.
                    cleanSurfaceViewRenderer();
                }
                removeRendererFromVideoTrack();
            }

            this.videoTrack = videoTrack;

            if (videoTrack != null) {
                tryAddRendererToVideoTrack();
                if (oldVideoTrack == null) {
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

            rendererAttached = true;
        }
    }
}
