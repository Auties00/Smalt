package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Calls CoreMedia: sample buffers, format descriptions and the host clock.
 *
 * <p>This class loads the framework when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class CoreMedia {
    /**
     * The framework.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.CORE_MEDIA);

    /**
     * {@code CVImageBufferRef CMSampleBufferGetImageBuffer(CMSampleBufferRef sbuf)}.
     */
    private static final MethodHandle IMAGE_BUFFER = Native.function(LIBRARY, "CMSampleBufferGetImageBuffer",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code CMTime CMSampleBufferGetPresentationTimeStamp(CMSampleBufferRef sbuf)}.
     */
    private static final MethodHandle PRESENTATION_TIME = Native.function(LIBRARY, "CMSampleBufferGetPresentationTimeStamp",
            FunctionDescriptor.of(CmTime.LAYOUT, ADDRESS));

    /**
     * {@code CFArrayRef CMSampleBufferGetSampleAttachmentsArray(CMSampleBufferRef sbuf,
     * Boolean createIfNecessary)}.
     */
    private static final MethodHandle SAMPLE_ATTACHMENTS = Native.function(LIBRARY, "CMSampleBufferGetSampleAttachmentsArray",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_BYTE));

    /**
     * {@code CMVideoDimensions CMVideoFormatDescriptionGetDimensions(
     * CMVideoFormatDescriptionRef videoDesc)}.
     */
    private static final MethodHandle DIMENSIONS = Native.function(LIBRARY, "CMVideoFormatDescriptionGetDimensions",
            FunctionDescriptor.of(NativeLayouts.CM_VIDEO_DIMENSIONS, ADDRESS));

    /**
     * {@code FourCharCode CMFormatDescriptionGetMediaSubType(CMFormatDescriptionRef desc)}.
     */
    private static final MethodHandle MEDIA_SUBTYPE = Native.function(LIBRARY, "CMFormatDescriptionGetMediaSubType",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code CMClockRef CMClockGetHostTimeClock(void)}.
     */
    private static final MethodHandle HOST_CLOCK = Native.function(LIBRARY, "CMClockGetHostTimeClock",
            FunctionDescriptor.of(ADDRESS));

    /**
     * {@code CMTime CMClockGetTime(CMClockRef clock)}.
     */
    private static final MethodHandle CLOCK_TIME = Native.function(LIBRARY, "CMClockGetTime",
            FunctionDescriptor.of(CmTime.LAYOUT, ADDRESS));

    /**
     * Prevents instantiation.
     */
    private CoreMedia() {
        throw new AssertionError();
    }

    /**
     * Returns the image of a video sample buffer.
     *
     * @param sampleBuffer the sample buffer
     * @return the {@code CVImageBufferRef}, not retained, or {@code NULL} when the buffer carries
     * no image
     */
    static MemorySegment imageBuffer(MemorySegment sampleBuffer) {
        try {
            return (MemorySegment) IMAGE_BUFFER.invokeExact(sampleBuffer);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the presentation time of a sample buffer.
     *
     * @param sampleBuffer the sample buffer
     * @return the time, possibly not numeric
     */
    static CmTime presentationTime(MemorySegment sampleBuffer) {
        try (var arena = Arena.ofConfined()) {
            var time = (MemorySegment) PRESENTATION_TIME.invokeExact((SegmentAllocator) arena, sampleBuffer);
            return CmTime.read(time);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the per-sample attachment dictionaries of a sample buffer.
     *
     * @param sampleBuffer the sample buffer
     * @return the {@code CFArrayRef}, not retained, or {@code NULL} when it has none
     */
    static MemorySegment sampleAttachments(MemorySegment sampleBuffer) {
        try {
            return (MemorySegment) SAMPLE_ATTACHMENTS.invokeExact(sampleBuffer, (byte) 0);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the dimensions of a video format description.
     *
     * @param description the format description
     * @return the width and height in pixels
     */
    static int[] dimensions(MemorySegment description) {
        try (var arena = Arena.ofConfined()) {
            var dimensions = (MemorySegment) DIMENSIONS.invokeExact((SegmentAllocator) arena, description);
            return new int[]{dimensions.get(JAVA_INT, 0), dimensions.get(JAVA_INT, 4)};
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the media subtype of a format description.
     *
     * @param description the format description
     * @return the subtype, a four-character code such as {@code '420v'}
     */
    static int mediaSubtype(MemorySegment description) {
        try {
            return (int) MEDIA_SUBTYPE.invokeExact(description);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the current time of the host clock, the clock capture timestamps are expressed in.
     *
     * @return the time
     */
    static CmTime hostTime() {
        try (var arena = Arena.ofConfined()) {
            var clock = (MemorySegment) HOST_CLOCK.invokeExact();
            var time = (MemorySegment) CLOCK_TIME.invokeExact((SegmentAllocator) arena, clock);
            return CmTime.read(time);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }
}
