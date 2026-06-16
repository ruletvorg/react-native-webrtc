package com.oney.WebRTCModule;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import org.webrtc.VideoCapturer;

public abstract class AbstractVideoCaptureController {
    protected int targetWidth;
    protected int targetHeight;
    protected int targetFps;

    protected int actualWidth;
    protected int actualHeight;
    protected int actualFps;

    /**
     * {@link VideoCapturer} which this controller manages.
     */
    protected VideoCapturer videoCapturer;

    protected CapturerEventsListener capturerEventsListener;
    private boolean captureStarted;

    public AbstractVideoCaptureController(int width, int height, int fps) {
        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFps = fps;
        this.actualWidth = width;
        this.actualHeight = height;
        this.actualFps = fps;
    }

    public void initializeVideoCapturer() {
        videoCapturer = createVideoCapturer();
    }

    @Nullable
    public abstract String getDeviceId();

    public synchronized void dispose() {
        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
        }
        captureStarted = false;
    }

    public int getHeight() {
        return actualHeight;
    }

    public int getWidth() {
        return actualWidth;
    }

    public int getFrameRate() {
        return actualFps;
    }

    public WritableMap getSettings() {
        WritableMap settings = Arguments.createMap();
        settings.putString("deviceId", getDeviceId());
        settings.putString("groupId", "");
        settings.putInt("height", getHeight());
        settings.putInt("width", getWidth());
        settings.putInt("frameRate", getFrameRate());
        return settings;
    }

    public VideoCapturer getVideoCapturer() {
        return videoCapturer;
    }

    public synchronized void startCapture() {
        try {
            if (videoCapturer != null && !captureStarted) {
                videoCapturer.startCapture(targetWidth, targetHeight, targetFps);
                captureStarted = true;
            }
        } catch (RuntimeException e) {
            // XXX This can only fail if we initialize the capturer incorrectly,
            // which we don't. Thus, ignore any failures here since we trust
            // ourselves.
        }
    }

    public synchronized boolean stopCapture() {
        try {
            if (videoCapturer != null && captureStarted) {
                captureStarted = false;
                videoCapturer.stopCapture();
            }
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    protected synchronized void markCaptureStopped() {
        captureStarted = false;
    }

    protected void notifyCapturerEnded() {
        markCaptureStopped();
        if (capturerEventsListener != null) {
            capturerEventsListener.onCapturerEnded();
        }
    }

    public void startCaptureAsync() {
        ThreadUtils.runOnCaptureExecutor(this::startCapture);
    }

    public void stopCaptureAsync() {
        ThreadUtils.runOnCaptureExecutor(this::stopCapture);
    }

    public void applyConstraints(ReadableMap constraints, @Nullable Consumer<Exception> onFinishedCallback) {
        if (onFinishedCallback != null) {
            onFinishedCallback.accept(new UnsupportedOperationException("This video track does not support applyConstraints."));
        }
    }

    public void setCapturerEventsListener(CapturerEventsListener listener) {
        this.capturerEventsListener = listener;
    }

    protected abstract VideoCapturer createVideoCapturer();

    public interface CapturerEventsListener {
        /** Called when the capturer is ended and in an irrecoverable state. */
        public void onCapturerEnded();
    }
}
