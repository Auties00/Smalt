package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks how camera media types become capture modes and how a native type is chosen for a mode.
 */
@DisplayName("camera media types")
class NativeTypeTest {
    private static int index;

    private static NativeType type(CameraFormat format, int width, int height, long numerator, long denominator) {
        return new NativeType(index++, format, width, height, new Rational(numerator, denominator).reduce(), 0, ColorInfo.BT601_LIMITED);
    }

    @Test
    @DisplayName("recognizes the FourCC and D3DFORMAT subtypes and rejects others")
    void formats() {
        assertEquals(CameraFormat.NV12, CameraFormat.of(0x3231564E));
        assertEquals(CameraFormat.YUY2, CameraFormat.of(0x32595559));
        assertEquals(CameraFormat.MJPG, CameraFormat.of(0x47504A4D));
        assertEquals(CameraFormat.RGB32, CameraFormat.of(22));
        assertEquals(CameraFormat.RGB24, CameraFormat.of(20));
        assertNull(CameraFormat.of(0x34363248));
    }

    @Test
    @DisplayName("lists distinct modes, largest first, then fastest")
    void modes() {
        var types = List.of(
                type(CameraFormat.YUY2, 640, 480, 30, 1),
                type(CameraFormat.MJPG, 1920, 1080, 30000, 1001),
                type(CameraFormat.NV12, 640, 480, 30, 1),
                type(CameraFormat.MJPG, 1920, 1080, 60, 1),
                type(CameraFormat.YUY2, 1280, 720, 10000000, 333333));
        var modes = NativeType.modes(types);
        assertEquals(List.of(
                new CaptureMode(1920, 1080, new Rational(60, 1)),
                new CaptureMode(1920, 1080, new Rational(30000, 1001)),
                new CaptureMode(1280, 720, new Rational(10000000, 333333)),
                CaptureMode.of(640, 480, 30)), modes);
    }

    @Test
    @DisplayName("chooses the preferred format at the requested size and rate")
    void preference() {
        var mjpg = type(CameraFormat.MJPG, 1280, 720, 30, 1);
        var yuy2 = type(CameraFormat.YUY2, 1280, 720, 30, 1);
        var nv12 = type(CameraFormat.NV12, 1280, 720, 30, 1);
        var rgb = type(CameraFormat.RGB32, 1280, 720, 30, 1);
        var mode = CaptureMode.of(1280, 720, 30);
        assertSame(nv12, NativeType.choose(List.of(mjpg, yuy2, rgb, nv12), mode, null));
        assertSame(yuy2, NativeType.choose(List.of(mjpg, yuy2, rgb), mode, null));
        assertSame(mjpg, NativeType.choose(List.of(rgb, mjpg), mode, null));
        assertSame(rgb, NativeType.choose(List.of(rgb), mode, null));
    }

    @Test
    @DisplayName("matches frame rates within one percent and nothing else")
    void rates() {
        var ntsc = type(CameraFormat.YUY2, 640, 480, 30000, 1001);
        var sixty = type(CameraFormat.YUY2, 640, 480, 60, 1);
        var types = List.of(ntsc, sixty);
        assertSame(ntsc, NativeType.choose(types, CaptureMode.of(640, 480, 30), null));
        assertSame(sixty, NativeType.choose(types, new CaptureMode(640, 480, new Rational(60000, 1001)), null));
        assertNull(NativeType.choose(types, CaptureMode.of(640, 480, 25), null));
        assertNull(NativeType.choose(types, CaptureMode.of(640, 360, 30), null));
    }

    @Test
    @DisplayName("defaults to the driver's current size and rate, else to the largest mode up to 1080p near 30 fps")
    void defaults() {
        var small = type(CameraFormat.YUY2, 640, 480, 30, 1);
        var hd = type(CameraFormat.MJPG, 1920, 1080, 30, 1);
        var hdFast = type(CameraFormat.MJPG, 1920, 1080, 60, 1);
        var uhd = type(CameraFormat.MJPG, 3840, 2160, 30, 1);
        var smallNv12 = type(CameraFormat.NV12, 640, 480, 30, 1);
        var types = List.of(small, hd, hdFast, uhd, smallNv12);
        assertSame(smallNv12, NativeType.choose(types, null, small));
        assertSame(hd, NativeType.choose(types, null, null));
        var unsupportedCurrent = type(CameraFormat.YUY2, 800, 600, 15, 1);
        assertSame(hd, NativeType.choose(types, null, unsupportedCurrent));
        assertSame(uhd, NativeType.choose(List.of(uhd), null, null));
        assertNull(NativeType.choose(List.of(), null, null));
    }
}
