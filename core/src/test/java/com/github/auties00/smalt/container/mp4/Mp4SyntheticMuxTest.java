package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Muxes packets parsed by hand from elementary streams (ADTS AAC, Annex B H.264), as an encoder
 * would hand them over, and checks the result with ffprobe and ffmpeg: priming and end trimming
 * through the edit list, display orientation, composition offsets, and a clean decode.
 */
@DisplayName("Mp4Muxer with synthetic packets")
class Mp4SyntheticMuxTest {
    record Adts(byte[] config, int sampleRate, int channels, List<byte[]> frames) {
    }

    static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    static Adts parseAdts(byte[] data) {
        var frames = new ArrayList<byte[]>();
        var position = 0;
        int profile = 0, rateIndex = 0, channels = 0;
        while (position + 7 <= data.length) {
            assertEquals(0xFF, data[position] & 0xFF);
            assertEquals(0xF0, data[position + 1] & 0xF6);
            var protectionAbsent = (data[position + 1] & 1) == 1;
            profile = ((data[position + 2] & 0xFF) >> 6) + 1;
            rateIndex = ((data[position + 2] & 0xFF) >> 2) & 0xF;
            channels = ((data[position + 2] & 1) << 2) | ((data[position + 3] & 0xFF) >> 6);
            var length = ((data[position + 3] & 3) << 11) | ((data[position + 4] & 0xFF) << 3) | ((data[position + 5] & 0xFF) >> 5);
            var header = protectionAbsent ? 7 : 9;
            frames.add(Arrays.copyOfRange(data, position + header, position + length));
            position += length;
        }
        var config = new byte[]{(byte) ((profile << 3) | (rateIndex >> 1)), (byte) (((rateIndex & 1) << 7) | (channels << 3))};
        return new Adts(config, RATES[rateIndex], channels, frames);
    }

    @Test
    @DisplayName("muxes ADTS frames into an M4A whose edit list trims priming and padding")
    void adts() throws IOException {
        var seconds = 1.3;
        var adtsFile = Fixtures.ffmpeg("aac", Mp4Fixtures.audio(44100, seconds), List.of("-c:a", "aac", "-ac", "2", "-f", "adts"));
        var adts = parseAdts(Fixtures.read(adtsFile));
        var samples = Math.round(seconds * 44100);
        var track = AudioTrack.builder().id(7).codec(Codec.AAC).timeBase(Rational.timeBaseOf(adts.sampleRate()))
                .duration(samples)
                .codecConfig(adts.config())
                .language("ita")
                .sampleRate(adts.sampleRate())
                .layout(ChannelLayout.STEREO)
                .skipSamples(1024)
                .build();
        var output = Mp4MuxerTest.output("synthetic-adts").resolveSibling("synthetic-adts.m4a");
        try (var channel = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING); var muxer = new Mp4Muxer(MediaSink.of(channel),
                        Mp4MuxerOptions.m4a())) {
            muxer.addTrack(track);
            for (var index = 0; index < adts.frames().size(); index++) {
                // encoder convention: timestamps count decoder output from zero, priming included
                muxer.writePacket(new Packet(7, adts.frames().get(index), index * 1024L, index * 1024L, 1024, true));
            }
            muxer.finish();
        }
        var probe = ContainerProbe.probe(output);
        var stream = probe.streams().getFirst();
        assertEquals("0.000000", stream.get("start_time"));
        assertEquals(samples, Long.parseLong(stream.get("duration_ts")));
        assertEquals("ita", stream.get("tag:language"));
        assertEquals("M4A ", probe.format().get("tag:major_brand"));
        var packets = probe.packetsOf(0);
        assertEquals(adts.frames().size(), packets.size());
        for (var index = 0; index < packets.size(); index++) {
            assertEquals(ContainerProbe.crc32(adts.frames().get(index)), packets.get(index).hash());
        }
        Oracle.assertDecodesCleanly(output);
        var reference = Oracle.decodeAudio(adtsFile);
        var decoded = Oracle.decodeAudio(output);
        var aligned = Arrays.copyOfRange(reference, 2 * 1024, reference.length);
        assertTrue(com.github.auties00.smalt.testkit.Quality.snr(aligned, decoded) > 90, "identical samples after the priming");
    }

    /** Splits an Annex B stream written with access unit delimiters into access units of NAL units. */
    static List<List<byte[]>> accessUnits(byte[] stream) {
        var nals = new ArrayList<byte[]>();
        var start = -1;
        for (var index = 0; index + 3 <= stream.length; index++) {
            if (stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1) {
                if (start >= 0) {
                    var end = index;
                    while (end > start && stream[end - 1] == 0) {
                        end--;
                    }
                    nals.add(Arrays.copyOfRange(stream, start, end));
                }
                start = index + 3;
                index += 2;
            }
        }
        nals.add(Arrays.copyOfRange(stream, start, stream.length));
        var units = new ArrayList<List<byte[]>>();
        for (var nal : nals) {
            if ((nal[0] & 0x1F) == 9 || units.isEmpty()) {
                units.add(new ArrayList<>());
            }
            if ((nal[0] & 0x1F) != 9) {
                units.getLast().add(nal);
            }
        }
        return units;
    }

    @Test
    @DisplayName("muxes Annex B H.264 converted to length prefixes, with orientation and a start delay")
    void annexB() throws IOException {
        var raw = Fixtures.read(Fixtures.ffmpeg("h264", Mp4Fixtures.video(128, 96, 1, 25),
                List.of("-c:v", "libx264", "-bf", "0", "-g", "10", "-x264-params", "aud=1", "-f", "h264")));
        var units = accessUnits(raw);
        byte[] sps = null;
        byte[] pps = null;
        var packets = new ArrayList<byte[]>();
        var keyframes = new ArrayList<Boolean>();
        for (var unit : units) {
            var out = new ByteArrayOutputStream();
            var key = false;
            for (var nal : unit) {
                var type = nal[0] & 0x1F;
                if (type == 7) {
                    sps = nal;
                } else if (type == 8) {
                    pps = nal;
                }
                key |= type == 5;
                out.writeBytes(new byte[]{(byte) (nal.length >> 24), (byte) (nal.length >> 16), (byte) (nal.length >> 8), (byte) nal.length});
                out.writeBytes(nal);
            }
            packets.add(out.toByteArray());
            keyframes.add(key);
        }
        var avcC = new ByteArrayOutputStream();
        avcC.writeBytes(new byte[]{1, sps[1], sps[2], sps[3], (byte) 0xFF, (byte) 0xE1, (byte) (sps.length >> 8), (byte) sps.length});
        avcC.writeBytes(sps);
        avcC.writeBytes(new byte[]{1, (byte) (pps.length >> 8), (byte) pps.length});
        avcC.writeBytes(pps);
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 25))
                .codecConfig(avcC.toByteArray())
                .width(128)
                .height(96)
                .orientation(Orientation.ROTATE_90)
                .frameRate(new Rational(25, 1))
                .build();
        var output = Mp4MuxerTest.output("synthetic-annexb");
        try (var channel = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING); var muxer = new Mp4Muxer(MediaSink.of(channel),
                        Mp4MuxerOptions.mp4())) {
            muxer.addTrack(track);
            for (var index = 0; index < packets.size(); index++) {
                // the video starts two frames into the presentation
                muxer.writePacket(new Packet(1, packets.get(index), index + 2L, index + 2L, 1, keyframes.get(index)));
            }
            muxer.finish();
        }
        var probe = ContainerProbe.probe(output);
        var stream = probe.streams().getFirst();
        assertEquals(-90, stream.rotation());
        assertEquals("0.080000", stream.get("start_time"));
        assertEquals(25, probe.packetsOf(0).size());
        assertTrue(probe.packetsOf(0).getFirst().key());
        Oracle.assertDecodesCleanly(output);
        var frames = Oracle.decodeVideo(output, "gray", 0);
        assertEquals(25 * 128 * 96, frames.length);
        assertTrue(Files.size(output) > 1000);
    }

    @Test
    @DisplayName("writes a frame duration in nanoseconds at its frame rate, so a long frame reordering keeps its composition offsets")
    void nanosecondFrameTimeBase() throws IOException {
        // sixteen B pictures between anchors present the anchors over half a second after decoding them
        var input = Fixtures.ffmpeg("mp4", Mp4Fixtures.video(160, 120, 2, 30),
                List.of("-c:v", "libx264", "-bf", "16", "-x264-params", "b-adapt=0:b-pyramid=none", "-pix_fmt", "yuv420p"));
        // a Matroska frame duration of 30 frames per second, as a transcode from WebM hands it to the encoder
        var timeBase = new Rational(33_333_333, 1_000_000_000);
        var output = Mp4MuxerTest.output("synthetic-nanosecond-frames");
        var frames = 0;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(input));
             var channel = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING); var muxer = new Mp4Muxer(MediaSink.of(channel),
                             Mp4MuxerOptions.mp4())) {
            var source = (VideoTrack) demuxer.tracks().getFirst();
            muxer.addTrack(source.toBuilder().id(1).timeBase(timeBase).duration(-1).build());
            Packet packet;
            var reordered = false;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() != source.id()) {
                    continue;
                }
                var pts = source.timeBase().rescale(packet.pts(), timeBase);
                var dts = source.timeBase().rescale(packet.dts(), timeBase);
                reordered |= pts - dts > 8;
                muxer.writePacket(new Packet(1, packet.data(), pts, dts, 1, packet.keyframe()));
                frames++;
            }
            assertTrue(reordered, "the fixture reorders frames by more than 8 frame durations");
            muxer.finish();
        }
        assertEquals(60, frames);
        Oracle.assertDecodesCleanly(output);
        assertEquals(60, Oracle.decodeVideo(output, "gray", 0).length / (160 * 120), "decoded frames");
        var stream = ContainerProbe.probe(output).streams().getFirst();
        assertEquals(30, stream.timeBaseDenominator());
        assertEquals(1, stream.timeBaseNumerator());
    }
}
