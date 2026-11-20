package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers XLL data sent through a peak bit rate smoothing buffer (ETSI TS 102 114 clause 7.5.2): the XLL frames of a
 * stream are cut into equal chunks, one per extension substream frame, sent ahead of their time, the first sync point
 * waiting the frames that keep the buffer from running dry. Such a stream must decode, frame for frame, as the stream
 * that carries each XLL frame whole in the frame of its time and no XLL frame before the delay has run out, and as
 * ffmpeg, used as a black box, decodes it.
 */
final class DtsPeakBitRateDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();
    private static final int FRAME = 512;
    private static final int FRAMES = 10;

    /**
     * A synthetic stream: the core frames and XLL frames of each time, and its smoothing.
     *
     * @param cores the core frames, or {@code null} for a stream without core
     * @param xll the XLL frames
     * @param delay the number of frames the first sync point waits
     * @param chunks the start of the XLL data each extension substream frame carries in the XLL frames from
     *               {@code delay} on, then their length
     * @param starts the start of each XLL frame from {@code delay} on in that data, then its length
     */
    record Smoothed(List<byte[]> cores, List<byte[]> xll, int delay, int[] chunks, int[] starts) {
    }

    @ParameterizedTest(name = "amplitude {0}")
    @ValueSource(ints = {20, 3000})
    @DisplayName("a smoothed stream over a core decodes the core until the delay runs out, then losslessly")
    void overCore(int amplitude) throws IOException {
        var stream = smooth(source(amplitude, true));
        var units = units(stream, 0, 15);
        var buffers = DtsUnits.decode(units);
        var expected = DtsUnits.decode(plain(stream, 0));
        assertEquals(FRAMES, buffers.size());
        assertArrayEquals(DtsUnits.interleave(expected), DtsUnits.interleave(buffers), "smoothed");
        Oracle.assume(Oracle.Tool.FFMPEG);
        var reference = DtsUnits.ffmpegInt("smoothed-core-" + amplitude, units);
        var decoded = DtsUnits.interleave32(buffers);
        report("over core " + amplitude, reference, decoded, buffers, stream.delay());
        assertEquals(decoded.length, reference.length, "ffmpeg sample count");
        // ffmpeg outputs the first frame with lossless data after frames without it as its fixed-point core
        var from = (stream.delay() + 1) * FRAME * 6;
        EXACT.assertWithin("smoothed over core " + amplitude, Arrays.copyOfRange(reference, from, reference.length),
                Arrays.copyOfRange(decoded, from, decoded.length));
        // while it waits ffmpeg outputs the fixed-point core, which zero residuals over the core reproduce
        var waiting = stream.delay() * FRAME * 6;
        var fixed = new ArrayList<byte[]>();
        for (var f = 0; f < stream.delay(); f++) {
            var set = new XllStreamWriter.ChannelSet(6, 0x3F);
            set.residuals = new int[6][FRAME];
            fixed.add(unit(stream.cores().get(f), XllStreamWriter.component(List.of(set), FRAME),
                    XllStreamWriter.Sync.START));
        }
        EXACT.assertWithin("fixed-point core while waiting " + amplitude, Arrays.copyOf(reference, waiting),
                DtsUnits.interleave32(DtsUnits.decode(fixed)));
    }

    @Test
    @DisplayName("a smoothed stream without core is silent until the delay runs out, then lossless")
    void alone() throws IOException {
        var stream = smooth(source(500, false));
        var units = units(stream, 0, 15);
        var buffers = DtsUnits.decode(units);
        assertEquals(FRAMES, buffers.size());
        for (var f = 0; f < stream.delay(); f++) {
            var buffer = buffers.get(f);
            assertEquals(FRAME, buffer.frames());
            assertEquals(6, buffer.channels());
            for (var ch = 0; ch < 6; ch++) {
                for (var i = 0; i < FRAME; i++) {
                    assertEquals(0.0f, buffer.plane(ch)[i]);
                }
            }
        }
        var expected = DtsUnits.decode(plain(stream, stream.delay()));
        assertArrayEquals(DtsUnits.interleave(expected),
                DtsUnits.interleave(buffers.subList(stream.delay(), buffers.size())), "lossless frames");
        Oracle.assume(Oracle.Tool.FFMPEG);
        // ffmpeg outputs nothing while it waits, nor for the first lossless frame, which it outputs as its core in a
        // stream with one (see overCore); from the second on it outputs Smalt's frames
        var reference = DtsUnits.ffmpegInt("smoothed-alone", units);
        report("alone", reference, DtsUnits.interleave32(buffers), buffers, stream.delay());
        EXACT.assertWithin("smoothed alone", reference,
                DtsUnits.interleave32(buffers.subList(stream.delay() + 1, buffers.size())));
    }

    @ParameterizedTest(name = "joining at frame {0}")
    @ValueSource(ints = {1, 3, 5})
    @DisplayName("a decoder joining a smoothed stream between sync points starts at the next one")
    void joining(int first) {
        var stream = smooth(source(200, true));
        var units = units(stream, 0, 15).subList(first, FRAMES);
        var buffers = DtsUnits.decode(units);
        var sync = -1;
        for (var i = first; i < FRAMES && sync < 0; i++) {
            for (var q = 0; q + 1 < stream.starts().length; q++) {
                if (stream.starts()[q] >= stream.chunks()[i] && stream.starts()[q] < stream.chunks()[i + 1]) {
                    sync = q + stream.delay();
                    break;
                }
            }
        }
        assertTrue(sync >= first, "a later sync point");
        var expected = DtsUnits.decode(plain(stream, 0, first, sync));
        assertArrayEquals(DtsUnits.interleave(expected), DtsUnits.interleave(buffers), "joined");
    }

    @Test
    @DisplayName("frames without sync point that start inside an XLL frame decode their core")
    void unsynchronizedFrames() {
        var stream = smooth(source(200, true));
        var data = data(stream);
        var cut = new ArrayList<byte[]>();
        var cores = new ArrayList<byte[]>();
        for (var i = 1; i < FRAMES; i++) {
            if (Arrays.binarySearch(stream.starts(), stream.chunks()[i]) >= 0) {
                continue;
            }
            cut.add(unit(stream.cores().get(i), Arrays.copyOfRange(data, stream.chunks()[i], stream.chunks()[i + 1]),
                    XllStreamWriter.Sync.NONE));
            cores.add(stream.cores().get(i));
        }
        assertTrue(cut.size() > 4, "frames starting inside an XLL frame");
        assertArrayEquals(DtsUnits.interleave(DtsUnits.decode(cores)), DtsUnits.interleave(DtsUnits.decode(cut)));
    }

    @Test
    @DisplayName("data beyond the buffer size of the sync point is malformed, and the frames after it decode their core")
    void overflow() {
        var stream = source(3000, true);
        var data = new ByteArrayOutputStream();
        stream.xll().forEach(data::writeBytes);
        var bytes = data.toByteArray();
        assertTrue(bytes.length >= 4000 * FRAMES, "XLL data of " + bytes.length + " bytes");
        var decoder = DtsUnits.decoder();
        for (var i = 0; i < FRAMES; i++) {
            var sync = i == 0 ? new XllStreamWriter.Sync(true, 1, 5, 20, 0) : XllStreamWriter.Sync.NONE;
            var unit = unit(stream.cores().get(i), Arrays.copyOfRange(bytes, 4000 * i, 4000 * (i + 1)), sync);
            var buffers = new ArrayList<AudioBuffer>();
            if (i == 4) {
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffers::add));
            } else {
                decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffers::add);
                assertEquals(1, buffers.size());
            }
        }
    }

    @Test
    @DisplayName("a buffer that does not hold the whole next XLL frame is malformed")
    void underflow() {
        var stream = smooth(source(200, true));
        var data = data(stream);
        var units = new ArrayList<byte[]>();
        units.add(unit(stream.cores().getFirst(), Arrays.copyOf(data, stream.starts()[1] - 1),
                new XllStreamWriter.Sync(true, 15, 5, 0, 0)));
        assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(units));
    }

    @Test
    @DisplayName("a sync offset past the component is malformed")
    void offsetPastComponent() {
        var stream = smooth(source(200, true));
        var chunk = Arrays.copyOf(data(stream), 100);
        var unit = unit(stream.cores().getFirst(), chunk, new XllStreamWriter.Sync(true, 15, 5, 1, 101));
        assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(List.of(unit)));
    }

    @Test
    @DisplayName("a delay longer than the buffer could wait is malformed")
    void delayPastBuffer() {
        var stream = smooth(source(200, true));
        var chunk = Arrays.copyOf(data(stream), 100);
        var unit = unit(stream.cores().getFirst(), chunk, new XllStreamWriter.Sync(true, 1, 32, 1 << 20, 0));
        assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(List.of(unit)));
    }

    @Test
    @DisplayName("damaged smoothed units fail only with media exceptions")
    void damaged() {
        var stream = smooth(source(200, true));
        var units = units(stream, 0, 15);
        var random = new SplittableRandom(9);
        for (var round = 0; round < 150; round++) {
            var decoder = DtsUnits.decoder();
            for (var unit : units) {
                var damaged = unit.clone();
                for (var flip = 0; flip < 3; flip++) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try {
                    decoder.decode(new Packet(0, damaged, 0, 0, 0, true), buffer -> {
                    });
                } catch (MediaException expected) {
                    // a damaged frame may be rejected, but only so
                }
            }
        }
    }

    /**
     * Prints where Smalt and ffmpeg agree frame by frame, to read ffmpeg's handling of the delay from a CI log.
     */
    private static void report(String name, int[] reference, int[] decoded, List<AudioBuffer> buffers, int delay) {
        var line = new StringBuilder("DTS-PBR " + name + " delay " + delay + " ffmpeg " + reference.length
                + " smalt " + decoded.length + ":");
        var size = FRAME * 6;
        for (var k = 0; k * size < reference.length; k++) {
            var match = -1;
            for (var j = 0; (j + 1) * size <= decoded.length && match < 0; j++) {
                if (Arrays.equals(reference, k * size, Math.min((k + 1) * size, reference.length), decoded, j * size,
                        (j + 1) * size)) {
                    match = j;
                }
            }
            line.append(" f").append(k).append("=").append(match);
        }
        line.append(" |");
        var position = 0;
        for (var buffer : buffers) {
            var count = buffer.frames() * buffer.channels();
            var differences = 0;
            for (var i = position; i < position + count; i++) {
                if (i >= reference.length || reference[i] != decoded[i]) {
                    differences++;
                }
            }
            line.append(' ').append(differences);
            position += count;
        }
        System.out.println(line);
    }

    /**
     * Builds the core frames and XLL frames of {@link #FRAMES} times, the XLL channels coded against the core or alone.
     */
    static Smoothed source(int amplitude, boolean core) {
        var random = new SplittableRandom(amplitude * 2L + (core ? 1 : 0));
        var cores = core ? new ArrayList<byte[]>() : null;
        var xll = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6);
            var set = DtsFixedCoreDifferentialTest.set(6, 0x3F, core ? 0 : 0x3F, null, random, amplitude, 1);
            set.rateCode = 12;
            xll.add(XllStreamWriter.component(List.of(set), FRAME));
            if (core) {
                cores.add(CoreStreamWriter.write(frame));
            }
        }
        return new Smoothed(cores, xll, 0, null, null);
    }

    /**
     * Cuts the XLL frames into equal chunks with the shortest delay that keeps each frame whole by its time.
     */
    static Smoothed smooth(Smoothed source) {
        for (var delay = 1; delay < FRAMES; delay++) {
            var starts = new int[FRAMES - delay + 1];
            for (var q = 0; q < FRAMES - delay; q++) {
                starts[q + 1] = starts[q] + source.xll().get(q + delay).length;
            }
            var total = starts[FRAMES - delay];
            var chunks = new int[FRAMES + 1];
            for (var i = 0; i <= FRAMES; i++) {
                chunks[i] = (int) ((long) i * total / FRAMES);
            }
            var feasible = true;
            for (var j = delay; j < FRAMES; j++) {
                feasible &= chunks[j + 1] >= starts[j - delay + 1];
            }
            if (feasible) {
                return new Smoothed(source.cores(), source.xll(), delay, chunks, starts);
            }
        }
        throw new AssertionError("no delay keeps the frames whole");
    }

    /**
     * {@return the XLL data of a smoothed stream: its XLL frames from the delay on}
     */
    private static byte[] data(Smoothed stream) {
        var out = new ByteArrayOutputStream();
        for (var q = stream.delay(); q < FRAMES; q++) {
            out.writeBytes(stream.xll().get(q));
        }
        return out.toByteArray();
    }

    /**
     * {@return the access units of a smoothed stream from a frame on, each extension substream frame with a sync point
     * at the first XLL frame that starts in its chunk and the delay that frame waits}
     */
    static List<byte[]> units(Smoothed stream, int first, int peakBuffer) {
        var data = data(stream);
        var units = new ArrayList<byte[]>();
        for (var i = first; i < FRAMES; i++) {
            var from = stream.chunks()[i];
            var to = stream.chunks()[i + 1];
            var sync = XllStreamWriter.Sync.NONE;
            for (var q = 0; q + 1 < stream.starts().length; q++) {
                if (stream.starts()[q] >= from && stream.starts()[q] < to) {
                    sync = new XllStreamWriter.Sync(true, peakBuffer, 5, q + stream.delay() - i,
                            stream.starts()[q] - from);
                    break;
                }
            }
            units.add(unit(stream.cores() == null ? null : stream.cores().get(i), Arrays.copyOfRange(data, from, to),
                    sync));
        }
        return units;
    }

    /**
     * {@return the access units of the stream with each XLL frame whole in the frame of its time, from a frame on}
     */
    private static List<byte[]> plain(Smoothed stream, int first) {
        return plain(stream, first, first, stream.delay());
    }

    /**
     * {@return the access units from a frame on of the stream with each XLL frame whole in the frame of its time from
     * {@code lossless} on and no XLL frame before}
     */
    private static List<byte[]> plain(Smoothed stream, int first, int from, int lossless) {
        var units = new ArrayList<byte[]>();
        for (var i = Math.max(first, from); i < FRAMES; i++) {
            var core = stream.cores() == null ? null : stream.cores().get(i);
            if (i < lossless) {
                if (core != null) {
                    units.add(core);
                }
                continue;
            }
            units.add(unit(core, stream.xll().get(i), XllStreamWriter.Sync.START));
        }
        return units;
    }

    /**
     * {@return an access unit of a core frame, or none, and an extension substream frame with an XLL component}
     */
    private static byte[] unit(byte[] core, byte[] component, XllStreamWriter.Sync sync) {
        var out = new ByteArrayOutputStream();
        if (core != null) {
            out.writeBytes(core);
        }
        out.writeBytes(XllStreamWriter.substream(component, 6, 0xF, 12, sync, List.of()));
        return out.toByteArray();
    }
}
