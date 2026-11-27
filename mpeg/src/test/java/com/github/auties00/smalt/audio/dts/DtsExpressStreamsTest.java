package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.StreamingVectors;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the DTS Express ({@code dtse}, DTS-HD LBR) audio of the DASH-IF multichannel audio test vectors (the
 * "Paint" content: 2.0 at 128 kbit/s, 5.1 at 192 and 384 kbit/s, fragmented MP4 segments of a production encoder).
 * The MPD of each test case names its audio representations, their channel configuration and their segment
 * timeline; each representation, its initialization segment followed by its media segments in the timeline's order,
 * must demux as DTS at 48 kHz with the MPD's channel count and decode as ffmpeg's DTS Express decoder decodes it,
 * channel by channel, to the level of the LFE interpolation filter (as {@code LbrDifferentialTest} measures it).
 *
 * <p>The encoder writes every {@code tfhd} with an explicit base data offset, the offset of the media data in the
 * segment's own file (140: {@code styp} 28 + {@code moof} 104 + the {@code mdat} header), so in the joined file that
 * offset counts from the start of each segment. ffmpeg reads it as an absolute offset and fails the joined file;
 * its reference is the decode of the raw DTS stream of the media data, the {@code mdat} payloads of the segments in
 * order, which the demuxed packets must also equal byte for byte.
 */
@DisplayName("DTS Express streams of the DASH-IF multichannel audio vectors")
final class DtsExpressStreamsTest {
    private static final Pattern AUDIO = Pattern.compile("<AdaptationSet[^>]*codecs=\"dtse\"(.*?)</AdaptationSet>",
            Pattern.DOTALL);

    private static final Pattern CHANNELS = Pattern.compile("AudioChannelConfiguration[^>]*value=\"(\\d+)\"");

    private static final Pattern TIMELINE = Pattern.compile("<S t=\"0\" d=\"(\\d+)\" r=\"(\\d+)\"/>");

    private static final Pattern REPRESENTATION = Pattern.compile("<Representation id=\"([^\"]+)\"");

    private static final Pattern PRESENTATION = Pattern.compile(
            "mediaPresentationDuration=\"PT(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?\"");

    /** ffmpeg's order of the speakers of a DTS stream, side and back surrounds alike. */
    private static int ffmpegRank(Speaker speaker) {
        return switch (speaker) {
            case FRONT_LEFT -> 0;
            case FRONT_RIGHT -> 1;
            case FRONT_CENTER -> 2;
            case LOW_FREQUENCY -> 3;
            case BACK_LEFT, SIDE_LEFT -> 4;
            case BACK_RIGHT, SIDE_RIGHT -> 5;
            default -> throw new AssertionError("unexpected speaker " + speaker);
        };
    }

    @ParameterizedTest(name = "test case {0}")
    @ValueSource(strings = {"A", "B", "D"})
    @DisplayName("every audio representation of the MPD decodes as ffmpeg decodes it")
    void representations(String testCase) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mpd = Files.readString(Conformance.vector("dashif-mca-dts-Paint_dtse_test" + testCase + ".mpd"),
                StandardCharsets.UTF_8);
        var audio = AUDIO.matcher(mpd);
        assertTrue(audio.find(), "the MPD has a dtse adaptation set");
        var set = audio.group(1);
        var channelsMatcher = CHANNELS.matcher(set);
        assertTrue(channelsMatcher.find(), "channel configuration");
        var channels = Integer.parseInt(channelsMatcher.group(1));
        var timeline = TIMELINE.matcher(set);
        assertTrue(timeline.find(), "segment timeline");
        var duration = Long.parseLong(timeline.group(1));
        var segments = Integer.parseInt(timeline.group(2)) + 1;
        var representations = REPRESENTATION.matcher(set).results().map(result -> result.group(1)).toList();
        assertFalse(representations.isEmpty(), "representations");
        for (var representation : representations) {
            var names = new ArrayList<String>();
            names.add("dashif-mca-dts-" + representation + ".000");
            for (var number = 1; number <= segments; number++) {
                names.add("dashif-mca-dts-" + representation + ".%03d".formatted(number));
            }
            var data = StreamingVectors.join(names);
            var buffers = new ArrayList<AudioBuffer>();
            var packets = new ByteArrayOutputStream();
            AudioTrack track;
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.DTS, track.codec(), representation);
                try (var decoder = AudioDecoders.create(track)) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        packets.writeBytes(packet.data());
                        decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
                    }
                }
            }
            var elementary = mediaData(names.subList(1, names.size()));
            assertArrayEquals(elementary, packets.toByteArray(), representation + " packets");
            assertFalse(buffers.isEmpty(), representation + " decoded nothing");
            var layout = buffers.getFirst().layout();
            assertEquals(channels, layout.channels(), representation + " channels");
            assertEquals(48000, buffers.getFirst().sampleRate(), representation + " rate");
            var decoded = DtsTestSupport.interleave(buffers);
            var frames = decoded.samples().length / channels;
            // the timeline gives every segment its nominal 3 s, the last one included, while the last segment holds
            // 2.2 s (its trun durations): the media covers the presentation and lies within the timeline
            var presentation = presentationSamples(mpd);
            assertTrue(frames >= presentation && frames <= duration * segments, representation + ": " + frames
                    + " frames for a presentation of " + presentation + " and a timeline of " + duration * segments);
            var order = new int[channels];
            for (var ch = 0; ch < channels; ch++) {
                order[ffmpegRank(layout.speakers().get(ch))] = ch;
            }
            var reordered = new float[decoded.samples().length];
            for (var i = 0; i < frames; i++) {
                for (var rank = 0; rank < channels; rank++) {
                    reordered[i * channels + rank] = decoded.samples()[i * channels + order[rank]];
                }
            }
            var file = StreamingVectors.write(representation + ".dts", elementary);
            var reference = DtsTestSupport.ffmpegFloats(file, List.of("-f", "dts"));
            assertEquals(reference.length, reordered.length, representation + " sample count");
            DtsTestSupport.report(representation, reference, reordered, channels);
            Tolerance.snr(65).assertPcm(representation, reference, reordered);
        }
    }

    /**
     * Returns the samples at 48 kHz of the {@code mediaPresentationDuration} of an MPD, an ISO 8601 duration of hours,
     * minutes and seconds.
     */
    private static long presentationSamples(String mpd) {
        var matcher = PRESENTATION.matcher(mpd);
        assertTrue(matcher.find(), "mediaPresentationDuration");
        var seconds = 0.0;
        for (var group = 1; group <= 3; group++) {
            var value = matcher.group(group);
            if (value != null) {
                seconds += Double.parseDouble(value) * (group == 1 ? 3600 : group == 2 ? 60 : 1);
            }
        }
        return Math.round(seconds * 48000);
    }

    /**
     * Returns the payloads of the {@code mdat} boxes of media segments, in order.
     */
    private static byte[] mediaData(List<String> segments) throws IOException {
        var out = new ByteArrayOutputStream();
        for (var segment : segments) {
            var bytes = Files.readAllBytes(Conformance.vector(segment));
            var position = 0;
            while (position + 8 <= bytes.length) {
                var size = ByteBuffer.wrap(bytes, position, 4).getInt();
                assertTrue(size >= 8 && size <= bytes.length - position, segment + " box size");
                if (new String(bytes, position + 4, 4, StandardCharsets.US_ASCII).equals("mdat")) {
                    out.write(bytes, position + 8, size - 8);
                }
                position += size;
            }
        }
        return out.toByteArray();
    }
}
