package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.HlsStitch;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HLS media playlists that join two encodings made by {@code ffmpeg -f hls} behind an
 * {@code EXT-X-DISCONTINUITY}: a change of picture size and channel count (one H.264 and one AAC track whose second
 * section carries a new codec configuration) and a change from H.264 to HEVC (a new track), in transport stream and
 * fragmented MP4 segments. Each track's pictures and samples must equal those of each section's playlist decoded
 * alone, bit for bit (the mono audio of the second section mixed to the stereo of the output), and the video
 * timestamps of each section, measured from its start, must equal those ffprobe reads from the section alone.
 */
@DisplayName("HLS playlists that change encoding at a discontinuity")
class HlsDiscontinuityDecodeTest {
    /** A demuxer read to the end. */
    private record Read(List<Track> tracks, List<Packet> packets) {
        List<Packet> of(Track track) {
            return packets.stream().filter(packet -> packet.trackId() == track.id()).toList();
        }

        Track first(TrackKind kind) {
            return tracks.stream().filter(track -> track.kind() == kind).findFirst().orElseThrow();
        }
    }

    /** Encodes one clip as an HLS media playlist in a directory and returns the playlist text. */
    private static String encode(Path directory, String name, String size, int channels, String videoCodec,
                                 boolean fragmented) {
        Oracle.assumeEncoder(videoCodec);
        var arguments = new ArrayList<>(List.of("-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=10:duration=1.5",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.5",
                "-c:v", videoCodec, "-g", "5", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", Integer.toString(channels), "-threads", "1",
                "-f", "hls", "-hls_time", "0.5", "-hls_playlist_type", "vod"));
        if (videoCodec.equals("libx265")) {
            arguments.addAll(List.of("-x265-params", "log-level=error:pools=1", "-tag:v", "hvc1"));
        }
        // ffmpeg finds the playlist's directory, where it writes the initialization section, by the last '/'
        var base = directory.toAbsolutePath().toString().replace('\\', '/') + "/" + name;
        if (fragmented) {
            arguments.addAll(List.of("-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", name + "_init.mp4",
                    "-hls_segment_filename", base + "_%03d.m4s"));
        } else {
            arguments.addAll(List.of("-hls_segment_type", "mpegts", "-hls_segment_filename", base + "_%03d.ts"));
        }
        var playlist = directory.resolve(name + ".m3u8");
        arguments.add(base + ".m3u8");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        try {
            return Files.readString(playlist);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path write(Path directory, String name, String text) {
        try {
            var file = directory.resolve(name);
            Files.writeString(file, text);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Read read(Path playlist) {
        try (var source = MediaSource.of(playlist); var demuxer = new HlsDemuxer(source, DemuxerOptions.builder()
                .dataReferences(DataReferenceResolver.directory(playlist.getParent())).build())) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Read(demuxer.tracks(), packets);
        }
    }

    /** Writes the packets of one track into an MP4 file, its codec configuration changes as sample descriptions. */
    private static byte[] remux(Track track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(switch (track) {
                case VideoTrack video -> video.toBuilder().duration(Packet.UNKNOWN_DURATION).build();
                case AudioTrack audio -> audio.toBuilder().duration(Packet.UNKNOWN_DURATION).build();
                case DataTrack data -> data.toBuilder().duration(Packet.UNKNOWN_DURATION).build();
            });
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static String digest(Picture picture) {
        var yuv = (YuvPicture) picture;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var row = 0; row < yuv.height(); row++) {
                digest.update(yuv.y(), row * yuv.yStride(), yuv.width());
            }
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                digest.update(yuv.u(), row * yuv.uvStride(), yuv.chromaWidth());
                digest.update(yuv.v(), row * yuv.uvStride(), yuv.chromaWidth());
            }
            return yuv.width() + "x" + yuv.height() + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Decodes a video track through an MP4 copy with the reader, which recreates its decoder on a change. */
    private static List<String> frames(Track track, List<Packet> packets) {
        var result = new ArrayList<String>();
        try (var source = MediaSource.of(remux(track, packets)); var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                result.add(digest(frame.picture()));
            }
        }
        return result;
    }

    /** Decodes an audio track's packets, in the layout of the track. */
    private static float[][] samples(AudioTrack track, List<Packet> packets) {
        try (var decoder = AudioStreamDecoder.create(track)) {
            var channels = decoder.layout().channels();
            var planes = new float[channels][0];
            var block = new float[channels][4096];
            var length = 0;
            for (var index = 0; index <= packets.size(); index++) {
                if (index < packets.size()) {
                    decoder.decode(packets.get(index));
                } else {
                    decoder.finish();
                }
                for (int read; (read = decoder.read(block, 0, block[0].length)) > 0; length += read) {
                    for (var channel = 0; channel < channels; channel++) {
                        planes[channel] = Arrays.copyOf(planes[channel], length + read);
                        System.arraycopy(block[channel], 0, planes[channel], length, read);
                    }
                }
            }
            return planes;
        }
    }

    /** Returns the presentation times ffprobe reads from a playlist's video packets, in microseconds from the first. */
    private static List<Long> probedVideoTimes(Path playlist) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0",
                playlist.toAbsolutePath().toString())), StandardCharsets.UTF_8);
        var times = new ArrayList<Long>();
        for (var line : output.split("\\R")) {
            if (!line.isBlank()) {
                times.add(Math.round(Double.parseDouble(line.strip().replace(",", "")) * 1_000_000));
            }
        }
        var first = times.getFirst();
        return times.stream().map(time -> time - first).toList();
    }

    /** Returns the earliest presentation time of a read, in microseconds. */
    private static long earliestMicros(Read read) {
        var earliest = Long.MAX_VALUE;
        for (var packet : read.packets()) {
            var track = read.tracks().stream().filter(candidate -> candidate.id() == packet.trackId()).findFirst()
                    .orElseThrow();
            earliest = Math.min(earliest, track.timeBase().toMicros(packet.pts()));
        }
        return earliest;
    }

    /** Returns a track's presentation times in microseconds from its first packet, in decoding order. */
    private static List<Long> relativeTimes(Track track, List<Packet> packets) {
        var first = track.timeBase().toMicros(packets.getFirst().pts());
        return packets.stream().map(packet -> track.timeBase().toMicros(packet.pts()) - first).toList();
    }

    private static void assertTimesMatch(List<Long> expected, List<Long> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": packet count");
        for (var index = 0; index < expected.size(); index++) {
            // ffprobe prints six decimals, and a 90 kHz tick is 11.1 microseconds
            assertEquals(expected.get(index), actual.get(index), 12, what + " packet " + index);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ts", "fmp4"})
    @DisplayName("decodes a change of picture size and channel count as its sections decoded alone")
    void configurationChange(String segments) {
        var fragmented = segments.equals("fmp4");
        var directory = Scratch.directory("hls-discontinuity-decode").resolve("config-" + segments);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var first = encode(directory, "a", "64x48", 2, "libx264", fragmented);
        var second = encode(directory, "b", "96x64", 1, "libx264", fragmented);
        var joined = write(directory, "joined.m3u8", HlsStitch.join(List.of(first, second), true, List.of()));
        if (!fragmented) {
            // ffmpeg's fragmented MP4 initialization sections declare a next_track_ID equal to their track's
            Validators.assertValidHls(joined);
        }
        var alone = List.of(read(directory.resolve("a.m3u8")), read(directory.resolve("b.m3u8")));
        var actual = read(joined);
        assertEquals(alone.getFirst().tracks().stream().map(Track::codec).toList(),
                actual.tracks().stream().map(Track::codec).toList());
        var video = actual.first(TrackKind.VIDEO);
        var expected = new ArrayList<String>();
        for (var section : alone) {
            var track = section.first(TrackKind.VIDEO);
            expected.addAll(frames(track, section.of(track)));
        }
        var decoded = frames(video, actual.of(video));
        assertEquals(15, decoded.stream().filter(frame -> frame.startsWith("96x64")).count());
        assertEquals(expected, decoded);
        var videoPackets = actual.of(video);
        var count = alone.getFirst().of(alone.getFirst().first(TrackKind.VIDEO)).size();
        assertTimesMatch(probedVideoTimes(directory.resolve("a.m3u8")), relativeTimes(video,
                videoPackets.subList(0, count)), "first section");
        assertTimesMatch(probedVideoTimes(directory.resolve("b.m3u8")), relativeTimes(video,
                videoPackets.subList(count, videoPackets.size())), "second section");
        var sectionStart = video.timeBase().toMicros(videoPackets.get(count).pts())
                - video.timeBase().toMicros(videoPackets.getFirst().pts());
        assertEquals(HlsStitch.durationMicros(first), sectionStart, 12, "the second section starts after the first's"
                + " EXTINF durations");
        if (!fragmented) {
            // the fragmented MP4 sections trim their AAC priming with an edit list, which applies to a section read
            // alone and not to the track that continues across the discontinuity
            // the first section's AAC frames that start where the second section starts are encoder padding past
            // its EXTINF durations, left out of the continued track
            var audio = (AudioTrack) actual.first(TrackKind.AUDIO);
            var firstTrack = alone.get(0).first(TrackKind.AUDIO);
            var boundary = earliestMicros(alone.get(0)) + HlsStitch.durationMicros(first);
            var kept = alone.get(0).of(firstTrack).stream()
                    .filter(packet -> firstTrack.timeBase().toMicros(packet.pts()) < boundary).toList();
            assertTrue(kept.size() < alone.get(0).of(firstTrack).size(), "the padding overruns the EXTINF durations");
            var firstAudio = samples((AudioTrack) firstTrack, kept);
            var secondAudio = samples((AudioTrack) alone.get(1).first(TrackKind.AUDIO),
                    alone.get(1).of(alone.get(1).first(TrackKind.AUDIO)));
            var samples = samples(audio, actual.of(audio));
            assertEquals(2, samples.length);
            var mixed = new float[2][secondAudio[0].length];
            new ChannelMixer(ChannelLayout.MONO, ChannelLayout.STEREO).process(secondAudio, mixed,
                    secondAudio[0].length);
            for (var channel = 0; channel < 2; channel++) {
                var joinedExpected = Arrays.copyOf(firstAudio[channel], firstAudio[channel].length
                        + mixed[channel].length);
                System.arraycopy(mixed[channel], 0, joinedExpected, firstAudio[channel].length, mixed[channel].length);
                assertArrayEquals(joinedExpected, samples[channel], "channel " + channel);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ts", "fmp4"})
    @DisplayName("decodes a change from H.264 to HEVC as a second video track")
    void codecChange(String segments) {
        var fragmented = segments.equals("fmp4");
        var directory = Scratch.directory("hls-discontinuity-decode").resolve("codec-" + segments);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var first = encode(directory, "a", "64x48", 2, "libx264", fragmented);
        var second = encode(directory, "b", "64x48", 2, "libx265", fragmented);
        var joined = write(directory, "joined.m3u8", HlsStitch.join(List.of(first, second), true, List.of()));
        if (!fragmented) {
            Validators.assertValidHls(joined);
        }
        var alone = List.of(read(directory.resolve("a.m3u8")), read(directory.resolve("b.m3u8")));
        var actual = read(joined);
        var videos = actual.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).toList();
        assertEquals(List.of(Codec.H264, Codec.HEVC), videos.stream().map(Track::codec).toList());
        assertEquals(1, actual.tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO).count());
        for (var index = 0; index < 2; index++) {
            var section = alone.get(index);
            var track = section.first(TrackKind.VIDEO);
            assertEquals(frames(track, section.of(track)), frames(videos.get(index), actual.of(videos.get(index))),
                    "track " + videos.get(index).codec());
        }
        assertTimesMatch(probedVideoTimes(directory.resolve("b.m3u8")), relativeTimes(videos.get(1),
                actual.of(videos.get(1))), "HEVC");
        assertTrue(actual.packets().stream().noneMatch(packet -> packet.trackId() == videos.get(1).id()
                && packet.sideData().newCodecConfig().isPresent()));
    }

    @Test
    @DisplayName("reads the sections' packets on one increasing timeline per track")
    void increasing() {
        var directory = Scratch.directory("hls-discontinuity-decode").resolve("increasing");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var first = encode(directory, "a", "64x48", 2, "libx264", false);
        var joined = write(directory, "joined.m3u8", HlsStitch.join(List.of(first, first, first), true, List.of()));
        var actual = read(joined);
        for (var track : actual.tracks()) {
            var previous = Long.MIN_VALUE;
            for (var packet : actual.of(track)) {
                assertTrue(packet.dts() > previous, "track " + track.id() + " decoding times increase");
                previous = packet.dts();
            }
        }
    }
}
