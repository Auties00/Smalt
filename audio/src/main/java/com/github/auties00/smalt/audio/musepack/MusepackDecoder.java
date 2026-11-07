/* Ports libmpcdec r475 (mpc_decoder.c, mpc_demux.c, streaminfo.c). Copyright (c) 2005-2009, The Musepack
   Development Team; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.musepack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Musepack audio of stream versions 7 and 8 into planar float samples.
 *
 * <p>Packets and the configuration are as {@link Codec#MUSEPACK7} and {@link Codec#MUSEPACK8} describe. The output
 * floats are bit-identical to those of libmpcdec r475 in its floating-point build: the decoder drops the first 481
 * samples (the delay of the synthesis filter bank) and the leading silence of a version 8 stream, and ends the
 * output at the stream's length as that library does, which for a version 7 stream ends with its last frame. A
 * version 7 decode that starts after the first frame converges after a few frames; a version 8 decode starting at
 * any packet is exact after its first frame. A frame whose bitstream does not fill its declared length exactly, or
 * that codes invalid values, raises {@link MediaException.Malformed}.
 *
 * <p>Instances are not thread-safe.
 */
public final class MusepackDecoder implements AudioDecoder {
    private static final int SYNTH_DELAY = 481;

    private static final int FRAME = MusepackFrameDecoder.FRAME_LENGTH;

    private static final int[] SAMPLE_RATES = {44100, 48000, 37800, 32000};

    private final boolean sv8;

    private final int sampleRate;

    private final int channels;

    private final ChannelLayout layout;

    private final int framesPerPacket;

    private final MediaLimits limits;

    private final long totalFrames;

    private final boolean gapless;

    /**
     * The stream's length in samples as the decoder counts it, from the first frame and with the delay.
     */
    private long samples;

    private final long leading;

    private final MusepackFrameDecoder frames;

    private long skip;

    private final float[][] frame;

    private AudioBuffer floats;

    private DoubleAudioBuffer doubles;

    private float[][] staged;

    private int stagedFrames;

    /**
     * Constructs a decoder for a Musepack track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#MUSEPACK7} or {@link Codec#MUSEPACK8}
     * @throws IllegalArgumentException if the track is not a Musepack track
     * @throws MediaException.Malformed if the configuration is not a stream header of its version
     * @throws MediaException.Unsupported if the header names an unsupported stream version
     */
    public MusepackDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a Musepack track.
     *
     * <p>The decoder outputs the stream's own rate.
     *
     * @param track the track; its codec must be {@link Codec#MUSEPACK7} or {@link Codec#MUSEPACK8}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a Musepack track
     * @throws MediaException.Malformed if the configuration is not a stream header of its version
     * @throws MediaException.Unsupported if the header names an unsupported stream version
     */
    public MusepackDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.MUSEPACK7 && track.codec() != Codec.MUSEPACK8) {
            throw new IllegalArgumentException("not a Musepack track: " + track.codec());
        }
        var config = track.codecConfig().orElseThrow(
                () -> new MediaException.Malformed("Musepack track without a stream header"));
        this.sv8 = track.codec() == Codec.MUSEPACK8;
        this.limits = options.limits();
        int maxBand;
        boolean midSide;
        if (sv8) {
            var bit = new long[]{0};
            if (config.length < 6) {
                throw new MediaException.Malformed("Musepack stream header of " + config.length + " bytes");
            }
            bit[0] = 32;
            var version = (int) read(config, bit, 8);
            if (version != 8) {
                throw new MediaException.Unsupported("musepack", "Musepack stream version " + version);
            }
            var total = varint(config, bit);
            this.leading = varint(config, bit);
            var rateIndex = (int) read(config, bit, 3);
            maxBand = (int) read(config, bit, 5) + 1;
            this.channels = (int) read(config, bit, 4) + 1;
            midSide = read(config, bit, 1) != 0;
            this.framesPerPacket = 1 << ((int) read(config, bit, 3) * 2);
            if (rateIndex >= SAMPLE_RATES.length || channels > 2 || leading > total) {
                throw new MediaException.Malformed("invalid Musepack stream header");
            }
            this.sampleRate = SAMPLE_RATES[rateIndex];
            this.samples = total;
            this.totalFrames = -1;
            this.gapless = true;
        } else {
            if (config.length < 28 || config[0] != 'M' || config[1] != 'P' || config[2] != '+'
                    || (config[3] & 15) != 7) {
                throw new MediaException.Malformed("not a Musepack stream version 7 header");
            }
            var frameCount = u32(config, 4);
            var flags = u32(config, 8);
            var gaplessWord = u32(config, 20);
            midSide = ((flags >>> 30) & 1) != 0;
            maxBand = (int) ((flags >>> 24) & 63);
            this.sampleRate = SAMPLE_RATES[(int) ((flags >>> 16) & 3)];
            this.gapless = (gaplessWord >>> 31) != 0;
            var last = (gaplessWord >>> 20) & 2047;
            if (last == 0) {
                last = FRAME;
            } else if (last > FRAME) {
                throw new MediaException.Malformed("Musepack last frame of " + last + " samples");
            }
            this.channels = 2;
            this.framesPerPacket = 1;
            this.totalFrames = frameCount;
            this.leading = 0;
            this.samples = gapless ? frameCount * FRAME : frameCount * FRAME - SYNTH_DELAY;
        }
        if (maxBand == 0 && !sv8 || maxBand >= 32) {
            throw new MediaException.Malformed("Musepack maximum band " + maxBand);
        }
        this.layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        this.frames = new MusepackFrameDecoder(sv8, maxBand, midSide, channels);
        this.skip = SYNTH_DELAY + leading;
        this.frame = new float[2][FRAME];
        this.staged = new float[channels][FRAME * Math.min(framesPerPacket, 64)];
    }

    private static long read(byte[] data, long[] bit, int count) {
        var value = 0L;
        for (var i = 0; i < count; i++) {
            var at = bit[0]++;
            var index = (int) (at >>> 3);
            if (index >= data.length) {
                throw new MediaException.Malformed("truncated Musepack stream header");
            }
            value = value << 1 | ((data[index] >>> (7 - (at & 7))) & 1);
        }
        return value;
    }

    private static long varint(byte[] data, long[] bit) {
        var value = 0L;
        for (var i = 0; i < 9; i++) {
            var b = read(data, bit, 8);
            value = value << 7 | (b & 0x7F);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new MediaException.Malformed("invalid Musepack size");
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    /**
     * Decodes one packet into {@link #staged}, setting {@link #stagedFrames}.
     */
    private void decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        stagedFrames = 0;
        var data = packet.data();
        try {
            if (sv8) {
                var firstFrame = Math.floorDiv(packet.pts() + SYNTH_DELAY + leading, FRAME);
                frames.load(data, 0, data.length, 0);
                var decoded = Math.max(firstFrame, 0) * FRAME;
                var complete = true;
                for (var i = 0; i < framesPerPacket; i++) {
                    var left = samples - decoded + SYNTH_DELAY;
                    if (left <= 0 && samples != 0) {
                        complete = false;
                        break;
                    }
                    frames.readFrame(i == 0);
                    if (frames.position() > frames.bits()) {
                        throw new MediaException.Malformed("Musepack audio packet overread");
                    }
                    decoded = emitFrame(decoded, left);
                }
                if (complete && frames.bits() - frames.position() > 7) {
                    throw new MediaException.Malformed("Musepack audio packet with unread data");
                }
            } else {
                if (data.length < 5) {
                    throw new MediaException.Malformed("Musepack frame of " + data.length + " bytes");
                }
                var index = Math.floorDiv(packet.pts() + SYNTH_DELAY, FRAME);
                var decoded = Math.max(index, 0) * FRAME;
                var left = samples - decoded + SYNTH_DELAY;
                if (left <= 0 && samples != 0) {
                    return;
                }
                frames.loadWords(data, 1, (data.length - 1) / 4 * 4, data[0] & 31);
                var length = frames.read(20);
                var start = frames.position();
                frames.readFrame(false);
                decoded = decodeSv7Tail(decoded, left, index, start, length);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Musepack frame", exception);
        }
    }

    private long decodeSv7Tail(long decoded, long left, long index, long start, int length) {
        var synthesize = skip < FRAME + SYNTH_DELAY;
        if (synthesize) {
            frames.requantize();
            frames.synthesize(frame, 0);
        }
        decoded += FRAME;
        var expected = (long) length;
        if (Long.compareUnsigned(decoded - samples, FRAME) < 0) {
            var last = frames.read(11);
            expected += 11;
            if (decoded == samples) {
                if (last == 0) {
                    last = FRAME;
                }
                samples += last - FRAME;
                left += last - FRAME;
            }
        }
        if (frames.position() - start != expected) {
            throw new MediaException.Malformed("Musepack frame of " + (frames.position() - start)
                    + " bits declares " + expected);
        }
        stage(left);
        return decoded;
    }

    private long emitFrame(long decoded, long left) {
        if (skip < FRAME + SYNTH_DELAY) {
            frames.requantize();
            frames.synthesize(frame, 0);
        }
        stage(left);
        return decoded + FRAME;
    }

    /**
     * Appends the samples of the frame synthesized last that the stream's length and the leading skip leave.
     */
    private void stage(long left) {
        var count = (int) Math.max(0, Math.min(FRAME, left));
        var start = 0;
        if (skip > 0) {
            if (count <= skip) {
                skip -= count;
                return;
            }
            start = (int) skip;
            count -= start;
            skip = 0;
        }
        if (staged[0].length < stagedFrames + count) {
            // the staged planes and the output buffer of the same size, at most a double per sample
            LimitChecks.checkAllocation(limits, (stagedFrames + count) * 2L * channels * (4 + 8));
            for (var c = 0; c < channels; c++) {
                staged[c] = java.util.Arrays.copyOf(staged[c], (stagedFrames + count) * 2);
            }
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(frame[c], start, staged[c], stagedFrames, count);
        }
        stagedFrames += count;
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (stagedFrames == 0) {
            return;
        }
        if (floats == null || floats.capacity() < stagedFrames) {
            floats = new AudioBuffer(layout, sampleRate, stagedFrames);
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(staged[c], 0, floats.plane(c), 0, stagedFrames);
        }
        floats.setFrames(stagedFrames);
        output.accept(floats);
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The doubles are the decoded floats, unchanged.
     *
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (stagedFrames == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < stagedFrames) {
            doubles = new DoubleAudioBuffer(layout, sampleRate, stagedFrames);
        }
        for (var c = 0; c < channels; c++) {
            var source = staged[c];
            var target = doubles.plane(c);
            for (var i = 0; i < stagedFrames; i++) {
                target[i] = source[i];
            }
        }
        doubles.setFrames(stagedFrames);
        output.accept(doubles);
    }

    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }
}
