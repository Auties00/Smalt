package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.ops.CompositeOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.WatermarkOptions;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleRenderer;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the burned-in subtitles and the watermark on the frames shown at each time.
 *
 * <p>A cue is drawn for the size of the first frame and kept while it is shown. Cues shown together
 * in the same keypad row are stacked away from their edge, in the order they start. A frame with
 * nothing to draw passes unchanged; another is copied and drawn on. An instance is not thread-safe.
 */
final class OverlayStep implements FrameSink {
    private final FrameSink next;

    /**
     * The cues by start time, or {@code null} without burned-in subtitles.
     */
    private final List<SubtitleCue> cues;

    private final SubtitleMarkup markup;

    private final SubtitleStyle style;

    /**
     * The watermark, or {@code null} for none.
     */
    private final Picture watermark;

    private final WatermarkOptions watermarkOptions;

    private final ColorTransformOptions conversion;

    private final Rational timeBase;

    /**
     * The start of the kept range in the input time base.
     */
    private final long start;

    /**
     * Whether {@link #cues} needs sorting before the next frame.
     */
    private boolean unsorted;

    /**
     * The drawn cues still shown, with the stacking offset they were drawn at.
     */
    private final Map<SubtitleCue, Drawn> drawn = new IdentityHashMap<>();

    /**
     * The prepared watermark, or {@code null} before the first frame.
     */
    private VideoOverlay mark;

    /**
     * The copy frames are drawn on, or {@code null} before the first.
     */
    private Picture canvas;

    /**
     * The overlays of the current frame, reused from frame to frame.
     */
    private final List<VideoOverlay> shown = new ArrayList<>();

    /**
     * The height taken in each keypad row by the cues of the current frame.
     */
    private final int[] stacked = new int[3];

    /**
     * Constructs a step.
     *
     * @param next the receiver of the drawn frames
     * @param cues the cues known before the first frame, with times on the input timeline in
     * microseconds, or {@code null} without burned-in subtitles
     * @param markup the markup of the cue text
     * @param style the style of the drawn cues, or {@code null} without burned-in subtitles
     * @param watermark the watermark, or {@code null} for none
     * @param watermarkOptions the placement of the watermark
     * @param conversion the options of the conversion of the drawn colors to the frame color
     * @param timeBase the time base of the frame times
     * @param start the start of the kept range in {@code timeBase}
     */
    OverlayStep(FrameSink next, List<SubtitleCue> cues, SubtitleMarkup markup, SubtitleStyle style, Picture watermark,
                WatermarkOptions watermarkOptions, ColorTransformOptions conversion, Rational timeBase, long start) {
        this.next = next;
        this.cues = cues == null ? null : new ArrayList<>(cues);
        this.markup = markup;
        this.style = style;
        this.watermark = watermark;
        this.watermarkOptions = watermarkOptions;
        this.conversion = conversion;
        this.timeBase = timeBase;
        this.start = start;
        this.unsorted = true;
    }

    /**
     * Adds a cue that arrived during the transcode.
     *
     * @param cue the cue, with times on the input timeline in microseconds
     */
    void addCue(SubtitleCue cue) {
        if (cues != null) {
            cues.add(cue);
            unsorted = true;
        }
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        if (watermark != null && mark == null) {
            mark = prepareWatermark(picture);
        }
        var overlays = active(timeBase.toMicros(time + start), picture);
        if (overlays.isEmpty() && (mark == null || mark.isEmpty())) {
            next.accept(picture, time, duration, scan);
            return;
        }
        canvas = PictureCopy.copy(picture, canvas);
        for (var overlay : overlays) {
            overlay.apply(canvas);
        }
        if (mark != null) {
            mark.apply(canvas);
        }
        next.accept(canvas, time, duration, scan);
    }

    /**
     * Returns the drawn cues shown at a time, drawing the cues that start showing and forgetting
     * those that ended.
     *
     * @param micros the time on the input timeline, in microseconds
     * @param template a frame of the size and color the cues are drawn for
     * @return the overlays to apply, in order
     */
    private List<VideoOverlay> active(long micros, Picture template) {
        shown.clear();
        if (cues == null || cues.isEmpty()) {
            return shown;
        }
        if (unsorted) {
            cues.sort(Comparator.comparingLong(SubtitleCue::startMicros));
            unsorted = false;
        }
        drawn.keySet().removeIf(cue -> cue.endMicros() <= micros || cue.startMicros() > micros);
        Arrays.fill(stacked, 0);
        for (var cue : cues) {
            if (cue.startMicros() > micros) {
                break;
            }
            if (cue.endMicros() <= micros) {
                continue;
            }
            var bitmap = drawn.get(cue);
            if (bitmap == null || bitmap.offset != stacked[bitmap.row]) {
                bitmap = draw(cue, template, stacked);
                if (bitmap == null) {
                    continue;
                }
                drawn.put(cue, bitmap);
            }
            stacked[bitmap.row] += bitmap.height;
            shown.add(bitmap.overlay);
        }
        return shown;
    }

    /**
     * Draws a cue.
     *
     * @param cue the cue to draw
     * @param template a frame of the target size and color
     * @param stacked the height already taken in each row by the cues drawn before it
     * @return the drawn cue, or {@code null} when it shows nothing
     */
    private Drawn draw(SubtitleCue cue, Picture template, int[] stacked) {
        var rendered = SubtitleRenderer.render(cue, markup, style, template.width(), template.height());
        if (rendered.isEmpty()) {
            return null;
        }
        var bitmap = rendered.get();
        var center = bitmap.y() + bitmap.picture().height() / 2;
        var row = center < template.height() / 3 ? 2 : center > 2 * template.height() / 3 ? 0 : 1;
        var offset = stacked[row];
        var y = switch (row) {
            case 0 -> bitmap.y() - offset;
            case 2 -> bitmap.y() + offset;
            default -> bitmap.y();
        };
        var overlay = new VideoOverlay(bitmap.picture(), bitmap.x(), y,
                CompositeOptions.defaults(), template, conversion);
        var margin = (int) Math.ceil(style.outline() * style.size() * template.height()) + 1;
        return new Drawn(overlay, row, offset, row == 1 ? 0 : Math.max(0, bitmap.picture().height() - 2 * margin));
    }

    /**
     * Prepares the watermark for frames like a template.
     *
     * @param template the first frame
     * @return the prepared watermark
     */
    private VideoOverlay prepareWatermark(Picture template) {
        var overlay = watermark;
        if (watermarkOptions.scale() > 0) {
            var width = (int) Math.max(1, Math.round(template.width() * watermarkOptions.scale()));
            var height = (int) Math.max(1, Math.round((double) width * overlay.height() / overlay.width()));
            overlay = ImageOps.resize(overlay, width, height, watermarkOptions.filter());
        }
        var anchor = watermarkOptions.anchor().ordinal();
        var x = offset(anchor % 3, template.width(), overlay.width(), watermarkOptions.marginX());
        var y = offset(anchor / 3, template.height(), overlay.height(), watermarkOptions.marginY());
        return new VideoOverlay(overlay, x, y, watermarkOptions.composite(), template, conversion);
    }

    /**
     * {@return the offset of an anchored span along one axis, as the image watermark computes it}
     *
     * @param position the anchored position: 0 start, 1 center, 2 end
     * @param outer the length of the frame
     * @param inner the length of the mark
     * @param margin the distance from the anchored edge
     */
    private static int offset(int position, int outer, int inner, int margin) {
        return switch (position) {
            case 0 -> margin;
            case 1 -> Math.floorDiv(outer - inner, 2);
            default -> outer - inner - margin;
        };
    }

    @Override
    public void finish(long end) {
        next.finish(end);
    }

    /**
     * Holds a drawn cue.
     *
     * @param overlay the prepared overlay
     * @param row the keypad row: 0 bottom, 1 middle, 2 top
     * @param offset the stacking offset it was drawn at
     * @param height the height it takes in its row
     */
    private record Drawn(VideoOverlay overlay, int row, int offset, int height) {
    }
}
