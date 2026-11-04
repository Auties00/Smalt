package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.image.RgbaPicture;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Holds one bitmap subtitle: the pictures shown on a canvas, from when, until when.
 *
 * <p>The canvas is the frame the subtitle stream composes its pictures on, usually the size of the video it
 * accompanies (1920 by 1080 for a Blu-ray presentation graphic stream, the display definition of a DVB stream,
 * 720 by 576 or 480 for a DVD); the regions are drawn on it in order, a later region over an earlier one. A cue
 * without regions is never produced by a decoder: a display set that clears the screen ends the cue before it.
 *
 * <p>A forced cue is one the stream marks to be shown even when subtitles are off, such as the translation of a
 * sign or of a line in another language.
 *
 * <p>Instances are not thread-safe.
 *
 * @param startMicros the time the pictures appear, in microseconds
 * @param endMicros the time the pictures disappear, in microseconds; not before {@code startMicros}
 * @param canvasWidth the width of the canvas in pixels, positive
 * @param canvasHeight the height of the canvas in pixels, positive
 * @param regions the pictures and their positions, in drawing order; unmodifiable
 * @param forced whether the stream marks the cue as forced
 */
public record BitmapCue(long startMicros, long endMicros, int canvasWidth, int canvasHeight,
                        List<BitmapRegion> regions, boolean forced) {
    /**
     * Validates the cue and copies the list of regions.
     *
     * @throws NullPointerException if a region is {@code null}
     * @throws IllegalArgumentException if {@code endMicros} is before {@code startMicros} or a canvas dimension is
     * not positive
     */
    public BitmapCue {
        regions = List.copyOf(regions);
        if (endMicros < startMicros) {
            throw new IllegalArgumentException("cue ends at " + endMicros + " before it starts at " + startMicros);
        }
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + canvasWidth + "x" + canvasHeight);
        }
    }

    /**
     * Returns how long the cue is shown.
     *
     * @return the duration in microseconds
     */
    public long durationMicros() {
        return endMicros - startMicros;
    }

    /**
     * Returns a copy shown at other times.
     *
     * @param startMicros the time the pictures appear, in microseconds
     * @param endMicros the time the pictures disappear, in microseconds
     * @return the copy
     * @throws IllegalArgumentException if {@code endMicros} is before {@code startMicros}
     */
    public BitmapCue withTiming(long startMicros, long endMicros) {
        return new BitmapCue(startMicros, endMicros, canvasWidth, canvasHeight, regions, forced);
    }

    /**
     * Draws the regions on a transparent canvas.
     *
     * <p>Each region is composited over the pixels below it with straight-alpha source-over blending, rounded to
     * the nearest 8-bit value; the parts of a region outside the canvas are cut off. A region that covers only
     * transparent pixels leaves its own pixels unchanged.
     *
     * @return a new picture of the canvas size, with an alpha channel
     */
    public RgbaPicture render() {
        var canvas = RgbaPicture.allocate(canvasWidth, canvasHeight, true);
        var target = canvas.pixels();
        var targetStride = canvas.stride();
        for (var region : regions) {
            var picture = region.picture();
            var source = picture.pixels();
            var width = Math.min(picture.width(), canvasWidth - region.x());
            var height = Math.min(picture.height(), canvasHeight - region.y());
            for (var row = 0; row < height; row++) {
                var from = row * picture.stride();
                var to = (region.y() + row) * targetStride + region.x() * 4;
                for (var column = 0; column < width; column++, from += 4, to += 4) {
                    blend(source, from, target, to);
                }
            }
        }
        return canvas;
    }

    /**
     * Composites one straight-alpha pixel over another.
     *
     * @param source the source samples
     * @param from the index of the source pixel
     * @param target the destination samples, updated in place
     * @param to the index of the destination pixel
     */
    private static void blend(byte[] source, int from, byte[] target, int to) {
        var alpha = source[from + 3] & 0xFF;
        if (alpha == 0) {
            return;
        }
        var below = target[to + 3] & 0xFF;
        if (alpha == 255 || below == 0) {
            System.arraycopy(source, from, target, to, 4);
            return;
        }
        var under = below * (255 - alpha);
        var out = alpha * 255 + under;
        for (var channel = 0; channel < 3; channel++) {
            var value = ((source[from + channel] & 0xFF) * alpha * 255 + (target[to + channel] & 0xFF) * under
                    + out / 2) / out;
            target[to + channel] = (byte) value;
        }
        target[to + 3] = (byte) ((out + 127) / 255);
    }

    /**
     * Compares cues by their times, canvas, forced flag and the positions and pixels of their regions.
     *
     * @param other the object to compare
     * @return {@code true} when the cues show the same pixels at the same times
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof BitmapCue that && startMicros == that.startMicros && endMicros == that.endMicros
                && canvasWidth == that.canvasWidth && canvasHeight == that.canvasHeight && forced == that.forced
                && regions.size() == that.regions.size() && sameRegions(that.regions);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(startMicros, endMicros, canvasWidth, canvasHeight, forced, regions.size());
    }

    /**
     * Returns whether the regions of another cue have the same positions and pixels.
     *
     * @param others the regions of the other cue, as many as this cue's
     * @return {@code true} when every region matches
     */
    private boolean sameRegions(List<BitmapRegion> others) {
        for (var index = 0; index < regions.size(); index++) {
            var a = regions.get(index);
            var b = others.get(index);
            if (a.x() != b.x() || a.y() != b.y() || a.width() != b.width() || a.height() != b.height()) {
                return false;
            }
            var pa = a.picture();
            var pb = b.picture();
            for (var row = 0; row < a.height(); row++) {
                if (!Arrays.equals(pa.pixels(), row * pa.stride(), row * pa.stride() + a.width() * 4,
                        pb.pixels(), row * pb.stride(), row * pb.stride() + a.width() * 4)) {
                    return false;
                }
            }
        }
        return true;
    }
}
