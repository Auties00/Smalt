package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns the formats an {@code AVCaptureDevice} reports into capture modes, and picks the format
 * and frame duration that honor a requested mode.
 *
 * <p>An {@code AVCaptureDeviceFormat} has fixed dimensions and a list of
 * {@code AVFrameRateRange}s; a range admits every frame duration between its
 * {@code minFrameDuration} (the fastest rate) and its {@code maxFrameDuration} (the slowest). A
 * camera offers one {@link CaptureMode} per format and range, at the range's fastest rate.
 */
final class CameraFormats {
    /**
     * The relative frame rate difference under which a requested rate snaps to a range's limit,
     * so that a request for 30 frames per second opens a camera that runs at 30000/1001.
     */
    private static final double RATE_TOLERANCE = 0.002;

    /**
     * Orders modes largest first, then by width, then fastest first.
     */
    private static final Comparator<CaptureMode> LARGEST_FIRST = Comparator
            .comparingLong((CaptureMode mode) -> (long) mode.width() * mode.height()).reversed()
            .thenComparing(Comparator.comparingInt(CaptureMode::width).reversed())
            .thenComparing(Comparator.comparingDouble((CaptureMode mode) -> mode.frameRate().toDouble()).reversed());

    /**
     * Prevents instantiation.
     */
    private CameraFormats() {
        throw new AssertionError();
    }

    /**
     * Describes one {@code AVFrameRateRange}.
     *
     * @param minRate the slowest rate in frames per second ({@code minFrameRate})
     * @param maxRate the fastest rate in frames per second ({@code maxFrameRate})
     * @param minDuration the shortest frame duration ({@code minFrameDuration}), the reciprocal
     * of {@code maxRate}
     * @param maxDuration the longest frame duration ({@code maxFrameDuration}), the reciprocal of
     * {@code minRate}
     */
    record RateRange(double minRate, double maxRate, CmTime minDuration, CmTime maxDuration) {
        /**
         * Validates the range.
         *
         * @throws NullPointerException if a duration is {@code null}
         */
        RateRange {
            Objects.requireNonNull(minDuration, "minDuration");
            Objects.requireNonNull(maxDuration, "maxDuration");
        }

        /**
         * Returns the fastest rate of the range as an exact fraction.
         *
         * <p>The rate is the reciprocal of {@link #minDuration()} when that is a positive numeric
         * time, else {@link #maxRate()} rounded to a thousandth of a frame.
         *
         * @return the fastest rate, or empty when the range reports none
         */
        Optional<Rational> fastest() {
            if (minDuration.isNumeric() && minDuration.value() > 0) {
                return Optional.of(minDuration.toRate());
            }
            if (Double.isFinite(maxRate) && maxRate >= 0.001) {
                return Optional.of(new Rational(Math.round(maxRate * 1000), 1000).reduce());
            }
            return Optional.empty();
        }

        /**
         * Returns the duration to configure for a requested frame duration, if the range can
         * honor it.
         *
         * <p>A duration inside the range is returned as is, or as the range's own limit when it
         * equals one, so the device receives the exact value it reported. A duration outside the
         * range but within {@link #RATE_TOLERANCE} of a limit snaps to that limit.
         *
         * @param requested the requested frame duration, numeric and positive
         * @return the duration to configure, or empty when the range cannot honor the request
         */
        Optional<CmTime> honor(CmTime requested) {
            if (!minDuration.isNumeric() || !maxDuration.isNumeric() || minDuration.value() <= 0
                    || maxDuration.value() <= 0 || minDuration.compareDuration(maxDuration) > 0) {
                return Optional.empty();
            }
            var lower = requested.compareDuration(minDuration);
            var upper = requested.compareDuration(maxDuration);
            if (lower == 0) {
                return Optional.of(minDuration);
            }
            if (upper == 0) {
                return Optional.of(maxDuration);
            }
            if (lower > 0 && upper < 0) {
                return Optional.of(requested);
            }
            var rate = 1 / seconds(requested);
            if (lower < 0 && Math.abs(rate * seconds(minDuration) - 1) <= RATE_TOLERANCE) {
                return Optional.of(minDuration);
            }
            if (upper > 0 && Math.abs(rate * seconds(maxDuration) - 1) <= RATE_TOLERANCE) {
                return Optional.of(maxDuration);
            }
            return Optional.empty();
        }

        /**
         * Returns a numeric time in seconds.
         *
         * @return {@code value / timescale}
         */
        private static double seconds(CmTime time) {
            return (double) time.value() / time.timescale();
        }
    }

    /**
     * Describes one {@code AVCaptureDeviceFormat}.
     *
     * @param width the width in pixels ({@code CMVideoDimensions.width})
     * @param height the height in pixels ({@code CMVideoDimensions.height})
     * @param pixelFormat the media subtype of the format description, a four-character code
     * @param ranges the supported frame rate ranges
     */
    record Format(int width, int height, int pixelFormat, List<RateRange> ranges) {
        /**
         * Validates the format and freezes the ranges.
         *
         * @throws NullPointerException if {@code ranges} or a range is {@code null}
         */
        Format {
            ranges = List.copyOf(ranges);
        }

        /**
         * Returns whether the camera produces this format as NV12, so the output needs no
         * conversion.
         *
         * @return whether the pixel format is {@code '420v'} or {@code '420f'}
         */
        boolean nativeNv12() {
            return pixelFormat == FourCc.NV12_VIDEO_RANGE || pixelFormat == FourCc.NV12_FULL_RANGE;
        }
    }

    /**
     * Identifies the format and frame duration to configure.
     *
     * @param index the index of the format in the device's {@code formats} array
     * @param mode the mode the camera captures in once configured
     * @param frameDuration the frame duration to set as both {@code activeVideoMinFrameDuration}
     * and {@code activeVideoMaxFrameDuration}
     */
    record Selection(int index, CaptureMode mode, CmTime frameDuration) {
    }

    /**
     * Lists the capture modes of a camera.
     *
     * <p>Each format contributes one mode per frame rate range, at the range's fastest rate;
     * formats with a non-positive dimension and ranges without a rate are skipped, and duplicates
     * (several pixel formats of the same size and rate) are listed once.
     *
     * @param formats the camera's formats
     * @return the modes, largest first, then fastest first
     */
    static List<CaptureMode> modes(List<Format> formats) {
        var modes = new LinkedHashSet<CaptureMode>();
        for (var format : formats) {
            if (format.width() <= 0 || format.height() <= 0) {
                continue;
            }
            for (var range : format.ranges()) {
                range.fastest().ifPresent(rate -> modes.add(new CaptureMode(format.width(), format.height(), rate)));
            }
        }
        var sorted = new ArrayList<>(modes);
        sorted.sort(LARGEST_FIRST);
        return List.copyOf(sorted);
    }

    /**
     * Picks the format and frame duration that honor a mode.
     *
     * <p>The format must have the mode's dimensions and a range that honors the mode's frame
     * duration (see {@link RateRange#honor(CmTime)}). Among several candidates a format the
     * camera produces as NV12 wins, then the first one listed.
     *
     * @param formats the camera's formats
     * @param mode the requested mode
     * @return the selection, or empty when no format honors the mode
     */
    static Optional<Selection> select(List<Format> formats, CaptureMode mode) {
        var requested = CmTime.frameDuration(mode.frameRate());
        Selection best = null;
        var bestNative = false;
        for (var index = 0; index < formats.size(); index++) {
            var format = formats.get(index);
            if (format.width() != mode.width() || format.height() != mode.height()) {
                continue;
            }
            for (var range : format.ranges()) {
                var duration = range.honor(requested);
                if (duration.isEmpty()) {
                    continue;
                }
                if (best == null || (!bestNative && format.nativeNv12())) {
                    best = new Selection(index, new CaptureMode(format.width(), format.height(), duration.get().toRate()),
                            duration.get());
                    bestNative = format.nativeNv12();
                }
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Describes the mode a camera is configured for when it is opened without a mode.
     *
     * <p>That is the device's {@code activeFormat} at its {@code activeVideoMinFrameDuration}, or
     * at the fastest rate of the format's first range when the active duration is not one the
     * format admits.
     *
     * @param formats the camera's formats
     * @param activeIndex the index of the active format in {@code formats}
     * @param activeDuration the device's active minimum frame duration
     * @return the selection, or empty when the index is out of range or the format has no usable
     * dimensions or rate
     */
    static Optional<Selection> active(List<Format> formats, int activeIndex, CmTime activeDuration) {
        if (activeIndex < 0 || activeIndex >= formats.size()) {
            return Optional.empty();
        }
        var format = formats.get(activeIndex);
        if (format.width() <= 0 || format.height() <= 0) {
            return Optional.empty();
        }
        if (activeDuration.isNumeric() && activeDuration.value() > 0) {
            for (var range : format.ranges()) {
                var duration = range.honor(activeDuration);
                if (duration.isPresent()) {
                    return Optional.of(new Selection(activeIndex,
                            new CaptureMode(format.width(), format.height(), duration.get().toRate()), duration.get()));
                }
            }
        }
        for (var range : format.ranges()) {
            if (range.minDuration().isNumeric() && range.minDuration().value() > 0) {
                return Optional.of(new Selection(activeIndex,
                        new CaptureMode(format.width(), format.height(), range.minDuration().toRate()), range.minDuration()));
            }
        }
        return Optional.empty();
    }
}
