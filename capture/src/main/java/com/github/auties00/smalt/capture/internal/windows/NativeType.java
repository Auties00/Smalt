package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Describes one native media type of a camera's video stream.
 *
 * @param index the index of the type in the source reader's native type list
 * @param format the pixel format
 * @param width the width in pixels; positive
 * @param height the height in pixels; positive
 * @param frameRate the nominal frame rate, reduced
 * @param defaultStride the signed stride of a contiguous buffer, negative for a bottom-up image,
 * or {@code 0} when the type does not declare it
 * @param color the color description of the samples the type delivers
 */
record NativeType(int index, CameraFormat format, int width, int height, Rational frameRate, int defaultStride,
                  ColorInfo color) {
    /**
     * The largest relative difference between a requested and a native frame rate that still
     * counts as a match, so that {@code 30} matches {@code 30000/1001} and
     * {@code 10000000/333333}.
     */
    private static final double RATE_TOLERANCE = 0.01;

    /**
     * The largest frame area preferred by the default mode when the camera reports no current
     * type.
     */
    private static final long DEFAULT_AREA = 1920L * 1080;

    /**
     * The frame rate preferred by the default mode when the camera reports no current type.
     */
    private static final double DEFAULT_RATE = 30;

    /**
     * Returns the frame rate as a real number.
     *
     * @return the frames per second
     */
    double fps() {
        return frameRate.toDouble();
    }

    /**
     * Returns the capture mode this type delivers.
     *
     * @return the mode
     */
    CaptureMode mode() {
        return new CaptureMode(width, height, frameRate);
    }

    /**
     * Lists the distinct capture modes of a set of native types.
     *
     * @param types the native types
     * @return the modes, largest area first, then fastest, then widest
     */
    static List<CaptureMode> modes(List<NativeType> types) {
        var modes = new LinkedHashSet<CaptureMode>();
        for (var type : types) {
            modes.add(type.mode());
        }
        var sorted = new ArrayList<>(modes);
        sorted.sort(Comparator.comparingLong((CaptureMode mode) -> (long) mode.width() * mode.height()).reversed()
                .thenComparing((first, second) -> Double.compare(second.frameRate().toDouble(), first.frameRate().toDouble()))
                .thenComparing(Comparator.comparingInt(CaptureMode::width).reversed()));
        return List.copyOf(sorted);
    }

    /**
     * Chooses the native type that serves a requested mode.
     *
     * <p>With a mode, the candidates are the types of exactly the requested size whose frame rate
     * is within one percent of the requested one; among them the best-ranked
     * {@link CameraFormat} wins, then the closest frame rate. Without a mode, the size and rate
     * of {@code current} (the driver's default type) are requested the same way; when there is
     * no current type, or no supported format at its size and rate, the largest type up to
     * 1920x1080 is chosen, then the one closest to 30 frames per second, then the best-ranked.
     *
     * @param types the supported native types
     * @param mode the requested mode, or {@code null} for the default
     * @param current the driver's current type, or {@code null}
     * @return the chosen type, or {@code null} when no type serves {@code mode}
     */
    static NativeType choose(List<NativeType> types, CaptureMode mode, NativeType current) {
        if (mode != null) {
            return best(types, mode.width(), mode.height(), mode.frameRate().toDouble());
        }
        if (current != null) {
            var match = best(types, current.width, current.height, current.fps());
            if (match != null) {
                return match;
            }
        }
        NativeType chosen = null;
        for (var type : types) {
            if (chosen == null || prefersAsDefault(type, chosen)) {
                chosen = type;
            }
        }
        return chosen;
    }

    /**
     * Returns the best-ranked type of a size at about a frame rate.
     *
     * @param types the supported native types
     * @param fps the frame rate
     * @return the type, or {@code null} when none matches
     */
    private static NativeType best(List<NativeType> types, int width, int height, double fps) {
        NativeType chosen = null;
        for (var type : types) {
            if (type.width != width || type.height != height || Math.abs(type.fps() / fps - 1) > RATE_TOLERANCE) {
                continue;
            }
            if (chosen == null || type.format.rank() < chosen.format.rank()
                    || type.format.rank() == chosen.format.rank() && Math.abs(type.fps() - fps) < Math.abs(chosen.fps() - fps)) {
                chosen = type;
            }
        }
        return chosen;
    }

    /**
     * Returns whether one type makes a better default than another.
     *
     * @param chosen the type chosen so far
     * @return whether {@code candidate} is preferred
     */
    private static boolean prefersAsDefault(NativeType candidate, NativeType chosen) {
        var candidateArea = (long) candidate.width * candidate.height;
        var chosenArea = (long) chosen.width * chosen.height;
        var candidateFits = candidateArea <= DEFAULT_AREA;
        var chosenFits = chosenArea <= DEFAULT_AREA;
        if (candidateFits != chosenFits) {
            return candidateFits;
        }
        if (candidateArea != chosenArea) {
            return candidateFits == candidateArea > chosenArea;
        }
        var candidateDistance = Math.abs(candidate.fps() - DEFAULT_RATE);
        var chosenDistance = Math.abs(chosen.fps() - DEFAULT_RATE);
        if (candidateDistance != chosenDistance) {
            return candidateDistance < chosenDistance;
        }
        return candidate.format.rank() < chosen.format.rank();
    }
}
