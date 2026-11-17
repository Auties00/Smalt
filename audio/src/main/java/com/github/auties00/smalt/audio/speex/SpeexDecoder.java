/* Ports libspeex 1.2.1 (libspeex/speex.c, libspeex/speex_header.c, src/speexdec.c). Copyright 2002-2008 Xiph.org
   Foundation, Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Speex speech: narrowband (8 kHz), wideband (16 kHz) and ultra-wideband (32 kHz) streams, every
 * sub-mode, variable bit rate, discontinuous transmission and intensity stereo, with the perceptual enhancer on.
 *
 * <p>The track's configuration is the 80-byte {@code "Speex   "} header of an Ogg Speex stream, which gives the
 * mode, the channel count and the frames of each packet. A track without one is a stream as FLV carries it: mono,
 * of the mode of the track's sampling rate (wideband when it is 16 kHz or unknown), each packet holding as many
 * frames as it codes. Each packet decodes into its frames, 160, 320 or 640 samples each; a packet that ends before
 * its declared frames (with a terminator, or with fewer than a frame's bits left) yields the frames before, possibly
 * none. The output rate is the header's, or the mode's.
 *
 * <p>The decoded samples are bit-identical to those of the fixed-point build of libspeex 1.2.1, enhancer on, as
 * {@link #decodeIntegers(Packet, Consumer)} outputs them (16 bits); the float and double outputs scale them by
 * 2^-15. A packet that codes an invalid mode or a frame that runs past its end raises
 * {@link MediaException.Malformed}.
 *
 * <p>A lost span ({@link #conceal(int, Consumer)}) is produced in whole frames, the concealment unit, as the
 * reference conceals lost packets: the pitch and noise of the last frames, attenuated frame by frame, and the
 * frames after the loss recover their energy.
 *
 * <p>Instances are not thread-safe.
 */
public final class SpeexDecoder implements AudioDecoder {
    private static final int HEADER_SIZE = 80;

    private static final int MAX_FRAMES = 64;

    private final SpeexFrameDecoder frames;

    private final SpeexStereoDecoder stereo;

    private final SpeexBits bits = new SpeexBits();

    private final int channels;

    private final int frameSize;

    private final int framesPerPacket;

    private final int sampleRate;

    private final ChannelLayout layout;

    private final short[] frame;

    private short[] decoded;

    private int decodedFrames;

    private AudioBuffer floats;

    private IntegerAudioBuffer integers;

    private DoubleAudioBuffer doubles;

    /**
     * Constructs a decoder for a Speex track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#SPEEX}
     * @throws IllegalArgumentException if the track is not a Speex track
     * @throws MediaException.Malformed if the configuration is not a valid Speex header
     * @throws MediaException.Unsupported if the header names an unknown mode or bitstream version
     */
    public SpeexDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a Speex track.
     *
     * <p>The options' limits apply; the decoder outputs the stream's own rate.
     *
     * @param track the track; its codec must be {@link Codec#SPEEX}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a Speex track
     * @throws MediaException.Malformed if the configuration is not a valid Speex header
     * @throws MediaException.Unsupported if the header names an unknown mode or bitstream version
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    public SpeexDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.SPEEX) {
            throw new IllegalArgumentException("not a Speex track: " + track.codec());
        }
        MediaLimits limits = options.limits();
        var header = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        int mode;
        int rate;
        if (header != null) {
            if (header.length < HEADER_SIZE
                    || !new String(header, 0, 8, StandardCharsets.ISO_8859_1).equals("Speex   ")) {
                throw new MediaException.Malformed("invalid Speex header");
            }
            rate = i32(header, 36);
            mode = i32(header, 40);
            var bitstream = i32(header, 44);
            var headerChannels = i32(header, 48);
            var packetFrames = i32(header, 64);
            if (mode < 0 || mode > 2) {
                throw new MediaException.Unsupported("speex", "Speex mode " + mode);
            }
            if (bitstream != 4) {
                throw new MediaException.Unsupported("speex", "Speex bitstream version " + bitstream);
            }
            if (headerChannels != 1 && headerChannels != 2) {
                throw new MediaException.Malformed("Speex header of " + headerChannels + " channels");
            }
            if (packetFrames < 0 || packetFrames > MAX_FRAMES) {
                throw new MediaException.Malformed("Speex header of " + packetFrames + " frames per packet");
            }
            channels = headerChannels;
            framesPerPacket = Math.max(packetFrames, 1);
        } else {
            rate = track.sampleRate();
            mode = rate == 8000 ? 0 : rate == 32000 ? 2 : 1;
            channels = 1;
            framesPerPacket = 0;
        }
        com.github.auties00.smalt.internal.limits.LimitChecks.checkChannels(limits, channels);
        SpeexFrameDecoder decoder = new SpeexNarrowbandDecoder(true);
        if (mode >= 1) {
            decoder = new SpeexSubbandDecoder(decoder, false);
        }
        if (mode == 2) {
            decoder = new SpeexSubbandDecoder(decoder, true);
        }
        this.frames = decoder;
        this.stereo = new SpeexStereoDecoder();
        decoder.setStereo(stereo);
        this.frameSize = decoder.frameSize();
        this.sampleRate = rate > 0 ? rate : 8000 << mode;
        this.layout = channels == 2 ? ChannelLayout.STEREO : ChannelLayout.MONO;
        this.frame = new short[2 * frameSize];
        this.decoded = new short[Math.max(framesPerPacket, 1) * frameSize * channels];
    }

    private static int i32(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | data[at + 3] << 24;
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
     * {@return the samples per channel of a frame: 160, 320 or 640}
     */
    public int frameSize() {
        return frameSize;
    }

    /**
     * Decodes the frames of a packet, or conceals {@code count} frames when {@code data} is {@code null}, into
     * {@link #decoded}.
     */
    private void decodeFrames(byte[] data, int count) {
        decodedFrames = 0;
        if (data != null) {
            bits.reset(data, 0, data.length);
        }
        var limit = data == null ? count : framesPerPacket == 0 ? MAX_FRAMES : framesPerPacket;
        for (var j = 0; j < limit; j++) {
            int ret;
            try {
                ret = frames.decode(data == null ? null : bits, frame, 0);
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt Speex frame", exception);
            }
            if (ret == -2) {
                throw new MediaException.Malformed("corrupt Speex frame");
            }
            if (ret == -1) {
                break;
            }
            if (data != null && bits.remaining() < 0) {
                throw new MediaException.Malformed("Speex frame runs past its packet");
            }
            if (channels == 2) {
                stereo.expand(frame, 0, frameSize);
            }
            var needed = (decodedFrames + 1) * frameSize * channels;
            if (decoded.length < needed) {
                decoded = java.util.Arrays.copyOf(decoded, needed * 2);
            }
            System.arraycopy(frame, 0, decoded, decodedFrames * frameSize * channels, frameSize * channels);
            decodedFrames++;
        }
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodeFrames(packet.data(), 0);
        emitFloats(output);
    }

    private void emitFloats(Consumer<AudioBuffer> output) {
        var count = decodedFrames * frameSize;
        if (count == 0) {
            return;
        }
        if (floats == null || floats.capacity() < count) {
            floats = new AudioBuffer(layout, sampleRate, count);
        }
        for (var c = 0; c < channels; c++) {
            var plane = floats.plane(c);
            for (var i = 0; i < count; i++) {
                plane[i] = decoded[i * channels + c] * 0x1p-15f;
            }
        }
        floats.setFrames(count);
        output.accept(floats);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: a Speex decoder buffers no samples between packets.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return the nearest whole number of frames to {@code samples}, at least one, in samples
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        var count = concealFrames(samples);
        for (var done = 0; done < count; done += MAX_FRAMES) {
            decodeFrames(null, Math.min(MAX_FRAMES, count - done));
            emitFloats(output);
        }
        return count * frameSize;
    }

    private int concealFrames(int samples) {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE / frameSize, ((long) samples + frameSize / 2) / frameSize));
    }

    /**
     * Conceals a span of lost packets into the stream's integer samples, as {@link #conceal(int, Consumer)} does.
     *
     * @param samples the duration of the lost span in samples per channel, positive
     * @param output receives one or more blocks, each valid only during the call
     * @return the number of samples per channel handed to {@code output}
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    public int concealIntegers(int samples, Consumer<IntegerAudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        var count = concealFrames(samples);
        for (var done = 0; done < count; done += MAX_FRAMES) {
            decodeFrames(null, Math.min(MAX_FRAMES, count - done));
            emitIntegers(output);
        }
        return count * frameSize;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsIntegerOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return 16
     */
    @Override
    public int bitsPerSample() {
        return 16;
    }

    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodeFrames(packet.data(), 0);
        emitIntegers(output);
    }

    private void emitIntegers(Consumer<IntegerAudioBuffer> output) {
        var count = decodedFrames * frameSize;
        if (count == 0) {
            return;
        }
        if (integers == null || integers.capacity() < count) {
            integers = new IntegerAudioBuffer(layout, sampleRate, 16, count);
        }
        for (var c = 0; c < channels; c++) {
            var plane = integers.plane(c);
            for (var i = 0; i < count; i++) {
                plane[i] = decoded[i * channels + c];
            }
        }
        integers.setFrames(count);
        output.accept(integers);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: a Speex decoder buffers no samples between packets.
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodeFrames(packet.data(), 0);
        var count = decodedFrames * frameSize;
        if (count == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < count) {
            doubles = new DoubleAudioBuffer(layout, sampleRate, count);
        }
        for (var c = 0; c < channels; c++) {
            var plane = doubles.plane(c);
            for (var i = 0; i < count; i++) {
                plane[i] = decoded[i * channels + c] * 0x1p-15;
            }
        }
        doubles.setFrames(count);
        output.accept(doubles);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: a Speex decoder buffers no samples between packets.
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    @Override
    public void close() {
    }
}
