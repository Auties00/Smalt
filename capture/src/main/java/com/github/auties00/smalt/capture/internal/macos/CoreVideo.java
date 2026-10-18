package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls CoreVideo: pixel buffer access and color attachments.
 *
 * <p>This class loads the framework when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class CoreVideo {
    /**
     * The framework.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.CORE_VIDEO);

    /**
     * {@code kCVPixelBufferLock_ReadOnly} ({@code CVPixelBuffer.h}).
     */
    private static final long LOCK_READ_ONLY = 1;

    /**
     * {@code kCVReturnSuccess} ({@code CVReturn.h}).
     */
    private static final int SUCCESS = 0;

    /**
     * The {@code kCVPixelBufferPixelFormatTypeKey} key of output settings dictionaries.
     */
    static final MemorySegment PIXEL_FORMAT_KEY = Frameworks.requiredConstant(LIBRARY, "kCVPixelBufferPixelFormatTypeKey");

    /**
     * The {@code kCVPixelBufferWidthKey} key of output settings dictionaries.
     */
    static final MemorySegment WIDTH_KEY = Frameworks.requiredConstant(LIBRARY, "kCVPixelBufferWidthKey");

    /**
     * The {@code kCVPixelBufferHeightKey} key of output settings dictionaries.
     */
    static final MemorySegment HEIGHT_KEY = Frameworks.requiredConstant(LIBRARY, "kCVPixelBufferHeightKey");

    /**
     * {@code CVReturn CVPixelBufferLockBaseAddress(CVPixelBufferRef, CVPixelBufferLockFlags)}.
     */
    private static final MethodHandle LOCK = Native.function(LIBRARY, "CVPixelBufferLockBaseAddress",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    /**
     * {@code CVReturn CVPixelBufferUnlockBaseAddress(CVPixelBufferRef, CVPixelBufferLockFlags)}.
     */
    private static final MethodHandle UNLOCK = Native.function(LIBRARY, "CVPixelBufferUnlockBaseAddress",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    /**
     * {@code OSType CVPixelBufferGetPixelFormatType(CVPixelBufferRef)}.
     */
    private static final MethodHandle PIXEL_FORMAT = Native.function(LIBRARY, "CVPixelBufferGetPixelFormatType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code Boolean CVPixelBufferIsPlanar(CVPixelBufferRef)}.
     */
    private static final MethodHandle IS_PLANAR = Native.function(LIBRARY, "CVPixelBufferIsPlanar",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS));

    /**
     * {@code size_t CVPixelBufferGetPlaneCount(CVPixelBufferRef)}.
     */
    private static final MethodHandle PLANE_COUNT = Native.function(LIBRARY, "CVPixelBufferGetPlaneCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code size_t CVPixelBufferGetWidth(CVPixelBufferRef)}.
     */
    private static final MethodHandle WIDTH = Native.function(LIBRARY, "CVPixelBufferGetWidth",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code size_t CVPixelBufferGetHeight(CVPixelBufferRef)}.
     */
    private static final MethodHandle HEIGHT = Native.function(LIBRARY, "CVPixelBufferGetHeight",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code void *CVPixelBufferGetBaseAddress(CVPixelBufferRef)}.
     */
    private static final MethodHandle BASE_ADDRESS = Native.function(LIBRARY, "CVPixelBufferGetBaseAddress",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code size_t CVPixelBufferGetBytesPerRow(CVPixelBufferRef)}.
     */
    private static final MethodHandle BYTES_PER_ROW = Native.function(LIBRARY, "CVPixelBufferGetBytesPerRow",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code size_t CVPixelBufferGetWidthOfPlane(CVPixelBufferRef, size_t planeIndex)}.
     */
    private static final MethodHandle PLANE_WIDTH = Native.function(LIBRARY, "CVPixelBufferGetWidthOfPlane",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    /**
     * {@code size_t CVPixelBufferGetHeightOfPlane(CVPixelBufferRef, size_t planeIndex)}.
     */
    private static final MethodHandle PLANE_HEIGHT = Native.function(LIBRARY, "CVPixelBufferGetHeightOfPlane",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    /**
     * {@code void *CVPixelBufferGetBaseAddressOfPlane(CVPixelBufferRef, size_t planeIndex)}.
     */
    private static final MethodHandle PLANE_BASE_ADDRESS = Native.function(LIBRARY, "CVPixelBufferGetBaseAddressOfPlane",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    /**
     * {@code size_t CVPixelBufferGetBytesPerRowOfPlane(CVPixelBufferRef, size_t planeIndex)}.
     */
    private static final MethodHandle PLANE_BYTES_PER_ROW = Native.function(LIBRARY, "CVPixelBufferGetBytesPerRowOfPlane",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    /**
     * {@code CFTypeRef CVBufferCopyAttachment(CVBufferRef, CFStringRef key,
     * CVAttachmentMode *attachmentMode)} (macOS 12), which returns a retained value.
     */
    private static final Optional<MethodHandle> COPY_ATTACHMENT = Native.optionalFunction(LIBRARY, "CVBufferCopyAttachment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code CFTypeRef CVBufferGetAttachment(CVBufferRef, CFStringRef key,
     * CVAttachmentMode *attachmentMode)}, deprecated in macOS 12, which returns a borrowed value.
     */
    private static final MethodHandle GET_ATTACHMENT = Native.function(LIBRARY, "CVBufferGetAttachment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * The {@code kCVImageBufferYCbCrMatrixKey} attachment key.
     */
    private static final MemorySegment MATRIX_KEY = Frameworks.requiredConstant(LIBRARY, "kCVImageBufferYCbCrMatrixKey");

    /**
     * The {@code kCVImageBufferColorPrimariesKey} attachment key.
     */
    private static final MemorySegment PRIMARIES_KEY = Frameworks.requiredConstant(LIBRARY, "kCVImageBufferColorPrimariesKey");

    /**
     * The {@code kCVImageBufferTransferFunctionKey} attachment key.
     */
    private static final MemorySegment TRANSFER_KEY = Frameworks.requiredConstant(LIBRARY, "kCVImageBufferTransferFunctionKey");

    /**
     * The values of {@link ColorAttachments#MATRICES}, {@code NULL} where this system lacks one.
     */
    private static final MemorySegment[] MATRIX_VALUES = constants(ColorAttachments.MATRICES);

    /**
     * The values of {@link ColorAttachments#PRIMARIES}, {@code NULL} where this system lacks one.
     */
    private static final MemorySegment[] PRIMARIES_VALUES = constants(ColorAttachments.PRIMARIES);

    /**
     * The values of {@link ColorAttachments#TRANSFERS}, {@code NULL} where this system lacks one.
     */
    private static final MemorySegment[] TRANSFER_VALUES = constants(ColorAttachments.TRANSFERS);

    /**
     * Prevents instantiation.
     */
    private CoreVideo() {
        throw new AssertionError();
    }

    /**
     * Resolves the exported constants of a table.
     *
     * @return the constants' values in table order, {@code NULL} for missing ones
     */
    private static MemorySegment[] constants(List<? extends ColorAttachments.Constant<?>> table) {
        var values = new MemorySegment[table.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = Frameworks.constant(LIBRARY, table.get(i).symbol()).orElse(MemorySegment.NULL);
        }
        return values;
    }

    /**
     * Locks a pixel buffer's memory for reading.
     *
     * @param pixelBuffer the pixel buffer
     * @return whether the lock succeeded; only then may the base addresses be read
     */
    static boolean lock(MemorySegment pixelBuffer) {
        try {
            return (int) LOCK.invokeExact(pixelBuffer, LOCK_READ_ONLY) == SUCCESS;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Unlocks a pixel buffer locked by {@link #lock(MemorySegment)}.
     *
     * @param pixelBuffer the pixel buffer
     */
    static void unlock(MemorySegment pixelBuffer) {
        try {
            var ignored = (int) UNLOCK.invokeExact(pixelBuffer, LOCK_READ_ONLY);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the pixel format of a pixel buffer.
     *
     * @param pixelBuffer the pixel buffer
     * @return the format, a four-character code
     */
    static int pixelFormat(MemorySegment pixelBuffer) {
        try {
            return (int) PIXEL_FORMAT.invokeExact(pixelBuffer);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns whether a pixel buffer is planar.
     *
     * @param pixelBuffer the pixel buffer
     * @return whether it has separate planes
     */
    static boolean isPlanar(MemorySegment pixelBuffer) {
        try {
            return (byte) IS_PLANAR.invokeExact(pixelBuffer) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the number of planes of a pixel buffer.
     *
     * @param pixelBuffer the pixel buffer
     * @return the plane count, {@code 0} for a packed buffer
     */
    static long planeCount(MemorySegment pixelBuffer) {
        try {
            return (long) PLANE_COUNT.invokeExact(pixelBuffer);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Describes one plane of a locked pixel buffer.
     *
     * @param memory the plane's memory, {@code stride * height} bytes, or {@code NULL}
     * @param stride the bytes per row
     * @param width the visible width in samples (pixels, or Cb/Cr pairs)
     * @param height the visible height in rows
     */
    record Plane(MemorySegment memory, long stride, int width, int height) {
        /**
         * Returns whether the plane can be read.
         *
         * @return whether it has memory and a positive size
         */
        boolean readable() {
            return !ObjC.isNull(memory) && width > 0 && height > 0 && stride > 0;
        }
    }

    /**
     * Returns a plane of a locked planar pixel buffer.
     *
     * @param pixelBuffer the pixel buffer, locked
     * @param index the plane index
     * @return the plane
     */
    static Plane plane(MemorySegment pixelBuffer, int index) {
        try {
            var base = (MemorySegment) PLANE_BASE_ADDRESS.invokeExact(pixelBuffer, (long) index);
            var stride = (long) PLANE_BYTES_PER_ROW.invokeExact(pixelBuffer, (long) index);
            var width = (long) PLANE_WIDTH.invokeExact(pixelBuffer, (long) index);
            var height = (long) PLANE_HEIGHT.invokeExact(pixelBuffer, (long) index);
            return plane(base, stride, width, height);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the single plane of a locked packed pixel buffer.
     *
     * @param pixelBuffer the pixel buffer, locked
     * @return the plane
     */
    static Plane packed(MemorySegment pixelBuffer) {
        try {
            var base = (MemorySegment) BASE_ADDRESS.invokeExact(pixelBuffer);
            var stride = (long) BYTES_PER_ROW.invokeExact(pixelBuffer);
            var width = (long) WIDTH.invokeExact(pixelBuffer);
            var height = (long) HEIGHT.invokeExact(pixelBuffer);
            return plane(base, stride, width, height);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Describes a plane, sizing its memory from its stride and height.
     *
     * @param base the base address
     * @param stride the bytes per row
     * @param width the visible width
     * @param height the visible height
     * @return the plane; unreadable when a value is out of range
     */
    private static Plane plane(MemorySegment base, long stride, long width, long height) {
        if (ObjC.isNull(base) || stride <= 0 || width <= 0 || height <= 0 || width > Integer.MAX_VALUE
                || height > Integer.MAX_VALUE || stride > Long.MAX_VALUE / height) {
            return new Plane(MemorySegment.NULL, 0, 0, 0);
        }
        return new Plane(base.reinterpret(stride * height), stride, (int) width, (int) height);
    }

    /**
     * Returns the index of a buffer's matrix attachment in {@link ColorAttachments#MATRICES}.
     *
     * @param buffer the image buffer
     * @return the index, or {@code -1} when absent or unknown
     */
    static int matrix(MemorySegment buffer) {
        return attachmentIndex(buffer, MATRIX_KEY, MATRIX_VALUES);
    }

    /**
     * Returns the index of a buffer's primaries attachment in {@link ColorAttachments#PRIMARIES}.
     *
     * @param buffer the image buffer
     * @return the index, or {@code -1} when absent or unknown
     */
    static int primaries(MemorySegment buffer) {
        return attachmentIndex(buffer, PRIMARIES_KEY, PRIMARIES_VALUES);
    }

    /**
     * Returns the index of a buffer's transfer attachment in {@link ColorAttachments#TRANSFERS}.
     *
     * @param buffer the image buffer
     * @return the index, or {@code -1} when absent or unknown
     */
    static int transfer(MemorySegment buffer) {
        return attachmentIndex(buffer, TRANSFER_KEY, TRANSFER_VALUES);
    }

    /**
     * Finds which of a set of constants a buffer's attachment equals.
     *
     * @param buffer the image buffer
     * @param key the attachment key
     * @param values the candidate values
     * @return the index of the equal value, or {@code -1}
     */
    private static int attachmentIndex(MemorySegment buffer, MemorySegment key, MemorySegment[] values) {
        try {
            MemorySegment value;
            var retained = COPY_ATTACHMENT.isPresent();
            if (retained) {
                value = (MemorySegment) COPY_ATTACHMENT.get().invokeExact(buffer, key, MemorySegment.NULL);
            } else {
                value = (MemorySegment) GET_ATTACHMENT.invokeExact(buffer, key, MemorySegment.NULL);
            }
            if (ObjC.isNull(value)) {
                return -1;
            }
            try {
                for (var i = 0; i < values.length; i++) {
                    if (CoreFoundation.equal(value, values[i])) {
                        return i;
                    }
                }
                return -1;
            } finally {
                if (retained) {
                    CoreFoundation.release(value);
                }
            }
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }
}
