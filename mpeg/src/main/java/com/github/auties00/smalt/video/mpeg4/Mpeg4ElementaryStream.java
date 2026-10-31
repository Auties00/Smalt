package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.raw.Mpeg4VideoDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Objects;

/**
 * A reader that splits a raw MPEG-4 Part 2 ({@code .m4v}, {@code .cmp}) or H.263 ({@code .263})
 * elementary stream into one packet per picture, for {@link Mpeg4Decoder} and
 * {@link com.github.auties00.smalt.video.h263.H263Decoder}.
 *
 * <p>An MPEG-4 stream starts with a visual object sequence, visual object, video object or video
 * object layer start code ({@code 00 00 01 B0}, {@code B5}, {@code 00} to {@code 1F},
 * {@code 20} to {@code 2F}) or, with the short video header, with a picture start code; an
 * H.263 stream starts with a picture start code ({@code 00 00 80} to {@code 00 00 83}). Every
 * packet ends where the next picture's headers begin: the configuration headers and group of VOP
 * headers that precede a VOP belong to the packet of that VOP, so the first packet carries the
 * stream headers. The headers before the first VOP are also the track's
 * {@link VideoTrack#codecConfig() codec configuration}.
 *
 * <p>Every packet carries the presentation and decoding times {@link Mpeg4VideoDemuxer} derives
 * from the pictures' VOP times or temporal references, in the track's time base: the inverse of
 * the video object layer's {@code vop_time_increment_resolution}, or the H.263 picture clock.
 * Packets of intra pictures are flagged as key frames.
 *
 * <p>The source is read sequentially and not closed. Instances are not thread-safe.
 *
 * @see Mpeg4VideoDemuxer
 */
public final class Mpeg4ElementaryStream implements AutoCloseable {
    /**
     * The demuxer that splits the stream.
     */
    private final Mpeg4VideoDemuxer demuxer;

    /**
     * Opens an elementary stream with the {@linkplain DemuxerOptions#defaults() default} demuxer
     * settings.
     *
     * @param source the stream, read from offset zero
     * @return the stream
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263
     * elementary stream
     * @throws MediaException.Io if reading fails
     */
    public static Mpeg4ElementaryStream open(MediaSource source) {
        return open(source, DemuxerOptions.defaults());
    }

    /**
     * Opens an elementary stream with demuxer settings, whose {@link DemuxerOptions#limits()}
     * bound the size of one picture.
     *
     * @param source the stream, read from offset zero
     * @param options the demuxer settings
     * @return the stream
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263
     * elementary stream
     * @throws MediaException.Io if reading fails
     */
    public static Mpeg4ElementaryStream open(MediaSource source, DemuxerOptions options) {
        return new Mpeg4ElementaryStream(new Mpeg4VideoDemuxer(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(options, "options")));
    }

    /**
     * Returns whether bytes start an MPEG-4 Part 2 or H.263 elementary stream.
     *
     * @param header the first bytes of the stream, at least four
     * @return {@code true} when they start with a configuration or picture start code
     */
    public static boolean matches(byte[] header) {
        if (header.length < 4 || header[0] != 0 || header[1] != 0) {
            return false;
        }
        if ((header[2] & 0xFC) == 0x80) {
            return true;
        }
        if (header[2] != 1) {
            return false;
        }
        var code = header[3] & 0xFF;
        return code == 0xB0 || code == 0xB5 || code <= 0x2F;
    }

    /**
     * Wraps a demuxer.
     *
     * @param demuxer the demuxer that reads the stream
     */
    private Mpeg4ElementaryStream(Mpeg4VideoDemuxer demuxer) {
        this.demuxer = demuxer;
    }

    /**
     * {@return the track the stream describes: MPEG-4 Part 2 with the headers before the first VOP
     * as configuration, or H.263; its size is left for the decoder to read}
     */
    public VideoTrack track() {
        return (VideoTrack) demuxer.tracks().getFirst();
    }

    /**
     * Reads the packet of the next picture, flagged as a key frame when the picture is intra
     * coded.
     *
     * @return the packet, or {@code null} at the end of the stream
     * @throws MediaException.LimitExceeded if a picture exceeds the allocation limit
     * @throws MediaException.Io if reading fails
     */
    public Packet read() {
        return demuxer.read();
    }

    /**
     * Does nothing: the source belongs to the caller.
     */
    @Override
    public void close() {
    }
}
