package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the scalable H.264 layers of an MP4 file stored as ISO/IEC 14496-15 describes: the SVC live
 * conformance file of the MPEG file format conformance suite, an {@code avc1} base layer track and two
 * {@code avc2} tracks whose samples rebuild their access units from the tracks they reference through
 * extractors, in fragments of separate segments. Each track's frames are compared with the JSVM 9.19.15
 * decoding of the track's access units as the demuxer rebuilds them. A second test wraps the NAL units of
 * those access units in aggregators, which the decoder unwraps.
 */
@DisplayName("H.264 scalable layers in MP4 tracks")
class H264Mp4LayersTest {
    private static final List<String> FILES = List.of("mp4-live-LastTime-depRep.mp4",
            "mp4-live-LastTime-depRep-R1-0001.mp4", "mp4-live-LastTime-depRep-R2-0001.mp4",
            "mp4-live-LastTime-depRep-R3-0001.mp4", "mp4-live-LastTime-depRep-R1-0002.mp4",
            "mp4-live-LastTime-depRep-R2-0002.mp4", "mp4-live-LastTime-depRep-R3-0002.mp4");

    /** The initialization segment followed by the media segments of the three representations, in time order. */
    static byte[] file() throws IOException {
        var out = new ByteArrayOutputStream();
        for (var name : FILES) {
            out.writeBytes(Files.readAllBytes(Conformance.fetchListed("mpegff-svc-" + name)));
        }
        return out.toByteArray();
    }

    record Decoded(String md5, int frames, List<Integer> layers) {
    }

    static final class Digest implements Consumer<VideoFrame> {
        final MessageDigest digest;

        final List<Integer> layers = new ArrayList<>();

        int frames;

        Digest() {
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void accept(VideoFrame frame) {
            digest.update(H264TestStreams.serialize((YuvPicture) frame.picture()));
            frames++;
            var layer = frame.sideData().layer().map(value -> value.id()).orElse(0);
            if (!layers.contains(layer)) {
                layers.add(layer);
            }
        }

        Decoded result() {
            return new Decoded(HexFormat.of().formatHex(digest.digest()), frames, List.copyOf(layers));
        }
    }

    /** Decodes a track of the file, optionally wrapping the NAL units of every packet in aggregators. */
    static Decoded decode(byte[] file, int trackId, boolean aggregate) {
        var output = new Digest();
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(entry -> entry.id() == trackId).findFirst()
                    .orElseThrow();
            try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().highestLayer().build())) {
                Packet packet;
                var index = 0;
                while ((packet = demuxer.read()) != null) {
                    if (packet.trackId() != trackId) {
                        continue;
                    }
                    var data = aggregate ? aggregated(packet.data(), index++) : packet.data();
                    decoder.decode(new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(),
                            packet.keyframe()), output);
                }
                decoder.flush(output);
            }
        }
        return output.result();
    }

    /**
     * Wraps runs of the length-prefixed NAL units of an access unit in aggregators (a type 30 NAL unit
     * header of four bytes followed by the units), a run of one to three units each, varying with the
     * packet index.
     */
    static byte[] aggregated(byte[] data, int index) {
        var units = new ArrayList<byte[]>();
        for (var position = 0; position < data.length; ) {
            var length = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            units.add(java.util.Arrays.copyOfRange(data, position, position + 4 + length));
            position += 4 + length;
        }
        var out = new ByteArrayOutputStream();
        var run = 1 + index % 3;
        for (var start = 0; start < units.size(); start += run) {
            var body = new ByteArrayOutputStream();
            body.writeBytes(new byte[]{0x7E, (byte) 0x80, 0, 0});
            for (var k = start; k < Math.min(units.size(), start + run); k++) {
                body.writeBytes(units.get(k));
            }
            var size = body.size();
            out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
            out.writeBytes(body.toByteArray());
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "track {0}")
    @CsvSource({"2, 59f1b8750426bc73afb3b5f3433196c7, 0", "3, 652104cd5cf097b6b48fb2b9049c6db4, 1",
            "4, 08f1584bba7d513744772635e884d56d, 2"})
    @DisplayName("decodes every layer track, rebuilt from the tracks it extends, as JSVM decodes it")
    void layers(int trackId, String md5, int dqId) throws IOException {
        var decoded = decode(file(), trackId, false);
        assertEquals(new Decoded(md5, 225, List.of(dqId)), decoded);
    }

    @Test
    @DisplayName("unwraps aggregators in the packets of a track")
    void aggregators() throws IOException {
        var file = file();
        assertEquals(decode(file, 4, false), decode(file, 4, true));
    }
}
