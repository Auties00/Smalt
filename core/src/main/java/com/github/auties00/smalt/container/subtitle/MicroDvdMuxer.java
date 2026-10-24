package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.internal.subtitle.MicroDvdText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Objects;

/**
 * Writes a MicroDVD ({@code .sub}) file from one {@link Codec#SUBRIP} track.
 *
 * <p>The file starts with a cue declaring its frame rate ({@code {1}{1}23.976}), then holds one line per
 * cue, its times rounded to the nearest frame and its lines separated by {@code |}. The styles, color,
 * font and size a whole line shares become its control codes; styles covering part of a line and
 * placements are not written. Consecutive packets with the same text that follow each other without a
 * gap become one cue.
 *
 * <p>Instances are not thread-safe.
 */
public final class MicroDvdMuxer extends BufferedCueMuxer {
    /**
     * The frame rate of the file.
     */
    private final Rational frameRate;

    /**
     * Constructs a muxer writing at 24000/1001 frames per second.
     *
     * @param output the sink, written from its position; not closed by the muxer
     */
    public MicroDvdMuxer(MediaSink output) {
        this(output, MicroDvdText.DEFAULT_RATE);
    }

    /**
     * Constructs a muxer writing at a frame rate.
     *
     * @param output the sink, written from its position; not closed by the muxer
     * @param frameRate the frame rate the frame numbers count in, from 0.001 to 1000 frames per second; the
     * file declares it with three decimals and counts its frames at the declared rate, which is this one for
     * the NTSC rates (23.976, 29.97, 59.94 and the like) and for rates with at most three decimals
     * @throws IllegalArgumentException if the frame rate is not positive or is outside that range
     */
    public MicroDvdMuxer(MediaSink output, Rational frameRate) {
        super(Codec.SUBRIP, 1, output);
        Objects.requireNonNull(frameRate, "frameRate");
        if (frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            throw new IllegalArgumentException("the frame rate must be positive, not " + frameRate);
        }
        var declared = MicroDvdText.declarable(frameRate);
        if (declared == null) {
            throw new IllegalArgumentException("a MicroDVD file cannot declare the frame rate " + frameRate);
        }
        this.frameRate = declared;
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#SUBRIP} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.SUBRIP;
    }

    @Override
    String name() {
        return "MicroDVD";
    }

    @Override
    String format(List<Entry> entries) {
        return MicroDvdText.write(entries.isEmpty() ? List.of() : entries.getFirst().cues(), frameRate);
    }
}
