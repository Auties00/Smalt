/* Ports libvorbis 1.3.7 (lib/vorbisenc.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.vorbis.internal.HeaderWriter;
import com.github.auties00.smalt.audio.vorbis.internal.VorbisAnalyzer;
import com.github.auties00.smalt.internal.header.VorbisChannelOrder;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Encodes planar float samples into Vorbis I audio packets.
 *
 * <p>The three Vorbis headers (identification, comment and setup) are returned Xiph-laced by
 * {@link #codecConfig()}, the form Matroska's {@code CodecPrivate} and Smalt's Ogg muxer take;
 * they are known as soon as the encoder exists. The comment header carries the vendor string
 * {@value #VENDOR} and no comments: muxers write the file's tags. Every packet produced by
 * {@link #encode(AudioBuffer, Consumer)} and {@link #flush(Consumer)} is one audio packet, in
 * decode order.
 *
 * <p>Vorbis packets have two sizes (the short and long blocks the encoder switches between on
 * transients), and a packet's output spans a quarter of the previous block plus a quarter of its
 * own. A packet's timestamp is the number of samples decoded before it and its duration the number
 * it adds, so the first packet, which only primes the decoder, lasts zero samples, and the
 * timestamps match the Ogg granule positions; decoding starts exactly at the first input sample,
 * so {@link #delay()} is zero. The last packet is declared shorter than it codes, ending at the
 * last input sample.
 *
 * <p>Input channels are in WAVE order ({@link ChannelLayout#defaultFor(int)}); for three to eight
 * channels they are reordered into the Vorbis I channel order (for example 5.1 is coded as front
 * left, center, front right, back left, back right, LFE), so {@link VorbisDecoder} returns them in
 * the input order. Other channel counts are coded in input order. NaN and infinite samples are
 * coded as silence.
 *
 * <p>A {@linkplain VorbisEncoderOptions#isBitrateManaged() bitrate managed} stream codes every
 * block at several rates and keeps, through a bit reservoir, the
 * {@linkplain VorbisEncoderOptions#averageBitrate() average},
 * {@linkplain VorbisEncoderOptions#minimumBitrate() minimum} and
 * {@linkplain VorbisEncoderOptions#maximumBitrate() maximum} bitrates of its options; the
 * identification header carries the minimum, nominal and maximum bitrates.
 *
 * <p>Encoding is deterministic: the same samples and options produce the same packets, however
 * the input is split across {@link #encode(AudioBuffer, Consumer)} calls. The packets are
 * byte-identical to those of libvorbis 1.3.7 for the same input fed in 1024-sample chunks, as
 * {@code oggenc} feeds it, and the same settings, bitrate management included (an unset limit given
 * to libvorbis as 0, and bitrates in whole kilobits per second, the unit of part of libvorbis's
 * interface), given a libvorbis build whose {@code rint} rounds exact halves to even. The one
 * exception is a maximum so low that libvorbis truncates a packet to nothing: an empty packet does
 * not decode, so this encoder keeps one byte.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation ports the libvorbis encoder, with the aoTuV tuning merged into
 * libvorbis, in its quality and bitrate managed modes.
 */
public final class VorbisEncoder implements AudioEncoder {
    /**
     * The vendor string of the comment header.
     */
    public static final String VENDOR = "Smalt";

    /**
     * The number of samples per channel handed to the analysis at a time. libvorbis's output
     * depends on how its input is split (the backwards extrapolation of the stream start uses
     * the samples buffered when the first long block fills), so the input is regrouped into
     * fixed chunks, the size {@code oggenc} reads, making the packets independent of how callers
     * split their input.
     */
    private static final int CHUNK = 1024;

    /**
     * The options the encoder was created with.
     */
    private final VorbisEncoderOptions options;

    /**
     * The analysis engine.
     */
    private final VorbisAnalyzer analyzer;

    /**
     * The Xiph-laced headers.
     */
    private final byte[] codecConfig;

    /**
     * The half size of the long block, the most samples one packet adds.
     */
    private final int frameSize;

    /**
     * The WAVE plane of each Vorbis channel.
     */
    private final int[] channelOrder;

    /**
     * The chunk being filled, one plane per channel in Vorbis order.
     */
    private final float[][] chunk;

    /**
     * The number of samples per channel in {@link #chunk}.
     */
    private int filled;

    /**
     * The granule position after the last emitted packet.
     */
    private long granule;

    /**
     * The number of input samples per channel received.
     */
    private long received;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder for 44.1 kHz stereo input at quality 3, the settings of
     * {@link VorbisEncoderOptions#defaults()}.
     */
    public VorbisEncoder() {
        this(VorbisEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder with the given settings.
     *
     * @param options the settings
     * @throws IllegalArgumentException if no encoder setting covers the options
     */
    public VorbisEncoder(VorbisEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var channels = options.layout().channels();
        var setup = options.codecSetup();
        this.analyzer = new VorbisAnalyzer(setup);
        this.codecConfig = XiphLacing.lace(List.of(
                HeaderWriter.identification(setup),
                HeaderWriter.comment(VENDOR, List.of()),
                HeaderWriter.setup(setup)));
        this.frameSize = setup.blockSizes[1] / 2;
        var order = VorbisChannelOrder.toWave(channels);
        this.channelOrder = order != null ? order : IntStream.range(0, channels).toArray();
        this.chunk = new float[channels][CHUNK];
    }

    /**
     * {@return the options the encoder was created with}
     */
    public VorbisEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.VORBIS;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return half the long block size, the most samples one packet adds; packets of short
     * blocks add fewer
     */
    @Override
    public int frameSize() {
        return frameSize;
    }

    /**
     * {@inheritDoc}
     *
     * @return zero: the first decoded sample is the first input sample
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @return the identification, comment and setup headers, Xiph-laced
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(codecConfig.clone());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the input's rate or layout differs from the encoder's
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + options.sampleRate() + " Hz " + options.layout().speakers());
        }
        var count = input.frames();
        if (count == 0) {
            return;
        }
        var planes = input.planes();
        received += count;
        var offset = 0;
        while (offset < count) {
            var take = Math.min(count - offset, CHUNK - filled);
            for (var i = 0; i < chunk.length; i++) {
                System.arraycopy(planes[channelOrder[i]], offset, chunk[i], filled, take);
            }
            filled += take;
            offset += take;
            if (filled == CHUNK) {
                analyzer.write(chunk, 0, CHUNK);
                filled = 0;
                drain(output);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>After a flush the encoder accepts no further input. Without any input, no packet is
     * produced.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (received == 0) {
            return;
        }
        if (filled > 0) {
            analyzer.write(chunk, 0, filled);
            filled = 0;
        }
        analyzer.finish();
        drain(output);
    }

    /**
     * Emits every packet the analyzer can produce.
     *
     * @param output receives the packets
     */
    private void drain(Consumer<Packet> output) {
        VorbisAnalyzer.AudioPacket packet;
        while ((packet = analyzer.nextPacket()) != null) {
            var start = granule;
            granule = packet.granule();
            output.accept(new Packet(0, packet.data(), start, start, granule - start, true));
            if (packet.endOfStream()) {
                return;
            }
        }
    }
}
