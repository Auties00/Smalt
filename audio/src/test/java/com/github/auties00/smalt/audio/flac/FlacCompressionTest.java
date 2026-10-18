package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the size of the frames {@link FlacEncoder} produces with those of the reference
 * {@code flac} tool at the same compression level, on real music and speech; skipped unless
 * ffmpeg (which prepares the clips) and {@code flac} are installed. Each case prints both sizes.
 */
class FlacCompressionTest {
    /** The tolerated size excess over the reference encoder. */
    private static final double TOLERANCE = 1.03;

    /** One clip: where it comes from and how ffmpeg prepares it. */
    record Clip(String name, Supplier<Path> source, int seconds, int rate, int channels, int bits) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        var clips = List.of(
                new Clip("music 44.1 kHz 16-bit", () -> FlacConformanceCorpus.vector("subset", "25"), 25, 44_100, 2, 16),
                new Clip("music 48 kHz 16-bit", () -> FlacConformanceCorpus.vector("subset", "53"), 60, 48_000, 2, 16),
                new Clip("music 96 kHz 24-bit", () -> FlacConformanceCorpus.vector("subset", "30"), 8, 96_000, 2, 24),
                new Clip("speech 22.05 kHz 16-bit", () -> Conformance.fetch("librivox-art_of_war_03-04_64kb.mp3",
                        "https://archive.org/download/art_of_war_librivox/art_of_war_03-04_sun_tzu_64kb.mp3",
                        "57e879fe7822578fd22010f1280185799a119a3482e025d9a71f49878ff45ba7"), 120, 22_050, 1, 16));
        var cases = new ArrayList<Arguments>();
        for (var clip : clips) {
            for (var level : new int[]{0, 5, 8}) {
                cases.add(Arguments.of(clip, level));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, level {1}")
    @MethodSource("cases")
    @DisplayName("frames stay within 3% of flac's at the same level")
    void compression(Clip clip, int level, @TempDir Path directory) throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assume(Oracle.Tool.FLAC);
        var wav = directory.resolve("clip.wav");
        var codec = clip.bits() == 16 ? "pcm_s16le" : "pcm_s24le";
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-t", Integer.toString(clip.seconds()),
                "-i", clip.source().get().toString(), "-ac", Integer.toString(clip.channels()), "-ar", Integer.toString(clip.rate()),
                "-c:a", codec, "-map_metadata", "-1", "-y", wav.toString()));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", wav.toString(),
                "-f", "s32le", "-"));
        var interleaved = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(interleaved);
        var frames = interleaved.length / clip.channels();
        var samples = new int[clip.channels()][frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < clip.channels(); c++) {
                samples[c][i] = interleaved[i * clip.channels() + c] >> (32 - clip.bits());
            }
        }
        var options = FlacEncoderOptions.ofCompressionLevel(level).toBuilder().bitsPerSample(clip.bits())
                .format(clip.rate(), ChannelLayout.defaultFor(clip.channels())).noSeekTable().padding(0).build();
        var ours = FlacEncoding.encode(options, samples).bytes();
        var reference = directory.resolve("reference.flac");
        Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(5), List.of("-" + level, "-s", "-f", "--no-padding", "--no-seektable",
                "-o", reference.toString(), wav.toString()));
        var theirs = frameBytes(Files.readAllBytes(reference));
        var ratio = (double) ours / theirs;
        System.out.printf("%-26s level %d: smalt %,d bytes, flac %,d bytes, ratio %.4f%n", clip, level, ours, theirs, ratio);
        assertTrue(ratio <= TOLERANCE, clip + " level " + level + ": " + ours + " bytes against flac's " + theirs);
    }

    /** Returns the size of a native FLAC file without its marker and metadata blocks. */
    static long frameBytes(byte[] file) {
        var offset = 4;
        while (true) {
            var last = (file[offset] & 0x80) != 0;
            var length = (file[offset + 1] & 0xFF) << 16 | (file[offset + 2] & 0xFF) << 8 | (file[offset + 3] & 0xFF);
            offset += 4 + length;
            if (last) {
                return file.length - offset;
            }
        }
    }
}
