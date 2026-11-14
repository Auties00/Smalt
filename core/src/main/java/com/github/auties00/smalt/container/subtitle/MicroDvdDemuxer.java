package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.MicroDvdText;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demuxes a MicroDVD ({@code .sub}) file into one {@link Codec#SUBRIP} track.
 *
 * <p>The file is read whole and decoded like a SubRip file (a byte order mark, then the encoding of
 * {@link DemuxerOptions#charset()} or the one detected), and must start with a {@code {start}{end}} cue.
 * Frame numbers count in {@link DemuxerOptions#subtitleFrameRate()} when it is set, else in the rate the
 * file declares in its first cue, else at 24000/1001 frames per second; {@link #frameRate()} reports the
 * rate used. Each packet is one cue, its control codes turned into SubRip markup, in a millisecond time
 * base.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class MicroDvdDemuxer extends CueFileDemuxer {
    /**
     * The frame rate the frame numbers count in.
     */
    private final Rational frameRate;

    /**
     * The frame rate the file declares, or {@code null}.
     */
    private final Rational declared;

    /**
     * Opens a MicroDVD file with the default options.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file does not start with a MicroDVD cue
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public MicroDvdDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a MicroDVD file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the limits, the text encoding and the frame rate
     * @throws MediaException.Malformed if the file does not start with a MicroDVD cue
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public MicroDvdDemuxer(MediaSource source, DemuxerOptions options) {
        this(parse(readText(source, options), options.subtitleFrameRate().orElse(null)),
                options.subtitleFrameRate().orElse(null));
    }

    /**
     * Constructs a demuxer from a parsed file.
     *
     * @param parsed the file
     * @param forced the frame rate the options set, or {@code null}
     */
    private MicroDvdDemuxer(MicroDvdText.Parsed parsed, Rational forced) {
        super(List.of(new Stream(SubtitleEncoder.track(Codec.SUBRIP, 1), parsed.cues(), Map.of())), Map.of());
        this.declared = parsed.frameRate();
        this.frameRate = forced != null ? forced : declared != null ? declared : MicroDvdText.DEFAULT_RATE;
    }

    /**
     * Parses a file, checking its signature.
     *
     * @param text the file text
     * @param frameRate the frame rate the options set, or {@code null}
     * @return the parsed file
     * @throws MediaException.Malformed if the text does not start with a MicroDVD cue
     */
    private static MicroDvdText.Parsed parse(String text, Rational frameRate) {
        if (!MicroDvdText.isSignature(text)) {
            throw new MediaException.Malformed("not a MicroDVD file: it does not start with a {start}{end} cue");
        }
        return MicroDvdText.parse(text, frameRate);
    }

    /**
     * {@return the frame rate the cue times were computed with}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the frame rate the file declares in its first cue, or empty when it declares none}
     */
    public Optional<Rational> declaredFrameRate() {
        return Optional.ofNullable(declared);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MICRODVD;
    }
}
