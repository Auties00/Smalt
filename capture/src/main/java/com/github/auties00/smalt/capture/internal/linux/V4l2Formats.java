package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Enumerates what a Video4Linux2 camera offers and chooses the pixel format to capture a mode in.
 *
 * <p>A camera lists its pixel formats ({@code VIDIOC_ENUM_FMT}), the frame sizes of each format
 * ({@code VIDIOC_ENUM_FRAMESIZES}: a list of discrete sizes, or one stepwise or continuous range)
 * and the frame intervals of each size ({@code VIDIOC_ENUM_FRAMEINTERVALS}: a list of discrete
 * intervals, or one range). Discrete entries become modes as they are; ranges contribute the
 * common sizes and rates they contain plus their largest size and highest rate, so the mode list
 * stays short. A mode offered in several pixel formats is captured in the most preferred one
 * (see {@link V4l2PixelFormat}).
 *
 * <p>Every enumeration stops after {@value #MAX_ENTRIES} entries, so a driver that never reports
 * the end of a list cannot hang the caller, and a driver that does not implement the size or
 * interval enumeration is still usable: such a format contributes no listed mode, but an explicit
 * mode is attempted with it and validated by {@code VIDIOC_S_FMT}.
 */
final class V4l2Formats {
    /**
     * The largest number of entries read from one enumeration.
     */
    static final int MAX_ENTRIES = 256;

    /**
     * The frame rate assumed when a driver does not enumerate its intervals.
     */
    static final Rational FALLBACK_RATE = new Rational(30, 1);

    /**
     * The common frame sizes a stepwise or continuous range contributes when it contains them.
     */
    private static final int[][] COMMON_SIZES = {
            {3840, 2160}, {2560, 1440}, {1920, 1080}, {1600, 1200}, {1280, 960}, {1280, 720}, {1024, 768},
            {960, 540}, {800, 600}, {640, 480}, {640, 360}, {424, 240}, {352, 288}, {320, 240}, {320, 180},
            {176, 144}, {160, 120}
    };

    /**
     * The common frame rates an interval range contributes when it contains them.
     */
    private static final int[] COMMON_RATES = {60, 50, 30, 25, 24, 20, 15, 10, 5};

    /**
     * The area of the size a default mode aims at, 1280x720.
     */
    private static final long DEFAULT_AREA = 1280L * 720;

    /**
     * The lowest frame rate a default mode prefers, a little under 30 so that 30000/1001 counts.
     */
    private static final double DEFAULT_RATE = 29.9;

    /**
     * Prevents instantiation.
     */
    private V4l2Formats() {
        throw new AssertionError();
    }

    /**
     * Queries the identity and capabilities of the opened node.
     *
     * @return the capability summary
     * @throws MediaException.Io if the node does not answer {@code VIDIOC_QUERYCAP}
     */
    static Capability query(V4l2Device device) {
        try (var arena = Arena.ofConfined()) {
            var capability = arena.allocate(V4l2.CAPABILITY);
            var error = device.ioctl(V4l2.VIDIOC_QUERYCAP, capability);
            if (error != 0) {
                throw failure("VIDIOC_QUERYCAP", error);
            }
            var capabilities = capability.get(JAVA_INT, V4l2.CAPABILITY_CAPABILITIES);
            if ((capabilities & V4l2.CAP_DEVICE_CAPS) != 0) {
                capabilities = capability.get(JAVA_INT, V4l2.CAPABILITY_DEVICE_CAPS);
            }
            var card = cString(capability, V4l2.CAPABILITY_CARD, V4l2.CAPABILITY_CARD_SIZE);
            return new Capability(card.isEmpty() ? "Camera" : card, capabilities);
        }
    }

    /**
     * Lists the pixel formats the device captures that this backend converts.
     *
     * <p>A single-planar device never offers the formats of several memory planes; should one list
     * them, they are skipped.
     *
     * @return the formats in the order of preference of {@link V4l2PixelFormat}
     * @throws MediaException.Io if the enumeration fails
     */
    static List<V4l2PixelFormat> pixelFormats(V4l2Device device) {
        var found = EnumSet.noneOf(V4l2PixelFormat.class);
        try (var arena = Arena.ofConfined()) {
            var description = arena.allocate(V4l2.FMTDESC);
            for (var index = 0; index < MAX_ENTRIES; index++) {
                description.fill((byte) 0);
                description.set(JAVA_INT, V4l2.FMTDESC_INDEX, index);
                description.set(JAVA_INT, V4l2.FMTDESC_TYPE, device.bufferType());
                var error = device.ioctl(V4l2.VIDIOC_ENUM_FMT, description);
                if (error == Posix.EINVAL) {
                    break;
                }
                if (error != 0) {
                    throw failure("VIDIOC_ENUM_FMT", error);
                }
                var format = V4l2PixelFormat.of(description.get(JAVA_INT, V4l2.FMTDESC_PIXELFORMAT));
                if (format != null && (format.contiguous() || device.multiPlanar())) {
                    found.add(format);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Lists the frame sizes of a pixel format.
     *
     * @param format the pixel format
     * @return the sizes; unknown when the driver does not enumerate them
     * @throws MediaException.Io if the enumeration fails
     */
    static Sizes frameSizes(V4l2Device device, V4l2PixelFormat format) {
        var discrete = new ArrayList<Size>();
        try (var arena = Arena.ofConfined()) {
            var entry = arena.allocate(V4l2.FRMSIZEENUM);
            for (var index = 0; index < MAX_ENTRIES; index++) {
                entry.fill((byte) 0);
                entry.set(JAVA_INT, V4l2.FRMSIZE_INDEX, index);
                entry.set(JAVA_INT, V4l2.FRMSIZE_PIXEL_FORMAT, format.fourcc());
                var error = device.ioctl(V4l2.VIDIOC_ENUM_FRAMESIZES, entry);
                if (error == Posix.EINVAL || (index == 0 && error == Posix.ENOTTY)) {
                    break;
                }
                if (error != 0) {
                    throw failure("VIDIOC_ENUM_FRAMESIZES", error);
                }
                var type = entry.get(JAVA_INT, V4l2.FRMSIZE_TYPE);
                if (type == V4l2.TYPE_DISCRETE) {
                    var width = entry.get(JAVA_INT, V4l2.FRMSIZE_DISCRETE_WIDTH);
                    var height = entry.get(JAVA_INT, V4l2.FRMSIZE_DISCRETE_HEIGHT);
                    if (validSize(width, height)) {
                        discrete.add(new Size(width, height));
                    }
                } else if (index == 0 && (type == V4l2.TYPE_STEPWISE || type == V4l2.TYPE_CONTINUOUS)) {
                    var range = SizeRange.of(
                            entry.get(JAVA_INT, V4l2.FRMSIZE_MIN_WIDTH), entry.get(JAVA_INT, V4l2.FRMSIZE_MAX_WIDTH),
                            type == V4l2.TYPE_CONTINUOUS ? 1 : entry.get(JAVA_INT, V4l2.FRMSIZE_STEP_WIDTH),
                            entry.get(JAVA_INT, V4l2.FRMSIZE_MIN_HEIGHT), entry.get(JAVA_INT, V4l2.FRMSIZE_MAX_HEIGHT),
                            type == V4l2.TYPE_CONTINUOUS ? 1 : entry.get(JAVA_INT, V4l2.FRMSIZE_STEP_HEIGHT));
                    return new Sizes(List.of(), range);
                } else {
                    break;
                }
            }
        }
        return new Sizes(List.copyOf(discrete), null);
    }

    /**
     * Lists the frame rates of a pixel format at a frame size.
     *
     * @param format the pixel format
     * @param width the frame width
     * @param height the frame height
     * @return the rates; unknown when the driver does not enumerate them
     * @throws MediaException.Io if the enumeration fails
     */
    static Rates frameRates(V4l2Device device, V4l2PixelFormat format, int width, int height) {
        var discrete = new ArrayList<Rational>();
        try (var arena = Arena.ofConfined()) {
            var entry = arena.allocate(V4l2.FRMIVALENUM);
            for (var index = 0; index < MAX_ENTRIES; index++) {
                entry.fill((byte) 0);
                entry.set(JAVA_INT, V4l2.FRMIVAL_INDEX, index);
                entry.set(JAVA_INT, V4l2.FRMIVAL_PIXEL_FORMAT, format.fourcc());
                entry.set(JAVA_INT, V4l2.FRMIVAL_WIDTH, width);
                entry.set(JAVA_INT, V4l2.FRMIVAL_HEIGHT, height);
                var error = device.ioctl(V4l2.VIDIOC_ENUM_FRAMEINTERVALS, entry);
                if (error == Posix.EINVAL || (index == 0 && error == Posix.ENOTTY)) {
                    break;
                }
                if (error != 0) {
                    throw failure("VIDIOC_ENUM_FRAMEINTERVALS", error);
                }
                var type = entry.get(JAVA_INT, V4l2.FRMIVAL_TYPE);
                if (type == V4l2.TYPE_DISCRETE) {
                    var rate = rateOfInterval(entry, V4l2.FRMIVAL_DISCRETE);
                    if (rate != null && !discrete.contains(rate)) {
                        discrete.add(rate);
                    }
                } else if (index == 0 && (type == V4l2.TYPE_STEPWISE || type == V4l2.TYPE_CONTINUOUS)) {
                    var fastest = rateOfInterval(entry, V4l2.FRMIVAL_MIN);
                    var slowest = rateOfInterval(entry, V4l2.FRMIVAL_MAX);
                    if (fastest == null || slowest == null || compare(slowest, fastest) > 0) {
                        break;
                    }
                    return new Rates(List.of(), slowest, fastest);
                } else {
                    break;
                }
            }
        }
        return new Rates(List.copyOf(discrete), null, null);
    }

    /**
     * Lists every combination of pixel format, size and rate the device offers.
     *
     * @return the offers, in the order of preference of {@link V4l2PixelFormat}
     * @throws MediaException.Io if an enumeration fails
     */
    static List<Offer> offers(V4l2Device device) {
        var offers = new ArrayList<Offer>();
        for (var format : pixelFormats(device)) {
            for (var size : frameSizes(device, format).candidates()) {
                for (var rate : frameRates(device, format, size.width(), size.height()).candidates()) {
                    offers.add(new Offer(format, size.width(), size.height(), rate));
                }
            }
        }
        return offers;
    }

    /**
     * Derives the capture modes of a list of offers.
     *
     * @return one mode per distinct size and rate, largest first and fastest first within a size
     */
    static List<CaptureMode> modes(List<Offer> offers) {
        var distinct = new LinkedHashMap<List<Long>, CaptureMode>();
        for (var offer : offers) {
            var key = List.of((long) offer.width(), (long) offer.height(), offer.rate().numerator(), offer.rate().denominator());
            distinct.putIfAbsent(key, new CaptureMode(offer.width(), offer.height(), offer.rate()));
        }
        var modes = new ArrayList<>(distinct.values());
        modes.sort(Comparator.comparingLong((CaptureMode mode) -> (long) mode.width() * mode.height()).reversed()
                .thenComparing(CaptureMode::width, Comparator.reverseOrder())
                .thenComparing((first, second) -> compare(second.frameRate(), first.frameRate())));
        return List.copyOf(modes);
    }

    /**
     * Chooses the offer that captures a mode.
     *
     * <p>Without a mode, the default is the offer that best matches 1280x720 at 30 frames per
     * second: offers reaching 30 frames per second come first, then those not larger than
     * 1280x720 (largest first), then the larger ones (smallest first), then the rate closest to
     * 30 frames per second, then the preferred pixel format. A camera that enumerates no size falls back to its current
     * format. With a mode, the first pixel format in the order of preference whose sizes and
     * rates contain the mode is chosen.
     *
     * @param mode the requested mode, or {@code null} for the default
     * @return the offer
     * @throws MediaException.Unsupported if the camera cannot capture {@code mode}, or offers no
     * pixel format this backend converts
     * @throws MediaException.Io if an enumeration fails
     */
    static Offer select(V4l2Device device, CaptureMode mode) {
        if (mode == null) {
            var offers = offers(device);
            if (offers.isEmpty()) {
                return current(device);
            }
            return offers.stream().min(defaultOrder()).orElseThrow();
        }
        var rate = mode.frameRate().reduce();
        for (var format : pixelFormats(device)) {
            if (!frameSizes(device, format).contains(mode.width(), mode.height())) {
                continue;
            }
            if (frameRates(device, format, mode.width(), mode.height()).contains(rate)) {
                return new Offer(format, mode.width(), mode.height(), rate);
            }
        }
        throw new MediaException.Unsupported("v4l2 mode", "the camera cannot capture " + mode.width() + "x"
                + mode.height() + " at " + rate + " frames per second in a supported pixel format");
    }

    /**
     * Sets the device's format to an offer and returns the layout and converter of its frames,
     * letting the driver choose the field order.
     *
     * @param kernel the row kernel of the converter
     * @return the negotiated frames
     * @throws MediaException.Unsupported if the driver substitutes another format or size, or
     * reports a field order or plane layout this backend does not assemble
     * @throws MediaException.Io if the request fails, for example because another
     * process is capturing
     */
    static Negotiated negotiate(V4l2Device device, Offer offer, ConversionKernel kernel) {
        return negotiate(device, offer, kernel, V4l2.FIELD_ANY);
    }

    /**
     * Sets the device's format to an offer with a field order and returns the layout and converter
     * of its frames.
     *
     * <p>The driver must accept the offer's pixel format and size exactly; for the field orders
     * that deliver one field per buffer ({@code V4L2_FIELD_TOP}, {@code V4L2_FIELD_BOTTOM},
     * {@code V4L2_FIELD_ALTERNATE}) the height it reports is that of a field, half the offer's.
     * The field order the driver answers with may differ from the one requested; every order of
     * {@link V4l2FrameLayout} is accepted. The color of the frames comes from the format's
     * colorspace, encoding, quantization and transfer; R'G'B' and luma-only samples are full range
     * unless the driver reports limited quantization, as the V4L2 test pattern generator produces them.
     *
     * @param kernel the row kernel of the converter
     * @param field the requested {@code field}, {@link V4l2#FIELD_ANY} to let the driver choose
     * @return the negotiated frames
     * @throws MediaException.Unsupported if the driver substitutes another format or size, or
     * reports a field order or plane layout this backend does not assemble
     * @throws MediaException.Io if the request fails, for example because another
     * process is capturing
     */
    static Negotiated negotiate(V4l2Device device, Offer offer, ConversionKernel kernel, int field) {
        try (var arena = Arena.ofConfined()) {
            var format = arena.allocate(V4l2.FORMAT);
            var pix = V4l2.FORMAT_PIX;
            format.set(JAVA_INT, V4l2.FORMAT_TYPE, device.bufferType());
            format.set(JAVA_INT, pix + V4l2.PIX_WIDTH, offer.width());
            format.set(JAVA_INT, pix + V4l2.PIX_HEIGHT, fieldRows(field) ? offer.height() / 2 : offer.height());
            format.set(JAVA_INT, pix + V4l2.PIX_PIXELFORMAT, offer.format().fourcc());
            format.set(JAVA_INT, pix + V4l2.PIX_FIELD, field);
            if (device.multiPlanar()) {
                format.set(JAVA_BYTE, pix + V4l2.MP_NUM_PLANES, (byte) offer.format().memoryPlanes());
            }
            var error = device.ioctl(V4l2.VIDIOC_S_FMT, format);
            if (error != 0) {
                throw failure(error == Posix.EBUSY ? "VIDIOC_S_FMT (the camera is in use)" : "VIDIOC_S_FMT", error);
            }
            var width = format.get(JAVA_INT, pix + V4l2.PIX_WIDTH);
            var rows = format.get(JAVA_INT, pix + V4l2.PIX_HEIGHT);
            var fourcc = format.get(JAVA_INT, pix + V4l2.PIX_PIXELFORMAT);
            var actualField = format.get(JAVA_INT, pix + V4l2.PIX_FIELD);
            var height = fieldRows(actualField) ? 2L * rows : rows;
            if (fourcc != offer.format().fourcc() || width != offer.width() || height != offer.height()) {
                throw new MediaException.Unsupported("v4l2 mode", "the driver replaced " + offer.width() + "x" + offer.height()
                        + " " + V4l2.fourccName(offer.format().fourcc()) + " with " + width + "x" + height + " "
                        + V4l2.fourccName(fourcc));
            }
            int[] strides;
            int colorspace;
            int encoding;
            int quantization;
            int transfer;
            if (device.multiPlanar()) {
                var planes = Byte.toUnsignedInt(format.get(JAVA_BYTE, pix + V4l2.MP_NUM_PLANES));
                if (planes != offer.format().memoryPlanes()) {
                    throw new MediaException.Unsupported("v4l2 planes", "the driver describes " + V4l2.fourccName(fourcc)
                            + " with " + planes + " memory planes instead of " + offer.format().memoryPlanes());
                }
                strides = new int[planes];
                for (var plane = 0; plane < planes; plane++) {
                    strides[plane] = format.get(JAVA_INT, pix + V4l2.MP_PLANE_FMT + plane * V4l2.PLANE_PIX_FORMAT.byteSize()
                            + V4l2.PLANE_FMT_BYTESPERLINE);
                }
                colorspace = format.get(JAVA_INT, pix + V4l2.MP_COLORSPACE);
                encoding = Byte.toUnsignedInt(format.get(JAVA_BYTE, pix + V4l2.MP_YCBCR_ENC));
                quantization = Byte.toUnsignedInt(format.get(JAVA_BYTE, pix + V4l2.MP_QUANTIZATION));
                transfer = Byte.toUnsignedInt(format.get(JAVA_BYTE, pix + V4l2.MP_XFER_FUNC));
            } else {
                strides = new int[]{format.get(JAVA_INT, pix + V4l2.PIX_BYTESPERLINE)};
                colorspace = format.get(JAVA_INT, pix + V4l2.PIX_COLORSPACE);
                encoding = format.get(JAVA_INT, pix + V4l2.PIX_YCBCR_ENC);
                quantization = format.get(JAVA_INT, pix + V4l2.PIX_QUANTIZATION);
                transfer = format.get(JAVA_INT, pix + V4l2.PIX_XFER_FUNC);
            }
            var kind = offer.format().kind();
            if ((kind == V4l2PixelFormat.Kind.RGB || kind == V4l2PixelFormat.Kind.LUMA)
                    && quantization != V4l2Colors.QUANTIZATION_LIMITED) {
                quantization = V4l2Colors.QUANTIZATION_FULL;
            }
            var color = V4l2Colors.colorInfo(colorspace, encoding, quantization, transfer);
            var layout = new V4l2FrameLayout(offer.format(), width, rows, actualField, strides);
            return new Negotiated(layout, new FrameConverter(layout, color, kernel));
        }
    }

    /**
     * Returns whether a field order delivers one field per buffer, so the reported height is a
     * field's.
     *
     * @param field the {@code field}
     * @return whether the order is top, bottom or alternate
     */
    static boolean fieldRows(int field) {
        return field == V4l2.FIELD_TOP || field == V4l2.FIELD_BOTTOM || field == V4l2.FIELD_ALTERNATE;
    }

    /**
     * Asks the device to capture at a frame rate.
     *
     * <p>The rate is a request: a driver without {@code V4L2_CAP_TIMEPERFRAME} keeps its own rate,
     * and a driver may round the interval. The interval is scaled down until both terms fit in
     * 32 bits.
     *
     * @param rate the frame rate
     * @throws MediaException.Io if the device is busy
     */
    static void requestRate(V4l2Device device, Rational rate) {
        try (var arena = Arena.ofConfined()) {
            var parameters = arena.allocate(V4l2.STREAMPARM);
            parameters.set(JAVA_INT, V4l2.STREAMPARM_TYPE, device.bufferType());
            if (device.ioctl(V4l2.VIDIOC_G_PARM, parameters) != 0
                    || (parameters.get(JAVA_INT, V4l2.STREAMPARM_CAPABILITY) & V4l2.CAP_TIMEPERFRAME) == 0) {
                return;
            }
            var numerator = rate.denominator();
            var denominator = rate.numerator();
            while (numerator > 0xFFFFFFFFL || denominator > 0xFFFFFFFFL) {
                numerator = Math.max(1, numerator >>> 1);
                denominator = Math.max(1, denominator >>> 1);
            }
            parameters.set(JAVA_INT, V4l2.STREAMPARM_TIMEPERFRAME, (int) numerator);
            parameters.set(JAVA_INT, V4l2.STREAMPARM_TIMEPERFRAME + 4, (int) denominator);
            var error = device.ioctl(V4l2.VIDIOC_S_PARM, parameters);
            if (error == Posix.EBUSY) {
                throw failure("VIDIOC_S_PARM (the camera is in use)", error);
            }
        }
    }

    /**
     * Returns the order that ranks offers for the default mode, best first.
     *
     * @return the comparator
     */
    static Comparator<Offer> defaultOrder() {
        return Comparator.comparing((Offer offer) -> offer.rate().toDouble() < DEFAULT_RATE)
                .thenComparing(offer -> offer.area() > DEFAULT_AREA)
                .thenComparingLong(offer -> offer.area() > DEFAULT_AREA ? offer.area() : -offer.area())
                .thenComparingDouble(offer -> offer.rate().toDouble() < DEFAULT_RATE ? -offer.rate().toDouble() : offer.rate().toDouble());
    }

    /**
     * Returns the device's current format as an offer.
     *
     * @return the offer at the current size, with {@link #FALLBACK_RATE}
     * @throws MediaException.Unsupported if the current pixel format is not converted
     * @throws MediaException.Io if the format cannot be read
     */
    private static Offer current(V4l2Device device) {
        try (var arena = Arena.ofConfined()) {
            var format = arena.allocate(V4l2.FORMAT);
            format.set(JAVA_INT, V4l2.FORMAT_TYPE, device.bufferType());
            var error = device.ioctl(V4l2.VIDIOC_G_FMT, format);
            if (error != 0) {
                throw failure("VIDIOC_G_FMT", error);
            }
            var pixelFormat = V4l2PixelFormat.of(format.get(JAVA_INT, V4l2.FORMAT_PIX + V4l2.PIX_PIXELFORMAT));
            var width = format.get(JAVA_INT, V4l2.FORMAT_PIX + V4l2.PIX_WIDTH);
            var rows = format.get(JAVA_INT, V4l2.FORMAT_PIX + V4l2.PIX_HEIGHT);
            var height = fieldRows(format.get(JAVA_INT, V4l2.FORMAT_PIX + V4l2.PIX_FIELD)) ? 2 * rows : rows;
            if (pixelFormat == null || (!pixelFormat.contiguous() && !device.multiPlanar()) || !validSize(width, height)) {
                throw new MediaException.Unsupported("v4l2 pixel format", "the camera offers no pixel format this backend converts");
            }
            return new Offer(pixelFormat, width, height, FALLBACK_RATE);
        }
    }

    /**
     * Reads a frame interval and inverts it into a frame rate.
     *
     * @param entry the structure holding the interval
     * @param offset the offset of the {@code struct v4l2_fract}
     * @return the reduced rate, or {@code null} when a term is zero
     */
    private static Rational rateOfInterval(MemorySegment entry, long offset) {
        var numerator = Integer.toUnsignedLong(entry.get(JAVA_INT, offset));
        var denominator = Integer.toUnsignedLong(entry.get(JAVA_INT, offset + 4));
        if (numerator == 0 || denominator == 0) {
            return null;
        }
        return new Rational(denominator, numerator).reduce();
    }

    /**
     * Compares two rates exactly.
     *
     * @param first the first rate
     * @param second the second rate
     * @return a negative number, zero or a positive number as {@code first} is smaller than, equal
     * to or larger than {@code second}
     */
    static int compare(Rational first, Rational second) {
        return BigInteger.valueOf(first.numerator()).multiply(BigInteger.valueOf(second.denominator()))
                .compareTo(BigInteger.valueOf(second.numerator()).multiply(BigInteger.valueOf(first.denominator())));
    }

    /**
     * Checks that a frame size is positive and within the default picture limits.
     *
     * @return whether the size is usable
     */
    private static boolean validSize(int width, int height) {
        var limits = MediaLimits.DEFAULT;
        return width > 0 && height > 0 && width <= limits.maxDimension() && height <= limits.maxDimension()
                && (long) width * height <= limits.maxPixels();
    }

    /**
     * Decodes a NUL-terminated UTF-8 string field.
     *
     * @param segment the structure
     * @param offset the offset of the field
     * @param size the size of the field
     * @return the string, trimmed
     */
    private static String cString(MemorySegment segment, long offset, int size) {
        var bytes = new byte[size];
        MemorySegment.copy(segment, JAVA_BYTE, offset, bytes, 0, size);
        var length = 0;
        while (length < size && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
    }

    /**
     * Builds the failure of a request.
     *
     * @param request the request name
     * @param errno the error number
     * @return the failure
     */
    static MediaException.Io failure(String request, int errno) {
        var cause = new IOException(request + " failed: " + Posix.describe(errno));
        return new MediaException.Io(cause.getMessage(), cause);
    }

    /**
     * Summarizes {@code VIDIOC_QUERYCAP}.
     *
     * @param card the device's name
     * @param capabilities the capabilities of the opened node
     */
    record Capability(String card, int capabilities) {
        /**
         * Returns whether the node captures single-planar or multi-planar video with streaming I/O.
         *
         * @return whether this backend can capture from the node
         */
        boolean canCapture() {
            return (capabilities & (V4l2.CAP_VIDEO_CAPTURE | V4l2.CAP_VIDEO_CAPTURE_MPLANE)) != 0
                    && (capabilities & V4l2.CAP_STREAMING) != 0;
        }

        /**
         * Returns the buffer type to capture the node with: single-planar when the node offers it.
         *
         * @return {@link V4l2#BUF_TYPE_VIDEO_CAPTURE} or {@link V4l2#BUF_TYPE_VIDEO_CAPTURE_MPLANE}
         */
        int bufferType() {
            return (capabilities & V4l2.CAP_VIDEO_CAPTURE) != 0 ? V4l2.BUF_TYPE_VIDEO_CAPTURE : V4l2.BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        }

        /**
         * Returns the device that issues requests to the node with its buffer type.
         *
         * @param requests the node's requests
         * @return the device
         */
        V4l2Device device(V4l2Device requests) {
            return V4l2Device.of(requests, bufferType());
        }
    }

    /**
     * Pairs a width and a height.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     */
    record Size(int width, int height) {
    }

    /**
     * Describes a stepwise or continuous range of frame sizes.
     *
     * @param minWidth the smallest width
     * @param maxWidth the largest width
     * @param stepWidth the width increment; positive
     * @param minHeight the smallest height
     * @param maxHeight the largest height
     * @param stepHeight the height increment; positive
     */
    record SizeRange(int minWidth, int maxWidth, int stepWidth, int minHeight, int maxHeight, int stepHeight) {
        /**
         * Builds a range from driver values, or returns {@code null} when they are inconsistent.
         *
         * @param minWidth the smallest width
         * @param maxWidth the largest width
         * @param stepWidth the width increment
         * @param minHeight the smallest height
         * @param maxHeight the largest height
         * @param stepHeight the height increment
         * @return the range, or {@code null}
         */
        static SizeRange of(int minWidth, int maxWidth, int stepWidth, int minHeight, int maxHeight, int stepHeight) {
            if (minWidth <= 0 || minHeight <= 0 || maxWidth < minWidth || maxHeight < minHeight || stepWidth <= 0 || stepHeight <= 0) {
                return null;
            }
            return new SizeRange(minWidth, maxWidth, stepWidth, minHeight, maxHeight, stepHeight);
        }

        /**
         * Returns whether the range contains a size.
         *
         * @return whether the size lies within the bounds and on the steps
         */
        boolean contains(int width, int height) {
            return width >= minWidth && width <= maxWidth && height >= minHeight && height <= maxHeight
                    && (width - minWidth) % stepWidth == 0 && (height - minHeight) % stepHeight == 0;
        }
    }

    /**
     * Describes the frame sizes of a pixel format: a list of discrete sizes, a range, or nothing
     * when the driver does not enumerate them.
     *
     * @param discrete the discrete sizes
     * @param range the range, or {@code null}
     */
    record Sizes(List<Size> discrete, SizeRange range) {
        /**
         * Returns whether a size is offered; an unknown set accepts every size.
         *
         * @return whether the size may be requested
         */
        boolean contains(int width, int height) {
            if (range != null) {
                return range.contains(width, height);
            }
            return discrete.isEmpty() || discrete.contains(new Size(width, height));
        }

        /**
         * Returns the sizes to list as modes.
         *
         * @return the discrete sizes, or the common sizes and the largest size of the range
         */
        List<Size> candidates() {
            if (range == null) {
                return discrete;
            }
            var candidates = new ArrayList<Size>();
            var largest = new Size(range.maxWidth(), range.maxHeight());
            if (validSize(largest.width(), largest.height())) {
                candidates.add(largest);
            }
            for (var common : COMMON_SIZES) {
                var size = new Size(common[0], common[1]);
                if (range.contains(size.width(), size.height()) && !candidates.contains(size)) {
                    candidates.add(size);
                }
            }
            return candidates;
        }
    }

    /**
     * Describes the frame rates of a pixel format at one size: a list of discrete rates, a range,
     * or nothing when the driver does not enumerate them.
     *
     * @param discrete the discrete rates, reduced
     * @param slowest the lowest rate of the range, or {@code null}
     * @param fastest the highest rate of the range, or {@code null}
     */
    record Rates(List<Rational> discrete, Rational slowest, Rational fastest) {
        /**
         * Returns whether a rate is offered; an unknown set accepts every rate.
         *
         * @param rate the rate, reduced
         * @return whether the rate may be requested
         */
        boolean contains(Rational rate) {
            if (slowest != null) {
                return compare(rate, slowest) >= 0 && compare(rate, fastest) <= 0;
            }
            return discrete.isEmpty() || discrete.contains(rate);
        }

        /**
         * Returns the rates to list as modes.
         *
         * @return the discrete rates, the common rates and the highest rate of the range, or
         * {@link #FALLBACK_RATE} when the rates are unknown
         */
        List<Rational> candidates() {
            if (slowest == null) {
                return discrete.isEmpty() ? List.of(FALLBACK_RATE) : discrete;
            }
            var candidates = new ArrayList<Rational>();
            candidates.add(fastest);
            for (var common : COMMON_RATES) {
                var rate = new Rational(common, 1);
                if (contains(rate) && !candidates.contains(rate)) {
                    candidates.add(rate);
                }
            }
            return candidates;
        }
    }

    /**
     * Pairs the layout of the negotiated frames with their converter.
     *
     * @param layout where the planes of a buffer lie and how buffers become frames
     * @param converter the converter of the assembled frames
     */
    record Negotiated(V4l2FrameLayout layout, FrameConverter converter) {
    }

    /**
     * Describes one way to capture: a pixel format at a size and rate.
     *
     * @param format the pixel format
     * @param width the frame width
     * @param height the frame height
     * @param rate the frame rate, reduced
     */
    record Offer(V4l2PixelFormat format, int width, int height, Rational rate) {
        /**
         * Validates the offer.
         *
         * @throws NullPointerException if {@code format} or {@code rate} is {@code null}
         */
        Offer {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(rate, "rate");
        }

        /**
         * Returns the frame area.
         *
         * @return {@code width * height}
         */
        long area() {
            return (long) width * height;
        }
    }
}
