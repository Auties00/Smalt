package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.Objects;

/**
 * Writes Sony Wave64 files from the packets of one PCM or coded audio track.
 *
 * <p>A Wave64 file holds what a RIFF WAVE file holds, in chunks named by GUIDs with 64-bit sizes that count their
 * 24-byte header, each starting on a multiple of eight bytes, so it has no 4 GiB limit. The track, its packets and the
 * {@code fmt } structure they get are those of {@link WavMuxer} with its default options, for the codecs
 * {@link #supports(Codec)} accepts; a {@code fact} chunk with the 64-bit sample count follows the {@code fmt } chunk
 * of float, A-law, mu-law and coded audio. The Broadcast Wave {@code mext} chunk of MPEG Layer I and II and tags are
 * not written. {@link WavDemuxer} reads the file back packet for packet.
 *
 * <p>On a seekable sink the sizes and the sample count are written by {@link #finish()}, which also pads the data
 * to a multiple of eight bytes. On a streaming sink they stay zero, and the file ends with the data; a zero
 * {@code data} size makes {@link WavDemuxer} read the data to the end of the file. The sink is written from its
 * position at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class Wave64Muxer implements Muxer {
    /**
     * The muxer that writes the file in its Wave64 layout.
     */
    private final WavMuxer muxer;

    // TODO: write the tags in a Wave64 list chunk once its layout can be checked against a reader

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public Wave64Muxer(MediaSink output) {
        this.muxer = new WavMuxer(Objects.requireNonNull(output, "output"), WavMuxerOptions.defaults(),
                Long.MAX_VALUE, true);
    }

    /**
     * Returns whether Wave64 files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for the codecs {@link WavMuxer#supports(Codec)} accepts
     */
    public static boolean supports(Codec codec) {
        return WavMuxer.supports(codec);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec {@link #supports(Codec)} accepts,
     * declares no sample rate or layout, or has more than 65535 channels
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        muxer.addTrack(track);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold what the codec's
     * data chunk stores, as for {@link WavMuxer#writePacket(Packet)}
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     */
    @Override
    public void writePacket(Packet packet) {
        muxer.writePacket(packet);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with an empty {@code data} chunk.
     *
     * @throws IllegalStateException if no track was declared or the muxer is finished
     */
    @Override
    public void finish() {
        muxer.finish();
    }

    /**
     * Marks the muxer finished; an unfinished file keeps the sizes of its header as written.
     */
    @Override
    public void close() {
        muxer.close();
    }
}
