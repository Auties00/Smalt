package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Frame;
import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Tone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds the DTS Express (LBR) decoder malformed frames: the most open tonal groups a frame can hold, and valid
 * tonal, residual, channel pair, LFE and band limited frames with random bit flips, byte overwrites and cut chunks.
 * Every frame must decode or raise a {@link MediaException} that hides no internal failure ({@link Fuzz#check}),
 * and every sample decoded must be finite.
 */
final class LbrRobustnessTest {
    /**
     * A valid stream to mutate.
     *
     * @param name the name of the case
     * @param rate the header sample rate
     * @param mask the speaker mask
     * @param channels the number of channels of the asset, LFE excluded
     * @param flags the header flags
     * @param bitRate the original and scaled bit rate of the header
     * @param payloads the LBR frame payloads (the chunks of each frame, concatenated)
     */
    record Source(String name, int rate, int mask, int channels, int flags, int bitRate, List<byte[]> payloads) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Source> sources() {
        var all = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo", "escapes");
        var identity = LbrResidualWriter.identity();
        var random = new SplittableRandom(97);
        var sources = new ArrayList<Source>();
        var mono = new ArrayList<byte[]>();
        for (var profile : new int[]{0x18, 0x38, 0x10, 0x00}) {
            var chunks = new ArrayList<byte[]>();
            chunks.add(LbrStreamWriter.scaleFactors(24, 26, 28, 30, 32, 34));
            chunks.addAll(randomTones(random, 1, 128));
            chunks.add(LbrStreamWriter.lfe(0x2000, 30, codes(random, 64, 4), false));
            chunks.addAll(LbrResidualWriter.chunks(LbrResidualWriter.random(random, profile, 48000, all), 48000,
                    128000, identity));
            mono.add(concat(chunks));
        }
        sources.add(new Source("mono with tones, residual and LFE", 48000, 0x1, 1, 0x16, 128000, mono));
        var pairs = new ArrayList<byte[]>();
        for (var profile : new int[]{0x18, 0x38, 0x10}) {
            var chunks = new ArrayList<byte[]>();
            chunks.add(LbrStreamWriter.scaleFactors(24, 26, 28, 30, 32, 34));
            chunks.addAll(randomTones(random, 4, 128));
            chunks.addAll(LbrResidualWriter.pairChunks(LbrResidualWriter.randomPair(random, profile, 48000, all),
                    48000, 192000, identity, 16, true, 32, 4));
            chunks.addAll(LbrResidualWriter.forPair(LbrResidualWriter.pairChunks(LbrResidualWriter.randomPair(random,
                    profile, 48000, all), 48000, 192000, identity, 16, true, 32, 4), 1));
            pairs.add(concat(chunks));
        }
        sources.add(new Source("two channel pairs with tones", 48000, 0x6, 4, 0x14, 192000, pairs));
        var limited = new ArrayList<byte[]>();
        for (var profile : new int[]{0x18, 0x20}) {
            var chunks = new ArrayList<byte[]>();
            chunks.add(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30));
            chunks.addAll(randomTones(random, 2, 64));
            chunks.add(LbrStreamWriter.lfe(0x1800, 20, codes(random, 96, 4), false));
            chunks.addAll(LbrResidualWriter.pairChunks(LbrResidualWriter.randomPair(random, profile, 16000, all),
                    16000, 128000, identity, 16, true, 0));
            limited.add(concat(chunks));
        }
        sources.add(new Source("stereo limited to a third, with LFE", 48000, 0x2, 2, 0x0E, 128000, limited));
        var low = new ArrayList<byte[]>();
        for (var profile : new int[]{0x18, 0x38}) {
            var chunks = new ArrayList<byte[]>();
            chunks.addAll(randomTones(random, 1, 32));
            chunks.add(LbrStreamWriter.lfe(0x80000, 40, codes(random, 64, 6), true));
            chunks.addAll(LbrResidualWriter.chunks(LbrResidualWriter.random(random, profile, 8000, all), 8000,
                    128000, identity));
            low.add(concat(chunks));
        }
        sources.add(new Source("8 kHz mono with 24-bit LFE", 8000, 0x1, 1, 0x17, 128000, low));
        return sources.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("mutated frames decode or raise a MediaException, with finite samples")
    void mutations(Source source) {
        var clean = decode(source, source.payloads());
        assertEquals(0, clean.failures(), "the unmutated stream decodes");
        assertTrue(clean.samples() > 0, "the unmutated stream has samples");
        var random = new SplittableRandom(source.name().hashCode());
        for (var round = 0; round < 150; round++) {
            var mutated = new ArrayList<byte[]>();
            for (var payload : source.payloads()) {
                mutated.add(mutate(random, payload));
            }
            decode(source, mutated);
        }
    }

    @Test
    @DisplayName("a frame opening a tonal group in its combined chunk and in every single group chunk decodes")
    void everyGroupOpen() {
        var chunks = new ArrayList<byte[]>();
        chunks.add(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30));
        // the combined chunk opens group 3 after a component of group 4; then every single group chunk opens its
        // group after one component, six open groups in all
        chunks.add(LbrStreamWriter.tonalCombined(null, Map.of(4, List.of(new Tone(0, 60 << 5, 10, 1)),
                3, List.of(new Tone(0, 60 << 4, 10, 2), new Tone(0, 124 << 4, 10, 3))), 1));
        for (var group = 4; group >= 0; group--) {
            var unit = 1 << (group + 1);
            chunks.add(LbrStreamWriter.tonalGroup(group, List.of(new Tone(0, 60 * unit, 10, group),
                    new Tone(0, 124 * unit, 10, 0)), 1));
        }
        var payloads = List.of(concat(chunks), new byte[0], new byte[0]);
        var result = decode(new Source("open", 48000, 0x1, 1, 0x14, 128000, payloads), payloads);
        assertEquals(0, result.failures(), "every frame decodes");
        assertTrue(result.energy() > 0, "the components of the frame are synthesized");
    }

    /**
     * The outcome of a decode: the number of frames that raised a {@link MediaException}, the samples decoded and
     * their energy.
     */
    private record Result(int failures, long samples, double energy) {
    }

    /**
     * Decodes the frames of a source with other payloads, frame by frame, failing the test on any exception other than
     * a {@link MediaException}, on one that hides an internal failure and on a sample that is not finite.
     */
    private static Result decode(Source source, List<byte[]> payloads) {
        var stream = new ByteArrayOutputStream();
        var frameSamples = 1024 << (source.rate() < 16000 ? 0 : source.rate() < 32000 ? 1 : 2);
        for (var payload : payloads) {
            var lbr = LbrStreamWriter.frame(false, payload);
            var component = LbrStreamWriter.component(source.rate(), source.mask(), source.flags(), source.bitRate(),
                    true, lbr);
            stream.writeBytes(LbrStreamWriter.substream(component, source.channels()
                    + ((source.flags() & 2) != 0 ? 1 : 0), source.mask(), source.rate(), frameSamples));
        }
        var failures = 0;
        var samples = 0L;
        var energy = 0.0;
        try (var demuxer = new DtsDemuxer(MediaSource.of(stream.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var decoder = new DtsDecoder(track, DtsDecoderOptions.defaults());
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                var counts = new double[2];
                try {
                    decoder.decode(packet, buffer -> {
                        for (var ch = 0; ch < buffer.channels(); ch++) {
                            var plane = buffer.plane(ch);
                            for (var i = 0; i < buffer.frames(); i++) {
                                if (!Float.isFinite(plane[i])) {
                                    fail("sample " + i + " of channel " + ch + " is " + plane[i]);
                                }
                                counts[1] += (double) plane[i] * plane[i];
                            }
                        }
                        counts[0] += (double) buffer.frames() * buffer.channels();
                    });
                } catch (MediaException rejected) {
                    Fuzz.check("decode " + source.name(), rejected);
                    failures++;
                }
                samples += (long) counts[0];
                energy += counts[1];
            }
        } catch (MediaException rejected) {
            Fuzz.check("demux " + source.name(), rejected);
            failures++;
        }
        return new Result(failures, samples, energy);
    }

    /**
     * {@return a copy of a payload with a few random bit flips, a random byte or a cut tail}
     */
    private static byte[] mutate(SplittableRandom random, byte[] payload) {
        if (payload.length == 0) {
            return payload;
        }
        var out = payload.clone();
        switch (random.nextInt(4)) {
            case 0 -> {
                for (var i = 1 + random.nextInt(8); i > 0; i--) {
                    out[random.nextInt(out.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> out[random.nextInt(out.length)] = (byte) random.nextInt(256);
            case 2 -> {
                for (var i = 1 + random.nextInt(32); i > 0; i--) {
                    out[random.nextInt(out.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            default -> {
                return java.util.Arrays.copyOf(out, random.nextInt(out.length));
            }
        }
        return out;
    }

    /**
     * {@return a tonal chunk per group of random components of some channels below the top lines}
     */
    private static List<byte[]> randomTones(SplittableRandom random, int channels, int lines) {
        var chunks = new ArrayList<byte[]>();
        for (var group = 0; group <= 4; group++) {
            var tones = new ArrayList<Tone>();
            var limit = (lines - 5) << (group + 1);
            for (var position = 0; position < 16 >> group; position++) {
                var frequency = (1 << (group + 1)) + random.nextInt(30);
                for (var t = 0; t < 2 && frequency < limit; t++) {
                    int[][] secondary = null;
                    var main = channels > 1 ? random.nextInt(channels) : 0;
                    if (channels > 1 && random.nextBoolean()) {
                        secondary = new int[channels][];
                        secondary[(main + 1) % channels] = new int[]{random.nextInt(6), random.nextInt(8)};
                    }
                    tones.add(new Tone(position, frequency, main, random.nextInt(14), random.nextInt(8), secondary));
                    frequency += 1 + random.nextInt(Math.max(1, limit / 3));
                }
            }
            chunks.add(LbrStreamWriter.tonalGroup(group, tones, channels));
        }
        return chunks;
    }

    /**
     * {@return random ADPCM codes of some bits}
     */
    private static int[] codes(SplittableRandom random, int count, int bits) {
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = random.nextInt(1 << bits);
        }
        return out;
    }

    private static byte[] concat(List<byte[]> chunks) {
        var out = new ByteArrayOutputStream();
        for (var chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }
}
