package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.internal.dsd.DsdDecimator;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes uncompressed 1-bit DSD audio into PCM by low-pass filtering and decimation.
 *
 * <p>Every {@linkplain Codec#isDsd() DSD codec} is supported. The output rate is the DSD rate divided by a power of
 * two from 8 to 512: by 16 by default (176.4 kHz for DSD64, 352.8 kHz for DSD128), or the ratio that gives the
 * lowest rate at or above {@link AudioDecoderOptions#preferredSampleRate()} (the DSD rate divided by 8 when the
 * preferred rate is higher). The filter is linear-phase, flat to 40% of the output rate and attenuates by 120 dB from
 * half the output rate; a DSD bit of 1 counts as +1 and a bit of 0 as -1, so a DSD signal of 50% modulation (the 0 dB
 * of the Super Audio CD) peaks at 0.5. Its delay is compensated: output sample {@code k} is centered on DSD sample
 * {@code k * ratio} and a stream of {@code n} DSD samples per channel decodes to {@code floor(n / ratio)} samples per
 * channel, the filter reaching the DSD silence pattern before the start and past the end. The output is the same
 * whatever the packetization, and at double precision ({@link #decodeDoubles(Packet, Consumer)}) it equals the
 * direct convolution of the stream with the filter to within rounding.
 *
 * <p>A packet holds whole bytes of every channel in the codec's layout; only the first {@code duration / 8} bytes of
 * each channel are used, so the padding of a stream's last packet is ignored. The output lags the input by half the
 * filter length: {@link #flush(Consumer)} produces the last samples.
 *
 * <p>Instances are not thread-safe.
 */
public final class DsdDecoder implements AudioDecoder {
    private final Codec codec;

    private final ChannelLayout layout;

    private final int channels;

    private final int dsdRate;

    private final int sampleRate;

    private final DsdDecimator decimator;

    private AudioBuffer floats;

    private DoubleAudioBuffer doubles;

    /**
     * Constructs a decoder for a DSD track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be a DSD codec
     * @throws IllegalArgumentException if the track is not a DSD track
     * @throws MediaException.Malformed if the track declares no DSD rate or no layout
     */
    public DsdDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a DSD track.
     *
     * @param track the track; its codec must be a DSD codec
     * @param options the limits and the preferred output rate
     * @throws IllegalArgumentException if the track is not a DSD track
     * @throws MediaException.Malformed if the track declares no DSD rate or no layout
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     */
    public DsdDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        this.codec = track.codec();
        if (!codec.isDsd()) {
            throw new IllegalArgumentException(codec + " is not a DSD codec");
        }
        if (track.sampleRate() < DsdDecimator.MIN_RATIO) {
            throw new MediaException.Malformed("DSD track without a sample rate");
        }
        this.layout = track.layout().orElseThrow(() -> new MediaException.Malformed("DSD track without a layout"));
        this.channels = layout.channels();
        LimitChecks.checkChannels(options.limits(), channels);
        this.dsdRate = track.sampleRate();
        var ratio = DsdDecimator.ratioFor(dsdRate, options.preferredSampleRate());
        while (ratio > DsdDecimator.MIN_RATIO && dsdRate / ratio < 1) {
            ratio /= 2;
        }
        this.sampleRate = Math.max(dsdRate / ratio, 1);
        this.decimator = new DsdDecimator(channels, ratio);
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
     * {@return the decimation ratio, the DSD rate divided by the output rate}
     */
    public int ratio() {
        return decimator.ratio();
    }

    private void feed(Packet packet) {
        var data = packet.data();
        var perChannel = data.length / channels;
        var used = perChannel;
        if (packet.duration() > 0) {
            used = (int) Math.min(perChannel, (packet.duration() + 7) / 8);
        }
        if (used <= 0) {
            return;
        }
        decimator.prepare(used);
        var planar = codec == Codec.DSD_LSBF_PLANAR || codec == Codec.DSD_MSBF_PLANAR;
        var reverse = codec == Codec.DSD_LSBF || codec == Codec.DSD_LSBF_PLANAR;
        for (var c = 0; c < channels; c++) {
            if (planar) {
                decimator.feed(c, data, c * perChannel, 1, used, reverse);
            } else {
                decimator.feed(c, data, c, channels, used, reverse);
            }
        }
        decimator.commit();
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
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
     * <p>The samples are the filter output at double precision, of which {@link #decode(Packet, Consumer)} outputs
     * the nearest floats.
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
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
