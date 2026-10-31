package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.internal.Pictures;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures the {@code vivid} test cameras CI provisions in every pixel format and field order they
 * offer, and compares each capture with the same test pattern captured in a reference format: a
 * single-planar camera named by {@code SMALT_TEST_VIVID} and a multi-planar one named by
 * {@code SMALT_TEST_VIVID_MPLANE}. It also grabs the X11 displays named by
 * {@code SMALT_TEST_X11_DEPTHS} ({@code display=bitsPerPixel=RRGGBB,...}), servers of depth 16, 15 and 30
 * whose root windows were painted a solid color. The patterns are made static with
 * {@code v4l2-ctl} first (no text overlay), so captures in different formats show the same image.
 */
@DisplayName("Linux formats (live)")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class V4l2FormatsLiveTest {
    /**
     * The largest mean absolute difference, per R'G'B' channel, between a capture and the reference.
     */
    private static final double COLOR_TOLERANCE = 8;

    @Test
    @DisplayName("every pixel format of the single-planar vivid camera shows the reference pattern")
    void singlePlanarFormats() {
        sweep(Skip.setting("smalt.test.vivid", "SMALT_TEST_VIVID"), "camera-formats");
    }

    @Test
    @DisplayName("every pixel format of the multi-planar vivid camera shows the reference pattern")
    void multiPlanarFormats() {
        sweep(Skip.setting("smalt.test.vividMplane", "SMALT_TEST_VIVID_MPLANE"), "camera-multiplanar");
    }

    @Test
    @DisplayName("every field order of the vivid S-Video input is woven or doubled into the interlaced frame")
    void fields() {
        var path = Skip.setting("smalt.test.vivid", "SMALT_TEST_VIVID");
        Skip.unlessDevice(!path.isEmpty(), "camera-fields", "set SMALT_TEST_VIVID to a vivid node");
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            var input = run("v4l2-ctl", "-d", path, "--list-inputs");
            var svideo = Pattern.compile("Input\\s*:\\s*(\\d+)\\s*\\n\\s*Name\\s*:\\s*S-Video").matcher(input);
            assertTrue(svideo.find(), input);
            run("v4l2-ctl", "-d", path, "-i", svideo.group(1));
            try {
                run("v4l2-ctl", "-d", path, "--set-standard=ntsc");
                still(path, "Alternating Hor Lines");
                var camera = V4l2Cameras.find(path).orElseThrow();
                var ntsc = new CaptureMode(720, 480, new Rational(30_000, 1001));
                var interlaced = luma(capture(camera, ntsc, V4l2PixelFormat.YUYV, V4l2.FIELD_INTERLACED));
                var rows = interlaced.length;
                assertTrue(contrast(interlaced) > 100, "the pattern alternates its rows");
                for (var field : new int[]{V4l2.FIELD_INTERLACED_TB, V4l2.FIELD_INTERLACED_BT, V4l2.FIELD_SEQ_TB,
                        V4l2.FIELD_SEQ_BT, V4l2.FIELD_ALTERNATE, V4l2.FIELD_TOP, V4l2.FIELD_BOTTOM}) {
                    var picture = luma(capture(camera, ntsc, V4l2PixelFormat.YUYV, field));
                    assertEquals(rows, picture.length, "field " + field);
                    var expected = interlaced;
                    if (field == V4l2.FIELD_TOP || field == V4l2.FIELD_BOTTOM) {
                        expected = new int[rows][];
                        for (var row = 0; row < rows; row++) {
                            expected[row] = interlaced[2 * (row / 2) + (field == V4l2.FIELD_BOTTOM ? 1 : 0)];
                        }
                    }
                    var difference = meanDifference(expected, picture);
                    System.out.println("field " + field + " differs by " + difference);
                    assertTrue(difference < COLOR_TOLERANCE, "field " + field + " differs by " + difference + "; rows "
                            + rowDifferences(expected, picture));
                }
            } finally {
                run("v4l2-ctl", "-d", path, "-i", "0");
            }
        });
    }

    @Test
    @DisplayName("X11 servers of depth 16, 15 and 30 are grabbed with their root window color")
    void x11Depths() {
        var setting = Skip.setting("smalt.test.x11Depths", "SMALT_TEST_X11_DEPTHS");
        Skip.unlessDevice(!setting.isEmpty(), "x11-depths", "set SMALT_TEST_X11_DEPTHS to display=bitsPerPixel=RRGGBB entries");
        var x11 = X11.instance();
        assertNotNull(x11);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var entry : setting.split(",")) {
                var parts = entry.split("=");
                var display = parts[0];
                var bits = Integer.parseInt(parts[1]);
                var color = Integer.parseInt(parts[2], 16);
                var screen = new CaptureDevice.Screen(display, display, 0, 0, 1 << 16, 1 << 16, true);
                // the pointer, which an X server starts at the center, is left out of the color check
                var options = ScreenCaptureOptions.builder().cursor(false).build();
                for (var shm : new boolean[]{true, false}) {
                    var grabber = X11ScreenGrabber.open(x11, display, screen, options, shm);
                    try {
                        var what = display + (grabber.shared() ? " through MIT-SHM" : " through XGetImage");
                        assertEquals(shm, grabber.shared(), what);
                        assertEquals(bits / 8, grabber.bytesPerPixel(), what);
                        var rgba = ImageOps.toRgba(grabber.grab());
                        for (var point : new int[][]{{rgba.width() / 2, rgba.height() / 2}, {4, 4}, {rgba.width() - 5, rgba.height() - 5}}) {
                            var offset = point[1] * rgba.stride() + point[0] * 4;
                            for (var channel = 0; channel < 3; channel++) {
                                var actual = rgba.pixels()[offset + channel] & 0xFF;
                                var wanted = (color >> (16 - 8 * channel)) & 0xFF;
                                assertTrue(Math.abs(actual - wanted) <= 8, what + " channel " + channel + " is " + actual
                                        + " instead of " + wanted + " (the server holds " + raw(x11, display, point[0], point[1]) + ")");
                            }
                        }
                    } finally {
                        grabber.close();
                    }
                }
            }
        });
    }

    /**
     * Reads one pixel of a display's root window with {@code XGetImage} and describes it.
     */
    private static String raw(X11 x11, String name, int x, int y) {
        var display = x11.openDisplay(name);
        try {
            var root = x11.rootWindow(display, x11.defaultScreen(display));
            var image = x11.getImage(display, root, x, y, 1, 1);
            if (image.equals(java.lang.foreign.MemorySegment.NULL)) {
                return "XGetImage failed";
            }
            try {
                var header = image.reinterpret(X11.IMAGE.byteSize());
                var bits = header.get(java.lang.foreign.ValueLayout.JAVA_INT, X11.IMAGE_BITS_PER_PIXEL);
                var data = java.lang.foreign.MemorySegment.ofAddress(header.get(java.lang.foreign.ValueLayout.JAVA_LONG, X11.IMAGE_DATA))
                        .reinterpret(4);
                return "XGetImage " + bits + " bpp, masks " + Long.toHexString(header.get(java.lang.foreign.ValueLayout.JAVA_LONG,
                        X11.IMAGE_RED_MASK)) + "/" + Long.toHexString(header.get(java.lang.foreign.ValueLayout.JAVA_LONG,
                        X11.IMAGE_GREEN_MASK)) + "/" + Long.toHexString(header.get(java.lang.foreign.ValueLayout.JAVA_LONG,
                        X11.IMAGE_BLUE_MASK)) + ", bytes " + java.util.HexFormat.of().formatHex(data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            } finally {
                x11.destroyImage(image);
            }
        } finally {
            x11.closeDisplay(display);
        }
    }

    /**
     * Captures a vivid camera in every format it offers and compares each with its YUYV capture.
     */
    private static void sweep(String path, String device) {
        Skip.unlessDevice(!path.isEmpty(), device, "set the vivid node of " + device);
        assertTimeoutPreemptively(Duration.ofSeconds(300), () -> {
            still(path, "75% Colorbar");
            var camera = V4l2Cameras.find(path).orElseThrow();
            var formats = formats(path);
            assertTrue(formats.size() > 4, "vivid offers " + formats);
            var reference = formats.contains(V4l2PixelFormat.YUYV) ? V4l2PixelFormat.YUYV : formats.getFirst();
            var expected = rgba(capture(camera, reference, V4l2.FIELD_ANY));
            still(path, "Gray Ramp");
            var expectedLuma = luma(capture(camera, reference, V4l2.FIELD_ANY));
            var failures = new ArrayList<String>();
            var differences = new ArrayList<String>();
            for (var format : formats) {
                if (format.kind() != V4l2PixelFormat.Kind.LUMA) {
                    continue;
                }
                var difference = meanDifference(expectedLuma, luma(capture(camera, format, V4l2.FIELD_ANY)));
                differences.add(format + " " + String.format("%.2f", difference));
                if (!(difference < COLOR_TOLERANCE)) {
                    failures.add(format + " differs by " + difference);
                }
            }
            still(path, "75% Colorbar");
            for (var format : formats) {
                if (format.kind() == V4l2PixelFormat.Kind.LUMA) {
                    continue;
                }
                var actual = rgba(capture(camera, format, V4l2.FIELD_ANY));
                assertEquals(expected.width(), actual.width(), format.toString());
                var difference = 0.0;
                for (var channel = 0; channel < 3; channel++) {
                    difference = Math.max(difference, meanDifference(expected, actual, channel));
                }
                differences.add(format + " " + String.format("%.2f", difference));
                if (!(difference < COLOR_TOLERANCE)) {
                    failures.add(format + " differs by " + difference);
                }
            }
            System.out.println(path + " format differences: " + differences);
            assertTrue(failures.isEmpty(), "formats " + formats + ": " + failures);
        });
    }

    /**
     * Lists the formats of a node this backend converts, through the node's own buffer type.
     */
    private static List<V4l2PixelFormat> formats(String path) {
        var posix = new Posix();
        var fd = posix.open(path, Posix.O_RDWR | Posix.O_NONBLOCK | Posix.O_CLOEXEC);
        assertTrue(fd >= 0, path);
        try {
            V4l2Device requests = (request, argument) -> posix.ioctl(fd, request, argument);
            return V4l2Formats.pixelFormats(V4l2Formats.query(requests).device(requests));
        } finally {
            posix.close(fd);
        }
    }

    /**
     * Captures the fifth frame of a camera in a pixel format and field order and copies it.
     */
    private static YuvPicture capture(CaptureDevice.Camera camera, V4l2PixelFormat format, int field) throws InterruptedException {
        return capture(camera, null, format, field);
    }

    /**
     * Captures the fifth frame of a camera in a mode, pixel format and field order and copies it.
     */
    private static YuvPicture capture(CaptureDevice.Camera camera, CaptureMode mode, V4l2PixelFormat format, int field)
            throws InterruptedException {
        try (var session = V4l2CameraSession.open(camera, mode, format, field)) {
            YuvPicture last = null;
            for (var frame = 0; frame < 5; frame++) {
                var read = session.read();
                assertNotNull(read, format + " field " + field);
                last = read.picture();
            }
            var copy = YuvPicture.allocate(last.width(), last.height(), last.chromaFormat(), false, last.color());
            Pictures.copy(last, copy);
            return copy;
        }
    }

    /**
     * Converts a picture to R'G'B'.
     */
    private static RgbaPicture rgba(YuvPicture picture) {
        return ImageOps.toRgba(picture);
    }

    /**
     * Returns the full-range luma of a picture, by row.
     */
    private static int[][] luma(YuvPicture picture) {
        var limited = picture.color().range() == ColorRange.LIMITED;
        var rows = new int[picture.height()][picture.width()];
        for (var row = 0; row < picture.height(); row++) {
            for (var x = 0; x < picture.width(); x++) {
                var sample = picture.y()[row * picture.yStride() + x] & 0xFF;
                rows[row][x] = limited ? (int) Math.round(Math.clamp((sample - 16) * 255.0 / 219.0, 0, 255)) : sample;
            }
        }
        return rows;
    }

    /**
     * Describes the rows whose mean difference is largest.
     */
    private static String rowDifferences(int[][] expected, int[][] actual) {
        var text = new StringBuilder();
        for (var row = 0; row < Math.min(expected.length, actual.length) && text.length() < 2000; row++) {
            var total = 0L;
            for (var x = 0; x < Math.min(expected[row].length, actual[row].length); x++) {
                total += Math.abs(expected[row][x] - actual[row][x]);
            }
            var mean = total / (double) expected[row].length;
            if (mean > 20) {
                text.append(row).append('=').append(String.format("%.0f", mean)).append("(e").append(expected[row][expected[row].length / 2])
                        .append(",a").append(actual[row][actual[row].length / 2]).append(") ");
            }
        }
        return text.toString();
    }

    /**
     * Returns the mean difference between the average luma of the even and the odd rows.
     */
    private static double contrast(int[][] luma) {
        var even = 0L;
        var odd = 0L;
        for (var row = 0; row + 1 < luma.length; row += 2) {
            for (var x = 0; x < luma[row].length; x++) {
                even += luma[row][x];
                odd += luma[row + 1][x];
            }
        }
        return Math.abs(even - odd) / (double) (luma.length / 2 * luma[0].length);
    }

    private static double meanDifference(int[][] expected, int[][] actual) {
        var total = 0L;
        var count = 0L;
        for (var row = 0; row < Math.min(expected.length, actual.length); row++) {
            for (var x = 0; x < Math.min(expected[row].length, actual[row].length); x++) {
                total += Math.abs(expected[row][x] - actual[row][x]);
                count++;
            }
        }
        return total / (double) count;
    }

    private static double meanDifference(RgbaPicture expected, RgbaPicture actual, int channel) {
        var total = 0L;
        var count = 0L;
        for (var row = 0; row < Math.min(expected.height(), actual.height()); row++) {
            for (var x = 0; x < Math.min(expected.width(), actual.width()); x++) {
                total += Math.abs((expected.pixels()[row * expected.stride() + 4 * x + channel] & 0xFF)
                        - (actual.pixels()[row * actual.stride() + 4 * x + channel] & 0xFF));
                count++;
            }
        }
        return total / (double) count;
    }

    /**
     * Selects a vivid test pattern by name and turns the text overlay off.
     */
    private static void still(String path, String pattern) throws IOException, InterruptedException {
        var menus = run("v4l2-ctl", "-d", path, "--list-ctrls-menus");
        var index = Pattern.compile("(\\d+): " + Pattern.quote(pattern) + "\\s*\\n").matcher(menus);
        assertTrue(index.find(), "no test pattern " + pattern + " in " + menus);
        run("v4l2-ctl", "-d", path, "--set-ctrl=test_pattern=" + index.group(1));
        var osd = Pattern.compile("osd_text_mode.*?\\n((?:\\s+\\d+: .*\\n)+)").matcher(menus);
        if (osd.find()) {
            var none = Pattern.compile("(\\d+): None").matcher(osd.group(1));
            if (none.find()) {
                run("v4l2-ctl", "-d", path, "--set-ctrl=osd_text_mode=" + none.group(1));
            }
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
        return output;
    }
}
