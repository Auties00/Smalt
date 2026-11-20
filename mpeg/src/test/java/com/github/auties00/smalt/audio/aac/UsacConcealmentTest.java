package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the concealment of lost and corrupt USAC access units on streams of the libxaac encoder
 * ({@code tools/testdata/usac-concealment}) covering the frequency domain coder (mono, complex prediction stereo,
 * 768-sample frames), the linear prediction domain coder, the switched coder with enhanced SBR and with predictive
 * vector coding, MPEG Surround 2-1-2 and MPEG-D DRC, each with an independent access unit about every second.
 *
 * <p>libxaac's own concealment is not usable as an oracle for these streams: its test decoder delays the output by
 * two frames while concealment is on, leaves single lost frames out of the output and fills about 45 frames with
 * full-scale noise after two lost frames in a row (commit 2fbadd57, a frequency domain stream at 48 kHz). The tests
 * therefore check the ported algorithm's contract: frames before a loss are bit-exact, the access units after a
 * loss are concealed until an independent one, a truncated access unit conceals exactly as a lost one, the
 * concealment fades by an eighth per frame to silence, keeps the high band and the upmix, and the decode converges
 * after the loss within {@link #RECOVERY}.
 */
class UsacConcealmentTest {
    /**
     * After the independent access unit that follows a loss and the frames that fade back in, the decode agrees with
     * the decode without loss up to the noise filling and spectral band replication noise, whose random state the
     * lost frames leave behind.
     */
    private static final Tolerance.Snr RECOVERY = new Tolerance.Snr(15);

    static AacTestSupport.Stream stream(String name) {
        return AacTestSupport.parseRaw(resource(name + ".raw"), new String(resource(name + ".meta"),
                StandardCharsets.US_ASCII));
    }

    private static byte[] resource(String name) {
        try (var input = UsacConcealmentTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/audio/aac/usac/" + name)) {
            assertTrue(input != null, "missing " + name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<Packet> packets(AacTestSupport.Stream stream) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < stream.frames().size(); i++) {
            packets.add(new Packet(1, stream.frames().get(i), i, i, 1, true));
        }
        return packets;
    }

    /**
     * {@return the indices of the independent access units}
     */
    static List<Integer> independent(List<Packet> packets) {
        var indices = new ArrayList<Integer>();
        for (var i = 0; i < packets.size(); i++) {
            var data = packets.get(i).data();
            if (data.length > 0 && (data[0] & 0x80) != 0) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * Collects every block a decoder hands over, one copy per block.
     */
    static final class Blocks implements Consumer<AudioBuffer> {
        final List<float[][]> blocks = new ArrayList<>();

        @Override
        public void accept(AudioBuffer buffer) {
            var copy = new float[buffer.channels()][];
            for (var c = 0; c < copy.length; c++) {
                copy[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
            }
            blocks.add(copy);
        }

        float[][] last() {
            return blocks.getLast();
        }
    }

    private static double energy(float[][] block) {
        return PacketLoss.energy(block, 0, block[0].length);
    }

    private static void assertSameBlocks(List<float[][]> expected, List<float[][]> actual, String label) {
        assertEquals(expected.size(), actual.size(), label + ": block count");
        for (var b = 0; b < expected.size(); b++) {
            for (var c = 0; c < expected.get(b).length; c++) {
                assertArrayEquals(expected.get(b)[c], actual.get(b)[c], label + ": block " + b + " channel " + c);
            }
        }
    }

    @Test
    @DisplayName("match every committed stream and meta file to its pin in usac.sha256")
    void pins() throws Exception {
        var lines = new String(resource("usac.sha256"), StandardCharsets.US_ASCII).strip().split("\\R");
        assertEquals(16, lines.length, "two pinned files per stream");
        for (var line : lines) {
            var fields = line.split("\\s+");
            var digest = MessageDigest.getInstance("SHA-256").digest(resource(fields[1]));
            assertEquals(fields[0], HexFormat.of().formatHex(digest), fields[1]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("conceal a loss before each independent access unit: exact length, no click, bounded level, recovery")
    void traces(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var points = independent(packets);
        assertTrue(points.size() >= 3, name + " has independent access units after the first: " + points);
        var lost = new TreeSet<Integer>();
        for (var point : points) {
            if (point > 8 && !points.contains(point - 1)) {
                lost.add(point - 3);
            }
        }
        assertTrue(!lost.isEmpty(), name + ": no loss placed");
        var bounds = new PacketLoss.Bounds(true, 3.0, 3, 256, 12, 7, RECOVERY.minimum());
        PacketLoss.assertConceals(() -> new AacDecoder(stream.track()), packets, lost, bounds);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("leave the blocks before the first loss bit-exact")
    void cleanBlocksUnchanged(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var reference = new Blocks();
        var lossy = new Blocks();
        try (var clean = new AacDecoder(stream.track()); var concealing = new AacDecoder(stream.track())) {
            for (var i = 0; i < packets.size(); i++) {
                clean.decode(packets.get(i), reference);
                if (i == 20 || i == 21) {
                    concealing.conceal(concealing.frameLength(), lossy);
                } else {
                    concealing.decode(packets.get(i), lossy);
                }
            }
        }
        assertSameBlocks(reference.blocks.subList(0, 20), lossy.blocks.subList(0, 20), name);
        assertEquals(reference.blocks.size(), lossy.blocks.size(), name + ": one block per access unit");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("conceal the dependent access units after a loss until an independent one, then decode again")
    void dependentUnits(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var points = independent(packets);
        var resume = points.get(1);
        var loss = resume - 4;
        var decoding = new Blocks();
        var concealing = new Blocks();
        try (var first = new AacDecoder(stream.track()); var second = new AacDecoder(stream.track())) {
            for (var i = 0; i < packets.size(); i++) {
                if (i == loss) {
                    first.conceal(first.frameLength(), decoding);
                    second.conceal(second.frameLength(), concealing);
                } else if (i > loss && i < resume) {
                    first.decode(packets.get(i), decoding);
                    second.conceal(second.frameLength(), concealing);
                } else {
                    first.decode(packets.get(i), decoding);
                    second.decode(packets.get(i), concealing);
                }
            }
        }
        assertSameBlocks(concealing.blocks, decoding.blocks, name);
        var resumed = 0.0;
        for (var b = resume + 2; b < resume + 6; b++) {
            resumed += energy(decoding.blocks.get(b));
        }
        assertTrue(resumed > 0, name + ": the decode resumes at access unit " + resume);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("conceal a truncated or empty access unit exactly as a lost one")
    void corruptUnits(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var points = independent(packets);
        var damaged = points.get(1) - 6;
        for (var length : new int[]{0, 2}) {
            var concealed = new Blocks();
            var decoded = new Blocks();
            try (var first = new AacDecoder(stream.track()); var second = new AacDecoder(stream.track())) {
                for (var i = 0; i < packets.size(); i++) {
                    if (i == damaged) {
                        first.conceal(first.frameLength(), concealed);
                        var data = Arrays.copyOf(packets.get(i).data(), length);
                        second.decode(new Packet(1, data, i, i, 1, true), decoded);
                    } else {
                        first.decode(packets.get(i), concealed);
                        second.decode(packets.get(i), decoded);
                    }
                }
            }
            assertSameBlocks(concealed.blocks, decoded.blocks, name + " cut to " + length + " bytes");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"fd-mono, 1", "fd-stereo, 2", "lpd-mono, 1", "switched-esbr, 1", "mps212, 2"})
    @DisplayName("fade by an eighth for each lost frame and fall silent by the ninth")
    void fade(String name, int channels) {
        var stream = stream(name);
        var packets = packets(stream);
        var blocks = new Blocks();
        try (var decoder = new AacDecoder(stream.track())) {
            for (var i = 0; i < 30; i++) {
                decoder.decode(packets.get(i), blocks);
            }
            var before = blocks.blocks.size();
            var unit = decoder.frameLength();
            assertEquals(12 * unit, decoder.conceal(12 * unit, blocks));
            var concealed = blocks.blocks.subList(before, blocks.blocks.size());
            assertEquals(12, concealed.size(), name + ": one block per unit");
            for (var block : concealed) {
                assertEquals(channels, block.length);
                assertEquals(unit, block[0].length);
            }
            var last = energy(blocks.blocks.get(before - 1));
            var first = energy(concealed.getFirst());
            assertTrue(first > last / 16 && first < last * 16,
                    name + ": the first concealed block keeps the level, " + first + " after " + last);
            for (var k = 9; k < 12; k++) {
                assertEquals(0.0, energy(concealed.get(k)), name + ": block " + k + " of the loss is silent");
            }
            for (var k = 0; k < 7; k++) {
                assertTrue(energy(concealed.get(k)) > 0, name + ": block " + k + " of the loss is heard");
            }
            assertTrue(energy(concealed.get(6)) < energy(concealed.getFirst()),
                    name + ": the concealment fades, " + energy(concealed.get(6)) + " after " + first);
        }
    }

    @Test
    @DisplayName("fade a frequency domain channel by the gains 1, 7/8, 3/4 ... 1/8 of its repeated spectrum")
    void fadeGains() {
        var stream = stream("fd-mono");
        var packets = packets(stream);
        var blocks = new Blocks();
        try (var decoder = new AacDecoder(stream.track())) {
            for (var i = 0; i < 30; i++) {
                decoder.decode(packets.get(i), blocks);
            }
            decoder.conceal(10 * decoder.frameLength(), blocks);
        }
        var concealed = blocks.blocks.subList(30, 40);
        // From the second lost frame on the spectrum repeats with random signs at the same magnitudes, so the RMS of
        // each block follows the fade gain within the variation the random signs give the overlap.
        var reference = Math.sqrt(energy(concealed.get(2))) / 0.75;
        for (var k = 2; k < 8; k++) {
            var gain = Math.sqrt(energy(concealed.get(k))) / reference;
            var expected = 1.0 - k / 8.0;
            assertTrue(Math.abs(gain - expected) < 0.25 * expected + 0.03,
                    "block " + k + " has gain " + gain + ", the fade gives " + expected);
        }
        // The tenth block is silent: the ninth still carries the delayed end of the eighth, which the peak limiter
        // of the MPEG-D DRC decoder holds back by 5 ms.
        assertEquals(0.0, energy(concealed.get(9)), "the tenth lost frame is silent");
    }

    @Test
    @DisplayName("keep the high band of enhanced SBR in the first concealed block")
    void highBand() {
        for (var name : new String[]{"switched-esbr", "pvc-esbr", "fd-768-esbr"}) {
            var stream = stream(name);
            var packets = packets(stream);
            var blocks = new Blocks();
            try (var decoder = new AacDecoder(stream.track())) {
                for (var i = 0; i < 30; i++) {
                    decoder.decode(packets.get(i), blocks);
                }
                decoder.conceal(decoder.frameLength(), blocks);
                var rate = decoder.sampleRate();
                var decoded = highBandEnergy(blocks.blocks.get(29)[0], rate);
                var concealed = highBandEnergy(blocks.last()[0], rate);
                assertTrue(decoded > 0, name + ": the decoded block has a high band");
                assertTrue(concealed > decoded / 30, name + ": the concealed block keeps a high band, " + concealed
                        + " after " + decoded);
            }
        }
    }

    /** Returns the energy of a block between a third and a half of the sampling rate, by a direct DFT. */
    private static double highBandEnergy(float[] block, int rate) {
        var n = block.length;
        var energy = 0.0;
        for (var k = n / 3; k < n / 2; k++) {
            var re = 0.0;
            var im = 0.0;
            for (var i = 0; i < n; i++) {
                var phase = 2 * Math.PI * k * i / n;
                re += block[i] * Math.cos(phase);
                im -= block[i] * Math.sin(phase);
            }
            energy += re * re + im * im;
        }
        return energy;
    }

    @Test
    @DisplayName("keep upmixing a concealed MPEG Surround 2-1-2 block into two different channels")
    void upmix() {
        var stream = stream("mps212");
        var packets = packets(stream);
        var blocks = new Blocks();
        try (var decoder = new AacDecoder(stream.track())) {
            for (var i = 0; i < 30; i++) {
                decoder.decode(packets.get(i), blocks);
            }
            decoder.conceal(2 * decoder.frameLength(), blocks);
        }
        for (var block : blocks.blocks.subList(30, 32)) {
            var left = block[0];
            var right = block[1];
            double ll = 0;
            double rr = 0;
            double lr = 0;
            for (var i = 0; i < left.length; i++) {
                ll += left[i] * left[i];
                rr += right[i] * right[i];
                lr += left[i] * right[i];
            }
            assertTrue(ll > 0 && rr > 0, "both channels are heard");
            assertTrue(Math.abs(lr) / Math.sqrt(ll * rr) < 0.9999, "the channels differ");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("survive random losses with finite, bounded output of the right length, and damaged units")
    void fuzzedLoss(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        for (var seed = 0; seed < 12; seed++) {
            var random = new Random(name.hashCode() * 31L + seed);
            var damage = seed >= 6;
            var expected = 0L;
            var blocks = new Blocks();
            int unit;
            try (var decoder = new AacDecoder(stream.track())) {
                unit = decoder.frameLength();
                for (var i = 0; i < packets.size(); i++) {
                    var choice = random.nextInt(10);
                    if (choice < 3) {
                        var units = 1 + random.nextInt(3);
                        decoder.conceal(unit * units, blocks);
                        expected += (long) unit * units;
                        continue;
                    }
                    var data = packets.get(i).data().clone();
                    if (damage && choice == 3) {
                        data = Arrays.copyOf(data, random.nextInt(data.length + 1));
                    } else if (damage && choice == 4 && data.length > 1) {
                        for (var flips = 0; flips < 4; flips++) {
                            var bit = 1 + random.nextInt(data.length * 8 - 1);
                            data[bit / 8] ^= (byte) (0x80 >>> (bit % 8));
                        }
                    }
                    expected += unit;
                    try {
                        decoder.decode(new Packet(1, data, i, i, 1, true), blocks);
                    } catch (MediaException.Unsupported unsupported) {
                        // A damaged access unit may signal a tool the decoder rejects, a reserved PVC mode for one.
                        expected -= unit;
                    }
                }
                decoder.flush(blocks);
            }
            var handed = 0L;
            for (var block : blocks.blocks) {
                handed += block[0].length;
                for (var plane : block) {
                    for (var value : plane) {
                        // A bit flip the decoder cannot detect decodes to arbitrary, but finite, values.
                        assertTrue(Float.isFinite(value) && (damage || Math.abs(value) < 4),
                                name + " seed " + seed + ": sample " + value);
                    }
                }
            }
            assertEquals(expected, handed, name + " seed " + seed + ": samples handed over");
        }
    }

    @TestFactory
    @DisplayName("conceal frames of the ISO/IEC 23003-3 conformance sequences lost at fixed positions")
    Stream<DynamicTest> conformance() {
        var archive = Conformance.archive("iso-23003-3-usac-smoke");
        return archive.members().stream().filter(member -> member.endsWith(".mp4")).sorted()
                .map(member -> DynamicTest.dynamicTest(member.substring(member.indexOf('/') + 1), () -> {
                    var stream = AacTestSupport.parseMp4(Fixtures.read(archive.fetch(member)));
                    var packets = packets(stream);
                    var count = packets.size();
                    var lost = PacketLoss.bursts(count / 4, 1, count / 2, 2, 3 * count / 4, 12);
                    var reference = new Blocks();
                    var lossy = new Blocks();
                    try (var clean = new AacDecoder(stream.track());
                         var concealing = new AacDecoder(stream.track())) {
                        for (var i = 0; i < count; i++) {
                            clean.decode(packets.get(i), reference);
                            if (lost.contains(i)) {
                                assertEquals(concealing.frameLength(),
                                        concealing.conceal(concealing.frameLength(), lossy));
                            } else {
                                concealing.decode(packets.get(i), lossy);
                            }
                        }
                    }
                    var first = count / 4;
                    assertSameBlocks(reference.blocks.subList(0, first), lossy.blocks.subList(0, first), member);
                    assertEquals(reference.blocks.size(), lossy.blocks.size(), member + ": one block per unit");
                    for (var b = first; b < lossy.blocks.size(); b++) {
                        var block = lossy.blocks.get(b);
                        for (var plane : block) {
                            for (var value : plane) {
                                assertTrue(Float.isFinite(value), member + ": block " + b + " sample " + value);
                            }
                        }
                        // Repeating the last frame doubles its level at most where its overlap adds in phase.
                        var louder = 0.0;
                        for (var r = Math.max(0, first - 2); r <= Math.min(b, reference.blocks.size() - 1); r++) {
                            louder = Math.max(louder, energy(reference.blocks.get(r)));
                        }
                        assertTrue(energy(block) <= 4 * louder + 1e-9,
                                member + ": block " + b + " energy " + energy(block) + " over 6 dB above " + louder);
                    }
                    var silent = lossy.blocks.get(3 * count / 4 + 11);
                    assertEquals(0.0, energy(silent), member + ": the twelfth unit of the long burst is silent");
                }));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("stay finite and silent over a long loss and decode again at the next independent access unit")
    void longLoss(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var points = independent(packets);
        var resume = points.get(1);
        var blocks = new Blocks();
        try (var decoder = new AacDecoder(stream.track())) {
            for (var i = 0; i < 10; i++) {
                decoder.decode(packets.get(i), blocks);
            }
            decoder.conceal(300 * decoder.frameLength(), blocks);
            for (var i = 10; i < packets.size(); i++) {
                decoder.decode(packets.get(i), blocks);
            }
        }
        for (var b = 0; b < blocks.blocks.size(); b++) {
            for (var plane : blocks.blocks.get(b)) {
                for (var value : plane) {
                    assertTrue(Float.isFinite(value) && Math.abs(value) < 4, name + ": block " + b + " sample " + value);
                }
            }
        }
        for (var b = 22; b < 310 + resume - 10; b++) {
            assertEquals(0.0, energy(blocks.blocks.get(b)), name + ": block " + b + " is silent");
        }
        var after = 0.0;
        for (var b = 300 + resume + 2; b < 300 + resume + 8 && b < blocks.blocks.size(); b++) {
            after += energy(blocks.blocks.get(b));
        }
        assertTrue(after > 0, name + ": the decode resumes at access unit " + resume);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-mono", "fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212",
            "drc"})
    @DisplayName("conceal a first access unit whose AudioPreRoll is truncated as a loss before the first unit")
    void corruptPreRoll(String name) {
        var stream = stream(name);
        var packets = packets(stream);
        var first = packets.getFirst().data();
        var concealed = new Blocks();
        var decoded = new Blocks();
        try (var lost = new AacDecoder(stream.track()); var cut = new AacDecoder(stream.track())) {
            lost.conceal(lost.frameLength(), concealed);
            cut.decode(new Packet(1, Arrays.copyOf(first, Math.min(first.length - 1, 3)), 0, 0, 1, true), decoded);
            for (var i = 1; i < packets.size(); i++) {
                lost.decode(packets.get(i), concealed);
                cut.decode(packets.get(i), decoded);
            }
        }
        assertSameBlocks(concealed.blocks, decoded.blocks, name);
        var resume = independent(packets).get(1);
        var after = 0.0;
        for (var b = resume + 2; b < resume + 8 && b < decoded.blocks.size(); b++) {
            after += energy(decoded.blocks.get(b));
        }
        assertTrue(after > 0, name + ": the decode resumes at access unit " + resume);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fd-stereo", "fd-768-esbr", "lpd-mono", "switched-esbr", "pvc-esbr", "mps212", "drc"})
    @DisplayName("conceal an access unit cut at lengths spread over its size, keeping one bounded block per unit")
    void truncationSweep(String name) {
        var stream = stream(name);
        var all = packets(stream);
        var damaged = independent(all).get(1) - 2;
        var packets = all.subList(0, Math.min(all.size(), damaged + 24));
        var length = packets.get(damaged).data().length;
        var reference = new Blocks();
        try (var clean = new AacDecoder(stream.track())) {
            for (var packet : packets) {
                clean.decode(packet, reference);
            }
        }
        for (var cut = 0; cut < length; cut += Math.max(1, length / 64)) {
            var blocks = new Blocks();
            try (var decoder = new AacDecoder(stream.track())) {
                for (var i = 0; i < packets.size(); i++) {
                    var data = packets.get(i).data();
                    if (i == damaged) {
                        data = Arrays.copyOf(data, cut);
                    }
                    decoder.decode(new Packet(1, data, i, i, 1, true), blocks);
                }
            }
            assertEquals(reference.blocks.size(), blocks.blocks.size(), name + " cut to " + cut + ": block count");
            assertSameBlocks(reference.blocks.subList(0, damaged), blocks.blocks.subList(0, damaged),
                    name + " cut to " + cut);
            for (var b = damaged; b < blocks.blocks.size(); b++) {
                var block = blocks.blocks.get(b);
                assertEquals(reference.blocks.get(b)[0].length, block[0].length, name + ": block " + b + " length");
                for (var plane : block) {
                    for (var value : plane) {
                        assertTrue(Float.isFinite(value) && Math.abs(value) < 4,
                                name + " cut to " + cut + ": block " + b + " sample " + value);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("report concealment and conceal silence before the first access unit")
    void beforeFirstUnit() {
        var stream = stream("fd-mono");
        try (var decoder = new AacDecoder(stream.track())) {
            assertTrue(decoder.supportsConcealment());
            var blocks = new Blocks();
            var produced = decoder.conceal(2 * decoder.frameLength() - 10, blocks);
            var handed = 0;
            for (var block : blocks.blocks) {
                assertEquals(0.0, energy(block));
                handed += block[0].length;
            }
            assertEquals(handed, produced, "the samples reported are the samples handed over");
            // The first block is shorter by the 5 ms the peak limiter of the MPEG-D DRC decoder holds back.
            assertEquals(2 * decoder.frameLength() - decoder.sampleRate() / 200, produced);
            assertEquals(2, blocks.blocks.size());
            for (var packet : packets(stream).subList(0, 10)) {
                decoder.decode(packet, blocks);
            }
            assertTrue(energy(blocks.last()) > 0, "the first access unit is independent and decodes");
        }
    }
}
