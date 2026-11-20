package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifSequence;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Plays the image sequence of an AVIF file ({@code avis}): the color track, and the alpha track
 * that references it with {@code auxl} when there is one, decoded sample by sample.
 *
 * <p>The color track is the first track with an {@code av01} sample entry and samples that is
 * not itself an auxiliary track; the alpha track is the first such track that references it with
 * {@code auxl} and whose {@code auxi} box, when present, names the alpha URN. Every sample of the
 * two tracks shows one frame, and the frame of the n-th alpha sample is the alpha plane of the
 * frame of the n-th color sample, whatever the timing of the two tracks. The samples are read
 * through {@link Mp4Demuxer}.
 *
 * <p>Each frame is converted to RGBA: 16-bit RGBA when the reader keeps a source depth above 8
 * bits, 8-bit RGBA otherwise. Color premultiplied by alpha ({@code prem} track reference) is
 * divided back; alpha frames of another bit depth than the color frames are rescaled to it, and
 * alpha frames of another size are resampled to it with {@link HeifPictures#resampleAlpha}. A
 * frame's timestamp is the presentation time of its color sample relative to the first one, and
 * it lasts until that sample's duration ends.
 *
 * <p>The reader seeks: it restarts both tracks at their last sync samples at or before the
 * requested time, decodes the samples up to the one shown then, which is the frame a decode from
 * the start shows, and pairs it with the alpha sample of the same index, so a seek returns the
 * frames a read from the start does. When the alpha track restarts after that sample, the reader
 * restarts both tracks at earlier times, down to the start, until the alpha track restarts at or
 * before it. A reader is not thread-safe.
 *
 * @implNote This implementation chooses the tracks, and pairs their samples by index, as
 * libavif's {@code avifDecoderReset} and {@code avifDecoderNextImage} ({@code src/read.c}, BSD
 * 2-clause) do.
 */
public final class SequenceReader implements AnimationReader {
    /**
     * The demuxer of the file.
     */
    private final Mp4Demuxer demuxer;

    /**
     * The color track.
     */
    private final VideoTrack colorTrack;

    /**
     * The alpha track, or {@code null}.
     */
    private final VideoTrack alphaTrack;

    /**
     * The decoder of the color track.
     */
    private final ItemDecoder colorDecoder;

    /**
     * The decoder of the alpha track, or {@code null}.
     */
    private final ItemDecoder alphaDecoder;

    /**
     * The color box of the color track's sample entry, or {@code null}.
     */
    private final HeifProperty.ColorNclx nclx;

    /**
     * The color track's description from its sample entry.
     */
    private final HeifMovie.Track info;

    /**
     * Whether the color is premultiplied by the alpha.
     */
    private final boolean premultiplied;

    /**
     * The bounds of the frames.
     */
    private final MediaLimits limits;

    /**
     * The samples of the color and alpha tracks, in track order.
     */
    private final HeifSequence samples;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The presentation time of the first color sample, in track time base units, or
     * {@link Packet#NO_TIMESTAMP} until it is known.
     */
    private long colorBase = Packet.NO_TIMESTAMP;

    /**
     * The frame a seek decoded, which the next {@link #read()} returns, or {@code null}.
     */
    private AnimationFrame pending;

    /**
     * Whether a seek went past the last frame, so that the next {@link #read()} returns
     * {@code null}.
     */
    private boolean exhausted;

    /**
     * The number of frames returned since the reader was opened or last sought.
     */
    private int frames;

    /**
     * Whether the frames {@link #read()} returns keep a source depth above 8 bits.
     */
    private final boolean highBitDepth;

    /**
     * Opens the sequence of a file.
     *
     * @param source the file
     * @param file the parsed file, which has a {@code moov} box
     * @param limits the bounds
     * @param highBitDepth whether the frames {@link #read()} returns keep a source depth above 8
     * bits, as {@link Rgba16Picture} pictures
     * @throws MediaException.Malformed if the file has no AV1 color track or its tracks are
     * corrupt
     * @throws MediaException.LimitExceeded if the canvas or the sample count exceeds the limits
     */
    public SequenceReader(MediaSource source, HeifFile file, MediaLimits limits, boolean highBitDepth) {
        var movie = HeifMovie.parse(source, file, limits);
        var color = colorTrack(movie);
        if (color == null) {
            throw new MediaException.Malformed("avif: the sequence has no AV1 color track");
        }
        var alpha = alphaTrack(movie, color.id());
        this.info = color;
        this.limits = limits;
        this.highBitDepth = highBitDepth;
        this.premultiplied = alpha != null && color.references("prem", alpha.id());
        this.nclx = color.property(HeifProperty.ColorNclx.class);
        this.demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().limits(limits).build());
        try {
            this.colorTrack = videoTrack(color.id());
            this.alphaTrack = alpha == null ? null : videoTrack(alpha.id());
            this.width = color.width() > 0 ? color.width() : colorTrack.width();
            this.height = color.height() > 0 ? color.height() : colorTrack.height();
            LimitChecks.checkPicture(limits, width, height);
            this.samples = new HeifSequence(demuxer, colorTrack.id(), alphaTrack == null ? -1 : alphaTrack.id(), limits,
                    "avif");
        } catch (RuntimeException exception) {
            demuxer.close();
            throw exception;
        }
        this.colorDecoder = new ItemDecoder(limits, true);
        this.alphaDecoder = alphaTrack == null ? null : new ItemDecoder(limits, true);
    }

    /**
     * Finds the color track.
     *
     * @param movie the parsed image sequence
     * @return the track, or {@code null} when there is none
     */
    static HeifMovie.Track colorTrack(HeifMovie movie) {
        for (var track : movie.tracks()) {
            if (track.id() > 0 && track.sampleEntry().equals("av01") && track.sampleCount() > 0
                    && !track.references().containsKey("auxl")) {
                return track;
            }
        }
        return null;
    }

    /**
     * Finds the alpha track of a color track.
     *
     * @param movie the parsed image sequence
     * @param colorId the color track identifier
     * @return the track, or {@code null} when there is none
     */
    static HeifMovie.Track alphaTrack(HeifMovie movie, int colorId) {
        for (var track : movie.tracks()) {
            if (track.id() <= 0 || !track.sampleEntry().equals("av01") || track.sampleCount() == 0) {
                continue;
            }
            var type = track.property(HeifProperty.AuxiliaryType.class);
            if (type != null && !type.isAlpha()) {
                continue;
            }
            if (track.references("auxl", colorId)) {
                return track;
            }
        }
        return null;
    }

    /**
     * Returns the demuxer's video track with an identifier.
     *
     * @param id the track identifier
     * @return the track
     * @throws MediaException.Malformed if the demuxer has no such AV1 track
     */
    private VideoTrack videoTrack(int id) {
        for (var track : demuxer.tracks()) {
            if (track.id() == id && track instanceof VideoTrack video && video.codec() == Codec.AV1) {
                return video;
            }
        }
        throw new MediaException.Malformed("avif: track " + id + " is not an AV1 video track");
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int loopCount() {
        return info.loopCount();
    }

    @Override
    public int frameCount() {
        return (int) Math.min(Integer.MAX_VALUE, info.sampleCount());
    }

    /**
     * Returns whether the sequence has an alpha track.
     *
     * @return {@code true} when frames carry alpha
     */
    public boolean hasAlpha() {
        return alphaTrack != null;
    }

    /**
     * Returns the orientation the color track's matrix declares.
     *
     * @return the orientation
     */
    public com.github.auties00.smalt.image.Orientation orientation() {
        return colorTrack.orientation();
    }

    @Override
    public AnimationFrame read() {
        if (pending != null) {
            var frame = pending;
            pending = null;
            return frame;
        }
        if (exhausted) {
            return null;
        }
        try {
            var color = nextColor();
            if (color == null) {
                return null;
            }
            Packet alpha = null;
            if (alphaTrack != null) {
                alpha = nextAlpha();
                if (alpha == null) {
                    throw new MediaException.Malformed("avif: the alpha track has fewer samples than the color track");
                }
            }
            LimitChecks.checkFrames(limits, frames + 1L);
            var frame = frame(color, alpha);
            frames++;
            return frame;
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding an AVIF sequence");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF sequence", exception);
        }
    }

    /**
     * Decodes a color sample and its alpha sample into a frame.
     *
     * @param color the color sample
     * @param alpha the alpha sample, or {@code null} without an alpha track
     * @return the frame
     */
    private AnimationFrame frame(Packet color, Packet alpha) {
        var picture = decode(color, alpha, highBitDepth);
        var start = relative(color.pts());
        var end = relative(color.pts() + Math.max(0, color.duration()));
        var straight = premultiplied ? HeifPictures.unpremultiply(picture) : picture;
        var rgba = highBitDepth && (straight instanceof Yuv16Picture || straight instanceof Rgba16Picture)
                ? ImageOps.toRgba16(straight) : ImageOps.toRgba(straight);
        return new AnimationFrame(rgba, start, Math.max(0, end - start));
    }

    /**
     * {@return a presentation time of the color track relative to its first sample, in
     * microseconds}
     *
     * @param pts the presentation time, in track time base units
     */
    private long relative(long pts) {
        return colorTrack.timeBase().toMicros(pts - colorBase);
    }

    /**
     * {@return the next color sample, or {@code null} at the end of the track}
     */
    private Packet nextColor() {
        var packet = samples.nextColor();
        if (packet != null && colorBase == Packet.NO_TIMESTAMP) {
            colorBase = packet.pts();
        }
        return packet;
    }

    /**
     * {@return the next alpha sample, or {@code null} at the end of the track}
     */
    private Packet nextAlpha() {
        return samples.nextAlpha();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The frame then returned equals the one a decode from the start returns at that time
     * (see the class description).
     *
     * @throws MediaException.Malformed if a sample decoded while seeking is corrupt, or the alpha
     * track has no sample for the frame
     * @throws MediaException.LimitExceeded if the samples of the two tracks are too far apart
     */
    @Override
    public void seek(long micros) {
        try {
            seekFrom(Math.max(0, micros));
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while seeking an AVIF sequence");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed AVIF sequence", exception);
        }
    }

    /**
     * Seeks to a time at or after the first frame.
     *
     * <p>The color sample shown at the target is found from the last sync samples at or before
     * it. When the alpha track restarts after that sample, both tracks restart at half the time,
     * then a quarter, down to the start, until the alpha track restarts at or before it, and the
     * color track is decoded again up to the same sample.
     *
     * @param target the time relative to the first color sample, in microseconds, at least zero
     */
    private void seekFrom(long target) {
        pending = null;
        exhausted = false;
        if (colorBase == Packet.NO_TIMESTAMP) {
            var first = samples.peekColor();
            if (first == null) {
                exhausted = true;
                return;
            }
            colorBase = first.pts();
        }
        var base = colorTrack.timeBase().toMicros(colorBase);
        restart(base + target);
        Packet color;
        while (true) {
            color = nextColor();
            if (color == null) {
                exhausted = true;
                return;
            }
            if (relative(color.pts() + Math.max(0, color.duration())) > target) {
                break;
            }
            skip(colorDecoder, colorTrack, color);
        }
        Packet alpha = null;
        if (alphaTrack != null) {
            var index = color.sampleIndex();
            if (!alphaRestartsBy(index)) {
                var time = target;
                do {
                    time /= 2;
                    // the last attempt restarts every track at its first sync sample, which an alpha track timed
                    // faster than its color track may hold before the first color sample
                    restart(time > 0 ? base + time : Math.min(0, base));
                } while (time > 0 && !alphaRestartsBy(index));
                if (!alphaRestartsBy(index)) {
                    throw new MediaException.Malformed("avif: the alpha track has no sample for color sample " + index);
                }
                color = colorSample(index);
            }
            alpha = alphaSample(index);
        }
        pending = frame(color, alpha);
    }

    /**
     * Seeks the demuxer and resets the decoders.
     *
     * @param micros the presentation time the demuxer seeks to, in microseconds
     */
    private void restart(long micros) {
        samples.seek(micros);
        colorDecoder.close();
        if (alphaDecoder != null) {
            alphaDecoder.close();
        }
        frames = 0;
    }

    /**
     * {@return whether the next alpha sample comes at or before a sample, so that its frame can be decoded}
     *
     * @param index the index of the sample
     */
    private boolean alphaRestartsBy(long index) {
        var first = samples.peekAlpha();
        return first != null && first.sampleIndex() <= index;
    }

    /**
     * Decodes the color samples before a sample and returns that sample.
     *
     * @param index the index of the sample
     * @return the sample
     * @throws MediaException.Malformed if the color track has no such sample after where it restarted
     */
    private Packet colorSample(long index) {
        while (true) {
            var color = nextColor();
            if (color == null || color.sampleIndex() > index) {
                throw new MediaException.Malformed("avif: the color track has no sample " + index);
            }
            if (color.sampleIndex() == index) {
                return color;
            }
            skip(colorDecoder, colorTrack, color);
        }
    }

    /**
     * Decodes the alpha samples before a sample and returns that sample.
     *
     * @param index the index of the sample
     * @return the sample
     * @throws MediaException.Malformed if the alpha track has no such sample
     */
    private Packet alphaSample(long index) {
        while (true) {
            var alpha = nextAlpha();
            if (alpha == null || alpha.sampleIndex() > index) {
                throw new MediaException.Malformed("avif: the alpha track has no sample for color sample " + index);
            }
            if (alpha.sampleIndex() == index) {
                return alpha;
            }
            skip(alphaDecoder, alphaTrack, alpha);
        }
    }

    /**
     * Decodes a sample for the decoding state of the samples after it and drops its picture.
     *
     * @param decoder the decoder of the track
     * @param track the track the sample belongs to
     * @param sample the sample decoded and dropped
     */
    private static void skip(ItemDecoder decoder, VideoTrack track, Packet sample) {
        var picture = decoder.decodeSample(track.codecConfig().orElse(null), sample.data());
        if (picture != null) {
            picture.release();
        }
    }

    /**
     * Decodes the first frame as a planar picture, for still decoding.
     *
     * <p>Without {@code highBitDepth}, a YCgCo-Re or YCgCo-Ro source of more than 8 bits decodes to 8-bit RGBA
     * (see {@link Colors#toEightBit}), and color premultiplied by alpha to straight RGBA.
     *
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the picture of the first frame
     * @throws MediaException.Malformed if the sequence has no frame
     */
    public Picture readFirstPicture(boolean highBitDepth) {
        var color = nextColor();
        if (color == null) {
            throw new MediaException.Malformed("avif: the sequence has no frame");
        }
        Packet alpha = alphaTrack == null ? null : nextAlpha();
        if (alphaTrack != null && alpha == null) {
            throw new MediaException.Malformed("avif: the alpha track has no sample");
        }
        var picture = decode(color, alpha, highBitDepth);
        var straight = premultiplied ? HeifPictures.unpremultiply(picture) : picture;
        return highBitDepth ? straight : Colors.toEightBit(straight);
    }

    /**
     * Decodes a color sample and its alpha sample into a planar picture.
     *
     * @param color the color sample
     * @param alpha the alpha sample, or {@code null}
     * @param wide whether a source of more than 8 bits keeps its depth; a YCgCo-Re or YCgCo-Ro source keeps it
     * regardless (see {@link Colors#reducesToRgb})
     * @return the picture
     */
    private Picture decode(Packet color, Packet alpha, boolean wide) {
        var colorFrame = colorDecoder.decodeSample(colorTrack.codecConfig().orElse(null), color.data());
        if (colorFrame == null) {
            throw new MediaException.Malformed("avif: a color sample shows no frame");
        }
        try {
            var lightProperty = info.property(HeifProperty.ContentLightLevel.class);
            var masteringProperty = info.property(HeifProperty.MasteringDisplay.class);
            var description = Colors.colorInfo(nclx, colorFrame.seqHdr,
                    masteringProperty != null ? HeifPictures.masteringDisplay(masteringProperty) : colorDecoder.masteringDisplay(),
                    lightProperty != null ? HeifPictures.contentLightLevel(lightProperty) : colorDecoder.contentLightLevel());
            var canvas = new Canvas(limits, colorFrame.w, colorFrame.h, SequenceHeader.chromaFormat(colorFrame.layout),
                    colorFrame.bpc, colorFrame.bpc > 8 && (wide || Colors.reducesToRgb(description.matrix(),
                    colorFrame.bpc)), alpha != null);
            canvas.writeColor(colorFrame, 0, 0, colorFrame.w, colorFrame.h, true);
            if (alpha != null) {
                var alphaFrame = alphaDecoder.decodeSample(alphaTrack.codecConfig().orElse(null), alpha.data());
                if (alphaFrame == null) {
                    throw new MediaException.Malformed("avif: an alpha sample shows no frame");
                }
                try {
                    var limited = !alphaFrame.seqHdr.colorRange;
                    if (alphaFrame.w == colorFrame.w && alphaFrame.h == colorFrame.h) {
                        canvas.writeAlpha(alphaFrame, 0, 0, colorFrame.w, colorFrame.h, limited);
                    } else {
                        var luma = HeifPictures.lumaPicture(alphaFrame.data[0], 0, alphaFrame.stride[0], alphaFrame.w,
                                alphaFrame.h, alphaFrame.bpc);
                        canvas.writeAlpha(HeifPictures.resampleAlpha(luma, colorFrame.w, colorFrame.h), 0, 0,
                                colorFrame.w, colorFrame.h, limited);
                    }
                } finally {
                    alphaFrame.release();
                }
            }
            return canvas.toPicture(description);
        } finally {
            colorFrame.release();
        }
    }

    @Override
    public void close() {
        colorDecoder.close();
        if (alphaDecoder != null) {
            alphaDecoder.close();
        }
        demuxer.close();
        samples.clear();
    }
}
