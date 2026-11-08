package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lists the windows ScreenCaptureKit can capture.
 *
 * <p>The windows are those of {@code SCShareableContent} that are on screen, at least 2x2 points, and in an
 * application window layer: from the normal layer 0 up to the layers of floating, modal and utility windows,
 * below the Dock's layer ({@value #DOCK_LAYER}), which leaves out the Dock, the menu bar, the status items and
 * the system overlays. A window's
 * position is in points on the global display space, as a screen's is; its size is in pixels, converted
 * with the backing scale of the display that holds the window's center, as a screen's size is.
 */
final class MacWindows {
    /**
     * How long the shareable content, which may show the permission prompt, may take.
     */
    private static final long CONTENT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /**
     * The window layer of the Dock ({@code kCGDockWindowLevel}), the first layer of the system's own windows.
     */
    static final long DOCK_LAYER = 20;

    private MacWindows() {
        throw new AssertionError();
    }

    /**
     * Lists the windows.
     *
     * <p>Runs on a platform thread inside an autorelease pool.
     *
     * @return the windows
     * @throws MediaException.Io if the Screen Recording permission is missing or the content cannot be listed
     */
    static List<CaptureDevice.Window> list(ObjcBridge bridge) {
        MacDisplays.requireCapturePermission();
        var content = content(bridge);
        try {
            var windows = ObjC.id(content, ScreenCaptureKit.WINDOWS);
            var count = ObjC.count(windows);
            var displays = displays();
            var result = new ArrayList<CaptureDevice.Window>(count);
            for (var index = 0; index < count; index++) {
                var window = ObjC.element(windows, index);
                var described = describe(window, displays);
                if (described != null) {
                    result.add(described);
                }
            }
            return List.copyOf(result);
        } finally {
            ObjC.release(content);
        }
    }

    /**
     * Lists the shareable content.
     *
     * @return the retained {@code SCShareableContent}
     * @throws MediaException.Io if it cannot be listed
     */
    static MemorySegment content(ObjcBridge bridge) {
        var listed = bridge.await(ObjcBridge.Handler.OBJECT_ERROR, CONTENT_TIMEOUT_MILLIS, "listing the windows",
                block -> ObjC.call(ScreenCaptureKit.SHAREABLE_CONTENT, ScreenCaptureKit.GET_SHAREABLE_CONTENT, block));
        if (listed.error() != null || ObjC.isNull(listed.object())) {
            ObjC.release(listed.object());
            var message = "cannot list the windows available for capture";
            throw listed.error() == null ? new MediaException.Io(message, new IOException(message))
                    : listed.error().toException(message);
        }
        return listed.object();
    }

    /**
     * Finds a window of the shareable content.
     *
     * @param content the {@code SCShareableContent}
     * @param windowId the {@code CGWindowID}
     * @return the {@code SCWindow}, or {@code NULL}
     */
    static MemorySegment find(MemorySegment content, int windowId) {
        var windows = ObjC.id(content, ScreenCaptureKit.WINDOWS);
        var count = ObjC.count(windows);
        for (var index = 0; index < count; index++) {
            var window = ObjC.element(windows, index);
            if (ObjC.unsignedInt(window, ScreenCaptureKit.WINDOW_ID) == windowId) {
                return window;
            }
        }
        return MemorySegment.NULL;
    }

    /**
     * Describes a window, or returns {@code null} for one that is not listed.
     */
    private static CaptureDevice.Window describe(MemorySegment window, List<double[]> displays) {
        var layer = ObjC.integer(window, ScreenCaptureKit.WINDOW_LAYER);
        if (layer < 0 || layer >= DOCK_LAYER || !ObjC.bool(window, ScreenCaptureKit.IS_ON_SCREEN)) {
            return null;
        }
        var frame = ObjC.rect(window, ScreenCaptureKit.FRAME);
        if (frame[2] < 2 || frame[3] < 2) {
            return null;
        }
        var scale = scale(displays, frame[0] + frame[2] / 2, frame[1] + frame[3] / 2);
        var width = (int) Math.min(Integer.MAX_VALUE, Math.round(frame[2] * scale));
        var height = (int) Math.min(Integer.MAX_VALUE, Math.round(frame[3] * scale));
        if (width < 2 || height < 2) {
            return null;
        }
        var title = CoreFoundation.string(ObjC.id(window, ScreenCaptureKit.TITLE));
        var application = ObjC.id(window, ScreenCaptureKit.OWNING_APPLICATION);
        var applicationName = ObjC.isNull(application) ? null
                : CoreFoundation.string(ObjC.id(application, ScreenCaptureKit.APPLICATION_NAME));
        return new CaptureDevice.Window(Integer.toUnsignedString(ObjC.unsignedInt(window, ScreenCaptureKit.WINDOW_ID)),
                title == null ? "" : title, applicationName == null ? "" : applicationName,
                (int) Math.round(frame[0]), (int) Math.round(frame[1]), width, height);
    }

    /**
     * Lists the active displays as their bounds in points followed by their backing scale.
     *
     * @return one {@code {x, y, width, height, scale}} per display
     */
    static List<double[]> displays() {
        var displays = new ArrayList<double[]>();
        for (var id : CoreGraphics.activeDisplays()) {
            var bounds = CoreGraphics.bounds(id);
            var pixels = CoreGraphics.pixelSize(id);
            var scale = bounds[2] > 0 ? pixels[0] / bounds[2] : 1;
            displays.add(new double[]{bounds[0], bounds[1], bounds[2], bounds[3], scale > 0 ? scale : 1});
        }
        return displays;
    }

    /**
     * Returns the backing scale of the display that holds a point, or of the first display.
     *
     * @param displays the displays, as {@link #displays()} lists them
     * @param x the point's x, in points
     * @param y the point's y, in points
     * @return the pixels per point, 1 without a display
     */
    static double scale(List<double[]> displays, double x, double y) {
        for (var display : displays) {
            if (x >= display[0] && x < display[0] + display[2] && y >= display[1] && y < display[1] + display[3]) {
                return display[4];
            }
        }
        return displays.isEmpty() ? 1 : displays.getFirst()[4];
    }
}
