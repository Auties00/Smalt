package com.github.auties00.smalt.capture.internal.linux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the Linux LP64 ABI the backend speaks. Every expected value was derived by hand from
 * linux/videodev2.h, asm-generic/ioctl.h, X11/Xlib.h, X11/extensions/XShm.h and
 * X11/extensions/Xrandr.h, and cross-checked by compiling the real kernel header with
 * _Static_assert(sizeof/offsetof/VIDIOC_*) under libclang for the x86_64, aarch64 and riscv64
 * linux-gnu targets.
 */
@DisplayName("Linux ABI")
class AbiTest {
    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("VIDIOC_QUERYCAP", V4l2.VIDIOC_QUERYCAP, 0x80685600L),
                Arguments.of("VIDIOC_ENUM_FMT", V4l2.VIDIOC_ENUM_FMT, 0xC0405602L),
                Arguments.of("VIDIOC_G_FMT", V4l2.VIDIOC_G_FMT, 0xC0D05604L),
                Arguments.of("VIDIOC_S_FMT", V4l2.VIDIOC_S_FMT, 0xC0D05605L),
                Arguments.of("VIDIOC_REQBUFS", V4l2.VIDIOC_REQBUFS, 0xC0145608L),
                Arguments.of("VIDIOC_QUERYBUF", V4l2.VIDIOC_QUERYBUF, 0xC0585609L),
                Arguments.of("VIDIOC_QBUF", V4l2.VIDIOC_QBUF, 0xC058560FL),
                Arguments.of("VIDIOC_DQBUF", V4l2.VIDIOC_DQBUF, 0xC0585611L),
                Arguments.of("VIDIOC_STREAMON", V4l2.VIDIOC_STREAMON, 0x40045612L),
                Arguments.of("VIDIOC_STREAMOFF", V4l2.VIDIOC_STREAMOFF, 0x40045613L),
                Arguments.of("VIDIOC_G_PARM", V4l2.VIDIOC_G_PARM, 0xC0CC5615L),
                Arguments.of("VIDIOC_S_PARM", V4l2.VIDIOC_S_PARM, 0xC0CC5616L),
                Arguments.of("VIDIOC_ENUM_FRAMESIZES", V4l2.VIDIOC_ENUM_FRAMESIZES, 0xC02C564AL),
                Arguments.of("VIDIOC_ENUM_FRAMEINTERVALS", V4l2.VIDIOC_ENUM_FRAMEINTERVALS, 0xC034564BL),
                Arguments.of("VIDIOC_G_CTRL", V4l2.VIDIOC_G_CTRL, 0xC008561BL),
                Arguments.of("VIDIOC_S_CTRL", V4l2.VIDIOC_S_CTRL, 0xC008561CL),
                Arguments.of("VIDIOC_QUERYCTRL", V4l2.VIDIOC_QUERYCTRL, 0xC0445624L),
                Arguments.of("VIDIOC_QUERYMENU", V4l2.VIDIOC_QUERYMENU, 0xC02C5625L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    @DisplayName("request numbers match the kernel's _IOC encoding")
    void requestNumbers(String name, long actual, long expected) {
        assertEquals(expected, actual, name);
    }

    static Stream<Arguments> sizes() {
        return Stream.of(
                Arguments.of("v4l2_capability", V4l2.CAPABILITY.byteSize(), 104L),
                Arguments.of("v4l2_fmtdesc", V4l2.FMTDESC.byteSize(), 64L),
                Arguments.of("v4l2_frmsizeenum", V4l2.FRMSIZEENUM.byteSize(), 44L),
                Arguments.of("v4l2_frmivalenum", V4l2.FRMIVALENUM.byteSize(), 52L),
                Arguments.of("v4l2_pix_format", V4l2.PIX_FORMAT.byteSize(), 48L),
                Arguments.of("v4l2_window", V4l2.WINDOW.byteSize(), 56L),
                Arguments.of("v4l2_format", V4l2.FORMAT.byteSize(), 208L),
                Arguments.of("v4l2_streamparm", V4l2.STREAMPARM.byteSize(), 204L),
                Arguments.of("v4l2_requestbuffers", V4l2.REQUESTBUFFERS.byteSize(), 20L),
                Arguments.of("v4l2_timecode", V4l2.TIMECODE.byteSize(), 16L),
                Arguments.of("v4l2_buffer", V4l2.BUFFER.byteSize(), 88L),
                Arguments.of("pollfd", Posix.POLLFD.byteSize(), 8L),
                Arguments.of("timespec", Posix.TIMESPEC.byteSize(), 16L),
                Arguments.of("XImage", X11.IMAGE.byteSize(), 136L),
                Arguments.of("XShmSegmentInfo", X11.SHM_SEGMENT_INFO.byteSize(), 32L),
                Arguments.of("XRRMonitorInfo", X11.MONITOR_INFO.byteSize(), 56L),
                Arguments.of("XErrorEvent", X11.ERROR_EVENT.byteSize(), 40L),
                Arguments.of("v4l2_queryctrl", V4l2.QUERYCTRL.byteSize(), 68L),
                Arguments.of("v4l2_control", V4l2.CONTROL.byteSize(), 8L),
                Arguments.of("v4l2_querymenu", V4l2.QUERYMENU.byteSize(), 44L),
                Arguments.of("XWindowAttributes", X11Windowing.WINDOW_ATTRIBUTES.byteSize(), 136L),
                Arguments.of("XFixesCursorImage", X11Windowing.CURSOR_IMAGE.byteSize(), 48L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sizes")
    @DisplayName("structure sizes match LP64 C")
    void structureSizes(String name, long actual, long expected) {
        assertEquals(expected, actual, name);
    }

    static Stream<Arguments> offsets() {
        return Stream.of(
                Arguments.of("v4l2_capability.card", V4l2.CAPABILITY_CARD, 16L),
                Arguments.of("v4l2_capability.capabilities", V4l2.CAPABILITY_CAPABILITIES, 84L),
                Arguments.of("v4l2_capability.device_caps", V4l2.CAPABILITY_DEVICE_CAPS, 88L),
                Arguments.of("v4l2_fmtdesc.type", V4l2.FMTDESC_TYPE, 4L),
                Arguments.of("v4l2_fmtdesc.pixelformat", V4l2.FMTDESC_PIXELFORMAT, 44L),
                Arguments.of("v4l2_frmsizeenum.pixel_format", V4l2.FRMSIZE_PIXEL_FORMAT, 4L),
                Arguments.of("v4l2_frmsizeenum.type", V4l2.FRMSIZE_TYPE, 8L),
                Arguments.of("v4l2_frmsizeenum.discrete.width", V4l2.FRMSIZE_DISCRETE_WIDTH, 12L),
                Arguments.of("v4l2_frmsizeenum.discrete.height", V4l2.FRMSIZE_DISCRETE_HEIGHT, 16L),
                Arguments.of("v4l2_frmsizeenum.stepwise.min_width", V4l2.FRMSIZE_MIN_WIDTH, 12L),
                Arguments.of("v4l2_frmsizeenum.stepwise.max_width", V4l2.FRMSIZE_MAX_WIDTH, 16L),
                Arguments.of("v4l2_frmsizeenum.stepwise.step_width", V4l2.FRMSIZE_STEP_WIDTH, 20L),
                Arguments.of("v4l2_frmsizeenum.stepwise.min_height", V4l2.FRMSIZE_MIN_HEIGHT, 24L),
                Arguments.of("v4l2_frmsizeenum.stepwise.max_height", V4l2.FRMSIZE_MAX_HEIGHT, 28L),
                Arguments.of("v4l2_frmsizeenum.stepwise.step_height", V4l2.FRMSIZE_STEP_HEIGHT, 32L),
                Arguments.of("v4l2_frmivalenum.width", V4l2.FRMIVAL_WIDTH, 8L),
                Arguments.of("v4l2_frmivalenum.height", V4l2.FRMIVAL_HEIGHT, 12L),
                Arguments.of("v4l2_frmivalenum.type", V4l2.FRMIVAL_TYPE, 16L),
                Arguments.of("v4l2_frmivalenum.discrete", V4l2.FRMIVAL_DISCRETE, 20L),
                Arguments.of("v4l2_frmivalenum.stepwise.min", V4l2.FRMIVAL_MIN, 20L),
                Arguments.of("v4l2_frmivalenum.stepwise.max", V4l2.FRMIVAL_MAX, 28L),
                Arguments.of("v4l2_format.fmt.pix", V4l2.FORMAT_PIX, 8L),
                Arguments.of("v4l2_pix_format.field", V4l2.PIX_FIELD, 12L),
                Arguments.of("v4l2_pix_format.bytesperline", V4l2.PIX_BYTESPERLINE, 16L),
                Arguments.of("v4l2_pix_format.sizeimage", V4l2.PIX_SIZEIMAGE, 20L),
                Arguments.of("v4l2_pix_format.colorspace", V4l2.PIX_COLORSPACE, 24L),
                Arguments.of("v4l2_pix_format.ycbcr_enc", V4l2.PIX_YCBCR_ENC, 36L),
                Arguments.of("v4l2_pix_format.quantization", V4l2.PIX_QUANTIZATION, 40L),
                Arguments.of("v4l2_pix_format.xfer_func", V4l2.PIX_XFER_FUNC, 44L),
                Arguments.of("v4l2_streamparm.parm.capture.capability", V4l2.STREAMPARM_CAPABILITY, 4L),
                Arguments.of("v4l2_streamparm.parm.capture.timeperframe", V4l2.STREAMPARM_TIMEPERFRAME, 12L),
                Arguments.of("v4l2_requestbuffers.memory", V4l2.REQBUFS_MEMORY, 8L),
                Arguments.of("v4l2_buffer.bytesused", V4l2.BUFFER_BYTESUSED, 8L),
                Arguments.of("v4l2_buffer.flags", V4l2.BUFFER_FLAGS, 12L),
                Arguments.of("v4l2_buffer.timestamp.tv_sec", V4l2.BUFFER_TV_SEC, 24L),
                Arguments.of("v4l2_buffer.timestamp.tv_usec", V4l2.BUFFER_TV_USEC, 32L),
                Arguments.of("v4l2_buffer.memory", V4l2.BUFFER_MEMORY, 60L),
                Arguments.of("v4l2_buffer.m.offset", V4l2.BUFFER_OFFSET, 64L),
                Arguments.of("v4l2_buffer.length", V4l2.BUFFER_LENGTH, 72L),
                Arguments.of("pollfd.events", Posix.POLLFD_EVENTS, 4L),
                Arguments.of("pollfd.revents", Posix.POLLFD_REVENTS, 6L),
                Arguments.of("XImage.data", X11.IMAGE_DATA, 16L),
                Arguments.of("XImage.byte_order", X11.IMAGE_BYTE_ORDER, 24L),
                Arguments.of("XImage.bytes_per_line", X11.IMAGE_BYTES_PER_LINE, 44L),
                Arguments.of("XImage.bits_per_pixel", X11.IMAGE_BITS_PER_PIXEL, 48L),
                Arguments.of("XImage.red_mask", X11.IMAGE_RED_MASK, 56L),
                Arguments.of("XImage.green_mask", X11.IMAGE_GREEN_MASK, 64L),
                Arguments.of("XImage.blue_mask", X11.IMAGE_BLUE_MASK, 72L),
                Arguments.of("XImage.f.destroy_image", X11.IMAGE_DESTROY, 96L),
                Arguments.of("XShmSegmentInfo.shmid", X11.SHM_ID, 8L),
                Arguments.of("XShmSegmentInfo.shmaddr", X11.SHM_ADDRESS, 16L),
                Arguments.of("XShmSegmentInfo.readOnly", X11.SHM_READ_ONLY, 24L),
                Arguments.of("XRRMonitorInfo.primary", X11.MONITOR_PRIMARY, 8L),
                Arguments.of("XRRMonitorInfo.x", X11.MONITOR_X, 20L),
                Arguments.of("XRRMonitorInfo.y", X11.MONITOR_Y, 24L),
                Arguments.of("XRRMonitorInfo.width", X11.MONITOR_WIDTH, 28L),
                Arguments.of("XRRMonitorInfo.height", X11.MONITOR_HEIGHT, 32L),
                Arguments.of("XErrorEvent.error_code", X11.ERROR_CODE, 32L),
                Arguments.of("v4l2_queryctrl.type", V4l2.QUERYCTRL_TYPE, 4L),
                Arguments.of("v4l2_queryctrl.minimum", V4l2.QUERYCTRL_MINIMUM, 40L),
                Arguments.of("v4l2_queryctrl.maximum", V4l2.QUERYCTRL_MAXIMUM, 44L),
                Arguments.of("v4l2_queryctrl.step", V4l2.QUERYCTRL_STEP, 48L),
                Arguments.of("v4l2_queryctrl.default_value", V4l2.QUERYCTRL_DEFAULT, 52L),
                Arguments.of("v4l2_queryctrl.flags", V4l2.QUERYCTRL_FLAGS, 56L),
                Arguments.of("v4l2_control.value", V4l2.CONTROL_VALUE, 4L),
                Arguments.of("v4l2_querymenu.index", V4l2.QUERYMENU_INDEX, 4L),
                Arguments.of("XWindowAttributes.width", X11Windowing.ATTRIBUTES_WIDTH, 8L),
                Arguments.of("XWindowAttributes.border_width", X11Windowing.ATTRIBUTES_BORDER, 16L),
                Arguments.of("XWindowAttributes.visual", X11Windowing.ATTRIBUTES_VISUAL, 24L),
                Arguments.of("XWindowAttributes.class", X11Windowing.ATTRIBUTES_CLASS, 40L),
                Arguments.of("XWindowAttributes.map_state", X11Windowing.ATTRIBUTES_MAP_STATE, 92L),
                Arguments.of("XWindowAttributes.override_redirect", X11Windowing.ATTRIBUTES_OVERRIDE_REDIRECT, 120L),
                Arguments.of("Visual.red_mask", X11Windowing.VISUAL_RED_MASK, 24L),
                Arguments.of("Visual.green_mask", X11Windowing.VISUAL_GREEN_MASK, 32L),
                Arguments.of("Visual.blue_mask", X11Windowing.VISUAL_BLUE_MASK, 40L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("offsets")
    @DisplayName("field offsets match LP64 C")
    void fieldOffsets(String name, long actual, long expected) {
        assertEquals(expected, actual, name);
    }

    static Stream<Arguments> controlIds() {
        return Stream.of(
                Arguments.of("V4L2_CID_BRIGHTNESS", V4l2.CID_BRIGHTNESS, 0x00980900),
                Arguments.of("V4L2_CID_CONTRAST", V4l2.CID_CONTRAST, 0x00980901),
                Arguments.of("V4L2_CID_SATURATION", V4l2.CID_SATURATION, 0x00980902),
                Arguments.of("V4L2_CID_HUE", V4l2.CID_HUE, 0x00980903),
                Arguments.of("V4L2_CID_AUTO_WHITE_BALANCE", V4l2.CID_AUTO_WHITE_BALANCE, 0x0098090C),
                Arguments.of("V4L2_CID_GAMMA", V4l2.CID_GAMMA, 0x00980910),
                Arguments.of("V4L2_CID_AUTOGAIN", V4l2.CID_AUTOGAIN, 0x00980912),
                Arguments.of("V4L2_CID_GAIN", V4l2.CID_GAIN, 0x00980913),
                Arguments.of("V4L2_CID_POWER_LINE_FREQUENCY", V4l2.CID_POWER_LINE_FREQUENCY, 0x00980918),
                Arguments.of("V4L2_CID_HUE_AUTO", V4l2.CID_HUE_AUTO, 0x00980919),
                Arguments.of("V4L2_CID_WHITE_BALANCE_TEMPERATURE", V4l2.CID_WHITE_BALANCE_TEMPERATURE, 0x0098091A),
                Arguments.of("V4L2_CID_SHARPNESS", V4l2.CID_SHARPNESS, 0x0098091B),
                Arguments.of("V4L2_CID_BACKLIGHT_COMPENSATION", V4l2.CID_BACKLIGHT_COMPENSATION, 0x0098091C),
                Arguments.of("V4L2_CID_EXPOSURE_AUTO", V4l2.CID_EXPOSURE_AUTO, 0x009A0901),
                Arguments.of("V4L2_CID_EXPOSURE_ABSOLUTE", V4l2.CID_EXPOSURE_ABSOLUTE, 0x009A0902),
                Arguments.of("V4L2_CID_PAN_ABSOLUTE", V4l2.CID_PAN_ABSOLUTE, 0x009A0908),
                Arguments.of("V4L2_CID_TILT_ABSOLUTE", V4l2.CID_TILT_ABSOLUTE, 0x009A0909),
                Arguments.of("V4L2_CID_FOCUS_ABSOLUTE", V4l2.CID_FOCUS_ABSOLUTE, 0x009A090A),
                Arguments.of("V4L2_CID_FOCUS_AUTO", V4l2.CID_FOCUS_AUTO, 0x009A090C),
                Arguments.of("V4L2_CID_ZOOM_ABSOLUTE", V4l2.CID_ZOOM_ABSOLUTE, 0x009A090D));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlIds")
    @DisplayName("control identifiers match the kernel's")
    void controlIdentifiers(String name, int actual, int expected) {
        assertEquals(expected, actual, name);
    }

    @Nested
    @DisplayName("constants")
    class Constants {
        @Test
        @DisplayName("four-character codes are little-endian")
        void fourcc() {
            assertEquals(0x56595559, V4l2PixelFormat.YUYV.fourcc());
            assertEquals(0x3231564E, V4l2PixelFormat.NV12.fourcc());
            assertEquals(0x32315559, V4l2PixelFormat.YUV420.fourcc());
            assertEquals(0x47504A4D, V4l2PixelFormat.MJPEG.fourcc());
            assertEquals("MJPG", V4l2.fourccName(V4l2PixelFormat.MJPEG.fourcc()));
            assertEquals(V4l2PixelFormat.UYVY, V4l2PixelFormat.of(V4l2.fourcc('U', 'Y', 'V', 'Y')));
            assertEquals(null, V4l2PixelFormat.of(V4l2.fourcc('H', '2', '6', '4')));
        }

        @Test
        @DisplayName("libc flags are the asm-generic values")
        void libcFlags() {
            assertEquals(02, Posix.O_RDWR);
            assertEquals(04000, Posix.O_NONBLOCK);
            assertEquals(02000000, Posix.O_CLOEXEC);
            assertEquals(01000, Posix.IPC_CREAT);
            assertEquals("EBUSY (device or resource busy)", Posix.describe(16));
            assertEquals("errno 999", Posix.describe(999));
        }
    }
}
