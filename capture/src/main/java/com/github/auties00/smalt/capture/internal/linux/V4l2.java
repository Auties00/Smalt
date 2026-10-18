package com.github.auties00.smalt.capture.internal.linux;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Declares the Video4Linux2 user-space ABI this backend speaks: request numbers, structure
 * layouts and constants of {@code linux/videodev2.h}.
 *
 * <p>The layouts are those of a 64-bit Linux (LP64) process: {@code long}, pointers and the two
 * fields of {@code struct timeval} are 8 bytes and 8-byte aligned, every other field is naturally
 * aligned. They are written with explicit padding, which the Foreign Function and Memory API
 * checks, so a misplaced field cannot go unnoticed. x86_64, aarch64 and riscv64 share these
 * layouts and the {@code asm-generic} request encoding:
 * {@snippet :
 * request = dir << 30 | size << 16 | type << 8 | nr   // _IOC(dir, type, nr, size)
 * // dir: 1 = _IOC_WRITE (_IOW), 2 = _IOC_READ (_IOR), 3 = both (_IOWR)
 * // size: sizeof the argument structure, 14 bits
 * }
 * so, for example, {@code VIDIOC_QUERYBUF = _IOWR('V', 9, struct v4l2_buffer)} is
 * {@code 3 << 30 | 88 << 16 | 0x56 << 8 | 9 = 0xC0585609}. Architectures with another encoding
 * (powerpc, mips, sparc, alpha) are not supported.
 *
 * <p>Field offsets are derived from the layouts, never written by hand.
 */
final class V4l2 {
    /**
     * The {@code V4L2_CAP_VIDEO_CAPTURE} capability: a single-planar capture device.
     */
    static final int CAP_VIDEO_CAPTURE = 0x00000001;

    /**
     * The {@code V4L2_CAP_VIDEO_CAPTURE_MPLANE} capability: a multi-planar capture device.
     */
    static final int CAP_VIDEO_CAPTURE_MPLANE = 0x00001000;

    /**
     * The {@code V4L2_CAP_META_CAPTURE} capability: a metadata capture device.
     */
    static final int CAP_META_CAPTURE = 0x00800000;

    /**
     * The {@code V4L2_CAP_STREAMING} capability: streaming I/O is supported.
     */
    static final int CAP_STREAMING = 0x04000000;

    /**
     * The {@code V4L2_CAP_DEVICE_CAPS} capability: {@code device_caps} describes the opened node.
     */
    static final int CAP_DEVICE_CAPS = 0x80000000;

    /**
     * The {@code V4L2_BUF_TYPE_VIDEO_CAPTURE} buffer type.
     */
    static final int BUF_TYPE_VIDEO_CAPTURE = 1;

    /**
     * The {@code V4L2_MEMORY_MMAP} memory type.
     */
    static final int MEMORY_MMAP = 1;

    /**
     * The {@code V4L2_FIELD_ANY} field order: the driver chooses.
     */
    static final int FIELD_ANY = 0;

    /**
     * The {@code V4L2_FIELD_NONE} field order: progressive frames.
     */
    static final int FIELD_NONE = 1;

    /**
     * The {@code V4L2_FIELD_INTERLACED} field order: both fields interleaved in one frame.
     */
    static final int FIELD_INTERLACED = 4;

    /**
     * The {@code V4L2_FIELD_INTERLACED_TB} field order: interleaved, top field first.
     */
    static final int FIELD_INTERLACED_TB = 8;

    /**
     * The {@code V4L2_FIELD_INTERLACED_BT} field order: interleaved, bottom field first.
     */
    static final int FIELD_INTERLACED_BT = 9;

    /**
     * The {@code V4L2_FRMSIZE_TYPE_DISCRETE} and {@code V4L2_FRMIVAL_TYPE_DISCRETE} types.
     */
    static final int TYPE_DISCRETE = 1;

    /**
     * The {@code V4L2_FRMSIZE_TYPE_CONTINUOUS} and {@code V4L2_FRMIVAL_TYPE_CONTINUOUS} types.
     */
    static final int TYPE_CONTINUOUS = 2;

    /**
     * The {@code V4L2_FRMSIZE_TYPE_STEPWISE} and {@code V4L2_FRMIVAL_TYPE_STEPWISE} types.
     */
    static final int TYPE_STEPWISE = 3;

    /**
     * The {@code V4L2_BUF_FLAG_ERROR} buffer flag: the frame is corrupt.
     */
    static final int BUF_FLAG_ERROR = 0x00000040;

    /**
     * The {@code V4L2_BUF_FLAG_TIMESTAMP_MASK} of the timestamp source bits.
     */
    static final int BUF_FLAG_TIMESTAMP_MASK = 0x0000e000;

    /**
     * The {@code V4L2_BUF_FLAG_TIMESTAMP_MONOTONIC} timestamp source: {@code CLOCK_MONOTONIC}.
     */
    static final int BUF_FLAG_TIMESTAMP_MONOTONIC = 0x00002000;

    /**
     * The {@code V4L2_CAP_TIMEPERFRAME} streaming parameter capability.
     */
    static final int CAP_TIMEPERFRAME = 0x1000;

    /**
     * The layout of {@code struct v4l2_capability}.
     */
    static final StructLayout CAPABILITY = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("driver"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("card"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("bus_info"),
            JAVA_INT.withName("version"),
            JAVA_INT.withName("capabilities"),
            JAVA_INT.withName("device_caps"),
            MemoryLayout.sequenceLayout(3, JAVA_INT).withName("reserved")).withName("v4l2_capability");

    /**
     * The offset of {@code v4l2_capability.card}.
     */
    static final long CAPABILITY_CARD = CAPABILITY.byteOffset(groupElement("card"));

    /**
     * The size of {@code v4l2_capability.card}.
     */
    static final int CAPABILITY_CARD_SIZE = 32;

    /**
     * The offset of {@code v4l2_capability.capabilities}.
     */
    static final long CAPABILITY_CAPABILITIES = CAPABILITY.byteOffset(groupElement("capabilities"));

    /**
     * The offset of {@code v4l2_capability.device_caps}.
     */
    static final long CAPABILITY_DEVICE_CAPS = CAPABILITY.byteOffset(groupElement("device_caps"));

    /**
     * The layout of {@code struct v4l2_fmtdesc}.
     */
    static final StructLayout FMTDESC = MemoryLayout.structLayout(
            JAVA_INT.withName("index"),
            JAVA_INT.withName("type"),
            JAVA_INT.withName("flags"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("description"),
            JAVA_INT.withName("pixelformat"),
            JAVA_INT.withName("mbus_code"),
            MemoryLayout.sequenceLayout(3, JAVA_INT).withName("reserved")).withName("v4l2_fmtdesc");

    /**
     * The offset of {@code v4l2_fmtdesc.index}.
     */
    static final long FMTDESC_INDEX = FMTDESC.byteOffset(groupElement("index"));

    /**
     * The offset of {@code v4l2_fmtdesc.type}.
     */
    static final long FMTDESC_TYPE = FMTDESC.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_fmtdesc.pixelformat}.
     */
    static final long FMTDESC_PIXELFORMAT = FMTDESC.byteOffset(groupElement("pixelformat"));

    /**
     * The layout of {@code struct v4l2_fract}.
     */
    static final StructLayout FRACT = MemoryLayout.structLayout(
            JAVA_INT.withName("numerator"),
            JAVA_INT.withName("denominator")).withName("v4l2_fract");

    /**
     * The layout of {@code struct v4l2_frmsizeenum}; its anonymous union is named {@code size}.
     */
    static final StructLayout FRMSIZEENUM = MemoryLayout.structLayout(
            JAVA_INT.withName("index"),
            JAVA_INT.withName("pixel_format"),
            JAVA_INT.withName("type"),
            MemoryLayout.unionLayout(
                    MemoryLayout.structLayout(
                            JAVA_INT.withName("width"),
                            JAVA_INT.withName("height")).withName("discrete"),
                    MemoryLayout.structLayout(
                            JAVA_INT.withName("min_width"),
                            JAVA_INT.withName("max_width"),
                            JAVA_INT.withName("step_width"),
                            JAVA_INT.withName("min_height"),
                            JAVA_INT.withName("max_height"),
                            JAVA_INT.withName("step_height")).withName("stepwise")).withName("size"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("reserved")).withName("v4l2_frmsizeenum");

    /**
     * The offset of {@code v4l2_frmsizeenum.index}.
     */
    static final long FRMSIZE_INDEX = FRMSIZEENUM.byteOffset(groupElement("index"));

    /**
     * The offset of {@code v4l2_frmsizeenum.pixel_format}.
     */
    static final long FRMSIZE_PIXEL_FORMAT = FRMSIZEENUM.byteOffset(groupElement("pixel_format"));

    /**
     * The offset of {@code v4l2_frmsizeenum.type}.
     */
    static final long FRMSIZE_TYPE = FRMSIZEENUM.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_frmsizeenum.discrete.width}.
     */
    static final long FRMSIZE_DISCRETE_WIDTH = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("discrete"), groupElement("width"));

    /**
     * The offset of {@code v4l2_frmsizeenum.discrete.height}.
     */
    static final long FRMSIZE_DISCRETE_HEIGHT = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("discrete"), groupElement("height"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.min_width}.
     */
    static final long FRMSIZE_MIN_WIDTH = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("min_width"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.max_width}.
     */
    static final long FRMSIZE_MAX_WIDTH = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("max_width"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.step_width}.
     */
    static final long FRMSIZE_STEP_WIDTH = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("step_width"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.min_height}.
     */
    static final long FRMSIZE_MIN_HEIGHT = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("min_height"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.max_height}.
     */
    static final long FRMSIZE_MAX_HEIGHT = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("max_height"));

    /**
     * The offset of {@code v4l2_frmsizeenum.stepwise.step_height}.
     */
    static final long FRMSIZE_STEP_HEIGHT = FRMSIZEENUM.byteOffset(groupElement("size"), groupElement("stepwise"), groupElement("step_height"));

    /**
     * The layout of {@code struct v4l2_frmivalenum}; its anonymous union is named
     * {@code interval}.
     */
    static final StructLayout FRMIVALENUM = MemoryLayout.structLayout(
            JAVA_INT.withName("index"),
            JAVA_INT.withName("pixel_format"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("type"),
            MemoryLayout.unionLayout(
                    FRACT.withName("discrete"),
                    MemoryLayout.structLayout(
                            FRACT.withName("min"),
                            FRACT.withName("max"),
                            FRACT.withName("step")).withName("stepwise")).withName("interval"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("reserved")).withName("v4l2_frmivalenum");

    /**
     * The offset of {@code v4l2_frmivalenum.index}.
     */
    static final long FRMIVAL_INDEX = FRMIVALENUM.byteOffset(groupElement("index"));

    /**
     * The offset of {@code v4l2_frmivalenum.pixel_format}.
     */
    static final long FRMIVAL_PIXEL_FORMAT = FRMIVALENUM.byteOffset(groupElement("pixel_format"));

    /**
     * The offset of {@code v4l2_frmivalenum.width}.
     */
    static final long FRMIVAL_WIDTH = FRMIVALENUM.byteOffset(groupElement("width"));

    /**
     * The offset of {@code v4l2_frmivalenum.height}.
     */
    static final long FRMIVAL_HEIGHT = FRMIVALENUM.byteOffset(groupElement("height"));

    /**
     * The offset of {@code v4l2_frmivalenum.type}.
     */
    static final long FRMIVAL_TYPE = FRMIVALENUM.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_frmivalenum.discrete}, a {@code struct v4l2_fract}.
     */
    static final long FRMIVAL_DISCRETE = FRMIVALENUM.byteOffset(groupElement("interval"), groupElement("discrete"));

    /**
     * The offset of {@code v4l2_frmivalenum.stepwise.min}, a {@code struct v4l2_fract}.
     */
    static final long FRMIVAL_MIN = FRMIVALENUM.byteOffset(groupElement("interval"), groupElement("stepwise"), groupElement("min"));

    /**
     * The offset of {@code v4l2_frmivalenum.stepwise.max}, a {@code struct v4l2_fract}.
     */
    static final long FRMIVAL_MAX = FRMIVALENUM.byteOffset(groupElement("interval"), groupElement("stepwise"), groupElement("max"));

    /**
     * The layout of {@code struct v4l2_pix_format}; its anonymous union
     * ({@code ycbcr_enc}/{@code hsv_enc}) is the field {@code ycbcr_enc}.
     */
    static final StructLayout PIX_FORMAT = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("pixelformat"),
            JAVA_INT.withName("field"),
            JAVA_INT.withName("bytesperline"),
            JAVA_INT.withName("sizeimage"),
            JAVA_INT.withName("colorspace"),
            JAVA_INT.withName("priv"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("ycbcr_enc"),
            JAVA_INT.withName("quantization"),
            JAVA_INT.withName("xfer_func")).withName("v4l2_pix_format");

    /**
     * The layout of {@code struct v4l2_window}, a member of the {@code v4l2_format} union whose
     * pointers give the union its 8-byte alignment; the pointers are modeled as {@code long}.
     */
    static final StructLayout WINDOW = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("w"),
            JAVA_INT.withName("field"),
            JAVA_INT.withName("chromakey"),
            JAVA_LONG.withName("clips"),
            JAVA_INT.withName("clipcount"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("bitmap"),
            JAVA_BYTE.withName("global_alpha"),
            MemoryLayout.paddingLayout(7)).withName("v4l2_window");

    /**
     * The layout of {@code struct v4l2_format}: the type, 4 bytes of padding, and the 200-byte,
     * 8-byte aligned {@code fmt} union.
     */
    static final StructLayout FORMAT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.unionLayout(
                    PIX_FORMAT.withName("pix"),
                    WINDOW.withName("win"),
                    MemoryLayout.sequenceLayout(200, JAVA_BYTE).withName("raw_data")).withName("fmt")).withName("v4l2_format");

    /**
     * The offset of {@code v4l2_format.type}.
     */
    static final long FORMAT_TYPE = FORMAT.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_format.fmt.pix}.
     */
    static final long FORMAT_PIX = FORMAT.byteOffset(groupElement("fmt"), groupElement("pix"));

    /**
     * The offset of {@code v4l2_pix_format.width} within the structure.
     */
    static final long PIX_WIDTH = PIX_FORMAT.byteOffset(groupElement("width"));

    /**
     * The offset of {@code v4l2_pix_format.height} within the structure.
     */
    static final long PIX_HEIGHT = PIX_FORMAT.byteOffset(groupElement("height"));

    /**
     * The offset of {@code v4l2_pix_format.pixelformat} within the structure.
     */
    static final long PIX_PIXELFORMAT = PIX_FORMAT.byteOffset(groupElement("pixelformat"));

    /**
     * The offset of {@code v4l2_pix_format.field} within the structure.
     */
    static final long PIX_FIELD = PIX_FORMAT.byteOffset(groupElement("field"));

    /**
     * The offset of {@code v4l2_pix_format.bytesperline} within the structure.
     */
    static final long PIX_BYTESPERLINE = PIX_FORMAT.byteOffset(groupElement("bytesperline"));

    /**
     * The offset of {@code v4l2_pix_format.sizeimage} within the structure.
     */
    static final long PIX_SIZEIMAGE = PIX_FORMAT.byteOffset(groupElement("sizeimage"));

    /**
     * The offset of {@code v4l2_pix_format.colorspace} within the structure.
     */
    static final long PIX_COLORSPACE = PIX_FORMAT.byteOffset(groupElement("colorspace"));

    /**
     * The offset of {@code v4l2_pix_format.ycbcr_enc} within the structure.
     */
    static final long PIX_YCBCR_ENC = PIX_FORMAT.byteOffset(groupElement("ycbcr_enc"));

    /**
     * The offset of {@code v4l2_pix_format.quantization} within the structure.
     */
    static final long PIX_QUANTIZATION = PIX_FORMAT.byteOffset(groupElement("quantization"));

    /**
     * The offset of {@code v4l2_pix_format.xfer_func} within the structure.
     */
    static final long PIX_XFER_FUNC = PIX_FORMAT.byteOffset(groupElement("xfer_func"));

    /**
     * The layout of {@code struct v4l2_captureparm}.
     */
    static final StructLayout CAPTUREPARM = MemoryLayout.structLayout(
            JAVA_INT.withName("capability"),
            JAVA_INT.withName("capturemode"),
            FRACT.withName("timeperframe"),
            JAVA_INT.withName("extendedmode"),
            JAVA_INT.withName("readbuffers"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("reserved")).withName("v4l2_captureparm");

    /**
     * The layout of {@code struct v4l2_streamparm}: the type and the 200-byte, 4-byte aligned
     * {@code parm} union.
     */
    static final StructLayout STREAMPARM = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.unionLayout(
                    CAPTUREPARM.withName("capture"),
                    MemoryLayout.sequenceLayout(200, JAVA_BYTE).withName("raw_data")).withName("parm")).withName("v4l2_streamparm");

    /**
     * The offset of {@code v4l2_streamparm.type}.
     */
    static final long STREAMPARM_TYPE = STREAMPARM.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_streamparm.parm.capture.capability}.
     */
    static final long STREAMPARM_CAPABILITY = STREAMPARM.byteOffset(groupElement("parm"), groupElement("capture"), groupElement("capability"));

    /**
     * The offset of {@code v4l2_streamparm.parm.capture.timeperframe}, a {@code struct v4l2_fract}.
     */
    static final long STREAMPARM_TIMEPERFRAME = STREAMPARM.byteOffset(groupElement("parm"), groupElement("capture"), groupElement("timeperframe"));

    /**
     * The layout of {@code struct v4l2_requestbuffers}.
     */
    static final StructLayout REQUESTBUFFERS = MemoryLayout.structLayout(
            JAVA_INT.withName("count"),
            JAVA_INT.withName("type"),
            JAVA_INT.withName("memory"),
            JAVA_INT.withName("capabilities"),
            JAVA_BYTE.withName("flags"),
            MemoryLayout.sequenceLayout(3, JAVA_BYTE).withName("reserved")).withName("v4l2_requestbuffers");

    /**
     * The offset of {@code v4l2_requestbuffers.count}.
     */
    static final long REQBUFS_COUNT = REQUESTBUFFERS.byteOffset(groupElement("count"));

    /**
     * The offset of {@code v4l2_requestbuffers.type}.
     */
    static final long REQBUFS_TYPE = REQUESTBUFFERS.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_requestbuffers.memory}.
     */
    static final long REQBUFS_MEMORY = REQUESTBUFFERS.byteOffset(groupElement("memory"));

    /**
     * The layout of {@code struct timeval} on LP64.
     */
    static final StructLayout TIMEVAL = MemoryLayout.structLayout(
            JAVA_LONG.withName("tv_sec"),
            JAVA_LONG.withName("tv_usec")).withName("timeval");

    /**
     * The layout of {@code struct v4l2_timecode}.
     */
    static final StructLayout TIMECODE = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("flags"),
            JAVA_BYTE.withName("frames"),
            JAVA_BYTE.withName("seconds"),
            JAVA_BYTE.withName("minutes"),
            JAVA_BYTE.withName("hours"),
            MemoryLayout.sequenceLayout(4, JAVA_BYTE).withName("userbits")).withName("v4l2_timecode");

    /**
     * The layout of {@code struct v4l2_buffer}: 4 bytes of padding align the {@code timeval}, the
     * {@code m} union holds an {@code unsigned long} and a pointer, and the second anonymous union
     * is named {@code request}.
     */
    static final StructLayout BUFFER = MemoryLayout.structLayout(
            JAVA_INT.withName("index"),
            JAVA_INT.withName("type"),
            JAVA_INT.withName("bytesused"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("field"),
            MemoryLayout.paddingLayout(4),
            TIMEVAL.withName("timestamp"),
            TIMECODE.withName("timecode"),
            JAVA_INT.withName("sequence"),
            JAVA_INT.withName("memory"),
            MemoryLayout.unionLayout(
                    JAVA_INT.withName("offset"),
                    JAVA_LONG.withName("userptr"),
                    JAVA_LONG.withName("planes"),
                    JAVA_INT.withName("fd")).withName("m"),
            JAVA_INT.withName("length"),
            JAVA_INT.withName("reserved2"),
            MemoryLayout.unionLayout(
                    JAVA_INT.withName("request_fd"),
                    JAVA_INT.withName("reserved")).withName("request"),
            MemoryLayout.paddingLayout(4)).withName("v4l2_buffer");

    /**
     * The offset of {@code v4l2_buffer.index}.
     */
    static final long BUFFER_INDEX = BUFFER.byteOffset(groupElement("index"));

    /**
     * The offset of {@code v4l2_buffer.type}.
     */
    static final long BUFFER_TYPE = BUFFER.byteOffset(groupElement("type"));

    /**
     * The offset of {@code v4l2_buffer.bytesused}.
     */
    static final long BUFFER_BYTESUSED = BUFFER.byteOffset(groupElement("bytesused"));

    /**
     * The offset of {@code v4l2_buffer.flags}.
     */
    static final long BUFFER_FLAGS = BUFFER.byteOffset(groupElement("flags"));

    /**
     * The offset of {@code v4l2_buffer.timestamp.tv_sec}.
     */
    static final long BUFFER_TV_SEC = BUFFER.byteOffset(groupElement("timestamp"), groupElement("tv_sec"));

    /**
     * The offset of {@code v4l2_buffer.timestamp.tv_usec}.
     */
    static final long BUFFER_TV_USEC = BUFFER.byteOffset(groupElement("timestamp"), groupElement("tv_usec"));

    /**
     * The offset of {@code v4l2_buffer.memory}.
     */
    static final long BUFFER_MEMORY = BUFFER.byteOffset(groupElement("memory"));

    /**
     * The offset of {@code v4l2_buffer.m.offset}.
     */
    static final long BUFFER_OFFSET = BUFFER.byteOffset(groupElement("m"), groupElement("offset"));

    /**
     * The offset of {@code v4l2_buffer.length}.
     */
    static final long BUFFER_LENGTH = BUFFER.byteOffset(groupElement("length"));

    /**
     * The {@code _IOC_WRITE} direction bit of a request number.
     */
    private static final long IOC_WRITE = 1;

    /**
     * The {@code _IOC_READ} direction bit of a request number.
     */
    private static final long IOC_READ = 2;

    /**
     * The {@code 'V'} request type of Video4Linux2.
     */
    private static final char TYPE = 'V';

    /**
     * {@code VIDIOC_QUERYCAP = _IOR('V', 0, struct v4l2_capability)}.
     */
    static final long VIDIOC_QUERYCAP = ioc(IOC_READ, 0, CAPABILITY);

    /**
     * {@code VIDIOC_ENUM_FMT = _IOWR('V', 2, struct v4l2_fmtdesc)}.
     */
    static final long VIDIOC_ENUM_FMT = ioc(IOC_READ | IOC_WRITE, 2, FMTDESC);

    /**
     * {@code VIDIOC_G_FMT = _IOWR('V', 4, struct v4l2_format)}.
     */
    static final long VIDIOC_G_FMT = ioc(IOC_READ | IOC_WRITE, 4, FORMAT);

    /**
     * {@code VIDIOC_S_FMT = _IOWR('V', 5, struct v4l2_format)}.
     */
    static final long VIDIOC_S_FMT = ioc(IOC_READ | IOC_WRITE, 5, FORMAT);

    /**
     * {@code VIDIOC_REQBUFS = _IOWR('V', 8, struct v4l2_requestbuffers)}.
     */
    static final long VIDIOC_REQBUFS = ioc(IOC_READ | IOC_WRITE, 8, REQUESTBUFFERS);

    /**
     * {@code VIDIOC_QUERYBUF = _IOWR('V', 9, struct v4l2_buffer)}.
     */
    static final long VIDIOC_QUERYBUF = ioc(IOC_READ | IOC_WRITE, 9, BUFFER);

    /**
     * {@code VIDIOC_QBUF = _IOWR('V', 15, struct v4l2_buffer)}.
     */
    static final long VIDIOC_QBUF = ioc(IOC_READ | IOC_WRITE, 15, BUFFER);

    /**
     * {@code VIDIOC_DQBUF = _IOWR('V', 17, struct v4l2_buffer)}.
     */
    static final long VIDIOC_DQBUF = ioc(IOC_READ | IOC_WRITE, 17, BUFFER);

    /**
     * {@code VIDIOC_STREAMON = _IOW('V', 18, int)}.
     */
    static final long VIDIOC_STREAMON = ioc(IOC_WRITE, 18, JAVA_INT);

    /**
     * {@code VIDIOC_STREAMOFF = _IOW('V', 19, int)}.
     */
    static final long VIDIOC_STREAMOFF = ioc(IOC_WRITE, 19, JAVA_INT);

    /**
     * {@code VIDIOC_G_PARM = _IOWR('V', 21, struct v4l2_streamparm)}.
     */
    static final long VIDIOC_G_PARM = ioc(IOC_READ | IOC_WRITE, 21, STREAMPARM);

    /**
     * {@code VIDIOC_S_PARM = _IOWR('V', 22, struct v4l2_streamparm)}.
     */
    static final long VIDIOC_S_PARM = ioc(IOC_READ | IOC_WRITE, 22, STREAMPARM);

    /**
     * {@code VIDIOC_ENUM_FRAMESIZES = _IOWR('V', 74, struct v4l2_frmsizeenum)}.
     */
    static final long VIDIOC_ENUM_FRAMESIZES = ioc(IOC_READ | IOC_WRITE, 74, FRMSIZEENUM);

    /**
     * {@code VIDIOC_ENUM_FRAMEINTERVALS = _IOWR('V', 75, struct v4l2_frmivalenum)}.
     */
    static final long VIDIOC_ENUM_FRAMEINTERVALS = ioc(IOC_READ | IOC_WRITE, 75, FRMIVALENUM);

    /**
     * Prevents instantiation.
     */
    private V4l2() {
        throw new AssertionError();
    }

    /**
     * Encodes a Video4Linux2 request number with the {@code asm-generic} {@code _IOC} macro.
     *
     * @param direction the {@code _IOC_READ} and {@code _IOC_WRITE} bits
     * @param number    the request's ordinal within the {@code 'V'} type
     * @param argument  the layout of the argument, whose size is encoded
     * @return the request number, as the unsigned 32-bit value widened to {@code long}
     * @throws IllegalArgumentException if the argument does not fit the 14-bit size field
     */
    static long ioc(long direction, int number, MemoryLayout argument) {
        var size = argument.byteSize();
        if (size >= 1 << 14) {
            throw new IllegalArgumentException("ioctl argument of " + size + " bytes");
        }
        return direction << 30 | size << 16 | (long) TYPE << 8 | number;
    }

    /**
     * Builds a four-character code.
     *
     * @param a the first character, stored in the lowest byte
     * @param b the second character
     * @param c the third character
     * @param d the fourth character, stored in the highest byte
     * @return the code
     */
    static int fourcc(char a, char b, char c, char d) {
        return a | b << 8 | c << 16 | d << 24;
    }

    /**
     * Renders a four-character code as text.
     *
     * @param fourcc the code
     * @return the four characters, with non-printable bytes as {@code '?'}
     */
    static String fourccName(int fourcc) {
        var name = new char[4];
        for (var i = 0; i < 4; i++) {
            var c = (fourcc >>> (8 * i)) & 0xFF;
            name[i] = c >= 0x20 && c < 0x7F ? (char) c : '?';
        }
        return new String(name);
    }
}
