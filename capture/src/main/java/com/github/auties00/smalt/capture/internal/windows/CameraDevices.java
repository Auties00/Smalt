package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.track.Rational;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Enumerates the Media Foundation video capture devices and describes their media types.
 *
 * <p>Every method must run on a thread that joined a COM apartment and started Media Foundation.
 */
final class CameraDevices {
    /**
     * The largest number of native media types read from one stream, a bound that makes the
     * enumeration terminate whatever the driver reports.
     */
    private static final int MAX_TYPES = 4096;

    /**
     * The largest number of devices listed.
     */
    private static final int MAX_DEVICES = 256;

    /**
     * The frame rate assumed for a media type that declares none.
     */
    private static final Rational DEFAULT_RATE = new Rational(30, 1);

    /**
     * Prevents instantiation.
     */
    private CameraDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the symbolic links of the cameras without activating them.
     *
     * <p>Runs on a thread in the multithreaded apartment with Media Foundation started.
     *
     * @return the links, in enumeration order
     * @throws MediaException.Io if the enumeration fails
     */
    static List<String> ids() {
        return list(null, false, false).stream().map(CaptureDevice.Camera::id).toList();
    }

    /**
     * Lists the video capture devices and the modes of each.
     *
     * <p>A device whose media source cannot be activated (for example because another
     * application holds it exclusively) is listed without modes.
     *
     * @return the cameras, in the system's order
     * @throws MediaException.Io if the enumeration fails
     */
    static List<CaptureDevice.Camera> list() {
        return list(null, false, true);
    }

    /**
     * Finds a video capture device, reading the modes of that device only.
     *
     * @param id the symbolic link, or {@code null} for the first device in the system's order
     * @return the camera, or empty when none matches
     * @throws MediaException.Io if the enumeration fails
     */
    static Optional<CaptureDevice.Camera> find(String id) {
        return list(id, true, true).stream().findFirst();
    }

    /**
     * Lists the video capture devices whose symbolic link is {@code id}, or every device when
     * {@code id} is {@code null}, reading the modes of the listed devices only, and only when
     * {@code withModes} is set; without, a camera's name is its link and it has no modes.
     */
    private static List<CaptureDevice.Camera> list(String id, boolean first, boolean withModes) {
        try (var arena = Arena.ofConfined()) {
            var slot = arena.allocate(ADDRESS);
            var attributes = MediaFoundation.createAttributes(1, slot);
            var array = arena.allocate(ADDRESS);
            var count = arena.allocate(JAVA_INT);
            try {
                Com.check(Com.call(attributes, MediaFoundation.ATTRIBUTES_SET_GUID, MediaFoundation.DEVSOURCE_SOURCE_TYPE,
                        MediaFoundation.DEVSOURCE_VIDCAP), "IMFAttributes::SetGUID");
                MediaFoundation.enumDeviceSources(attributes, array, count);
            } finally {
                Com.release(attributes);
            }
            var devices = Math.max(0, count.get(JAVA_INT, 0));
            var activates = array.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * devices);
            var cameras = new ArrayList<CaptureDevice.Camera>();
            try {
                for (var i = 0; i < Math.min(devices, MAX_DEVICES) && !(first && !cameras.isEmpty()); i++) {
                    var activate = activates.getAtIndex(ADDRESS, i);
                    var link = string(activate, MediaFoundation.DEVSOURCE_SYMBOLIC_LINK, arena);
                    if (link != null && (id == null || id.equals(link))) {
                        cameras.add(withModes ? describe(activate, link, arena)
                                : new CaptureDevice.Camera(link, link, List.of()));
                    }
                }
            } finally {
                for (var i = 0; i < devices; i++) {
                    Com.release(activates.getAtIndex(ADDRESS, i));
                }
                Com.free(array.get(ADDRESS, 0));
            }
            return List.copyOf(cameras);
        }
    }

    /**
     * Describes one device.
     *
     * @param activate the device's {@code IMFActivate}
     * @param id the device's symbolic link
     * @param arena the arena of the temporary structures
     * @return the camera
     */
    private static CaptureDevice.Camera describe(MemorySegment activate, String id, Arena arena) {
        var name = string(activate, MediaFoundation.DEVSOURCE_FRIENDLY_NAME, arena);
        return new CaptureDevice.Camera(id, name == null ? id : name, NativeType.modes(types(activate, arena)));
    }

    /**
     * Reads the native media types of a device without opening a capture.
     *
     * @param activate the device's {@code IMFActivate}
     * @param arena the arena of the temporary structures
     * @return the supported types; empty when the device cannot be activated
     */
    private static List<NativeType> types(MemorySegment activate, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        if (Com.call(activate, MediaFoundation.ACTIVATE_ACTIVATE_OBJECT, MediaFoundation.IID_MEDIA_SOURCE, slot) < 0) {
            return List.of();
        }
        var source = slot.get(ADDRESS, 0);
        var reader = MemorySegment.NULL;
        try {
            reader = MediaFoundation.createSourceReader(source, MemorySegment.NULL, slot);
            return nativeTypes(reader, arena);
        } catch (MediaException exception) {
            return List.of();
        } finally {
            Com.release(reader);
            Com.call(activate, MediaFoundation.ACTIVATE_SHUTDOWN_OBJECT);
            Com.release(source);
        }
    }

    /**
     * Reads a string attribute.
     *
     * @param attributes the {@code IMFAttributes}
     * @param key the attribute key
     * @param arena the arena of the temporary structures
     * @return the value, or {@code null} when the attribute is missing
     */
    private static String string(MemorySegment attributes, MemorySegment key, Arena arena) {
        var pointer = arena.allocate(ADDRESS);
        var length = arena.allocate(JAVA_INT);
        if (Com.call(attributes, MediaFoundation.ATTRIBUTES_GET_ALLOCATED_STRING, key, pointer, length) < 0) {
            return null;
        }
        var text = pointer.get(ADDRESS, 0);
        try {
            var chars = length.get(JAVA_INT, 0);
            if (chars < 0 || chars > 1 << 16) {
                return null;
            }
            return new String(text.reinterpret(2L * chars).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
        } finally {
            Com.free(text);
        }
    }

    /**
     * Lists the supported native media types of the first video stream of a source reader.
     *
     * @param reader the {@code IMFSourceReader}
     * @param arena the arena of the temporary structures
     * @return the supported types, in the reader's order
     * @throws MediaException.Io if reading a type fails
     */
    static List<NativeType> nativeTypes(MemorySegment reader, Arena arena) {
        var types = new ArrayList<NativeType>();
        var slot = arena.allocate(ADDRESS);
        for (var index = 0; index < MAX_TYPES; index++) {
            var hr = Com.call(reader, MediaFoundation.READER_GET_NATIVE_MEDIA_TYPE, MediaFoundation.FIRST_VIDEO_STREAM, index, slot);
            if (hr == MediaFoundation.MF_E_NO_MORE_TYPES) {
                break;
            }
            Com.check(hr, "IMFSourceReader::GetNativeMediaType");
            var type = slot.get(ADDRESS, 0);
            try {
                var described = describe(type, index, arena);
                if (described != null) {
                    types.add(described);
                }
            } finally {
                Com.release(type);
            }
        }
        return types;
    }

    /**
     * Describes the reader's current media type.
     *
     * @param reader the {@code IMFSourceReader}
     * @param arena the arena of the temporary structures
     * @return the type, or {@code null} when it is unsupported or cannot be read
     */
    static NativeType currentType(MemorySegment reader, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        if (Com.call(reader, MediaFoundation.READER_GET_CURRENT_MEDIA_TYPE, MediaFoundation.FIRST_VIDEO_STREAM, slot) < 0) {
            return null;
        }
        var type = slot.get(ADDRESS, 0);
        try {
            return describe(type, -1, arena);
        } finally {
            Com.release(type);
        }
    }

    /**
     * Describes a media type.
     *
     * @param type the {@code IMFMediaType}
     * @param index the type's index in the native type list, or {@code -1}
     * @param arena the arena of the temporary structures
     * @return the description, or {@code null} when the type is not uncompressed or Motion-JPEG
     * video the backend converts, or declares an invalid size
     */
    static NativeType describe(MemorySegment type, int index, Arena arena) {
        var guid = arena.allocate(16, 8);
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_GUID, MediaFoundation.MT_MAJOR_TYPE, guid) < 0
                || !Com.sameGuid(guid, MediaFoundation.MEDIA_TYPE_VIDEO)) {
            return null;
        }
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_GUID, MediaFoundation.MT_SUBTYPE, guid) < 0
                || guid.asSlice(4, 12).mismatch(MediaFoundation.MEDIA_TYPE_VIDEO.asSlice(4, 12)) != -1) {
            return null;
        }
        var format = CameraFormat.of(Com.data1(guid));
        if (format == null) {
            return null;
        }
        var value = arena.allocate(JAVA_LONG);
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_UINT64, MediaFoundation.MT_FRAME_SIZE, value) < 0) {
            return null;
        }
        var size = value.get(JAVA_LONG, 0);
        var width = (int) (size >>> 32);
        var height = (int) size;
        if (width <= 0 || height <= 0 || width > MediaLimits.DEFAULT.maxDimension() || height > MediaLimits.DEFAULT.maxDimension()) {
            return null;
        }
        var rate = DEFAULT_RATE;
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_UINT64, MediaFoundation.MT_FRAME_RATE, value) >= 0) {
            var numerator = value.get(JAVA_LONG, 0) >>> 32;
            var denominator = value.get(JAVA_LONG, 0) & 0xFFFFFFFFL;
            if (numerator > 0 && denominator > 0) {
                rate = new Rational(numerator, denominator).reduce();
            }
        }
        var stride = 0;
        var number = arena.allocate(JAVA_INT);
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_UINT32, MediaFoundation.MT_DEFAULT_STRIDE, number) >= 0) {
            stride = number.get(JAVA_INT, 0);
        }
        return new NativeType(index, format, width, height, rate, stride, color(type, format, number));
    }

    /**
     * Returns the color description of the samples a media type delivers.
     *
     * <p>RGB samples are converted to BT.709 limited range ({@link YuvCoefficients#BT709_LIMITED}),
     * like the frames of every screen; Motion-JPEG decodes to full-range BT.601 ({@link ColorInfo#JPEG});
     * Y'CbCr samples use the matrix and range the type declares, BT.601 and limited range when it
     * declares none.
     *
     * @param type the {@code IMFMediaType}
     * @param format the type's pixel format
     * @param number a 32-bit scratch slot
     * @return the color description
     */
    private static ColorInfo color(MemorySegment type, CameraFormat format, MemorySegment number) {
        if (format.rgb()) {
            return YuvCoefficients.BT709_LIMITED.color();
        }
        if (format == CameraFormat.MJPG) {
            return ColorInfo.JPEG;
        }
        var color = ColorInfo.BT601_LIMITED;
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_UINT32, MediaFoundation.MT_YUV_MATRIX, number) >= 0
                && number.get(JAVA_INT, 0) == MediaFoundation.MATRIX_BT709) {
            color = ColorInfo.BT709_LIMITED;
        }
        if (Com.call(type, MediaFoundation.ATTRIBUTES_GET_UINT32, MediaFoundation.MT_VIDEO_NOMINAL_RANGE, number) >= 0
                && number.get(JAVA_INT, 0) == MediaFoundation.RANGE_FULL) {
            color = color.withRange(ColorRange.FULL);
        }
        return color;
    }

    /**
     * Opens the media source of a camera by its symbolic link.
     *
     * @param id the camera's symbolic link
     * @param arena the arena of the temporary structures
     * @return the {@code IMFMediaSource}
     * @throws MediaException.Io if the camera does not exist or cannot be opened
     */
    static MemorySegment openSource(String id, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        var attributes = MediaFoundation.createAttributes(2, slot);
        try {
            Com.check(Com.call(attributes, MediaFoundation.ATTRIBUTES_SET_GUID, MediaFoundation.DEVSOURCE_SOURCE_TYPE,
                    MediaFoundation.DEVSOURCE_VIDCAP), "IMFAttributes::SetGUID");
            Com.check(Com.call(attributes, MediaFoundation.ATTRIBUTES_SET_STRING, MediaFoundation.DEVSOURCE_SYMBOLIC_LINK,
                    arena.allocateFrom(id, StandardCharsets.UTF_16LE)), "IMFAttributes::SetString");
            var hr = MediaFoundation.createDeviceSource(attributes, slot);
            if (hr < 0) {
                throw Com.failure("cannot open the camera " + id, hr);
            }
            return slot.get(ADDRESS, 0);
        } finally {
            Com.release(attributes);
        }
    }
}
