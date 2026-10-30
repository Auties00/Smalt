package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Sorenson H.263 picture layer of {@link H263Decoder}: version 0 pictures, made by rewriting the picture
 * header of the baseline pictures of Smalt's H.263 encoder (whose block layer is the one of version 0), decode to
 * exactly the pictures of the H.263 decode; a disposable picture is shown and never predicted from; corrupt headers
 * are rejected and corrupt macroblocks concealed with {@link MediaException.Malformed}; and the registry hands
 * Sorenson Spark tracks to the H.263 decoder.
 */
@DisplayName("Sorenson Spark decoder")
class SorensonDecoderTest {
    /** Reads and writes bit strings for header rewriting. */
    static final class Bits {
        private final StringBuilder bits = new StringBuilder();

        Bits put(long value, int count) {
            for (var bit = count - 1; bit >= 0; bit--) {
                bits.append((value >>> bit & 1) == 0 ? '0' : '1');
            }
            return this;
        }

        Bits putBits(String text) {
            bits.append(text);
            return this;
        }

        byte[] bytes() {
            var out = new byte[(bits.length() + 7) / 8];
            for (var index = 0; index < bits.length(); index++) {
                if (bits.charAt(index) == '1') {
                    out[index >> 3] |= (byte) (0x80 >>> (index & 7));
                }
            }
            return out;
        }

        static String of(byte[] data) {
            var text = new StringBuilder(data.length * 8);
            for (var value : data) {
                for (var bit = 7; bit >= 0; bit--) {
                    text.append((value >> bit & 1) == 0 ? '0' : '1');
                }
            }
            return text.toString();
        }
    }

    /**
     * Rewrites a baseline H.263 picture (PTYPE without PLUSPTYPE, no CPM, groups of blocks without headers) into a
     * Sorenson H.263 version 0 picture with the same macroblock data.
     */
    static byte[] toSorenson(byte[] picture) {
        var bits = Bits.of(picture);
        assertEquals("0000000000000000100000", bits.substring(0, 22), "picture start code");
        var reference = Integer.parseInt(bits.substring(22, 30), 2);
        assertEquals("10", bits.substring(30, 32), "PTYPE marker bits");
        var format = Integer.parseInt(bits.substring(35, 38), 2);
        var inter = bits.charAt(38) == '1';
        assertEquals("0000", bits.substring(39, 43), "no optional mode");
        var quant = Integer.parseInt(bits.substring(43, 48), 2);
        assertEquals('0', bits.charAt(48), "no continuous presence multipoint");
        var position = 49;
        while (bits.charAt(position) == '1') {
            position += 9;
        }
        position++;
        var size = switch (format) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 2;
            default -> throw new AssertionError("source format " + format);
        };
        var out = new Bits().put(1, 17).put(0, 5).put(reference, 8).put(size, 3).put(inter ? 1 : 0, 2).put(0, 1)
                .put(quant, 5).put(0, 1).putBits(bits.substring(position));
        return out.bytes();
    }

    static List<byte[]> decode(Codec codec, int width, int height, List<Packet> packets) {
        var track = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
        var pictures = new ArrayList<byte[]>();
        try (var decoder = new H263Decoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> pictures.add(H263DifferentialTest.raw((YuvPicture) frame.picture())));
            }
            decoder.flush(frame -> pictures.add(H263DifferentialTest.raw((YuvPicture) frame.picture())));
        }
        return pictures;
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"176, 144", "128, 96", "352, 288"})
    @DisplayName("decodes version 0 pictures to exactly the pictures of the same baseline macroblocks in H.263")
    void version0(int width, int height) {
        List<Packet> baseline;
        try (var encoder = new H263Encoder(width, height, H263EncoderTest.base().keyframeInterval(6).build())) {
            assertFalse(encoder.extendedType());
            baseline = EncoderScenes.encode(encoder, 14, 1, false, H263Encoder::lastReconstruction).packets();
        }
        var sorenson = baseline.stream().map(packet -> packet.withData(toSorenson(packet.data()))).toList();
        var expected = decode(Codec.H263, width, height, baseline);
        var actual = decode(Codec.FLV1, width, height, sorenson);
        assertEquals(14, expected.size());
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index), "picture " + index);
        }
    }

    static List<Packet> ffmpegPackets() {
        return ffmpegPackets("176x144");
    }

    static List<Packet> ffmpegPackets(String size) {
        var file = Fixtures.ffmpeg("flv", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=15"),
                List.of("-t", "1", "-c:v", "flv1", "-q:v", "4", "-g", "15"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            return Packets.readAll(demuxer);
        }
    }

    @Test
    @DisplayName("follows picture size changes at intra pictures, standard and custom sizes alike")
    void sizeChanges() {
        var sizes = List.of("176x144", "100x76", "352x288", "640x360");
        var all = new ArrayList<Packet>();
        var expected = new ArrayList<byte[]>();
        var dimensions = new ArrayList<String>();
        var offset = 0L;
        for (var size : sizes) {
            var packets = ffmpegPackets(size);
            var parts = size.split("x");
            expected.addAll(decode(Codec.FLV1, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), packets));
            for (var packet : packets) {
                all.add(packet.withTiming(packet.pts() + offset, packet.dts() + offset, packet.duration()));
                dimensions.add(size);
            }
            offset += 1000;
        }
        var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(176)
                .height(144).build();
        var frames = new ArrayList<byte[]>();
        var shown = new ArrayList<String>();
        try (var decoder = new H263Decoder(track)) {
            for (var packet : all) {
                decoder.decode(packet, frame -> {
                    frames.add(H263DifferentialTest.raw((YuvPicture) frame.picture()));
                    shown.add(frame.picture().width() + "x" + frame.picture().height());
                });
            }
            decoder.flush(frame -> {
                frames.add(H263DifferentialTest.raw((YuvPicture) frame.picture()));
                shown.add(frame.picture().width() + "x" + frame.picture().height());
            });
        }
        assertEquals(dimensions, shown);
        assertEquals(expected.size(), frames.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), frames.get(index), "picture " + index);
        }
    }

    /** Rewrites the picture type of a QCIF Sorenson picture (bits 33 and 34) to disposable inter. */
    static byte[] disposable(byte[] picture) {
        var data = picture.clone();
        var bits = Bits.of(data);
        assertEquals("011", bits.substring(30, 33), "QCIF size code");
        assertEquals("01", bits.substring(33, 35), "an inter picture");
        data[4] = (byte) ((data[4] & ~0x60) | 0x40);
        return data;
    }

    @Test
    @DisplayName("hands a disposable picture out at once with the timestamp of its packet")
    void disposableTimestamps() {
        var packets = ffmpegPackets();
        var marked = new ArrayList<>(packets);
        for (var index = 2; index < packets.size(); index += 3) {
            marked.set(index, packets.get(index).withData(disposable(packets.get(index).data())));
        }
        var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(176)
                .height(144).build();
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new H263Decoder(track)) {
            for (var index = 0; index < marked.size(); index++) {
                decoder.decode(marked.get(index), frames::add);
                if (index % 3 == 2) {
                    assertEquals(marked.get(index).pts(), frames.getLast().pts(), "disposable picture " + index
                            + " is handed out by its own packet");
                }
            }
            decoder.flush(frames::add);
        }
        assertEquals(marked.stream().map(Packet::pts).toList(), frames.stream().map(VideoFrame::pts).toList());
    }

    @Test
    @DisplayName("shows a disposable picture in order and predicts the next picture from the one before it")
    void disposablePicture() {
        var packets = ffmpegPackets();
        var k = 5;
        var normal = decode(Codec.FLV1, 176, 144, packets);
        var marked = new ArrayList<>(packets);
        marked.set(k, packets.get(k).withData(disposable(packets.get(k).data())));
        var withDisposable = decode(Codec.FLV1, 176, 144, marked);
        var removed = new ArrayList<>(packets);
        removed.remove(k);
        var withoutIt = decode(Codec.FLV1, 176, 144, removed);
        assertEquals(normal.size(), withDisposable.size());
        for (var index = 0; index <= k; index++) {
            assertArrayEquals(normal.get(index), withDisposable.get(index), "picture " + index);
        }
        for (var index = k + 1; index < withDisposable.size(); index++) {
            assertArrayEquals(withoutIt.get(index - 1), withDisposable.get(index), "picture " + index
                    + " is predicted as if the disposable picture were absent");
        }
        assertFalse(Arrays.equals(normal.get(k + 1), withDisposable.get(k + 1)), "the test needs the next picture"
                + " to depend on the disposable one");
    }

    @Test
    @DisplayName("rejects corrupt picture headers and conceals corrupt macroblocks, then decodes on")
    void corruptPictures() {
        var packets = ffmpegPackets();
        var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(176)
                .height(144).build();
        var key = packets.getFirst().data();
        var version2 = key.clone();
        version2[2] = (byte) ((version2[2] & 0x83) | 0x08);
        var reservedSize = key.clone();
        reservedSize[3] |= 0x03;
        reservedSize[4] |= (byte) 0x80;
        var noStartCode = key.clone();
        noStartCode[1] = 1;
        try (var decoder = new H263Decoder(track)) {
            assertThrows(MediaException.Unsupported.class, () -> decoder.decode(packets.getFirst().withData(version2),
                    _ -> {
                    }));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packets.getFirst()
                    .withData(reservedSize), _ -> {
            }));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packets.getFirst()
                    .withData(noStartCode), _ -> {
            }));
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packets.getFirst(), frames::add);
            var truncated = packets.get(1).withData(Arrays.copyOf(packets.get(1).data(), 12));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(truncated, frames::add));
            for (var index = 2; index < packets.size(); index++) {
                decoder.decode(packets.get(index), frames::add);
            }
            decoder.flush(frames::add);
            assertEquals(packets.size(), frames.size(), "the concealed picture is handed out too");
            // a rejected header leaves no timestamp behind: every frame carries the timestamp of its own packet
            assertEquals(packets.stream().map(Packet::pts).toList(), frames.stream().map(VideoFrame::pts).toList());
        }
    }

    @Test
    @DisplayName("fails only with MediaException on mutated pictures")
    void mutations() {
        var packets = ffmpegPackets();
        var random = new Random(42);
        var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(176)
                .height(144).build();
        for (var round = 0; round < 40; round++) {
            try (var decoder = new H263Decoder(track)) {
                for (var packet : packets) {
                    var data = packet.data().clone();
                    if (random.nextInt(3) == 0) {
                        data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                    }
                    if (random.nextInt(6) == 0) {
                        data = Arrays.copyOf(data, random.nextInt(data.length + 1));
                    }
                    try {
                        decoder.decode(packet.withData(data), frame -> assertTrue(frame.picture().width() > 0));
                    } catch (MediaException exception) {
                        // a rejected or concealed picture
                    }
                }
                decoder.flush(_ -> {
                });
            }
        }
    }

    @Test
    @DisplayName("hands nothing out for inter pictures without a reference and accepts only H.263 and Sorenson tracks")
    void interWithoutReference() {
        var packets = ffmpegPackets();
        var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(176)
                .height(144).build();
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new H263Decoder(track)) {
            for (var index = 1; index < 4; index++) {
                decoder.decode(packets.get(index), frames::add);
            }
            decoder.flush(frames::add);
        }
        assertTrue(frames.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new H263Decoder(track.toBuilder().codec(Codec.MPEG4_PART2)
                .build()));
        assertTrue(VideoDecoders.isSupported(Codec.FLV1));
        try (var decoder = VideoDecoders.create(track, VideoDecoderOptions.defaults())) {
            assertInstanceOf(H263Decoder.class, decoder);
        }
    }
}
