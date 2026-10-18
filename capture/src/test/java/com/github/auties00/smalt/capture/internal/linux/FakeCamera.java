package com.github.auties00.smalt.capture.internal.linux;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A simulated V4L2 driver that answers the enumeration and negotiation requests by filling the
 * argument structures at the offsets of the kernel ABI, the way a real driver would.
 */
final class FakeCamera implements V4l2Device {
    String card = "Fake Camera";
    int capabilities = V4l2.CAP_VIDEO_CAPTURE | V4l2.CAP_STREAMING | V4l2.CAP_DEVICE_CAPS;
    int deviceCaps = V4l2.CAP_VIDEO_CAPTURE | V4l2.CAP_STREAMING;
    final List<Integer> formats = new ArrayList<>();
    final Map<Integer, List<int[]>> discreteSizes = new HashMap<>();
    final Map<Integer, int[]> sizeRanges = new HashMap<>();
    final Map<String, List<int[]>> discreteIntervals = new HashMap<>();
    final Map<String, int[]> intervalRanges = new HashMap<>();
    final Map<Long, Integer> errors = new HashMap<>();
    boolean endlessFormats;
    int[] current = {640, 480, V4l2PixelFormat.YUYV.fourcc()};
    int[] substitute;
    int field = V4l2.FIELD_NONE;
    int bytesPerLine;
    int[] color = {0, 0, 0, 0};
    boolean timePerFrame = true;
    int[] requestedInterval;
    final List<Long> requests = new ArrayList<>();

    FakeCamera format(V4l2PixelFormat format, int[]... sizes) {
        formats.add(format.fourcc());
        discreteSizes.put(format.fourcc(), List.of(sizes));
        return this;
    }

    FakeCamera rawFormat(int fourcc) {
        formats.add(fourcc);
        return this;
    }

    FakeCamera rates(V4l2PixelFormat format, int width, int height, int... fps) {
        var intervals = new ArrayList<int[]>();
        for (var rate : fps) {
            intervals.add(new int[]{1, rate});
        }
        discreteIntervals.put(key(format.fourcc(), width, height), intervals);
        return this;
    }

    static String key(int fourcc, int width, int height) {
        return fourcc + ":" + width + "x" + height;
    }

    @Override
    public int ioctl(long request, MemorySegment argument) {
        requests.add(request);
        var injected = errors.get(request);
        if (injected != null) {
            return injected;
        }
        if (request == V4l2.VIDIOC_QUERYCAP) {
            var name = card.getBytes(StandardCharsets.UTF_8);
            MemorySegment.copy(name, 0, argument, JAVA_BYTE, V4l2.CAPABILITY_CARD, Math.min(name.length, 31));
            argument.set(JAVA_INT, V4l2.CAPABILITY_CAPABILITIES, capabilities);
            argument.set(JAVA_INT, V4l2.CAPABILITY_DEVICE_CAPS, deviceCaps);
            return 0;
        }
        if (request == V4l2.VIDIOC_ENUM_FMT) {
            var index = argument.get(JAVA_INT, V4l2.FMTDESC_INDEX);
            if (argument.get(JAVA_INT, V4l2.FMTDESC_TYPE) != V4l2.BUF_TYPE_VIDEO_CAPTURE) {
                return Posix.EINVAL;
            }
            if (endlessFormats) {
                argument.set(JAVA_INT, V4l2.FMTDESC_PIXELFORMAT, formats.get(index % formats.size()));
                return 0;
            }
            if (index >= formats.size()) {
                return Posix.EINVAL;
            }
            argument.set(JAVA_INT, V4l2.FMTDESC_PIXELFORMAT, formats.get(index));
            return 0;
        }
        if (request == V4l2.VIDIOC_ENUM_FRAMESIZES) {
            var index = argument.get(JAVA_INT, V4l2.FRMSIZE_INDEX);
            var fourcc = argument.get(JAVA_INT, V4l2.FRMSIZE_PIXEL_FORMAT);
            var range = sizeRanges.get(fourcc);
            if (range != null) {
                if (index != 0) {
                    return Posix.EINVAL;
                }
                argument.set(JAVA_INT, V4l2.FRMSIZE_TYPE, range[0]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_MIN_WIDTH, range[1]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_MAX_WIDTH, range[2]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_STEP_WIDTH, range[3]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_MIN_HEIGHT, range[4]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_MAX_HEIGHT, range[5]);
                argument.set(JAVA_INT, V4l2.FRMSIZE_STEP_HEIGHT, range[6]);
                return 0;
            }
            var sizes = discreteSizes.getOrDefault(fourcc, List.of());
            if (index >= sizes.size()) {
                return Posix.EINVAL;
            }
            argument.set(JAVA_INT, V4l2.FRMSIZE_TYPE, V4l2.TYPE_DISCRETE);
            argument.set(JAVA_INT, V4l2.FRMSIZE_DISCRETE_WIDTH, sizes.get(index)[0]);
            argument.set(JAVA_INT, V4l2.FRMSIZE_DISCRETE_HEIGHT, sizes.get(index)[1]);
            return 0;
        }
        if (request == V4l2.VIDIOC_ENUM_FRAMEINTERVALS) {
            var index = argument.get(JAVA_INT, V4l2.FRMIVAL_INDEX);
            var key = key(argument.get(JAVA_INT, V4l2.FRMIVAL_PIXEL_FORMAT), argument.get(JAVA_INT, V4l2.FRMIVAL_WIDTH),
                    argument.get(JAVA_INT, V4l2.FRMIVAL_HEIGHT));
            var range = intervalRanges.get(key);
            if (range != null) {
                if (index != 0) {
                    return Posix.EINVAL;
                }
                argument.set(JAVA_INT, V4l2.FRMIVAL_TYPE, range[0]);
                argument.set(JAVA_INT, V4l2.FRMIVAL_MIN, range[1]);
                argument.set(JAVA_INT, V4l2.FRMIVAL_MIN + 4, range[2]);
                argument.set(JAVA_INT, V4l2.FRMIVAL_MAX, range[3]);
                argument.set(JAVA_INT, V4l2.FRMIVAL_MAX + 4, range[4]);
                return 0;
            }
            var intervals = discreteIntervals.get(key);
            if (intervals == null || index >= intervals.size()) {
                return Posix.EINVAL;
            }
            argument.set(JAVA_INT, V4l2.FRMIVAL_TYPE, V4l2.TYPE_DISCRETE);
            argument.set(JAVA_INT, V4l2.FRMIVAL_DISCRETE, intervals.get(index)[0]);
            argument.set(JAVA_INT, V4l2.FRMIVAL_DISCRETE + 4, intervals.get(index)[1]);
            return 0;
        }
        if (request == V4l2.VIDIOC_G_FMT || request == V4l2.VIDIOC_S_FMT) {
            var pix = V4l2.FORMAT_PIX;
            if (request == V4l2.VIDIOC_S_FMT) {
                current = substitute != null ? substitute : new int[]{argument.get(JAVA_INT, pix + V4l2.PIX_WIDTH),
                        argument.get(JAVA_INT, pix + V4l2.PIX_HEIGHT), argument.get(JAVA_INT, pix + V4l2.PIX_PIXELFORMAT)};
            }
            argument.set(JAVA_INT, pix + V4l2.PIX_WIDTH, current[0]);
            argument.set(JAVA_INT, pix + V4l2.PIX_HEIGHT, current[1]);
            argument.set(JAVA_INT, pix + V4l2.PIX_PIXELFORMAT, current[2]);
            argument.set(JAVA_INT, pix + V4l2.PIX_FIELD, field);
            argument.set(JAVA_INT, pix + V4l2.PIX_BYTESPERLINE, bytesPerLine);
            argument.set(JAVA_INT, pix + V4l2.PIX_COLORSPACE, color[0]);
            argument.set(JAVA_INT, pix + V4l2.PIX_YCBCR_ENC, color[1]);
            argument.set(JAVA_INT, pix + V4l2.PIX_QUANTIZATION, color[2]);
            argument.set(JAVA_INT, pix + V4l2.PIX_XFER_FUNC, color[3]);
            return 0;
        }
        if (request == V4l2.VIDIOC_G_PARM) {
            argument.set(JAVA_INT, V4l2.STREAMPARM_CAPABILITY, timePerFrame ? V4l2.CAP_TIMEPERFRAME : 0);
            return 0;
        }
        if (request == V4l2.VIDIOC_S_PARM) {
            requestedInterval = new int[]{argument.get(JAVA_INT, V4l2.STREAMPARM_TIMEPERFRAME),
                    argument.get(JAVA_INT, V4l2.STREAMPARM_TIMEPERFRAME + 4)};
            return 0;
        }
        return Posix.ENOTTY;
    }
}
