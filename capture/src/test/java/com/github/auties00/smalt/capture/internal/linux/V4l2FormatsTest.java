package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.internal.convert.ScalarConversionKernel;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the enumeration, mode selection and negotiation logic against a simulated driver
 * ({@link FakeCamera}) that fills the kernel structures byte for byte.
 */
@DisplayName("V4L2 formats")
class V4l2FormatsTest {
    private static int[] size(int width, int height) {
        return new int[]{width, height};
    }

    /** A typical UVC webcam: YUYV up to 1280x720 (slow at the top), MJPEG up to 1920x1080 at 30. */
    private static FakeCamera webcam() {
        var camera = new FakeCamera()
                .format(V4l2PixelFormat.YUYV, size(640, 480), size(1280, 720))
                .format(V4l2PixelFormat.MJPEG, size(640, 480), size(1280, 720), size(1920, 1080))
                .rawFormat(V4l2.fourcc('H', '2', '6', '4'));
        camera.rates(V4l2PixelFormat.YUYV, 640, 480, 30, 15);
        camera.rates(V4l2PixelFormat.YUYV, 1280, 720, 10, 5);
        camera.rates(V4l2PixelFormat.MJPEG, 640, 480, 30);
        camera.rates(V4l2PixelFormat.MJPEG, 1280, 720, 30);
        camera.rates(V4l2PixelFormat.MJPEG, 1920, 1080, 30);
        return camera;
    }

    @Nested
    @DisplayName("capabilities")
    class Capabilities {
        @Test
        @DisplayName("device_caps describes the node when V4L2_CAP_DEVICE_CAPS is set")
        void deviceCaps() {
            var camera = new FakeCamera();
            camera.card = "HD Pro Webcam C920";
            var capability = V4l2Formats.query(camera);
            assertEquals("HD Pro Webcam C920", capability.card());
            assertTrue(capability.canCapture());
        }

        @Test
        @DisplayName("a UVC metadata node is not a capture node")
        void metadataNode() {
            var camera = new FakeCamera();
            camera.capabilities = V4l2.CAP_VIDEO_CAPTURE | V4l2.CAP_META_CAPTURE | V4l2.CAP_STREAMING | V4l2.CAP_DEVICE_CAPS;
            camera.deviceCaps = V4l2.CAP_META_CAPTURE | V4l2.CAP_STREAMING;
            assertFalse(V4l2Formats.query(camera).canCapture());
        }

        @Test
        @DisplayName("capabilities is used when device_caps is not valid")
        void legacyCapabilities() {
            var camera = new FakeCamera();
            camera.capabilities = V4l2.CAP_VIDEO_CAPTURE | V4l2.CAP_STREAMING;
            camera.deviceCaps = 0;
            assertTrue(V4l2Formats.query(camera).canCapture());
        }

        @Test
        @DisplayName("multi-planar-only and read-only nodes are rejected")
        void unsupportedNodes() {
            var multiPlanar = new FakeCamera();
            multiPlanar.deviceCaps = V4l2.CAP_VIDEO_CAPTURE_MPLANE | V4l2.CAP_STREAMING;
            assertFalse(V4l2Formats.query(multiPlanar).canCapture());
            var readOnly = new FakeCamera();
            readOnly.deviceCaps = V4l2.CAP_VIDEO_CAPTURE;
            assertFalse(V4l2Formats.query(readOnly).canCapture());
        }

        @Test
        @DisplayName("an empty card name gets a placeholder and a failing query raises Io")
        void emptyCardAndFailure() {
            var camera = new FakeCamera();
            camera.card = "";
            assertEquals("Camera", V4l2Formats.query(camera).card());
            camera.errors.put(V4l2.VIDIOC_QUERYCAP, Posix.ENOTTY);
            var failure = assertThrows(MediaException.Io.class, () -> V4l2Formats.query(camera));
            assertTrue(failure.getMessage().contains("ENOTTY"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("enumeration")
    class Enumeration {
        @Test
        @DisplayName("pixel formats come in preference order and unknown ones are skipped")
        void pixelFormats() {
            var camera = new FakeCamera().format(V4l2PixelFormat.MJPEG).rawFormat(V4l2.fourcc('H', '2', '6', '4'))
                    .format(V4l2PixelFormat.YUYV).format(V4l2PixelFormat.NV12);
            assertEquals(List.of(V4l2PixelFormat.NV12, V4l2PixelFormat.YUYV, V4l2PixelFormat.MJPEG), V4l2Formats.pixelFormats(camera));
        }

        @Test
        @DisplayName("a driver that never ends the list is cut off")
        void endlessEnumeration() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV);
            camera.endlessFormats = true;
            assertEquals(List.of(V4l2PixelFormat.YUYV), V4l2Formats.pixelFormats(camera));
            assertEquals(V4l2Formats.MAX_ENTRIES + 0L, camera.requests.stream().filter(r -> r == V4l2.VIDIOC_ENUM_FMT).count());
        }

        @Test
        @DisplayName("a failing enumeration raises Io")
        void failure() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV);
            camera.errors.put(V4l2.VIDIOC_ENUM_FMT, Posix.EIO);
            assertThrows(MediaException.Io.class, () -> V4l2Formats.pixelFormats(camera));
        }

        @Test
        @DisplayName("discrete sizes and rates are read as they are")
        void discrete() {
            var camera = webcam();
            var sizes = V4l2Formats.frameSizes(camera, V4l2PixelFormat.MJPEG);
            assertEquals(List.of(new V4l2Formats.Size(640, 480), new V4l2Formats.Size(1280, 720), new V4l2Formats.Size(1920, 1080)),
                    sizes.candidates());
            assertTrue(sizes.contains(1280, 720));
            assertFalse(sizes.contains(1280, 721));
            var rates = V4l2Formats.frameRates(camera, V4l2PixelFormat.YUYV, 640, 480);
            assertEquals(List.of(new Rational(30, 1), new Rational(15, 1)), rates.candidates());
            assertTrue(rates.contains(new Rational(15, 1)));
            assertFalse(rates.contains(new Rational(25, 1)));
        }

        @Test
        @DisplayName("NTSC intervals become exact rational rates")
        void ntscRates() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV, size(720, 480));
            camera.discreteIntervals.put(FakeCamera.key(V4l2PixelFormat.YUYV.fourcc(), 720, 480), List.of(new int[]{1001, 30000}));
            var rates = V4l2Formats.frameRates(camera, V4l2PixelFormat.YUYV, 720, 480);
            assertEquals(List.of(new Rational(30000, 1001)), rates.candidates());
        }

        @Test
        @DisplayName("a stepwise size range contributes its largest size and the common sizes on its steps")
        void stepwiseSizes() {
            var camera = new FakeCamera();
            camera.formats.add(V4l2PixelFormat.YUYV.fourcc());
            camera.sizeRanges.put(V4l2PixelFormat.YUYV.fourcc(), new int[]{V4l2.TYPE_STEPWISE, 320, 1920, 16, 240, 1080, 8});
            var sizes = V4l2Formats.frameSizes(camera, V4l2PixelFormat.YUYV);
            var candidates = sizes.candidates();
            assertEquals(new V4l2Formats.Size(1920, 1080), candidates.getFirst());
            assertTrue(candidates.contains(new V4l2Formats.Size(1280, 720)));
            assertTrue(candidates.contains(new V4l2Formats.Size(640, 480)));
            assertFalse(candidates.contains(new V4l2Formats.Size(424, 240)), "424 is not on the 16-pixel step from 320");
            assertFalse(candidates.contains(new V4l2Formats.Size(176, 144)), "below the minimum");
            assertTrue(sizes.contains(336, 248));
            assertFalse(sizes.contains(330, 248));
        }

        @Test
        @DisplayName("a continuous size range accepts every size within its bounds")
        void continuousSizes() {
            var camera = new FakeCamera();
            camera.sizeRanges.put(V4l2PixelFormat.NV12.fourcc(), new int[]{V4l2.TYPE_CONTINUOUS, 2, 4096, 0, 2, 2160, 0});
            var sizes = V4l2Formats.frameSizes(camera, V4l2PixelFormat.NV12);
            assertTrue(sizes.contains(333, 777));
            assertTrue(sizes.candidates().contains(new V4l2Formats.Size(3840, 2160)));
        }

        @Test
        @DisplayName("an inconsistent range is treated as unknown")
        void inconsistentRange() {
            var camera = new FakeCamera();
            camera.sizeRanges.put(V4l2PixelFormat.NV12.fourcc(), new int[]{V4l2.TYPE_STEPWISE, 640, 320, 16, 480, 240, 16});
            var sizes = V4l2Formats.frameSizes(camera, V4l2PixelFormat.NV12);
            assertTrue(sizes.candidates().isEmpty());
            assertTrue(sizes.contains(1234, 567));
        }

        @Test
        @DisplayName("an interval range contributes its highest rate and the common rates within it")
        void intervalRange() {
            var camera = new FakeCamera().format(V4l2PixelFormat.NV12, size(1280, 720));
            camera.intervalRanges.put(FakeCamera.key(V4l2PixelFormat.NV12.fourcc(), 1280, 720),
                    new int[]{V4l2.TYPE_CONTINUOUS, 1, 90, 1, 12});
            var rates = V4l2Formats.frameRates(camera, V4l2PixelFormat.NV12, 1280, 720);
            assertEquals(List.of(new Rational(90, 1), new Rational(60, 1), new Rational(50, 1), new Rational(30, 1),
                    new Rational(25, 1), new Rational(24, 1), new Rational(20, 1), new Rational(15, 1)), rates.candidates());
            assertTrue(rates.contains(new Rational(12, 1)));
            assertTrue(rates.contains(new Rational(30000, 1001)));
            assertFalse(rates.contains(new Rational(10, 1)));
            assertFalse(rates.contains(new Rational(91, 1)));
        }

        @Test
        @DisplayName("a driver without interval enumeration gets the fallback rate")
        void unknownRates() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV, size(640, 480));
            var rates = V4l2Formats.frameRates(camera, V4l2PixelFormat.YUYV, 640, 480);
            assertEquals(List.of(V4l2Formats.FALLBACK_RATE), rates.candidates());
            assertTrue(rates.contains(new Rational(7, 1)));
        }

        @Test
        @DisplayName("zero intervals and zero sizes are ignored")
        void zeroEntries() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV, size(0, 480), size(640, 480));
            camera.discreteIntervals.put(FakeCamera.key(V4l2PixelFormat.YUYV.fourcc(), 640, 480),
                    List.of(new int[]{0, 30}, new int[]{1, 0}, new int[]{1, 30}));
            assertEquals(List.of(new V4l2Formats.Size(640, 480)), V4l2Formats.frameSizes(camera, V4l2PixelFormat.YUYV).candidates());
            assertEquals(List.of(new Rational(30, 1)), V4l2Formats.frameRates(camera, V4l2PixelFormat.YUYV, 640, 480).candidates());
        }
    }

    @Nested
    @DisplayName("modes")
    class Modes {
        @Test
        @DisplayName("modes merge formats, largest first and fastest first within a size")
        void listing() {
            var modes = V4l2Formats.modes(V4l2Formats.offers(webcam()));
            assertEquals(List.of(CaptureMode.of(1920, 1080, 30), CaptureMode.of(1280, 720, 30), CaptureMode.of(1280, 720, 10),
                    CaptureMode.of(1280, 720, 5), CaptureMode.of(640, 480, 30), CaptureMode.of(640, 480, 15)), modes);
        }

        @Test
        @DisplayName("an explicit mode picks the most preferred format that offers it")
        void explicitMode() {
            var camera = webcam();
            assertEquals(V4l2PixelFormat.YUYV, V4l2Formats.select(camera, CaptureMode.of(640, 480, 30)).format());
            assertEquals(V4l2PixelFormat.MJPEG, V4l2Formats.select(camera, CaptureMode.of(1280, 720, 30)).format());
            assertEquals(V4l2PixelFormat.YUYV, V4l2Formats.select(camera, CaptureMode.of(1280, 720, 10)).format());
            var reducible = V4l2Formats.select(camera, new CaptureMode(640, 480, new Rational(60, 2)));
            assertEquals(new Rational(30, 1), reducible.rate());
        }

        @Test
        @DisplayName("a mode the camera does not offer raises Unsupported")
        void unsupportedMode() {
            var camera = webcam();
            var failure = assertThrows(MediaException.Unsupported.class, () -> V4l2Formats.select(camera, CaptureMode.of(1920, 1080, 60)));
            assertEquals("v4l2 mode", failure.format());
            assertThrows(MediaException.Unsupported.class, () -> V4l2Formats.select(camera, CaptureMode.of(800, 600, 30)));
        }

        @Test
        @DisplayName("the default mode is the largest real-time mode up to 1280x720")
        void defaultMode() {
            var offer = V4l2Formats.select(webcam(), null);
            assertEquals(new V4l2Formats.Offer(V4l2PixelFormat.MJPEG, 1280, 720, new Rational(30, 1)), offer);
        }

        @Test
        @DisplayName("the default mode prefers 30 over 60 frames per second and raw over MJPEG")
        void defaultPreferences() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV, size(1280, 720)).format(V4l2PixelFormat.MJPEG, size(1280, 720));
            camera.rates(V4l2PixelFormat.YUYV, 1280, 720, 60, 30);
            camera.rates(V4l2PixelFormat.MJPEG, 1280, 720, 60, 30);
            assertEquals(new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 1280, 720, new Rational(30, 1)), V4l2Formats.select(camera, null));
        }

        @Test
        @DisplayName("without a real-time mode the default is the fastest mode nearest 720p")
        void slowCamera() {
            var camera = new FakeCamera().format(V4l2PixelFormat.YUYV, size(2592, 1944), size(640, 480));
            camera.rates(V4l2PixelFormat.YUYV, 2592, 1944, 2);
            camera.rates(V4l2PixelFormat.YUYV, 640, 480, 15, 10);
            assertEquals(new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 640, 480, new Rational(15, 1)), V4l2Formats.select(camera, null));
        }

        @Test
        @DisplayName("above 720p the default is the smallest real-time mode")
        void largeOnly() {
            var camera = new FakeCamera().format(V4l2PixelFormat.MJPEG, size(3840, 2160), size(1920, 1080));
            camera.rates(V4l2PixelFormat.MJPEG, 3840, 2160, 30);
            camera.rates(V4l2PixelFormat.MJPEG, 1920, 1080, 30);
            assertEquals(1920, V4l2Formats.select(camera, null).width());
        }

        @Test
        @DisplayName("a camera that enumerates no size falls back to its current format")
        void currentFormat() {
            var camera = new FakeCamera();
            camera.formats.add(V4l2PixelFormat.NV12.fourcc());
            camera.errors.put(V4l2.VIDIOC_ENUM_FRAMESIZES, Posix.ENOTTY);
            camera.current = new int[]{800, 600, V4l2PixelFormat.NV12.fourcc()};
            assertEquals(new V4l2Formats.Offer(V4l2PixelFormat.NV12, 800, 600, V4l2Formats.FALLBACK_RATE), V4l2Formats.select(camera, null));
            assertEquals(V4l2PixelFormat.NV12, V4l2Formats.select(camera, CaptureMode.of(1024, 768, 30)).format());
        }

        @Test
        @DisplayName("a camera without a converted format raises Unsupported")
        void noFormat() {
            var camera = new FakeCamera().rawFormat(V4l2.fourcc('B', 'A', '8', '1'));
            camera.current = new int[]{640, 480, V4l2.fourcc('B', 'A', '8', '1')};
            assertThrows(MediaException.Unsupported.class, () -> V4l2Formats.select(camera, null));
        }
    }

    @Nested
    @DisplayName("negotiation")
    class Negotiation {
        @Test
        @DisplayName("the negotiated format builds a converter of the driver's size and stride")
        void accepted() {
            var camera = webcam();
            camera.bytesPerLine = 1344;
            var converter = V4l2Formats.negotiate(camera, new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 640, 480, new Rational(30, 1)),
                    new ScalarConversionKernel());
            assertEquals(640, converter.outputWidth());
            assertEquals(480, converter.outputHeight());
            assertEquals(1344 * 479 + 1280, converter.requiredBytes());
        }

        @Test
        @DisplayName("a substituted size or format raises Unsupported")
        void substituted() {
            var camera = webcam();
            camera.substitute = new int[]{640, 360, V4l2PixelFormat.YUYV.fourcc()};
            var offer = new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 640, 480, new Rational(30, 1));
            var failure = assertThrows(MediaException.Unsupported.class, () -> V4l2Formats.negotiate(camera, offer, new ScalarConversionKernel()));
            assertTrue(failure.getMessage().contains("640x360"), failure.getMessage());
        }

        @Test
        @DisplayName("separate fields raise Unsupported, interleaved ones are accepted")
        void fields() {
            var offer = new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 640, 480, new Rational(30, 1));
            var alternate = webcam();
            alternate.field = 7;
            assertThrows(MediaException.Unsupported.class, () -> V4l2Formats.negotiate(alternate, offer, new ScalarConversionKernel()));
            var interlaced = webcam();
            interlaced.field = V4l2.FIELD_INTERLACED;
            assertEquals(480, V4l2Formats.negotiate(interlaced, offer, new ScalarConversionKernel()).outputHeight());
        }

        @Test
        @DisplayName("a busy camera raises Io")
        void busy() {
            var camera = webcam();
            camera.errors.put(V4l2.VIDIOC_S_FMT, Posix.EBUSY);
            var offer = new V4l2Formats.Offer(V4l2PixelFormat.YUYV, 640, 480, new Rational(30, 1));
            var failure = assertThrows(MediaException.Io.class, () -> V4l2Formats.negotiate(camera, offer, new ScalarConversionKernel()));
            assertTrue(failure.getMessage().contains("in use"), failure.getMessage());
        }

        @Test
        @DisplayName("the frame rate is requested as an inverted interval")
        void rate() {
            var camera = webcam();
            V4l2Formats.requestRate(camera, new Rational(30000, 1001));
            assertEquals(1001, camera.requestedInterval[0]);
            assertEquals(30000, camera.requestedInterval[1]);
        }

        @Test
        @DisplayName("an oversized rate is scaled into 32 bits")
        void hugeRate() {
            var camera = webcam();
            V4l2Formats.requestRate(camera, new Rational(30L << 33, 1L << 33));
            assertEquals(30.0, Integer.toUnsignedLong(camera.requestedInterval[1]) / (double) Integer.toUnsignedLong(camera.requestedInterval[0]), 1e-6);
        }

        @Test
        @DisplayName("a driver without V4L2_CAP_TIMEPERFRAME keeps its rate")
        void noRateControl() {
            var camera = webcam();
            camera.timePerFrame = false;
            V4l2Formats.requestRate(camera, new Rational(15, 1));
            assertNull(camera.requestedInterval);
        }
    }

    @Nested
    @DisplayName("color")
    class Color {
        @Test
        @DisplayName("a UVC webcam's default color is limited-range BT.601 with sRGB primaries and transfer")
        void webcamDefault() {
            assertEquals(new ColorInfo(MatrixCoefficients.BT601, ColorRange.LIMITED, ColorPrimaries.BT709, TransferCharacteristics.SRGB),
                    V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_SRGB, 0, 0, 0));
            assertEquals(V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_SRGB, 0, 0, 0), V4l2Colors.colorInfo(0, 0, 0, 0));
        }

        @Test
        @DisplayName("Rec. 709 implies the BT.709 matrix and transfer")
        void rec709() {
            assertEquals(ColorInfo.BT709_LIMITED, V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_REC709, 0, 0, 0));
        }

        @Test
        @DisplayName("the JPEG colorspace implies full range and explicit fields override the defaults")
        void overrides() {
            assertEquals(ColorRange.FULL, V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_JPEG, 0, 0, 0).range());
            var explicit = V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_SRGB, V4l2Colors.ENC_709, V4l2Colors.QUANTIZATION_FULL, V4l2Colors.XFER_709);
            assertEquals(new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709, TransferCharacteristics.BT709), explicit);
            assertEquals(ColorInfo.BT601_LIMITED, V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_SMPTE170M, 0, 0, 0));
            assertEquals(MatrixCoefficients.BT2020_NCL, V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_BT2020, 0, 0, 0).matrix());
            assertEquals(TransferCharacteristics.PQ, V4l2Colors.colorInfo(V4l2Colors.COLORSPACE_BT2020, 0, 0, V4l2Colors.XFER_SMPTE2084).transfer());
        }
    }
}
