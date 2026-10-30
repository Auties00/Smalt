package com.github.auties00.smalt.container.adts;

import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.tags.MuxerTags;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes AAC elementary streams in ADTS framing (ISO/IEC 14496-3 clause 1.A.3) from the packets of
 * one AAC track.
 *
 * <p>The track's {@code AudioSpecificConfig} supplies the fields every 7-byte ADTS header repeats:
 * the profile (the object type minus one, so only object types 1 to 4, AAC Main, LC, SSR and LTP,
 * are representable), the sampling frequency index and the channel configuration. A configuration
 * that signals SBR or PS explicitly (object type 5 or 29) is written as its core object type at the
 * core sampling rate, the implicit signaling decoders recognize. A sampling rate without an index
 * (index 15), channel configuration 0, which needs a program configuration element inside the
 * bitstream, channel configurations above 7 and 960-sample frames cannot be expressed and are
 * rejected.
 *
 * <p>Each packet must be one raw AAC access unit (a {@code raw_data_block}), as the MP4, Matroska
 * and ADTS demuxers produce, of at most 8184 bytes; it becomes one ADTS frame without CRC, with the
 * buffer fullness set to {@code 0x7FF} (variable bitrate). Timestamps are ignored. The output is
 * written strictly in order, so any {@link MediaSink} works, a streaming one included; the sink is
 * written from its position and is never closed by the muxer. A packet that changes the
 * {@code AudioSpecificConfig} gets, as every packet after it, the header of the new
 * configuration.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdtsMuxer implements Muxer {
    private final MediaSink output;

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * The header fields the track's configuration implies, written before every frame.
     */
    private AdtsHeader header;

    /**
     * The configuration {@link #header} comes from.
     */
    private byte[] config;

    /**
     * The ID3v2 tag written before the first frame; empty without tags.
     */
    private byte[] id3 = new byte[0];

    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink from its position.
     *
     * @param output the sink, not closed by this muxer
     */
    public AdtsMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether ADTS can carry a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#AAC} only
     */
    public static boolean supports(Codec codec) {
        return Objects.requireNonNull(codec, "codec") == Codec.AAC;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not AAC, has no {@code AudioSpecificConfig}, or
     * its configuration cannot be expressed in an ADTS header
     * @throws IllegalStateException if a track was already declared, a packet was already written or
     * the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("an ADTS stream carries one track");
        }
        if (!(track instanceof AudioTrack) || track.codec() != Codec.AAC) {
            throw new IllegalArgumentException("ADTS cannot carry " + track.codec().displayName());
        }
        var config = track.codecConfig()
                .orElseThrow(() -> new IllegalArgumentException("an ADTS track needs its AudioSpecificConfig"));
        this.header = AdtsHeader.fromAudioSpecificConfig(config);
        this.config = config;
        this.track = track;
    }

    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become an ID3v2.4 tag before the first frame.
     *
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.id3 = MuxerTags.id3v2(tags);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track, is larger than an
     * ADTS frame can hold, or changes to a configuration ADTS cannot express
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws com.github.auties00.smalt.MediaException.Io {@inheritDoc}
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        var newConfig = ConfigChanges.changed(packet, config);
        if (newConfig != null) {
            header = AdtsHeader.fromAudioSpecificConfig(newConfig);
            config = newConfig;
        }
        var length = AdtsHeader.SIZE + packet.size();
        if (length > AdtsHeader.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("an AAC frame of " + packet.size() + " bytes does not fit an ADTS frame");
        }
        if (!started && id3.length > 0) {
            output.write(ByteBuffer.wrap(id3));
        }
        started = true;
        var bytes = new byte[AdtsHeader.SIZE];
        header.write(bytes, 0, length);
        output.write(ByteBuffer.wrap(bytes));
        output.write(ByteBuffer.wrap(packet.data()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>ADTS has no trailer, so this only marks the muxer finished.
     *
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
    }

    @Override
    public void close() {
        finished = true;
    }
}
