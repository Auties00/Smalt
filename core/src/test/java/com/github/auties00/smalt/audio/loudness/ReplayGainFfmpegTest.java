package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks ReplayGain tagging against ffmpeg used as a black box: the gains Smalt writes equal -18 LUFS
 * minus the integrated loudness ffmpeg's {@code ebur128} filter measures on each track and on the
 * concatenated album, and ffprobe reads the written {@code REPLAYGAIN_*} fields back from WAV
 * ({@code id3 } chunk) and Matroska ({@code SimpleTag}) files. The inputs are two synthesized programs
 * of different loudness and dynamics: a chord whose level steps, and bursts of shaped noise between pauses.
 */
@DisplayName("ReplayGain against ffmpeg")
class ReplayGainFfmpegTest {
    private static final Pattern EBUR128_I = Pattern.compile("I:\\s+(-?[0-9.]+) LUFS");

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static double ffmpegIntegrated(List<String> input) {
        var arguments = new java.util.ArrayList<>(List.of("-hide_banner", "-nostats"));
        arguments.addAll(input);
        arguments.addAll(List.of("-f", "null", "-"));
        var output = Oracle.runMerged(Oracle.Tool.FFMPEG, TIMEOUT, arguments);
        var matcher = EBUR128_I.matcher(output.substring(output.lastIndexOf("Summary:")));
        assertTrue(matcher.find(), output);
        return Double.parseDouble(matcher.group(1));
    }

    private static String probedTag(Path file, String key) {
        var output = Oracle.runMerged(Oracle.Tool.FFPROBE, TIMEOUT, List.of("-v", "error", "-show_entries",
                "format_tags", "-of", "default=noprint_wrappers=1", file.toString()));
        for (var line : output.split("\\R")) {
            var separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).toUpperCase(Locale.ROOT).equals("TAG:" + key)) {
                return line.substring(separator + 1).strip();
            }
        }
        throw new AssertionError("ffprobe shows no " + key + " in " + output);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wav", "mka"})
    @DisplayName("writes gains that match ffmpeg's loudness and that ffprobe reads back")
    void album(String extension, @TempDir Path directory) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assume(Oracle.Tool.FFPROBE);
        var files = new java.util.ArrayList<Path>();
        for (var name : List.of("chords", "bursts")) {
            var wav = Files.write(directory.resolve(name + ".wav"), Signals.wav(name.equals("chords") ? chords() : bursts(),
                    48_000));
            if (extension.equals("mka")) {
                var mka = directory.resolve(name + ".mka");
                var sink = MediaSink.memory();
                Transcoder.transcode(MediaSource.of(Files.readAllBytes(wav)), sink, TranscodeOptions.of(OutputContainer.MATROSKA)
                        .toBuilder().audio(AudioTranscodeOptions.encode(Codec.PCM_F32LE)).build());
                Files.write(mka, sink.toByteArray());
                files.add(mka);
            } else {
                files.add(wav);
            }
        }
        var gains = Loudness.writeReplayGain(files);
        var album = ffmpegIntegrated(List.of("-i", files.get(0).toString(), "-i", files.get(1).toString(),
                "-filter_complex", "[0:a][1:a]concat=n=2:v=0:a=1,ebur128=framelog=quiet[out]", "-map", "[out]"));
        for (var index = 0; index < files.size(); index++) {
            var file = files.get(index);
            var track = ffmpegIntegrated(List.of("-i", file.toString(), "-af", "ebur128=framelog=quiet"));
            var gain = gains.get(index);
            System.out.printf("%s: smalt track %.2f dB album %.2f dB | ffmpeg track %.2f dB album %.2f dB%n",
                    file.getFileName(), gain.trackGainDb(), gain.albumGainDb(), -18 - track, -18 - album);
            assertEquals(-18 - track, gain.trackGainDb(), 0.1, "track gain against ebur128");
            assertEquals(-18 - album, gain.albumGainDb(), 0.15, "album gain against ebur128 of the concatenation");
            // Matroska names the album values REPLAYGAIN_GAIN/PEAK at the album target and the track values the
            // same at the TRACK target, which ffprobe prefixes with "TRACK/".
            var matroska = extension.equals("mka");
            var trackGainKey = matroska ? "TRACK/REPLAYGAIN_GAIN" : "REPLAYGAIN_TRACK_GAIN";
            var albumGainKey = matroska ? "REPLAYGAIN_GAIN" : "REPLAYGAIN_ALBUM_GAIN";
            var trackPeakKey = matroska ? "TRACK/REPLAYGAIN_PEAK" : "REPLAYGAIN_TRACK_PEAK";
            assertEquals(String.format(Locale.ROOT, "%.2f dB", gain.trackGainDb()), probedTag(file, trackGainKey));
            assertEquals(String.format(Locale.ROOT, "%.2f dB", gain.albumGainDb()), probedTag(file, albumGainKey));
            assertEquals(gain.trackPeak(), Double.parseDouble(probedTag(file, trackPeakKey)), 1e-6);
            var read = ReplayGain.fromTags(TagEditor.read(file));
            assertEquals(gain.trackGainDb(), read.trackGainDb(), 0.005, "Smalt reads the track gain back");
            assertEquals(gain.albumGainDb(), read.albumGainDb(), 0.005, "Smalt reads the album gain back");
            assertEquals(gain.trackPeak(), read.trackPeak(), 1e-6, "Smalt reads the track peak back");
        }
    }
    /** Builds 20 s of a stereo three-note chord whose level steps every 4 s. */
    private static float[][] chords() {
        var rate = 48_000;
        var planes = new float[2][20 * rate];
        var levels = new double[]{-18, -26, -12, -30, -20};
        for (var i = 0; i < planes[0].length; i++) {
            var amplitude = Math.pow(10, levels[i / (4 * rate)] / 20) / 3;
            var t = (double) i / rate;
            var left = Math.sin(2 * Math.PI * 220 * t) + Math.sin(2 * Math.PI * 277.2 * t) + Math.sin(2 * Math.PI * 329.6 * t);
            var right = Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 1661.2 * t) + Math.sin(2 * Math.PI * 3951 * t);
            planes[0][i] = (float) (amplitude * left);
            planes[1][i] = (float) (amplitude * right);
        }
        return planes;
    }

    /** Builds 20 s of stereo bursts of low-pass noise with irregular levels, separated by pauses. */
    private static float[][] bursts() {
        var rate = 48_000;
        var planes = new float[2][20 * rate];
        var random = new java.util.Random(5);
        var position = 0;
        var low = new double[2];
        while (position < planes[0].length) {
            var burst = (int) (rate * (0.1 + random.nextDouble() * 0.5));
            var level = Math.pow(10, (-24 + random.nextGaussian() * 6) / 20);
            for (var index = 0; index < burst && position + index < planes[0].length; index++) {
                var envelope = Math.sin(Math.PI * index / burst);
                for (var channel = 0; channel < 2; channel++) {
                    low[channel] += 0.2 * (random.nextGaussian() - low[channel]);
                    planes[channel][position + index] = (float) (level * 3 * low[channel] * envelope);
                }
            }
            position += burst + (int) (rate * random.nextDouble() * 0.4);
        }
        return planes;
    }
}
