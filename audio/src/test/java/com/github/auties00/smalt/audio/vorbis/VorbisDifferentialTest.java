package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Encodes synthetic signals with libvorbis (through ffmpeg) over the whole encoder range and
 * compares our decode with ffmpeg's float Vorbis decoder: identical sample counts after the Ogg
 * granule trimming, and an SNR of at least 90 dB. The signal mixes frequency- and
 * amplitude-modulated tones with noise so every quality level codes a dense residue.
 *
 * <p>ffmpeg's Ogg demuxer does not apply end trimming when every audio packet sits on one page
 * (and over-trims when the final granule equals the decoded length), so for such very short
 * streams the test checks ffmpeg's output against our untrimmed decode and our granule trimming
 * against the input length.
 */
class VorbisDifferentialTest {
    /** Both decoders compute in float, so they differ only by rounding: at least 90 dB of SNR over the stream. */
    private static final Tolerance TOLERANCE = Tolerance.snr(90);

    /** ffmpeg channel layout names by channel count, in the order the channels are generated. */
    private static final String[] LAYOUTS = {null, "mono", "stereo", "3.0", "quad", "5.0", "5.1", "6.1", "7.1"};

    static List<String> music(int channels, int rate, double seconds) {
        var expressions = new ArrayList<String>();
        for (var c = 0; c < channels; c++) {
            expressions.add("0.25*sin(2*PI*" + (200 + 97 * c) + "*t+3*sin(2*PI*0.7*t))"
                    + "+0.2*sin(2*PI*" + (3000 + 1234 * c) + "*t)*sin(2*PI*1.3*t)"
                    + "+0.05*(2*random(" + c + ")-1)");
        }
        var layout = channels < LAYOUTS.length ? ":c=" + LAYOUTS[channels] : "";
        return List.of("-f", "lavfi", "-i", "aevalsrc=exprs='" + String.join("|", expressions) + "'" + layout
                + ":s=" + rate + ":d=" + seconds);
    }

    static void assertMatchesFfmpeg(Path file) throws IOException {
        var links = OggVorbis.read(Files.readAllBytes(file));
        assertEquals(1, links.size());
        var link = links.getFirst();
        var decoded = OggVorbis.decode(link);
        assertEquals(0, decoded.rejected(), "rejected packets");
        var reference = Oracle.decodeAudio(file);
        float[] ours;
        if (link.singleAudioPage()) {
            ours = decoded.untrimmedInterleaved();
            assertTrue(reference.length <= ours.length, "ffmpeg decoded " + reference.length + " of " + ours.length);
            ours = java.util.Arrays.copyOf(ours, reference.length);
        } else {
            ours = decoded.interleaved();
            assertEquals(reference.length, ours.length, "sample count");
        }
        var energy = 0.0;
        for (var value : reference) {
            energy += (double) value * value;
        }
        if (energy == 0) {
            // silence has no SNR; it compares exactly, -0.0 (ffmpeg's aarch64 build) equal to 0.0
            Tolerance.exact().assertPcm("silence", reference, ours);
            return;
        }
        TOLERANCE.assertPcm(file.getFileName().toString(), reference, ours);
    }

    @Nested
    @DisplayName("quality and bitrate")
    class Rates {
        @ParameterizedTest(name = "-q:a {0}")
        @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("decodes every VBR quality level of a stereo 44.1 kHz stream")
        void quality(int quality) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(2, 44100, 3),
                    List.of("-c:a", "libvorbis", "-q:a", Integer.toString(quality))));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"48k", "64k", "96k", "128k", "192k", "256k", "320k"})
        @DisplayName("decodes average-bitrate streams")
        void bitrate(String bitrate) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(2, 44100, 3), List.of("-c:a", "libvorbis", "-b:a", bitrate)));
        }

        @ParameterizedTest(name = "{0} (min {1}, max {2})")
        @CsvSource({"64k, 64k, 64k", "96k, 80k, 112k", "48k, 32k, 48k", "160k, 160k, 160k"})
        @DisplayName("decodes managed-bitrate streams whose packets libvorbis truncates mid-residue")
        void managed(String bitrate, String minimum, String maximum) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(2, 44100, 3), List.of("-c:a", "libvorbis",
                    "-b:a", bitrate, "-minrate", minimum, "-maxrate", maximum)));
        }
    }

    @Nested
    @DisplayName("channels and sample rates")
    class Formats {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 10})
        @DisplayName("decodes every channel count in WAVE order, beyond eight in stream order")
        void channels(int channels) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(channels, 48000, 2), List.of("-c:a", "libvorbis", "-q:a", "4")));
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000})
        @DisplayName("decodes every common sample rate")
        void rate(int rate) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(2, rate, 2), List.of("-c:a", "libvorbis", "-q:a", "3")));
        }

        @ParameterizedTest(name = "{0} Hz mono")
        @ValueSource(ints = {8000, 22050, 96000})
        @DisplayName("decodes mono at the rate extremes")
        void monoRate(int rate) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(1, rate, 2), List.of("-c:a", "libvorbis", "-q:a", "7")));
        }
    }

    @Nested
    @DisplayName("edge signals")
    class Edges {
        @ParameterizedTest(name = "{0} samples")
        @ValueSource(ints = {1, 2, 63, 100, 255, 1000, 2047, 2048, 2049, 5000})
        @DisplayName("decodes streams shorter than or about one block and trims them to the input length")
        void veryShort(int samples) throws IOException {
            var file = Fixtures.ffmpeg("ogg", music(2, 44100, 1),
                    List.of("-af", "atrim=end_sample=" + samples, "-c:a", "libvorbis", "-q:a", "5"));
            assertMatchesFfmpeg(file);
            assertEquals(samples, OggVorbis.decode(OggVorbis.read(Files.readAllBytes(file)).getFirst()).frames());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"16k", "24k", "32k"})
        @DisplayName("decodes low-bitrate mono at 22.05 kHz")
        void lowBitrate(String bitrate) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", music(1, 22050, 2), List.of("-c:a", "libvorbis", "-b:a", bitrate)));
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 6})
        @DisplayName("decodes digital silence to exact zeros")
        void silence(int channels) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i",
                    "anullsrc=r=44100:cl=" + LAYOUTS[channels]), List.of("-t", "2", "-c:a", "libvorbis", "-q:a", "5")));
        }

        @ParameterizedTest(name = "-q:a {0}")
        @ValueSource(ints = {0, 5, 10})
        @DisplayName("decodes full-scale white noise")
        void noise(int quality) throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i",
                    "anoisesrc=c=white:a=1:r=44100:d=2:seed=3"), List.of("-ac", "2", "-c:a", "libvorbis", "-q:a",
                    Integer.toString(quality))));
        }

        @Test
        @DisplayName("decodes a full-scale square wave whose ringing exceeds [-1, 1]")
        void clipping() throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i",
                    "aevalsrc=exprs='if(lt(mod(t*220,1),0.5),1,-1)':s=44100:d=2"), List.of("-c:a", "libvorbis", "-q:a", "6")));
        }

        @Test
        @DisplayName("decodes transients that alternate short and long blocks")
        void transients() throws IOException {
            assertMatchesFfmpeg(Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i",
                    "aevalsrc=exprs='if(lt(mod(t*8,1),0.02),0.9*(2*random(0)-1),0.01*sin(2*PI*440*t))':s=44100:d=3"),
                    List.of("-c:a", "libvorbis", "-q:a", "4")));
        }
    }
}
