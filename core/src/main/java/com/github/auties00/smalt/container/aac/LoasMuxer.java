package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.container.internal.Latm;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes AAC elementary streams in LOAS framing (ISO/IEC 14496-3 clause 1.7) from the packets of one AAC track.
 *
 * <p>Each packet must be one raw AAC access unit (a {@code raw_data_block}), as the MP4, Matroska, ADTS and LOAS
 * demuxers produce; it becomes one {@code AudioSyncStream} frame: the sync word {@code 0x2B7}, the 13-bit length
 * and an {@code AudioMuxElement} of one subframe of one program and layer, whose payload length is coded in the
 * element ({@code frameLengthType} 0, a buffer fullness of {@code 0xFF}, variable bitrate). The element of the first
 * frame, of every {@linkplain LoasMuxerOptions#configInterval() configuration interval} and of a packet that changes
 * the {@code AudioSpecificConfig} carries the {@code StreamMuxConfig}; the others refer back to it. The
 * configuration is written with {@code audioMuxVersion} 0, as most readers expect, unless the
 * {@code AudioSpecificConfig} carries data after its {@code GASpecificConfig} (an explicit, backward-compatible
 * signaling of HE-AAC), which only {@code audioMuxVersion} 1 delimits. Timestamps are ignored.
 *
 * <p>The track's {@code AudioSpecificConfig} must be of AAC Main, Low Complexity, Scalable Sample Rate or Long Term
 * Prediction, alone or under HE-AAC or HE-AAC v2 (object types 5 and 29), the configurations whose length a reader
 * of {@code audioMuxVersion} 0 can find. The output is written strictly in order, so any {@link MediaSink} works, a
 * streaming one included; the sink is written from its position and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class LoasMuxer implements Muxer {
    /**
     * The largest {@code AudioMuxElement}, in bytes: the 13-bit length of an {@code AudioSyncStream} frame.
     */
    private static final int MAX_ELEMENT = 8191;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The options.
     */
    private final LoasMuxerOptions options;

    /**
     * The writer of the frames, reused from frame to frame.
     */
    private final MsbBitWriter writer = new MsbBitWriter(1024);

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * The {@code AudioSpecificConfig} in force.
     */
    private byte[] config;

    /**
     * The length of the syntax of {@link #config} that a reader parses, in bits.
     */
    private long configBits;

    /**
     * Whether {@link #config} needs {@code audioMuxVersion} 1.
     */
    private boolean version1;

    /**
     * The number of frames written since the last one that carried the configuration, or {@code -1} before the first
     * frame and after a configuration change.
     */
    private int sinceConfig = -1;

    /**
     * Whether a packet was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink from its position, with {@link LoasMuxerOptions#defaults()}.
     *
     * @param output the sink, not closed by this muxer
     */
    public LoasMuxer(MediaSink output) {
        this(output, LoasMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer writing to a sink from its position.
     *
     * @param output the sink, not closed by this muxer
     * @param options the settings of the output
     */
    public LoasMuxer(MediaSink output, LoasMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns whether LOAS can carry a codec.
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
     * @throws IllegalArgumentException if the track is not AAC, has no {@code AudioSpecificConfig}, or its
     * configuration is not of an object type the class description lists
     * @throws IllegalStateException if a track was already declared, a packet was already written or the muxer is
     * finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("a LOAS stream carries one track");
        }
        if (!(track instanceof AudioTrack) || track.codec() != Codec.AAC) {
            throw new IllegalArgumentException("LOAS cannot carry " + track.codec().displayName());
        }
        configure(track.codecConfig()
                .orElseThrow(() -> new IllegalArgumentException("a LOAS track needs its AudioSpecificConfig")));
        this.track = track;
    }

    /**
     * Takes an {@code AudioSpecificConfig} as the one in force.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if the configuration is not of an object type the class description lists
     */
    private void configure(byte[] config) {
        var bits = Latm.audioSpecificConfigBits(config);
        if (bits < 0) {
            throw new IllegalArgumentException("LOAS cannot carry an AudioSpecificConfig that is not of AAC Main, LC,"
                    + " SSR or LTP, alone or under HE-AAC");
        }
        var trailing = false;
        for (var bit = bits; bit < 8L * config.length; bit++) {
            if ((config[(int) (bit >>> 3)] >>> (7 - (bit & 7)) & 1) != 0) {
                trailing = true;
                break;
            }
        }
        this.config = config;
        this.configBits = bits;
        this.version1 = trailing;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track, is too large for one
     * {@code AudioSyncStream} frame, or changes to a configuration LOAS cannot carry
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Io {@inheritDoc}
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
            configure(newConfig);
            sinceConfig = -1;
        }
        var withConfig = sinceConfig < 0 || sinceConfig + 1 >= options.configInterval();
        writer.reset();
        writer.write(0, 24);
        writer.write(withConfig ? 0 : 1, 1);
        if (withConfig) {
            writeStreamMuxConfig();
        }
        var data = packet.data();
        var length = data.length;
        while (length >= 255) {
            writer.write(255, 8);
            length -= 255;
        }
        writer.write(length, 8);
        if (writer.isByteAligned()) {
            writer.writeBytes(data, 0, data.length);
        } else {
            for (var value : data) {
                writer.write(value & 0xFF, 8);
            }
        }
        writer.alignWithZeros();
        var size = writer.size();
        var element = size - 3;
        if (element > MAX_ELEMENT) {
            throw new IllegalArgumentException("an AAC frame of " + data.length + " bytes does not fit a LOAS frame");
        }
        var bytes = writer.array();
        bytes[0] = 0x56;
        bytes[1] = (byte) (0xE0 | element >>> 8);
        bytes[2] = (byte) element;
        output.write(ByteBuffer.wrap(bytes, 0, size));
        started = true;
        sinceConfig = withConfig ? 0 : sinceConfig + 1;
    }

    /**
     * Writes the {@code StreamMuxConfig} of one program, one layer and one subframe per element (ISO/IEC 14496-3
     * clause 1.7.3.1, table 1.42).
     */
    private void writeStreamMuxConfig() {
        writer.write(version1 ? 1 : 0, 1);
        if (version1) {
            writer.write(0, 1);
            writeLatmValue(0xFF);
        }
        writer.write(1, 1);
        writer.write(0, 6);
        writer.write(0, 4);
        writer.write(0, 3);
        var bits = version1 ? 8L * config.length : configBits;
        if (version1) {
            writeLatmValue(bits);
        }
        var whole = (int) (bits >>> 3);
        for (var index = 0; index < whole; index++) {
            writer.write(config[index] & 0xFF, 8);
        }
        var rest = (int) (bits & 7);
        if (rest > 0) {
            writer.write((config[whole] & 0xFF) >>> (8 - rest), rest);
        }
        writer.write(0, 3);
        writer.write(0xFF, 8);
        writer.write(0, 1);
        writer.write(0, 1);
    }

    /**
     * Writes a {@code LatmGetValue()} field.
     *
     * @param value the value, below {@code 2^32}
     */
    private void writeLatmValue(long value) {
        var bytes = value < 1 << 8 ? 1 : value < 1 << 16 ? 2 : value < 1 << 24 ? 3 : 4;
        writer.write(bytes - 1, 2);
        writer.writeLong(value, 8 * bytes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>LOAS has no trailer, so this only marks the muxer finished.
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
