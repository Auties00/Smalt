package com.github.auties00.smalt.audio.dst;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.container.dsd.DsdiffDemuxer;
import com.github.auties00.smalt.internal.dsd.DsdDecimator;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes DST (Direct Stream Transfer, ISO/IEC 14496-3 subpart 10), the lossless coding of DSD, to its DSD and on to
 * PCM.
 *
 * <p>Packets and the configuration are as {@link Codec#DST} describes; a track whose configuration clears
 * {@code DSDDST_Coded} carries plain DSD in every packet. {@link #decodeDsd(Packet, byte[], int)} decodes a packet to
 * its DSD, bit-identical to the MPEG-4 Audio reference decoder; {@link #decode(Packet, Consumer)} converts the DSD
 * to PCM exactly as {@link com.github.auties00.smalt.audio.pcm.DsdDecoder} does, at the rate
 * {@link AudioDecoderOptions#preferredSampleRate()} selects (the DSD rate divided by 16 by default), the output
 * lagging the input by half the conversion filter until {@link #flush(Consumer)}. With
 * {@link AudioDecoderOptions#verifyIntegrity()}, a packet carrying the CRC of its decoded DSD (DSDIFF's
 * {@code "DSTC"} chunks, side data {@value DsdiffDemuxer#CRC_KEY}) is checked against it.
 *
 * <p>Instances are not thread-safe.
 */
public final class DstDecoder implements AudioDecoder {
    private final ChannelLayout layout;

    private final int channels;

    private final int dsdRate;

    private final int sampleRate;

    private final boolean coded;

    private final boolean verify;

    private final DstFrameDecoder frames;

    private final DsdDecimator decimator;

    private final byte[] dsd;

    private AudioBuffer floats;

    private DoubleAudioBuffer doubles;

    /**
     * Constructs a decoder for a DST track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#DST}
     * @throws IllegalArgumentException if the track is not a DST track
     * @throws MediaException.Malformed if the configuration is not a DST {@code AudioSpecificConfig}, or the track
     * declares no DSD rate of whole frames or no channels
     */
    public DstDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a DST track.
     *
     * @param track the track; its codec must be {@link Codec#DST}
     * @param options the limits, the preferred output rate and whether to verify the frames' CRCs
     * @throws IllegalArgumentException if the track is not a DST track
     * @throws MediaException.Malformed if the configuration is not a DST {@code AudioSpecificConfig}, or the track
     * declares no DSD rate of whole frames or no channels
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     */
    public DstDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.DST) {
            throw new IllegalArgumentException("not a DST track: " + track.codec());
        }
        var rate = track.sampleRate();
        var count = track.layout().map(ChannelLayout::channels).orElse(0);
        var isCoded = true;
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        if (config != null) {
            var parsed = parseConfig(config);
            if (parsed[0] > 0) {
                rate = parsed[0];
            }
            count = parsed[1];
            isCoded = parsed[2] != 0;
        }
        if (rate <= 0 || rate % 600 != 0) {
            throw new MediaException.Malformed("DST track at " + rate + " Hz");
        }
        if (count <= 0) {
            throw new MediaException.Malformed("DST track without channels");
        }
        LimitChecks.checkChannels(options.limits(), count);
        this.channels = count;
        this.dsdRate = rate;
        this.coded = isCoded;
        this.verify = options.verifyIntegrity();
        this.layout = track.layout().filter(l -> l.channels() == channels).orElse(ChannelLayout.defaultFor(channels));
        this.frames = new DstFrameDecoder(channels, rate / 600);
        this.dsd = new byte[frames.outputBytes()];
        var ratio = DsdDecimator.ratioFor(rate, options.preferredSampleRate());
        this.decimator = new DsdDecimator(channels, ratio);
        this.sampleRate = Math.max(rate / ratio, 1);
    }

    /**
     * Parses an {@code AudioSpecificConfig} of audio object type 35.
     *
     * @return the sampling frequency (0 when coded by index 0 to 14, which DST does not use), the channel count and
     * the {@code DSDDST_Coded} flag
     */
    private static int[] parseConfig(byte[] config) {
        var bit = new int[]{0};
        var type = read(config, bit, 5);
        if (type == 31) {
            type = 32 + read(config, bit, 6);
        }
        if (type != 35) {
            throw new MediaException.Malformed("not a DST AudioSpecificConfig: audio object type " + type);
        }
        var index = read(config, bit, 4);
        var frequency = index == 15 ? read(config, bit, 24) : 0;
        read(config, bit, 4);
        var coded = read(config, bit, 1);
        var channels = read(config, bit, 14);
        return new int[]{frequency, channels, coded};
    }

    private static int read(byte[] data, int[] bit, int count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            var at = bit[0]++;
            if (at >>> 3 >= data.length) {
                throw new MediaException.Malformed("truncated DST AudioSpecificConfig");
            }
            value = value << 1 | ((data[at >>> 3] >>> (7 - (at & 7))) & 1);
        }
        return value;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the bytes of DSD one packet decodes to, all channels: the DSD rate divided by 600, times the channel
     * count}
     */
    public int frameBytes() {
        return dsd.length;
    }

    /**
     * Decodes one packet to its DSD: interleaved channel bytes, the oldest sample in the most significant bit, as
     * DSDIFF stores uncompressed sound.
     *
     * <p>The decoder's PCM output is not affected.
     *
     * @param packet the packet to decode, a DST frame or plain DSD as the configuration says
     * @param output receives {@link #frameBytes()} bytes
     * @param offset the index of the first byte written
     * @throws IndexOutOfBoundsException if {@code output} has fewer than {@link #frameBytes()} bytes from
     * {@code offset}
     * @throws MediaException.Malformed if the packet is corrupt or fails its CRC
     */
    public void decodeDsd(Packet packet, byte[] output, int offset) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        Objects.checkFromIndexSize(offset, dsd.length, output.length);
        var data = packet.data();
        try {
            if (coded) {
                frames.decode(data, 0, data.length, output, offset);
            } else {
                if (data.length < dsd.length) {
                    throw new MediaException.Malformed("plain DSD packet of " + data.length + " bytes");
                }
                System.arraycopy(data, 0, output, offset, dsd.length);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DST frame", exception);
        }
        if (verify) {
            var crc = packet.sideData().extra(DsdiffDemuxer.CRC_KEY).orElse(null);
            if (crc != null && crc.length >= 4) {
                var expected = (crc[0] & 0xFFL) << 24 | (crc[1] & 0xFFL) << 16 | (crc[2] & 0xFFL) << 8
                        | (crc[3] & 0xFFL);
                if (crc(output, offset, dsd.length) != expected) {
                    throw new MediaException.Malformed("DST frame fails its CRC");
                }
            }
        }
    }

    /**
     * Computes the DSDIFF frame CRC: the remainder of the bits times {@code x^32}, divided by
     * {@code x^32 + x^31 + x^4 + 1}.
     */
    static long crc(byte[] data, int offset, int length) {
        var register = 0L;
        for (var i = 0; i < length; i++) {
            var b = data[offset + i] & 0xFF;
            for (var k = 7; k >= 0; k--) {
                var top = (register >>> 31) & 1;
                register = ((register << 1) | ((b >>> k) & 1)) & 0xFFFFFFFFL;
                if (top != 0) {
                    register ^= 0x80000011L;
                }
            }
        }
        for (var k = 0; k < 32; k++) {
            var top = (register >>> 31) & 1;
            register = (register << 1) & 0xFFFFFFFFL;
            if (top != 0) {
                register ^= 0x80000011L;
            }
        }
        return register;
    }

    private void feed(Packet packet) {
        decodeDsd(packet, dsd, 0);
        var bytes = dsd.length / channels;
        var used = packet.duration() > 0 ? (int) Math.min(bytes, (packet.duration() + 7) / 8) : bytes;
        decimator.prepare(used);
        for (var c = 0; c < channels; c++) {
            decimator.feed(c, dsd, c, channels, used, false);
        }
        decimator.commit();
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        feed(packet);
        emitFloats(output);
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decimator.flush();
        emitFloats(output);
    }

    private void emitFloats(Consumer<AudioBuffer> output) {
        var count = decimator.available();
        if (count == 0) {
            return;
        }
        if (floats == null || floats.capacity() < count) {
            floats = new AudioBuffer(layout, sampleRate, Math.max(count, 4096));
        }
        floats.setFrames(decimator.drain(floats.planes(), 0));
        output.accept(floats);
    }

    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The samples are the conversion filter's output at double precision, of which
     * {@link #decode(Packet, Consumer)} outputs the nearest floats.
     *
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        feed(packet);
        emitDoubles(output);
    }

    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decimator.flush();
        emitDoubles(output);
    }

    private void emitDoubles(Consumer<DoubleAudioBuffer> output) {
        var count = decimator.available();
        if (count == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < count) {
            doubles = new DoubleAudioBuffer(layout, sampleRate, Math.max(count, 4096));
        }
        doubles.setFrames(decimator.drain(doubles.planes(), 0));
        output.accept(doubles);
    }
}
