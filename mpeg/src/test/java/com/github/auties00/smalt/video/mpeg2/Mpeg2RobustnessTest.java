package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg2.Mpeg2TestStreams.Split;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Picture;
import com.github.auties00.smalt.video.mpeg2.internal.SyntaxStream.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the decoder truncated, corrupted and hostile streams and checks that it fails only as its contract says
 * ({@link MediaException.Malformed} after handing out the frames due, {@link MediaException.LimitExceeded},
 * {@link MediaException.Unsupported}), keeps decoding the packets that follow, and conceals what it could not decode
 * without changing the frame size; plus the argument and state checks and the configuration taken from the track.
 */
@DisplayName("MPEG video decoder robustness")
class Mpeg2RobustnessTest {
    static byte[] stream(long seed, boolean fields) {
        var s = new Sequence();
        s.width = 64;
        s.height = 64;
        var list = new ArrayList<Picture>();
        var types = new int[]{SyntaxStream.I, SyntaxStream.P, SyntaxStream.B, SyntaxStream.B, SyntaxStream.P,
                SyntaxStream.B, SyntaxStream.I, SyntaxStream.P};
        for (var i = 0; i < types.length; i++) {
            if (fields) {
                for (var field = 0; field < 2; field++) {
                    var p = new Picture(types[i], field == 0 ? SyntaxStream.TOP : SyntaxStream.BOTTOM);
                    p.concealment = true;
                    p.group = types[i] == SyntaxStream.I && field == 0;
                    list.add(p);
                }
            } else {
                var p = new Picture(types[i]);
                p.concealment = true;
                p.group = types[i] == SyntaxStream.I;
                list.add(p);
            }
        }
        return SyntaxStream.write(s, list, seed);
    }

    /**
     * Decodes packets, collecting frames and the Malformed failures (and the Unsupported ones of a damaged sequence
     * header that now declares scalability); any other exception fails the test.
     */
    static int decode(List<Packet> packets, List<VideoFrame> frames) {
        var failures = 0;
        try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
            for (var packet : packets) {
                try {
                    decoder.decode(packet, frames::add);
                } catch (MediaException.Malformed exception) {
                    failures++;
                } catch (MediaException.Unsupported exception) {
                    assertEquals("mpeg-2 scalability", exception.format());
                    failures++;
                }
            }
            decoder.flush(frames::add);
        }
        return failures;
    }

    @ParameterizedTest(name = "field pictures: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("truncated packets are concealed and decoding goes on")
    void truncation(boolean fields) {
        var random = new Random(fields ? 3 : 2);
        for (var trial = 0; trial < 30; trial++) {
            var packets = new ArrayList<>(Mpeg2TestStreams.packets(stream(trial, fields), Split.PICTURES));
            var victim = 1 + random.nextInt(packets.size() - 1);
            var data = packets.get(victim).data();
            var cut = Arrays.copyOf(data, Math.max(1, random.nextInt(data.length)));
            packets.set(victim, new Packet(1, cut, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                    Packet.UNKNOWN_DURATION, false));
            var frames = new ArrayList<VideoFrame>();
            decode(packets, frames);
            assertTrue(frames.size() >= 4, "frames after a truncated packet: " + frames.size());
            for (var frame : frames) {
                assertEquals(64, frame.picture().width());
                assertEquals(64, frame.picture().height());
            }
        }
    }

    @ParameterizedTest(name = "field pictures: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("corrupted bytes raise only Malformed, and the stream decodes to the end")
    void corruption(boolean fields) {
        var random = new Random(fields ? 5 : 4);
        for (var trial = 0; trial < 60; trial++) {
            var stream = stream(100 + trial, fields);
            var packets = new ArrayList<>(Mpeg2TestStreams.packets(stream, Split.PICTURES));
            for (var hit = 0; hit < 1 + random.nextInt(4); hit++) {
                var victim = random.nextInt(packets.size());
                var data = packets.get(victim).data().clone();
                for (var flips = 0; flips < 1 + random.nextInt(8); flips++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                }
                packets.set(victim, new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                        Packet.UNKNOWN_DURATION, false));
            }
            var frames = new ArrayList<VideoFrame>();
            decode(packets, frames);
            for (var frame : frames) {
                assertTrue(frame.picture().width() > 0);
            }
        }
    }

    @Test
    @DisplayName("random bytes raise only Malformed")
    void noise() {
        var random = new Random(9);
        for (var trial = 0; trial < 200; trial++) {
            var data = new byte[1 + random.nextInt(4000)];
            random.nextBytes(data);
            if (trial % 2 == 0) {
                var header = stream(trial, false);
                var prefix = Arrays.copyOf(header, 40);
                var joined = Arrays.copyOf(prefix, prefix.length + data.length);
                System.arraycopy(data, 0, joined, prefix.length, data.length);
                data = joined;
            }
            decode(List.of(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION,
                    true)), new ArrayList<>());
        }
    }

    @Test
    @DisplayName("a damaged picture raises Malformed after the frames due are handed out, then decoding resumes")
    void damageReported() {
        var packets = new ArrayList<>(Mpeg2TestStreams.packets(stream(42, false), Split.PICTURES));
        var reference = new ArrayList<VideoFrame>();
        assertEquals(0, decode(packets, reference));
        var data = packets.get(1).data();
        // keep the picture header and its extensions, drop the slices: every macroblock is concealed
        var slice = 4;
        while (!(data[slice] == 0 && data[slice + 1] == 0 && data[slice + 2] == 1
                && (data[slice + 3] & 0xFF) >= 1 && (data[slice + 3] & 0xFF) <= 0xAF)) {
            slice++;
        }
        var cut = Arrays.copyOf(data, slice);
        packets.set(1, new Packet(1, cut, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, false));
        var frames = new ArrayList<VideoFrame>();
        var failures = decode(packets, frames);
        assertTrue(failures >= 1, "the damage is reported");
        assertEquals(reference.size(), frames.size(), "every frame is still handed out");
    }

    @Test
    @DisplayName("a picture larger than the limits raises LimitExceeded")
    void limits() {
        var s = new Sequence();
        s.width = 640;
        s.height = 480;
        var stream = SyntaxStream.write(s, List.of(new Picture(SyntaxStream.I)), 1);
        var options = VideoDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(320 * 240).build())
                .build();
        try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO), options)) {
            assertThrows(MediaException.LimitExceeded.class, () -> decoder.decode(new Packet(1, stream,
                    Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true), frame -> { }));
        }
    }

    @Test
    @DisplayName("a sequence scalable extension raises Unsupported with the feature mpeg-2 scalability")
    void scalability() {
        var stream = stream(1, false);
        var header = MpegVideoHeaders.config(stream);
        // a sequence scalable extension (SNR scalability, layer 1) after the sequence extension
        var scalable = new byte[]{0, 0, 1, (byte) 0xB5, 0x51, 0x04};
        var patched = new byte[stream.length + scalable.length];
        System.arraycopy(stream, 0, patched, 0, header.length);
        System.arraycopy(scalable, 0, patched, header.length, scalable.length);
        System.arraycopy(stream, header.length, patched, header.length + scalable.length,
                stream.length - header.length);
        try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
            var exception = assertThrows(MediaException.Unsupported.class, () -> decoder.decode(new Packet(1, patched,
                    Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true), frame -> { }));
            assertEquals("mpeg-2 scalability", exception.format());
        }
    }

    @Test
    @DisplayName("a picture scalable extension in a sequence without scalability raises Malformed, the picture decodes")
    void strayScalableExtension() {
        var stream = stream(3, false);
        var reference = Mpeg2TestStreams.decodeElementary(stream);
        // a picture spatial scalable extension after the first picture coding extension
        var coding = 0;
        while (!(stream[coding] == 0 && stream[coding + 1] == 0 && stream[coding + 2] == 1
                && (stream[coding + 3] & 0xFF) == 0xB5 && (stream[coding + 4] & 0xF0) == 0x80)) {
            coding++;
        }
        var next = coding + 4;
        while (!(stream[next] == 0 && stream[next + 1] == 0 && stream[next + 2] == 1)) {
            next++;
        }
        var stray = new byte[]{0, 0, 1, (byte) 0xB5, (byte) 0x90, 0x12, 0x34, 0x56};
        var patched = new byte[stream.length + stray.length];
        System.arraycopy(stream, 0, patched, 0, next);
        System.arraycopy(stray, 0, patched, next, stray.length);
        System.arraycopy(stream, next, patched, next + stray.length, stream.length - next);
        var decoded = Mpeg2TestStreams.decodeElementary(patched);
        assertEquals(1, decoded.failures().size(), "failures: " + decoded.failures());
        assertArrayEquals(reference.raw(), decoded.raw());
    }

    @Test
    @DisplayName("the sequence header of the track configuration decodes packets that carry none")
    void configuration() {
        var stream = stream(7, false);
        var header = MpegVideoHeaders.config(stream);
        var expected = Mpeg2TestStreams.decodeElementary(stream).raw();
        var track = Mpeg2TestStreams.track(Codec.MPEG2_VIDEO).toBuilder().codecConfig(header).build();
        // the decoder recycles its output picture, so each frame is copied when it is handed out
        var out = new java.io.ByteArrayOutputStream();
        java.util.function.Consumer<VideoFrame> sink = frame -> out.writeBytes(
                Mpeg2TestStreams.raw((com.github.auties00.smalt.image.YuvPicture) frame.picture()));
        try (var decoder = new Mpeg2Decoder(track)) {
            for (var packet : Mpeg2TestStreams.packets(Arrays.copyOfRange(stream, header.length, stream.length),
                    Split.PICTURES)) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    @DisplayName("a corrupt configuration raises Malformed, another codec IllegalArgumentException")
    void construction() {
        var track = Mpeg2TestStreams.track(Codec.MPEG2_VIDEO).toBuilder().codecConfig(new byte[]{0, 0, 1,
                (byte) 0xB3, 0, 0}).build();
        assertThrows(MediaException.Malformed.class, () -> new Mpeg2Decoder(track));
        assertThrows(IllegalArgumentException.class, () -> new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.H264)));
    }

    @Test
    @DisplayName("a closed decoder rejects calls, and closing twice is harmless")
    void closed() {
        var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO));
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.decode(new Packet(1, new byte[4], 0, 0, 1, true),
                frame -> { }));
        assertThrows(IllegalStateException.class, () -> decoder.flush(frame -> { }));
    }

    @Test
    @DisplayName("an exception of the consumer reaches the caller and the decoder stays usable")
    void consumerFailure() {
        var packets = Mpeg2TestStreams.packets(stream(8, false), Split.PICTURES);
        try (var decoder = new Mpeg2Decoder(Mpeg2TestStreams.track(Codec.MPEG2_VIDEO))) {
            var thrown = 0;
            for (var packet : packets) {
                try {
                    decoder.decode(packet, frame -> {
                        throw new IllegalStateException("consumer");
                    });
                } catch (IllegalStateException exception) {
                    thrown++;
                }
            }
            assertTrue(thrown > 0);
            decoder.flush(frame -> { });
        }
    }
}
