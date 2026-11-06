package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.container.subtitle.SupMuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the pictures of bitmap subtitle streams as Smalt and ffmpeg (black box) show them: a presentation graphic
 * stream Smalt writes, the DVB subtitles and DVD subpictures ffmpeg's own encoders make of it, and the DVB
 * subtitle behavior sample. ffmpeg composites each stream over a transparent canvas of the cue's size
 * ({@code overlay} of a {@code color=black@0} source, in RGBA); the distinct non-empty canvases it shows, in order, must match the cues Smalt decodes, rendered with
 * {@link BitmapCue#render()}.
 *
 * <p>Tolerance: two levels per channel ({@link Tolerance#maxAbs(double)}), for the fixed-point Y'CbCr conversions
 * and the alpha compositing of the two implementations. Cropped compositions are left out: ffmpeg shows the whole
 * object (black-box observation, ffmpeg 7.1), where the composition shows its cropping rectangle. ffmpeg's DVB
 * encoder stamps each clearing page one tick before the screen it ends, and some before the screen they follow in
 * the stream (black-box observation, ffmpeg 7.1), so its packets are spaced half a second apart in stream order and
 * remuxed by Smalt before both sides read them.
 */
@DisplayName("bitmap subtitles against ffmpeg")
class BitmapSubtitleDifferentialTest {
    private static final Tolerance TOLERANCE = Tolerance.maxAbs(2);
    private static final Rational TICKS = Rational.timeBaseOf(90_000);

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Writes a presentation graphic stream file: three compositions (two objects, a palette update, a forced object
     * on another palette) and their clears.
     */
    static byte[] sup(int width, int height) {
        int[][] palette = {{1, 235, 128, 128, 255}, {2, 16, 128, 128, 255}, {3, 81, 90, 240, 255},
                {4, 145, 54, 34, 160}, {5, 41, 240, 110, 96}, {6, 170, 166, 16, 255}};
        var first = BitmapStreams.indices(96, 24, (x, y) -> y < 3 ? 2 : (x / 6 + y / 3) % 6 + 1);
        var second = BitmapStreams.indices(64, 16, (x, y) -> (x + y) % 7 == 0 ? 0 : 3 + (x + 2 * y) % 3);
        var third = BitmapStreams.indices(120, 30, (x, y) -> x % 10 < 6 ? 1 : (y % 4 < 2 ? 4 : 0));
        var sets = new ArrayList<byte[]>();
        sets.add(join(BitmapStreams.pcs(width, height, 0, 0x80, false, 0, new int[]{0, 0, 100, height - 100, 0},
                        new int[]{1, 1, 400, 40, 0}),
                BitmapStreams.wds(new int[]{0, 100, height - 100, 96, 24}, new int[]{1, 400, 40, 64, 16}),
                BitmapStreams.pds(0, 0, palette), join(BitmapStreams.ods(0, 96, 24, first, 4096).toArray(new byte[0][])),
                join(BitmapStreams.ods(1, 64, 16, second, 4096).toArray(new byte[0][])), BitmapStreams.pgsEnd()));
        sets.add(join(BitmapStreams.pcs(width, height, 1, 0, true, 0, new int[]{0, 0, 100, height - 100, 0},
                new int[]{1, 1, 400, 40, 0}), BitmapStreams.pds(0, 1, new int[]{1, 210, 146, 16, 255},
                new int[]{3, 81, 240, 90, 200}), BitmapStreams.pgsEnd()));
        sets.add(join(BitmapStreams.pcs(width, height, 2, 0, false, 0), BitmapStreams.wds(), BitmapStreams.pgsEnd()));
        sets.add(join(BitmapStreams.pcs(width, height, 3, 0x80, false, 1, new int[]{2, 0, 300, 200, 0x40}),
                BitmapStreams.wds(new int[]{0, 300, 200, 120, 30}), BitmapStreams.pds(1, 0, palette),
                join(BitmapStreams.ods(2, 120, 30, third, 4096).toArray(new byte[0][])), BitmapStreams.pgsEnd()));
        sets.add(join(BitmapStreams.pcs(width, height, 4, 0, false, 1), BitmapStreams.wds(), BitmapStreams.pgsEnd()));
        var track = DataTrack.builder().id(1).codec(Codec.PGS).timeBase(TICKS).build();
        var sink = MediaSink.memory();
        try (var muxer = new SupMuxer(sink)) {
            muxer.addTrack(track);
            var times = new long[]{45_000, 90_000, 135_000, 180_000, 270_000};
            for (var index = 0; index < sets.size(); index++) {
                muxer.writePacket(new Packet(1, sets.get(index), times[index], times[index], Packet.UNKNOWN_DURATION,
                        true));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static Path write(String name, byte[] data) {
        var file = Scratch.directory("bitmap-differential").resolve(name);
        try {
            Files.write(file, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    /**
     * Returns the distinct canvases ffmpeg shows, without the empty ones.
     */
    static List<byte[]> ffmpegCanvases(Path input, List<String> inputOptions, int width, int height) {
        var arguments = new ArrayList<String>(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=black@0.0:s=" + width + "x" + height + ":r=4:d=6,format=rgba"));
        arguments.addAll(inputOptions);
        arguments.addAll(List.of("-i", input.toString(), "-filter_complex",
                "[0:v][1:s]overlay=format=auto:eof_action=pass,format=rgba", "-f", "rawvideo", "-pix_fmt", "rgba",
                "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var size = width * height * 4;
        var canvases = new ArrayList<byte[]>();
        for (var offset = 0; offset + size <= raw.length; offset += size) {
            var frame = Arrays.copyOfRange(raw, offset, offset + size);
            var empty = true;
            for (var index = 3; index < size && empty; index += 4) {
                empty = frame[index] == 0;
            }
            if (empty || !canvases.isEmpty() && Arrays.equals(canvases.getLast(), frame)) {
                continue;
            }
            canvases.add(frame);
        }
        return canvases;
    }

    static List<byte[]> smaltCanvases(Path input) {
        var cues = Subtitles.readBitmaps(MediaSource.of(input));
        var canvases = new ArrayList<byte[]>();
        for (var cue : cues) {
            var pixels = cue.render().pixels();
            if (!canvases.isEmpty() && Arrays.equals(canvases.getLast(), pixels)) {
                continue;
            }
            canvases.add(pixels);
        }
        return canvases;
    }

    /**
     * Rewrites a transport stream with its packets half a second apart, in stream order.
     */
    static Path retimed(Path input, String name) {
        var sink = MediaSink.memory();
        try (var demuxer = new TsDemuxer(MediaSource.of(input));
             var muxer = new TsMuxer(sink)) {
            var track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            var index = 0L;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var time = 45_000 * ++index;
                muxer.writePacket(packet.withTiming(time, time, Packet.UNKNOWN_DURATION));
            }
            muxer.finish();
        }
        return write(name, sink.toByteArray());
    }

    static void assertSameCanvases(String what, List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), what + ": distinct canvases");
        for (var index = 0; index < expected.size(); index++) {
            TOLERANCE.assertWithin(what + " canvas " + index, expected.get(index), actual.get(index));
        }
    }

    @ParameterizedTest(name = "{0} lines")
    @ValueSource(ints = {480, 1080})
    @DisplayName("PGS compositions, palettes and forced objects look the same, BT.601 and BT.709")
    void pgs(int height) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var width = height == 480 ? 720 : 1920;
        var file = write("pgs-" + height + ".sup", sup(width, height));
        var smalt = smaltCanvases(file);
        assertEquals(3, smalt.size());
        assertSameCanvases("pgs", ffmpegCanvases(file, List.of(), width, height), smalt);
    }

    @Test
    @DisplayName("DVB subtitles of ffmpeg's encoder look the same")
    void dvb() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = write("dvb-source.sup", sup(720, 576));
        var encoded = Scratch.directory("bitmap-differential").resolve("dvb-ffmpeg.ts");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-c:s", "dvbsub", encoded.toString()));
        var ts = retimed(encoded, "dvb.ts");
        var smalt = smaltCanvases(ts);
        assertEquals(3, smalt.size());
        assertSameCanvases("dvb", ffmpegCanvases(ts, List.of(), 720, 576), smalt);
    }

    @Test
    @DisplayName("DVB subtitles of 2-bit and 4-bit regions on default and defined CLUTs look the same")
    void dvbSample() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Path.of("src", "test", "resources", "samples", "ts", "dvb-subtitles.ts");
        var smalt = smaltCanvases(file);
        assertEquals(2, smalt.size());
        // without it, ffmpeg replaces the default CLUT with one it guesses from the pixels (ffmpeg 7.1)
        assertSameCanvases("dvb sample", ffmpegCanvases(file, List.of("-compute_clut", "0"), 720, 576), smalt);
    }

    @Test
    @DisplayName("DVD subpictures of ffmpeg's encoder look the same, in Matroska")
    void dvd() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = write("dvd-source.sup", sup(720, 480));
        var output = Scratch.directory("bitmap-differential").resolve("dvd.mkv");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-c:s", "dvdsub", output.toString()));
        var smalt = smaltCanvases(output);
        assertEquals(3, smalt.size());
        assertSameCanvases("dvd", ffmpegCanvases(output, List.of(), 720, 480), smalt);
    }
}
