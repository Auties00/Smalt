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
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.ArrayDeque;

/**
 * Plays the image sequence of an AVIF file ({@code avis}): the color track, and the alpha track
 * that references it with {@code auxl} when there is one, decoded sample by sample.
 *
 * <p>The color track is the first track with an {@code av01} sample entry and samples that is
 * not itself an auxiliary track; the alpha track is the first such track that references it with
 * {@code auxl} and whose {@code auxi} box, when present, names the alpha URN. Every sample of the
 * two tracks shows one frame, and the n-th alpha frame is the alpha plane of the n-th color
 * frame. The samples are read through {@link Mp4Demuxer}.
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
 * the start shows, and pairs it with the alpha sample that starts last at or before it. That is
 * the alpha sample a read from the start pairs it with whenever the n-th alpha sample starts when
 * the n-th color sample does; for an alpha track timed otherwise the two can differ. A reader is
 * not thread-safe.
 *
 * @implNote This implementation chooses the tracks as libavif's {@code avifDecoderReset}
 * ({@code src/read.c}, BSD 2-clause) does.
 */
public final class SequenceReader implements AnimationReader {
    // TODO: pair the color and alpha samples by their index after a seek too, which needs the sample index of the
    //  packets the demuxer returns, so that an alpha track timed unlike its color track seeks as it reads
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
     * The color samples read ahead of their alpha samples.
     */
    private final ArrayDeque<Packet> colorPackets = new ArrayDeque<>();

    /**
     * The alpha samples read ahead of their color samples.
     */
    private final ArrayDeque<Packet> alphaPackets = new ArrayDeque<>();

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
     * The presentation time of the first alpha sample, in track time base units, or
     * {@link Packet#NO_TIMESTAMP} until it is known.
     */
    private long alphaBase = Packet.NO_TIMESTAMP;

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
     * Whether the demuxer has no more samples.
     */
    private boolean drained;

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
        var packet = next(colorPackets, colorTrack.id());
        if (packet != null && colorBase == Packet.NO_TIMESTAMP) {
            colorBase = packet.pts();
        }
        return packet;
    }

    /**
     * {@return the next alpha sample, or {@code null} at the end of the track}
     */
    private Packet nextAlpha() {
        var packet = next(alphaPackets, alphaTrack.id());
        if (packet != null && alphaBase == Packet.NO_TIMESTAMP) {
            alphaBase = packet.pts();
        }
        return packet;
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
     * <p>The frame then returned equals the one a decode from the start returns at that time, for
     * an alpha track timed like the color track (see the class description).
     *
     * @throws MediaException.Malformed if a sample decoded while seeking is corrupt, or the alpha
     * track has no sample for the frame
     * @throws MediaException.LimitExceeded if the samples of the two tracks are too far apart
     */
    @Override
    public void seek(long micros) {
        try {
            seek(Math.max(0, micros), false);
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
     * @param target the time relative to the first color sample, in microseconds, at least zero
     * @param fromStart whether to restart at the first sample rather than at the last sync sample
     * before the target, which a seek does when the alpha track restarts after its color track
     */
    private void seek(long target, boolean fromStart) {
        pending = null;
        exhausted = false;
        if (colorBase == Packet.NO_TIMESTAMP) {
            var first = nextColor();
            if (first == null) {
                exhausted = true;
                return;
            }
            colorPackets.addFirst(first);
        }
        if (alphaTrack != null && alphaBase == Packet.NO_TIMESTAMP) {
            var first = nextAlpha();
            if (first != null) {
                alphaPackets.addFirst(first);
            }
        }
        demuxer.seek(colorTrack.timeBase().toMicros(colorBase) + (fromStart ? 0 : target));
        colorPackets.clear();
        alphaPackets.clear();
        drained = false;
        colorDecoder.close();
        if (alphaDecoder != null) {
            alphaDecoder.close();
        }
        frames = 0;
        while (true) {
            var color = nextColor();
            if (color == null) {
                exhausted = true;
                return;
            }
            var start = relative(color.pts());
            var shown = relative(color.pts() + Math.max(0, color.duration())) > target;
            Packet alpha = null;
            if (alphaTrack != null) {
                alpha = alphaAt(start);
                if (alpha == null && shown) {
                    if (!fromStart) {
                        seek(target, true);
                        return;
                    }
                    throw new MediaException.Malformed("avif: the alpha track has no sample at " + start + " us");
                }
            }
            if (shown) {
                pending = frame(color, alpha);
                return;
            }
            skip(colorDecoder, colorTrack, color);
            if (alpha != null) {
                skip(alphaDecoder, alphaTrack, alpha);
            }
        }
    }

    /**
     * Takes the alpha sample shown with the color sample that starts at a time: the last alpha
     * sample that starts at or before it, decoding and dropping the ones before it.
     *
     * @param start the start of the color sample relative to the first color sample, in
     * microseconds
     * @return the alpha sample, or {@code null} when the next one starts later
     */
    private Packet alphaAt(long start) {
        Packet chosen = null;
        while (true) {
            var next = nextAlpha();
            if (next == null) {
                return chosen;
            }
            if (alphaTrack.timeBase().toMicros(next.pts() - alphaBase) > start) {
                alphaPackets.addFirst(next);
                return chosen;
            }
            if (chosen != null) {
                skip(alphaDecoder, alphaTrack, chosen);
            }
            chosen = next;
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

    /**
     * Returns the next sample of a track, reading ahead through the demuxer.
     *
     * @param queue the samples of the track already read
     * @param trackId the track identifier
     * @return the sample, or {@code null} when the track has no more
     */
    private Packet next(ArrayDeque<Packet> queue, int trackId) {
        while (queue.isEmpty() && !drained) {
            var packet = demuxer.read();
            if (packet == null) {
                drained = true;
            } else if (packet.trackId() == colorTrack.id()) {
                colorPackets.add(packet);
            } else if (alphaTrack != null && packet.trackId() == alphaTrack.id()) {
                alphaPackets.add(packet);
            }
            if (colorPackets.size() + alphaPackets.size() > limits.maxFrames()) {
                throw new MediaException.LimitExceeded("avif: the color and alpha samples are too far apart");
            }
        }
        return queue.poll();
    }

    @Override
    public void close() {
        colorDecoder.close();
        if (alphaDecoder != null) {
            alphaDecoder.close();
        }
        demuxer.close();
        colorPackets.clear();
        alphaPackets.clear();
    }
}
