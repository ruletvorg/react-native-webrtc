package org.webrtc;

import android.graphics.ImageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraEnumerationAndroid {
    static final ArrayList<Size> COMMON_RESOLUTIONS = new ArrayList<>(Arrays.asList(
            new Size(160, 120),
            new Size(240, 160),
            new Size(320, 240),
            new Size(400, 240),
            new Size(480, 320),
            new Size(640, 360),
            new Size(640, 480),
            new Size(768, 480),
            new Size(854, 480),
            new Size(800, 600),
            new Size(960, 540),
            new Size(960, 640),
            new Size(1024, 576),
            new Size(1024, 600),
            new Size(1280, 720),
            new Size(1280, 1024),
            new Size(1920, 1080),
            new Size(1920, 1440),
            new Size(2560, 1440),
            new Size(3840, 2160)
    ));

    public static CaptureFormat.FramerateRange getClosestSupportedFramerateRange(
            List<CaptureFormat.FramerateRange> ranges,
            int requestedFps
    ) {
        return Collections.min(ranges, new ClosestComparator<CaptureFormat.FramerateRange>() {
            private int progressivePenalty(int value, int threshold, int lowWeight, int highWeight) {
                return value < threshold
                        ? value * lowWeight
                        : threshold * lowWeight + (value - threshold) * highWeight;
            }

            @Override
            int diff(CaptureFormat.FramerateRange range) {
                int requestedFpsMillis = requestedFps * 1000;
                int minFpsPenalty = progressivePenalty(range.min, 8000, 1, 4);
                int maxFpsPenalty = progressivePenalty(
                        Math.abs(requestedFpsMillis - range.max),
                        5000,
                        1,
                        3
                );

                if (requestedFps >= 55
                        && range.min == range.max
                        && Math.abs(range.max - requestedFpsMillis) <= 5000) {
                    minFpsPenalty = -100000;
                }

                return minFpsPenalty + maxFpsPenalty;
            }
        });
    }

    public static Size getClosestSupportedSize(List<Size> sizes, int requestedWidth, int requestedHeight) {
        return Collections.min(sizes, new ClosestComparator<Size>() {
            @Override
            int diff(Size size) {
                return Math.abs(requestedWidth - size.width) + Math.abs(requestedHeight - size.height);
            }
        });
    }

    static void reportCameraResolution(Histogram histogram, Size size) {
        histogram.addSample(COMMON_RESOLUTIONS.indexOf(size) + 1);
    }

    public static class CaptureFormat {
        public final int width;
        public final int height;
        public final FramerateRange framerate;
        public final int imageFormat;

        public CaptureFormat(int width, int height, int minFramerate, int maxFramerate) {
            this.imageFormat = ImageFormat.NV21;
            this.width = width;
            this.height = height;
            this.framerate = new FramerateRange(minFramerate, maxFramerate);
        }

        public CaptureFormat(int width, int height, FramerateRange framerate) {
            this.imageFormat = ImageFormat.NV21;
            this.width = width;
            this.height = height;
            this.framerate = framerate;
        }

        public int frameSize() {
            return frameSize(width, height, imageFormat);
        }

        public static int frameSize(int width, int height, int imageFormat) {
            if (imageFormat != ImageFormat.NV21) {
                throw new UnsupportedOperationException(
                        "Don't know how to calculate the frame size of non-NV21 image formats."
                );
            }
            return width * height * ImageFormat.getBitsPerPixel(imageFormat) / 8;
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + framerate;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof CaptureFormat)) {
                return false;
            }
            CaptureFormat format = (CaptureFormat) object;
            return width == format.width
                    && height == format.height
                    && framerate.equals(format.framerate);
        }

        @Override
        public int hashCode() {
            return 1 + (width * 65497 + height) * 251 + framerate.hashCode();
        }

        public static class FramerateRange {
            public int min;
            public int max;

            public FramerateRange(int min, int max) {
                this.min = min;
                this.max = max;
            }

            @Override
            public String toString() {
                return (min / 1000.0f) + ":" + (max / 1000.0f);
            }

            @Override
            public boolean equals(Object object) {
                if (!(object instanceof FramerateRange)) {
                    return false;
                }
                FramerateRange range = (FramerateRange) object;
                return min == range.min && max == range.max;
            }

            @Override
            public int hashCode() {
                return 1 + 65537 * min + max;
            }
        }
    }

    abstract static class ClosestComparator<T> implements Comparator<T> {
        abstract int diff(T value);

        @Override
        public int compare(T first, T second) {
            return diff(first) - diff(second);
        }
    }
}
