package com.github.auties00.smalt.audio.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.audio.opus.OpusPackets;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;

/**
 * Fuzz targets of the audio decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#audio}), and of the
 * Opus packet parser ({@link OpusPackets}), whose input is one packet.
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
