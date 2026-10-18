package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.CaptureDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Enumerates the cameras AVFoundation offers and reads their formats.
 *
 * <p>Cameras are found with an {@code AVCaptureDeviceDiscoverySession} over the device types of
 * {@link AvFoundation#deviceTypes(boolean)}, or with the deprecated
 * {@code +[AVCaptureDevice devicesWithMediaType:]} when the discovery session class is missing.
 * A camera's id is its {@code uniqueID}, its name its {@code localizedName}, and its modes come
 * from its {@code formats} through {@link CameraFormats#modes(List)}. {@link #first()} and
 * {@link #find(String)} read the formats of the camera they return only, and agree with
 * {@link #list()}. Every method runs on a platform thread inside an autorelease pool.
 */
final class MacCameras {
    /**
     * The {@code Info.plist} key opting in to the Continuity Camera device type.
     */
    private static final String CONTINUITY_KEY = "NSCameraUseContinuityCameraDeviceType";

    /**
     * Prevents instantiation.
     */
    private MacCameras() {
        throw new AssertionError();
    }

    /**
     * Lists the cameras.
     *
     * @return the cameras, the system's default one first
     */
    static List<CaptureDevice.Camera> list() {
        var devices = devices();
        var defaultId = defaultId();
        var cameras = new ArrayList<CaptureDevice.Camera>();
        var count = ObjC.count(devices);
        for (var index = 0; index < count; index++) {
            var device = ObjC.element(devices, index);
            var id = id(device);
            if (id == null) {
                continue;
            }
            var camera = camera(device, id);
            if (id.equals(defaultId)) {
                cameras.addFirst(camera);
            } else {
                cameras.add(camera);
            }
        }
        return List.copyOf(cameras);
    }

    /**
     * Returns the camera {@link #list()} reports first: the system's default camera when it is
     * listed, otherwise the first listed one.
     *
     * @return the camera, or empty when there is none
     */
    static Optional<CaptureDevice.Camera> first() {
        var devices = devices();
        var defaultId = defaultId();
        MemorySegment first = null;
        String firstId = null;
        var count = ObjC.count(devices);
        for (var index = 0; index < count; index++) {
            var device = ObjC.element(devices, index);
            var id = id(device);
            if (id == null) {
                continue;
            }
            if (id.equals(defaultId)) {
                return Optional.of(camera(device, id));
            }
            if (first == null) {
                first = device;
                firstId = id;
            }
        }
        return first == null ? Optional.empty() : Optional.of(camera(first, firstId));
    }

    /**
     * Returns the camera {@link #list()} reports with an identifier.
     *
     * @param id the identifier
     * @return the camera, or empty when no listed camera has that identifier
     */
    static Optional<CaptureDevice.Camera> find(String id) {
        var devices = devices();
        var count = ObjC.count(devices);
        for (var index = 0; index < count; index++) {
            var device = ObjC.element(devices, index);
            if (id.equals(id(device))) {
                return Optional.of(camera(device, id));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the {@code uniqueID} of the system's default camera.
     *
     * @return the identifier, or {@code null} when there is no default camera
     */
    private static String defaultId() {
        var device = ObjC.id(AvFoundation.CAPTURE_DEVICE, AvFoundation.DEFAULT_DEVICE, AvFoundation.MEDIA_TYPE_VIDEO);
        return CoreFoundation.string(ObjC.id(device, AvFoundation.UNIQUE_ID));
    }

    /**
     * Returns the identifier of a device.
     *
     * @param device an {@code AVCaptureDevice}
     * @return its {@code uniqueID}, or {@code null} when it has none
     */
    private static String id(MemorySegment device) {
        var id = CoreFoundation.string(ObjC.id(device, AvFoundation.UNIQUE_ID));
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * Reads a device as a camera, with its name and the modes of its formats.
     *
     * @param device an {@code AVCaptureDevice}
     * @param id its identifier
     * @return the camera
     */
    private static CaptureDevice.Camera camera(MemorySegment device, String id) {
        var name = CoreFoundation.string(ObjC.id(device, AvFoundation.LOCALIZED_NAME));
        return new CaptureDevice.Camera(id, name == null || name.isBlank() ? id : name,
                CameraFormats.modes(formats(ObjC.id(device, AvFoundation.FORMATS))));
    }

    /**
     * Returns the video capture devices.
     *
     * @return an autoreleased {@code NSArray} of {@code AVCaptureDevice}
     */
    private static MemorySegment devices() {
        var types = ObjC.isNull(AvFoundation.DISCOVERY_SESSION) ? List.<MemorySegment>of()
                : AvFoundation.deviceTypes(continuityOptIn());
        if (types.isEmpty()) {
            return ObjC.id(AvFoundation.CAPTURE_DEVICE, AvFoundation.DEVICES_WITH_MEDIA_TYPE, AvFoundation.MEDIA_TYPE_VIDEO);
        }
        try (var arena = Arena.ofConfined()) {
            var objects = arena.allocate(ADDRESS, types.size());
            for (var index = 0; index < types.size(); index++) {
                objects.setAtIndex(ADDRESS, index, types.get(index));
            }
            var array = ObjC.idWithArray(AvFoundation.NS_ARRAY, AvFoundation.ARRAY_WITH_OBJECTS, objects, types.size());
            var session = ObjC.idWithLong(AvFoundation.DISCOVERY_SESSION, AvFoundation.DISCOVERY_WITH_TYPES, array,
                    AvFoundation.MEDIA_TYPE_VIDEO, AvFoundation.POSITION_UNSPECIFIED);
            return ObjC.id(session, AvFoundation.DEVICES);
        }
    }

    /**
     * Returns whether the application's {@code Info.plist} opts in to Continuity Camera devices.
     *
     * @return whether {@code NSCameraUseContinuityCameraDeviceType} is true
     */
    private static boolean continuityOptIn() {
        var bundle = ObjC.id(AvFoundation.NS_BUNDLE, AvFoundation.MAIN_BUNDLE);
        var key = CoreFoundation.createString(CONTINUITY_KEY);
        try {
            var value = ObjC.id(bundle, AvFoundation.INFO_VALUE, key);
            return !ObjC.isNull(value)
                    && ObjC.bool(value, ObjC.selector("respondsToSelector:"), AvFoundation.BOOL_VALUE)
                    && ObjC.bool(value, AvFoundation.BOOL_VALUE);
        } finally {
            CoreFoundation.release(key);
        }
    }

    /**
     * Reads the formats of a device.
     *
     * <p>The list is index-aligned with the {@code NSArray}: a format whose description cannot be
     * read is kept as a zero-sized entry, which offers no mode.
     *
     * @param formats the device's {@code formats} array
     * @return the formats
     */
    static List<CameraFormats.Format> formats(MemorySegment formats) {
        var count = ObjC.count(formats);
        var result = new ArrayList<CameraFormats.Format>(count);
        for (var index = 0; index < count; index++) {
            var format = ObjC.element(formats, index);
            var description = ObjC.id(format, AvFoundation.FORMAT_DESCRIPTION);
            if (ObjC.isNull(description)) {
                result.add(new CameraFormats.Format(0, 0, 0, List.of()));
                continue;
            }
            var dimensions = CoreMedia.dimensions(description);
            var subtype = CoreMedia.mediaSubtype(description);
            var ranges = ObjC.id(format, AvFoundation.FRAME_RATE_RANGES);
            var rangeCount = ObjC.count(ranges);
            var rateRanges = new ArrayList<CameraFormats.RateRange>(rangeCount);
            for (var rangeIndex = 0; rangeIndex < rangeCount; rangeIndex++) {
                var range = ObjC.element(ranges, rangeIndex);
                if (ObjC.isNull(range)) {
                    continue;
                }
                rateRanges.add(new CameraFormats.RateRange(
                        ObjC.real(range, AvFoundation.MIN_FRAME_RATE),
                        ObjC.real(range, AvFoundation.MAX_FRAME_RATE),
                        ObjC.time(range, AvFoundation.MIN_FRAME_DURATION),
                        ObjC.time(range, AvFoundation.MAX_FRAME_DURATION)));
            }
            result.add(new CameraFormats.Format(dimensions[0], dimensions[1], subtype, rateRanges));
        }
        return result;
    }

    /**
     * Finds the index of an object in an {@code NSArray} by identity.
     *
     * @return the index, or {@code -1}
     */
    static int indexOf(MemorySegment array, MemorySegment object) {
        Objects.requireNonNull(object, "object");
        var count = ObjC.count(array);
        for (var index = 0; index < count; index++) {
            if (ObjC.element(array, index).address() == object.address()) {
                return index;
            }
        }
        return -1;
    }
}
