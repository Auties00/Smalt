package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the audio of Matroska files with an ordered edition, whose tracks have a codec delay (the Opus pre-skip, an
 * AAC {@code CodecDelay}), along their virtual timeline, and compares every part with the samples a decode of the
 * stored file plays between the part's bounds: AAC sample for sample, Opus, whose decoder state after a jump only
 * converges, at the same place to within no sample and close in level. The files are written by Smalt's muxer and by
 * mkvmerge (black box) from inputs whose timestamps count samples (Ogg Opus, and AAC in Matroska with a timestamp
 * scale of one sample), since a cut follows the block timestamps. Every bound is a whole number of samples.
 */
@DisplayName("Matroska ordered chapters with a codec delay through the audio decoder")
class MatroskaOrderedChaptersDecodeTest {
    record Span(long startMs, long endMs) {
    }

    private static final Span[] OPUS_SPANS = {new Span(0, 500), new Span(1200, 1700), new Span(300, 800),
            new Span(2000, 2340)};

    private static final Span[] AAC_SPANS = {new Span(1000, 1600), new Span(200, 700), new Span(2000, 2500),
            new Span(0, 300)};

    private static Path opus() {
        // tones at unrelated frequencies and a sweep, which Opus codes as waveforms that its decoder state converges
        // to after a jump (noise-filled bands do not), and whose correlation peaks only where they align
        // in Ogg, whose granule positions count samples (ffmpeg's Matroska muxer rounds the Opus block timestamps)
        return Fixtures.ffmpeg("opus", List.of("-f", "lavfi", "-i",
                        "aevalsrc=0.2*sin(2*PI*440*t)+0.15*sin(2*PI*1234.5*t)+0.15*sin(2*PI*(300+200*t)*t):s=48000:d=3"),
                List.of("-c:a", "libopus", "-b:a", "128k"));
    }

    private static Path aac() {
        // 32 kHz, whose sample period is a whole 31250 ns, so that a Matroska timestamp scale can count samples;
        // perceptual noise substitution draws from a generator whose state a jump resets
        return Fixtures.ffmpeg("m4a", List.of("-f", "lavfi", "-i", "anoisesrc=d=3:c=pink:r=32000:a=0.3:s=7"),
                List.of("-c:a", "aac", "-b:a", "96k", "-aac_pns", "0"));
    }

    private static MediaTags edition(Span... spans) {
        var chapters = new ArrayList<Chapter>();
        for (var index = 0; index < spans.length; index++) {
            chapters.add(Chapter.of(spans[index].startMs() * 1000, spans[index].endMs() * 1000, "part " + index));
        }
        return MediaTags.builder().editions(List.of(new ChapterEdition(chapters, true, true, List.of()))).build();
    }

    private static byte[] remuxed(Path file, long timestampScaleNanos, Span... spans) {
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(Fixtures.read(file)); var demuxer = Containers.openDemuxer(source);
             var muxer = new MatroskaMuxer(sink, MatroskaMuxerOptions.builder()
                     .timestampScaleNanos(timestampScaleNanos).build())) {
            var track = audioTrack(demuxer);
            muxer.addTrack(track);
            if (spans.length > 0) {
                muxer.setTags(edition(spans));
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static String chapterXml(Span... spans) {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Chapters><EditionEntry>"
                + "<EditionUID>1</EditionUID><EditionFlagOrdered>1</EditionFlagOrdered>"
                + "<EditionFlagDefault>1</EditionFlagDefault>\n");
        for (var index = 0; index < spans.length; index++) {
            xml.append("<ChapterAtom><ChapterUID>").append(index + 1).append("</ChapterUID><ChapterTimeStart>")
                    .append(time(spans[index].startMs())).append("</ChapterTimeStart><ChapterTimeEnd>")
                    .append(time(spans[index].endMs())).append("</ChapterTimeEnd><ChapterDisplay><ChapterString>part ")
                    .append(index).append("</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay>")
                    .append("</ChapterAtom>\n");
        }
        return xml.append("</EditionEntry></Chapters>\n").toString();
    }

    private static String time(long ms) {
        return String.format("%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static byte[] mkvmerge(String name, Path file, List<String> options, Span... spans) {
        // the Opus pre-skip and the AAC CodecDelay of the inputs become the CodecDelay of the output
        Oracle.assume("mkvmerge");
        try {
            var directory = Scratch.directory("matroska-ordered-delay-" + name);
            var xml = directory.resolve("chapters.xml");
            Files.writeString(xml, chapterXml(spans));
            var output = directory.resolve(name + ".mkv");
            var arguments = new ArrayList<>(List.of("-o", output.toString(), "--chapters", xml.toString()));
            arguments.addAll(options);
            arguments.add(file.toString());
            Oracle.run("mkvmerge", Duration.ofMinutes(2), null, arguments);
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static AudioTrack audioTrack(Demuxer demuxer) {
        return demuxer.tracks().stream().filter(AudioTrack.class::isInstance).map(AudioTrack.class::cast)
                .findFirst().orElseThrow();
    }

    record Decode(float[] samples, int rate, long firstPts, Rational timeBase, long delay) {
    }

    private static Decode decode(byte[] file, boolean timeline) {
        var options = DemuxerOptions.builder().virtualTimeline(timeline).build();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source, options)) {
            var track = audioTrack(demuxer);
            var samples = new float[0];
            var length = 0;
            var first = Packet.NO_TIMESTAMP;
            var block = new float[1][4096];
            try (var decoder = AudioStreamDecoder.create(track)) {
                for (var packet = demuxer.read(); ; packet = demuxer.read()) {
                    if (packet == null) {
                        decoder.finish();
                    } else if (packet.trackId() != track.id()) {
                        continue;
                    } else {
                        if (first == Packet.NO_TIMESTAMP) {
                            first = packet.pts();
                        }
                        decoder.decode(packet);
                    }
                    for (var read = decoder.read(block, 0, 4096); read > 0; read = decoder.read(block, 0, 4096)) {
                        if (length + read > samples.length) {
                            samples = Arrays.copyOf(samples, Math.max(2 * samples.length, length + read));
                        }
                        System.arraycopy(block[0], 0, samples, length, read);
                        length += read;
                    }
                    if (packet == null) {
                        break;
                    }
                }
            }
            var delay = track.codec() == Codec.OPUS ? opusPreSkip(track) : track.skipSamples();
            return new Decode(Arrays.copyOf(samples, length), track.sampleRate(), first, track.timeBase(), delay);
        }
    }

    // the audio packets of the timeline that do not present whole, for a failure message
    private static String cuts(byte[] file) {
        var summary = new StringBuilder();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = audioTrack(demuxer);
            var count = 0;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() != track.id()) {
                    continue;
                }
                count++;
                var side = packet.sideData();
                if (side.discardStart() > 0 || side.discardEnd() > 0 || side.newCodecConfig().isPresent()
                        || packet.flags().contains(com.github.auties00.smalt.track.PacketFlag.DISCARD)) {
                    summary.append(' ').append(packet.pts()).append('/').append(packet.duration()).append('/')
                            .append(side.discardStart()).append('/').append(side.discardEnd())
                            .append(packet.flags().contains(com.github.auties00.smalt.track.PacketFlag.DISCARD)
                                    ? "d" : "");
                }
            }
            summary.insert(0, count + " packets, duration " + track.duration() + ":");
        }
        var options = DemuxerOptions.builder().virtualTimeline(false).build();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source, options)) {
            var track = audioTrack(demuxer);
            summary.append("; stored:");
            var shown = 0;
            for (var packet = demuxer.read(); packet != null && shown < 12; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    shown++;
                    summary.append(' ').append(packet.pts()).append('/').append(packet.duration())
                            .append(packet.flags());
                }
            }
        }
        return summary.toString();
    }

    private static long opusPreSkip(AudioTrack track) {
        var head = track.codecConfig().orElseThrow();
        return (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
    }

    private static float[][] expected(Decode stored, Span... spans) {
        // a block plays its first sample the codec delay before its timestamp, and the decoder drops the delay from
        // the start of the stream, so the first sample it outputs plays at the first block's timestamp
        var firstPlayed = stored.timeBase().rescale(stored.firstPts(), Rational.timeBaseOf(stored.rate()));
        var parts = new float[spans.length][];
        for (var index = 0; index < spans.length; index++) {
            var from = (int) (spans[index].startMs() * stored.rate() / 1000 - firstPlayed);
            var to = (int) (spans[index].endMs() * stored.rate() / 1000 - firstPlayed);
            parts[index] = Arrays.copyOfRange(stored.samples(), from, to);
        }
        return parts;
    }

    private static float[][] split(float[] samples, float[][] expected) {
        var parts = new float[expected.length][];
        var position = 0;
        for (var index = 0; index < expected.length; index++) {
            parts[index] = Arrays.copyOfRange(samples, position, Math.min(samples.length,
                    position + expected[index].length));
            position += expected[index].length;
        }
        return parts;
    }

    private static void assertExact(byte[] file, Span... spans) {
        var stored = decode(file, false);
        assertTrue(stored.delay() > 0, () -> "the fixture has a codec delay: " + cuts(file));
        var expected = expected(stored, spans);
        var timeline = decode(file, true);
        var total = Arrays.stream(expected).mapToInt(part -> part.length).sum();
        assertEquals(total, timeline.samples().length, () -> cuts(file));
        var actual = split(timeline.samples(), expected);
        for (var index = 0; index < spans.length; index++) {
            assertArrayEquals(expected[index], actual[index], "part " + index + " " + spans[index]);
        }
    }

    private static int lag(float[] actual, float[] expected, int skip, int range) {
        var best = 0;
        var bestScore = Double.NEGATIVE_INFINITY;
        for (var shift = -range; shift <= range; shift++) {
            var dot = 0.0;
            var energyA = 0.0;
            var energyE = 0.0;
            for (var index = skip + range; index < actual.length - range; index++) {
                var a = actual[index];
                var e = expected[index + shift];
                dot += a * e;
                energyA += a * a;
                energyE += e * e;
            }
            var score = dot / Math.sqrt(energyA * energyE + 1e-30);
            if (score > bestScore) {
                bestScore = score;
                best = shift;
            }
        }
        return best;
    }

    private static double snr(float[] actual, float[] expected, int skip) {
        var signal = 0.0;
        var noise = 0.0;
        for (var index = skip; index < actual.length; index++) {
            signal += expected[index] * expected[index];
            var difference = actual[index] - expected[index];
            noise += difference * difference;
        }
        return 10 * Math.log10(signal / Math.max(noise, 1e-30));
    }

    private static void assertAligned(byte[] file, Span... spans) {
        var stored = decode(file, false);
        assertEquals(312, stored.delay(), "the Opus pre-skip of the fixture");
        var expected = expected(stored, spans);
        var timeline = decode(file, true);
        var total = Arrays.stream(expected).mapToInt(part -> part.length).sum();
        assertEquals(total, timeline.samples().length, () -> cuts(file));
        var actual = split(timeline.samples(), expected);
        var skip = stored.rate() / 50;
        for (var index = 0; index < spans.length; index++) {
            var label = "part " + index + " " + spans[index];
            if (spans[index].startMs() == 0 && index == 0) {
                // the first part decodes from the start of the stream, as the stored file does
                assertArrayEquals(expected[index], actual[index], label);
                continue;
            }
            var lag = lag(actual[index], expected[index], skip, 2 * (int) stored.delay());
            var quality = snr(actual[index], expected[index], skip);
            assertEquals(0, lag, label + " (" + quality + " dB at lag 0)" + cuts(file));
            assertTrue(quality > 30, label + ": " + quality + " dB" + cuts(file));
        }
    }

    @Test
    @DisplayName("plays each AAC part at its place, sample for sample, in a file Smalt writes")
    void aacSmalt() {
        assertExact(remuxed(aac(), 31_250, AAC_SPANS), AAC_SPANS);
    }

    @Test
    @DisplayName("plays each Opus part at its place, in a file Smalt writes")
    void opusSmalt() {
        assertAligned(remuxed(opus(), 1_000_000, OPUS_SPANS), OPUS_SPANS);
    }

    @Test
    @DisplayName("plays each AAC part at its place, sample for sample, in a file mkvmerge writes")
    void aacMkvmerge() {
        // mkvmerge copies the CodecDelay of a Matroska input, which it does not derive from an MP4 edit list
        try {
            var input = Scratch.directory("matroska-ordered-delay-aac-input").resolve("aac.mkv");
            Files.write(input, remuxed(aac(), 31_250));
            assertExact(mkvmerge("aac", input, List.of("--timestamp-scale", "31250", "--no-chapters"), AAC_SPANS),
                    AAC_SPANS);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    @DisplayName("plays each Opus part at its place, in a file mkvmerge writes")
    void opusMkvmerge() {
        assertAligned(mkvmerge("opus", opus(), List.of(), OPUS_SPANS), OPUS_SPANS);
    }
}
