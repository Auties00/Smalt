package com.github.auties00.smalt.audio.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.alac.AlacDecoder;
import com.github.auties00.smalt.audio.alac.AlacEncoder;
import com.github.auties00.smalt.audio.alac.AlacEncoderOptions;
import com.github.auties00.smalt.audio.opus.OpusPackets;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Fuzz targets of the audio decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#audio}), of the
 * Opus packet parser ({@link OpusPackets}), whose input is one packet, and of the ALAC encoder, whose input is a
 * format and samples that must decode back unchanged.
 */
class AudioFuzzTest {
    @FuzzTest
    void opus(byte[] data) {
        FuzzMedia.audio(Codec.OPUS, data);
    }

    @FuzzTest
    void vorbis(byte[] data) {
        FuzzMedia.audio(Codec.VORBIS, data);
    }

    @FuzzTest
    void flac(byte[] data) {
        FuzzMedia.audio(Codec.FLAC, data);
    }

    @FuzzTest
    void alac(byte[] data) {
        FuzzMedia.audio(Codec.ALAC, data);
    }

    /** The frame lengths the ALAC encoder target picks from. */
    private static final int[] ALAC_FRAME_LENGTHS = {16, 64, 1000, 4096};

    /**
     * Encodes samples with the ALAC encoder and decodes them back. The input is a sample size selector (16, 20, 24 or
     * 32 bits), a channel count selector (1 to 8), a byte whose bit 0 asks for fast coding and bits 1 and 2 pick a
     * frame length, then the samples, interleaved, each a little-endian 32-bit word sign-extended from the sample
     * size; a decode that differs from the samples is a finding. The comparison goes through the decoder's float
     * output, so it is exact up to 24 bits and at 32 bits sees the low bits of samples beyond 2^24 only where they
     * change the rounded float; AlacEncoderTest checks those bits through the decoder's integer samples.
     */
    @FuzzTest
    void alacEncoder(byte[] data) {
        var run = Fuzz.start("alacEncoder");
        if (data.length < 3) {
            run.skip("the format takes three bytes");
            return;
        }
        var bits = new int[]{16, 20, 24, 32}[data[0] & 3];
        var channels = (data[1] & 7) + 1;
        var frameLength = ALAC_FRAME_LENGTHS[(data[2] >> 1) & 3];
        var frames = Math.min((data.length - 3) / 4 / channels, 3 * 4096);
        var samples = new int[channels][frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                var at = 3 + 4 * (i * channels + c);
                var word = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                        | data[at + 3] << 24;
                samples[c][i] = word << (32 - bits) >> (32 - bits);
            }
        }
        var options = AlacEncoderOptions.builder().layout(new ChannelLayout(Collections.nCopies(channels,
                Speaker.UNKNOWN))).bitsPerSample(bits).frameLength(frameLength).fast((data[2] & 1) != 0).build();
        var track = AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                .codecConfig(new AlacEncoder(options).codecConfig().orElseThrow()).build();
        var decoder = new AlacDecoder(track, AudioDecoderOptions.builder().limits(Fuzz.LIMITS).build());
        // the decoder's own speakers as the input layout, so that input channel c is decoded plane c
        var encoder = new AlacEncoder(options.toBuilder().layout(decoder.layout()).build());
        var packets = new ArrayList<Packet>();
        run.step("encode", () -> {
            encoder.encode(samples, 0, frames, packets::add);
            encoder.flush(packets::add);
        });
        run.step("decode", () -> {
            var position = new int[1];
            for (var packet : packets) {
                decoder.decode(packet, buffer -> {
                    var scale = Math.scalb(1f, 1 - bits);
                    for (var c = 0; c < channels; c++) {
                        for (var i = 0; i < buffer.frames(); i++) {
                            var expected = samples[c][position[0] + i] * scale;
                            if (Float.floatToRawIntBits(buffer.plane(c)[i]) != Float.floatToRawIntBits(expected)) {
                                throw new AssertionError("sample " + (position[0] + i) + " of channel " + c
                                        + " decoded as " + buffer.plane(c)[i] + ", not " + expected);
                            }
                        }
                    }
                    position[0] += buffer.frames();
                    run.output();
                });
            }
            if (position[0] != frames) {
                throw new AssertionError(position[0] + " samples decoded of " + frames);
            }
        });
    }

    @FuzzTest
    void mp3(byte[] data) {
        FuzzMedia.audio(Codec.MP3, data);
    }

    @FuzzTest
    void mp2(byte[] data) {
        FuzzMedia.audio(Codec.MP2, data);
    }

    @FuzzTest
    void opusPackets(byte[] data) {
        var run = Fuzz.start("opusPackets");
        if (data.length == 0) {
            run.skip("an Opus packet has at least its table of contents");
            return;
        }
        OpusPackets.bandwidth(data, 0);
        OpusPackets.channels(data, 0);
        var frameSamples = OpusPackets.samplesPerFrame(data, 0, 48_000);
        var frames = run.value("frames", () -> OpusPackets.frames(data, 0, data.length));
        run.value("samples", () -> OpusPackets.samples(data, 0, data.length, 48_000)).ifPresent(samples -> {
            if (frames.isPresent() && samples != frames.get() * frameSamples) {
                throw new AssertionError(samples + " samples for " + frames.get() + " frames of " + frameSamples);
            }
        });
        run.value("fec", () -> OpusPackets.hasInbandFec(data, 0, data.length));
        run.value("split", () -> OpusPackets.split(data, 0, data.length)).ifPresent(split -> {
            if (frames.isPresent() && split.size() != frames.get()) {
                throw new AssertionError("split into " + split.size() + " packets of " + frames.get() + " frames");
            }
            for (var frame : split) {
                if ((frame[0] & 0xFC) != (data[0] & 0xFC) || OpusPackets.frames(frame, 0, frame.length) != 1) {
                    throw new AssertionError("a split packet is not one frame of the same configuration");
                }
            }
            // the frames of one valid packet always join again, into a packet that splits into the same frames
            var merged = OpusPackets.merge(split);
            var again = OpusPackets.split(merged, 0, merged.length);
            if (again.size() != split.size() || !Arrays.deepEquals(again.toArray(), split.toArray())) {
                throw new AssertionError("the merged frames split differently");
            }
            run.output();
        });
    }
}
