package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers animated cursors (RIFF {@code ACON}): encoder output checked chunk by chunk with an independent
 * RIFF walker, round trips with custom {@code rate} and {@code seq} chunks, the animation writer's timing
 * and frame sharing, seeking, entry selection, canvas composition, the registry and both probes, the
 * Windows system cursors, and damaged and hostile files built by hand.
 */
class AniTest {
    private static final Charset ANSI = Charset.forName("windows-1252");

    /** The chunks of an animated cursor, found by walking it independently of the code under test. */
    record Chunks(List<String> order, byte[] anih, long[] rate, long[] seq, List<byte[]> icons, Map<String, byte[]> info) {
        long anih(int field) {
            return u32(anih, field * 4);
        }
    }

    static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
    }

    static Chunks parse(byte[] ani) {
        assertEquals("RIFF", new String(ani, 0, 4, ANSI));
        assertEquals("ACON", new String(ani, 8, 4, ANSI));
        assertEquals(ani.length - 8, u32(ani, 4));
        var order = new ArrayList<String>();
        byte[] anih = null;
        long[] rate = null;
        long[] seq = null;
        var icons = new ArrayList<byte[]>();
        var info = new LinkedHashMap<String, byte[]>();
        var position = 12;
        while (position < ani.length) {
            var id = new String(ani, position, 4, ANSI);
            var size = (int) u32(ani, position + 4);
            var body = Arrays.copyOfRange(ani, position + 8, position + 8 + size);
            if (id.equals("LIST")) {
                var type = new String(body, 0, 4, ANSI);
                order.add("LIST " + type);
                for (var sub = 4; sub < body.length; ) {
                    var subId = new String(body, sub, 4, ANSI);
                    var subSize = (int) u32(body, sub + 4);
                    var data = Arrays.copyOfRange(body, sub + 8, sub + 8 + subSize);
                    if (type.equals("fram")) {
                        assertEquals("icon", subId);
                        icons.add(data);
                    } else {
                        info.put(subId, data);
                    }
                    sub += 8 + subSize + (subSize & 1);
                }
            } else {
                order.add(id);
                switch (id) {
                    case "anih" -> anih = body;
                    case "rate" -> rate = dwords(body);
                    case "seq " -> seq = dwords(body);
                    default -> fail("unexpected chunk " + id);
                }
            }
            position += 8 + size + (size & 1);
        }
        assertEquals(ani.length, position);
        return new Chunks(order, anih, rate, seq, icons, info);
    }

    static long[] dwords(byte[] body) {
        var values = new long[body.length / 4];
        for (var i = 0; i < values.length; i++) {
            values[i] = u32(body, i * 4);
        }
        return values;
    }

    static RgbaPicture picture(int width, int height, long seed) {
        return IcoTest.picture(width, height, true, seed);
    }

    static long micros(long jiffies) {
        return jiffies * 1_000_000 / 60;
    }

    static List<AnimationFrame> read(AnimationReader reader) {
        var frames = new ArrayList<AnimationFrame>();
        for (var frame = reader.read(); frame != null; frame = reader.read()) {
            frames.add(new AnimationFrame(RgbaPicture.of(frame.picture().width(), frame.picture().height(),
                    frame.picture().width() * 4, RasterTesting.tight(frame.picture()), ((RgbaPicture) frame.picture()).hasAlpha()),
                    frame.timestampMicros(), frame.durationMicros()));
        }
        return frames;
    }

    static byte[] pixels(RgbaPicture picture) {
        return RasterTesting.tight(picture);
    }

    static byte[] decodedIcon(byte[] icon) {
        return RasterTesting.tight(IcoDecoder.decode(MediaSource.of(icon)).picture());
    }

    /** Writes a RIFF chunk, padded to an even size. */
    static byte[] chunk(String id, byte[]... parts) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(ANSI));
        var body = new ByteArrayOutputStream();
        for (var part : parts) {
            body.writeBytes(part);
        }
        out.writeBytes(le32(body.size()));
        out.writeBytes(body.toByteArray());
        if ((body.size() & 1) != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    static byte[] le32(long... values) {
        var out = new byte[values.length * 4];
        for (var i = 0; i < values.length; i++) {
            for (var b = 0; b < 4; b++) {
                out[i * 4 + b] = (byte) (values[i] >>> (8 * b));
            }
        }
        return out;
    }

    static byte[] anih(long frames, long steps, long rate, long flags) {
        return chunk("anih", le32(36, frames, steps, 0, 0, 0, 0, rate, flags));
    }

    static byte[] fram(byte[]... icons) {
        var parts = new byte[icons.length + 1][];
        parts[0] = "fram".getBytes(ANSI);
        for (var i = 0; i < icons.length; i++) {
            parts[i + 1] = chunk("icon", icons[i]);
        }
        return chunk("LIST", parts);
    }

    static byte[] riff(byte[]... chunks) {
        var parts = new byte[chunks.length + 1][];
        parts[0] = "ACON".getBytes(ANSI);
        System.arraycopy(chunks, 0, parts, 1, chunks.length);
        return chunk("RIFF", parts);
    }

    static byte[] icon(int size, long seed) {
        return IcoEncoder.encode(picture(size, size, seed));
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @Test
        @DisplayName("writes custom rate and seq chunks, the INFO list and cursor frames that read back exactly")
        void custom() {
            var pictures = List.of(picture(20, 14, 1), picture(20, 14, 2), picture(20, 14, 3));
            var steps = List.of(new AniStep(0, 5), new AniStep(1, 10), new AniStep(2, 0), new AniStep(1, 7),
                    new AniStep(0, 3));
            var frameOptions = IcoEncoderOptions.builder().hotspot(3, 4).build();
            var options = AniEncoderOptions.builder().title("Caf\u00e9 cursor").artist("Smalt").frameOptions(frameOptions)
                    .build();
            var ani = AniEncoder.encode(pictures, steps, options);
            var chunks = parse(ani);
            assertEquals(List.of("LIST INFO", "anih", "rate", "seq ", "LIST fram"), chunks.order());
            assertEquals(36, chunks.anih().length);
            assertEquals(List.of(36L, 3L, 5L, 0L, 0L, 0L, 0L, 5L, 3L),
                    Stream.iterate(0, i -> i + 1).limit(9).map(chunks::anih).toList());
            assertArrayEquals(new long[]{5, 10, 0, 7, 3}, chunks.rate());
            assertArrayEquals(new long[]{0, 1, 2, 1, 0}, chunks.seq());
            assertArrayEquals("Caf\u00e9 cursor\0".getBytes(ANSI), chunks.info().get("INAM"));
            assertArrayEquals("Smalt\0".getBytes(ANSI), chunks.info().get("IART"));
            assertEquals(3, chunks.icons().size());
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(IcoEncoder.encode(pictures.get(i), frameOptions), chunks.icons().get(i));
            }
            var header = AniDecoder.header(MediaSource.of(ani));
            assertEquals(new AniHeader(3, steps, 5, Optional.of("Caf\u00e9 cursor"), Optional.of("Smalt")), header);
            var frame = AniDecoder.frame(MediaSource.of(ani), 1, ImageDecoderOptions.defaults());
            assertArrayEquals(chunks.icons().get(1), frame);
            assertEquals(new IcoEntry.Hotspot(3, 4), IcoDecoder.entries(MediaSource.of(frame)).getFirst().hotspot().orElseThrow());
            try (var reader = AniDecoder.openAnimation(MediaSource.of(ani))) {
                assertEquals(20, reader.width());
                assertEquals(14, reader.height());
                assertEquals(0, reader.loopCount());
                assertEquals(5, reader.frameCount());
                var frames = read(reader);
                assertEquals(5, frames.size());
                var start = 0L;
                for (var i = 0; i < 5; i++) {
                    var step = steps.get(i);
                    assertArrayEquals(pixels(pictures.get(step.frame())), RasterTesting.tight(frames.get(i).picture()),
                            "step " + i);
                    assertEquals(micros(start), frames.get(i).timestampMicros());
                    assertEquals(micros(start + step.jiffies()) - micros(start), frames.get(i).durationMicros());
                    start += step.jiffies();
                }
            }
        }

        @Test
        @DisplayName("leaves out the rate, seq and INFO chunks a plain animation does not need")
        void plain() {
            var pictures = List.of(picture(8, 8, 4), picture(8, 8, 5));
            var ani = AniEncoder.encode(pictures, List.of(new AniStep(0, 12), new AniStep(1, 12)),
                    AniEncoderOptions.defaults());
            var chunks = parse(ani);
            assertEquals(List.of("anih", "LIST fram"), chunks.order());
            assertEquals(12, chunks.anih(7));
            assertEquals(1, chunks.anih(8));
            var header = AniDecoder.header(MediaSource.of(ani));
            assertEquals(12, header.displayRate());
            assertTrue(header.title().isEmpty());
            assertTrue(header.artist().isEmpty());
            var still = AniEncoder.encode(picture(8, 8, 6));
            assertEquals(List.of("anih", "LIST fram"), parse(still).order());
            assertEquals(List.of(new AniStep(0, 60)), AniDecoder.header(MediaSource.of(still)).steps());
        }

        @Test
        @DisplayName("stores an unshown frame and a repeated sequence")
        void sequence() {
            var pictures = List.of(picture(8, 8, 7), picture(8, 8, 8), picture(8, 8, 9));
            var steps = List.of(new AniStep(2, 4), new AniStep(2, 4));
            var ani = AniEncoder.encode(pictures, steps, AniEncoderOptions.defaults());
            var chunks = parse(ani);
            assertEquals(List.of("anih", "seq ", "LIST fram"), chunks.order());
            assertEquals(3, chunks.icons().size());
            assertEquals(new AniHeader(3, steps, 4, Optional.empty(), Optional.empty()),
                    AniDecoder.header(MediaSource.of(ani)));
        }

        @Test
        @DisplayName("rejects empty lists, unknown frames and frames an icon cannot hold")
        void invalid() {
            var picture = picture(8, 8, 1);
            var options = AniEncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> AniEncoder.encode(List.of(), List.of(new AniStep(0, 1)), options));
            assertThrows(IllegalArgumentException.class, () -> AniEncoder.encode(List.of(picture), List.of(), options));
            assertThrows(IllegalArgumentException.class,
                    () -> AniEncoder.encode(List.of(picture), List.of(new AniStep(1, 1)), options));
            assertThrows(IllegalArgumentException.class, () -> AniEncoder.encode(picture(300, 8, 1)));
            assertThrows(IllegalArgumentException.class, () -> new AniStep(-1, 1));
            assertThrows(IllegalArgumentException.class, () -> new AniStep(0, -1));
            assertThrows(IllegalArgumentException.class, () -> new AniStep(0, AniStep.MAX_JIFFIES + 1));
            assertThrows(IllegalArgumentException.class, () -> new AniHeader(1, List.of(new AniStep(1, 1)), 1,
                    Optional.empty(), Optional.empty()));
            var scaled = AniEncoderOptions.builder().frameOptions(IcoEncoderOptions.builder().sizes(32).build()).build();
            var ani = AniEncoder.encode(picture(300, 200, 1), scaled);
            assertEquals(32, Images.probe(MediaSource.of(ani)).width());
        }

        @Test
        @DisplayName("checks the INFO text and keeps the options' value semantics")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> AniEncoderOptions.builder().title("\u4e2d").build());
            assertThrows(IllegalArgumentException.class, () -> AniEncoderOptions.builder().artist("a\0b").build());
            assertThrows(NullPointerException.class, () -> AniEncoderOptions.builder().title(null));
            var options = AniEncoderOptions.builder().title("t").artist("a").build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertEquals(AniEncoderOptions.defaults(), options.toBuilder().noTitle().noArtist().build());
            assertEquals(IcoEncoderOptions.defaults(), AniEncoderOptions.defaults().frameOptions());
        }
    }

    @Nested
    @DisplayName("the animation writer")
    class Writer {
        @Test
        @DisplayName("rounds the timeline to jiffies and stores repeated pictures once")
        void timing() {
            var a = picture(16, 16, 11);
            var b = picture(16, 16, 12);
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.ANI, 16, 16, sink)) {
                writer.write(a, 100_000);
                writer.write(b, 100_000);
                writer.write(a, 50_000);
                writer.write(b, 16_667);
                writer.write(b, 16_667);
                writer.finish();
            }
            var ani = sink.toByteArray();
            var chunks = parse(ani);
            assertEquals(2, chunks.icons().size());
            assertArrayEquals(new long[]{6, 6, 3, 1, 1}, chunks.rate());
            assertArrayEquals(new long[]{0, 1, 0, 1, 1}, chunks.seq());
            try (var reader = Images.openAnimation(MediaSource.of(ani))) {
                var frames = read(reader);
                assertEquals(5, frames.size());
                assertArrayEquals(pixels(a), RasterTesting.tight(frames.get(2).picture()));
                assertArrayEquals(pixels(b), RasterTesting.tight(frames.get(4).picture()));
                assertEquals(micros(17), frames.get(4).timestampMicros() + frames.get(4).durationMicros());
            }
        }

        @Test
        @DisplayName("enforces the writer contract and the loop count an animated cursor stores")
        void contract() {
            var picture = picture(16, 16, 1);
            assertThrows(MediaException.Unsupported.class, () -> Images.openAnimationWriter(ImageFormat.ANI, 16, 16,
                    ImageEncoderOptions.builder().loopCount(3).build(), MediaSink.memory()));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> AniEncoder.openAnimationWriter(300, 16, AniEncoderOptions.defaults(), MediaSink.memory()));
            assertThrows(IllegalArgumentException.class,
                    () -> AniEncoder.openAnimationWriter(0, 16, AniEncoderOptions.defaults(), MediaSink.memory()));
            try (var writer = AniEncoder.openAnimationWriter(16, 16, AniEncoderOptions.defaults(), MediaSink.memory())) {
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalArgumentException.class, () -> writer.write(picture(8, 16, 1), 1000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(picture, -1));
                writer.write(picture, 1000);
                writer.finish();
                assertThrows(IllegalStateException.class, () -> writer.write(picture, 1000));
                assertThrows(IllegalStateException.class, writer::finish);
            }
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.ANI, 300, 200, sink)) {
                writer.write(picture(300, 200, 3), 50_000);
                writer.finish();
            }
            var info = Images.probe(MediaSource.of(sink.toByteArray()));
            assertEquals(256, info.width());
            assertEquals(256, info.height());
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        byte[] sample() {
            return AniEncoder.encode(List.of(picture(12, 12, 1), picture(12, 12, 2), picture(12, 12, 3)),
                    List.of(new AniStep(0, 5), new AniStep(1, 10), new AniStep(2, 0), new AniStep(1, 7)),
                    AniEncoderOptions.defaults());
        }

        @Test
        @DisplayName("seeks to the step shown at a time")
        void seek() {
            var ani = sample();
            try (var reader = AniDecoder.openAnimation(MediaSource.of(ani))) {
                assertTrue(reader.isSeekable());
                var all = read(reader);
                reader.seek(-5);
                assertEquals(0, reader.read().timestampMicros());
                reader.seek(micros(5));
                assertEquals(micros(5), reader.read().timestampMicros());
                reader.seek(micros(15));
                var frame = reader.read();
                assertEquals(micros(15), frame.timestampMicros());
                assertEquals(micros(7), frame.durationMicros(), "the zero-duration step before it is passed");
                assertArrayEquals(RasterTesting.tight(all.get(3).picture()), RasterTesting.tight(frame.picture()));
                reader.seek(micros(14));
                assertArrayEquals(RasterTesting.tight(all.get(1).picture()), RasterTesting.tight(reader.read().picture()));
                reader.seek(micros(22));
                assertNull(reader.read());
                reader.seek(0);
                assertArrayEquals(RasterTesting.tight(all.get(0).picture()), RasterTesting.tight(reader.read().picture()));
            }
        }

        @Test
        @DisplayName("is recognized and summarized by the registry and by the header probe")
        void registry() {
            var ani = sample();
            assertEquals(ImageFormat.ANI, ImageFormat.detect(ani, ani.length).orElseThrow());
            assertEquals(ImageFormat.ANI, Images.detect(MediaSource.of(ani)).orElseThrow());
            assertTrue(Images.isDecodable(ImageFormat.ANI));
            assertTrue(Images.isEncodable(ImageFormat.ANI));
            assertTrue(Images.isAnimationEncodable(ImageFormat.ANI));
            var info = Images.probe(MediaSource.of(ani));
            assertEquals(ImageFormat.ANI, info.format());
            assertEquals(12, info.width());
            assertEquals(4, info.frameCount());
            assertTrue(info.animated());
            var decoded = Images.decode(MediaSource.of(ani));
            assertEquals(ImageFormat.ANI, decoded.format());
            assertArrayEquals(pixels(picture(12, 12, 1)), RasterTesting.tight(decoded.picture()));
            var probed = Media.probe(MediaSource.of(ani));
            assertEquals(ImageFormat.ANI, probed.imageFormat().orElseThrow());
            var image = probed.image().orElseThrow();
            assertEquals(12, image.width());
            assertEquals(12, image.height());
            assertEquals(4, image.frameCount().orElseThrow());
            assertTrue(image.isAnimated());
            assertEquals(0, image.loopCount().orElseThrow());
            assertEquals(micros(22), probed.durationMicros().orElseThrow());
            var still = Images.encode(picture(20, 10, 4), ImageFormat.ANI);
            assertEquals(ImageFormat.ANI, ImageFormat.detect(still, still.length).orElseThrow());
            assertFalse(Images.probe(MediaSource.of(still)).animated());
        }

        @Test
        @DisplayName("shows the entry the size hint selects in every frame")
        void sizeHint() {
            var frameOptions = IcoEncoderOptions.builder().sizes(16, 32).build();
            var ani = AniEncoder.encode(List.of(picture(32, 32, 1), picture(32, 32, 2)),
                    List.of(new AniStep(0, 1), new AniStep(1, 1)), AniEncoderOptions.builder().frameOptions(frameOptions).build());
            try (var reader = AniDecoder.openAnimation(MediaSource.of(ani))) {
                assertEquals(32, reader.width());
            }
            var hint = ImageDecoderOptions.builder().minSize(10, 10).build();
            try (var reader = AniDecoder.openAnimation(MediaSource.of(ani), hint)) {
                assertEquals(16, reader.width());
                var frame = reader.read();
                var icon = IcoEncoder.encode(picture(32, 32, 1), frameOptions);
                var expected = IcoDecoder.decode(MediaSource.of(icon), 0, ImageDecoderOptions.defaults());
                assertArrayEquals(RasterTesting.tight(expected.picture()), RasterTesting.tight(frame.picture()));
            }
            assertEquals(16, AniDecoder.decode(MediaSource.of(ani), hint).picture().width());
        }

        @Test
        @DisplayName("draws a smaller frame at the top-left corner of a transparent canvas")
        void canvas() {
            var small = picture(10, 10, 1);
            var wide = picture(20, 8, 2);
            var ani = AniEncoder.encode(List.of(small, wide), List.of(new AniStep(0, 1), new AniStep(1, 1)),
                    AniEncoderOptions.defaults());
            try (var reader = AniDecoder.openAnimation(MediaSource.of(ani))) {
                assertEquals(20, reader.width());
                assertEquals(10, reader.height());
                for (var index = 0; index < 2; index++) {
                    var source = index == 0 ? small : wide;
                    var frame = (RgbaPicture) reader.read().picture();
                    assertTrue(frame.hasAlpha());
                    var expected = new byte[20 * 10 * 4];
                    var rows = pixels(source);
                    for (var y = 0; y < source.height(); y++) {
                        System.arraycopy(rows, y * source.width() * 4, expected, y * 80, source.width() * 4);
                    }
                    assertArrayEquals(expected, RasterTesting.tight(frame), "frame " + index);
                }
            }
        }
    }

    @Nested
    @DisplayName("damaged and hostile files")
    class Damaged {
        @Test
        @DisplayName("rejects files without the anih chunk, frames or valid steps")
        void malformed() {
            var icon = icon(8, 1);
            assertThrows(MediaException.Malformed.class, () -> AniDecoder.header(MediaSource.of(riff(fram(icon)))));
            assertThrows(MediaException.Malformed.class, () -> AniDecoder.header(MediaSource.of(riff(anih(1, 1, 1, 1)))));
            assertThrows(MediaException.Malformed.class, () -> AniDecoder.header(MediaSource.of(
                    riff(anih(1, 2, 1, 3), chunk("seq ", le32(0, 1)), fram(icon)))));
            assertThrows(MediaException.Malformed.class, () -> AniDecoder.header(MediaSource.of(new byte[12])));
            assertThrows(MediaException.Malformed.class, () -> AniDecoder.decode(MediaSource.of(
                    riff(anih(1, 1, 1, 1), fram(new byte[]{0, 0, 1, 0, 1, 0, 9, 9})))));
        }

        @Test
        @DisplayName("reports raw frame data as unsupported and reads icon frames whatever the flags say")
        void rawFrames() {
            var raw = riff(anih(1, 1, 1, 0), fram(new byte[]{40, 0, 0, 0, 8, 0, 0, 0}));
            assertThrows(MediaException.Unsupported.class, () -> AniDecoder.header(MediaSource.of(raw)));
            assertThrows(MediaException.Unsupported.class, () -> Images.decode(MediaSource.of(raw)));
            var icon = icon(8, 2);
            var unflagged = riff(anih(1, 1, 1, 0), fram(icon));
            assertArrayEquals(decodedIcon(icon), RasterTesting.tight(AniDecoder.decode(MediaSource.of(unflagged)).picture()));
        }

        @Test
        @DisplayName("follows the documented leniencies for counts, sizes and chunk order")
        void lenient() {
            var icons = new byte[][]{icon(8, 1), icon(8, 2), icon(8, 3)};
            var zeroSteps = AniDecoder.header(MediaSource.of(riff(anih(0, 0, 4, 1), fram(icons))));
            assertEquals(3, zeroSteps.frameCount());
            assertEquals(List.of(new AniStep(0, 4), new AniStep(1, 4), new AniStep(2, 4)), zeroSteps.steps());
            var tooMany = AniDecoder.header(MediaSource.of(riff(anih(3, 9, 4, 1), fram(icons))));
            assertEquals(3, tooMany.steps().size());
            var shortSeq = AniDecoder.header(MediaSource.of(riff(anih(3, 5, 4, 3), chunk("seq ", le32(2, 0)), fram(icons))));
            assertEquals(List.of(new AniStep(2, 4), new AniStep(0, 4)), shortSeq.steps());
            var shortRate = AniDecoder.header(MediaSource.of(riff(anih(3, 3, 4, 1), chunk("rate", le32(9)), fram(icons))));
            assertEquals(List.of(new AniStep(0, 9), new AniStep(1, 4), new AniStep(2, 4)), shortRate.steps());
            var reordered = riff(chunk("junk", new byte[3]), fram(icons), chunk("rate", le32(1, 2, 3)), anih(3, 3, 4, 1),
                    chunk("anih", le32(36, 1, 1, 0, 0, 0, 0, 99, 1)), chunk("LIST", "INFO".getBytes(ANSI),
                            chunk("INAM", "odd\0".getBytes(ANSI)), chunk("ISFT", "x".getBytes(ANSI))));
            var header = AniDecoder.header(MediaSource.of(reordered));
            assertEquals(List.of(new AniStep(0, 1), new AniStep(1, 2), new AniStep(2, 3)), header.steps());
            assertEquals(4, header.displayRate());
            assertEquals("odd", header.title().orElseThrow());
            var oversized = riff(anih(3, 3, 4, 1), fram(icons));
            oversized[4] = (byte) 0xFF;
            oversized[5] = (byte) 0xFF;
            oversized[6] = (byte) 0xFF;
            assertEquals(3, AniDecoder.header(MediaSource.of(oversized)).steps().size());
            try (var reader = AniDecoder.openAnimation(MediaSource.of(oversized))) {
                assertArrayEquals(decodedIcon(icons[2]), RasterTesting.tight(read(reader).get(2).picture()));
            }
        }

        @Test
        @DisplayName("fails only with media exceptions on every truncation and on corrupted bytes")
        void truncations() {
            var ani = AniEncoder.encode(List.of(picture(8, 8, 1), picture(8, 8, 2)),
                    List.of(new AniStep(1, 2), new AniStep(0, 3)), AniEncoderOptions.builder().title("t").build());
            for (var cut = 0; cut <= ani.length; cut++) {
                exercise(Arrays.copyOf(ani, cut));
            }
            var random = new java.util.Random(7);
            for (var round = 0; round < 300; round++) {
                var damaged = ani.clone();
                for (var flips = 0; flips < 4; flips++) {
                    damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                }
                exercise(damaged);
            }
        }

        void exercise(byte[] data) {
            try {
                AniDecoder.header(MediaSource.of(data));
                try (var reader = AniDecoder.openAnimation(MediaSource.of(data))) {
                    read(reader);
                }
            } catch (MediaException expected) {
                // a damaged file may fail, but only with a media exception
            }
            try {
                Media.probe(MediaSource.of(data));
            } catch (MediaException expected) {
                // the header probe of the core module fails the same way
            }
        }

        @Test
        @DisplayName("checks the frame and step counts against the limits")
        void limits() {
            var icon = icon(8, 1);
            var frames = new byte[40][];
            Arrays.fill(frames, icon);
            var many = riff(anih(40, 40, 1, 1), fram(frames));
            var limits = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxFrames(10).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> AniDecoder.header(MediaSource.of(many), limits));
            var steps = riff(anih(1, 40, 1, 3), chunk("seq ", le32(new long[40])), fram(icon));
            assertThrows(MediaException.LimitExceeded.class, () -> AniDecoder.openAnimation(MediaSource.of(steps), limits));
            assertEquals(40, AniDecoder.header(MediaSource.of(steps)).steps().size());
        }
    }

    @Nested
    @DisplayName("the assembled sample")
    class Sample {
        static final Path SAMPLE = Path.of("src", "test", "resources", "samples", "ico", "animated.ani");

        @Test
        @DisplayName("reads the steps, the INFO text and the hotspots assemble-samples.py wrote")
        void structure() throws IOException {
            var ani = Files.readAllBytes(SAMPLE);
            var header = AniDecoder.header(MediaSource.of(ani));
            assertEquals(new AniHeader(3, List.of(new AniStep(0, 6), new AniStep(1, 3), new AniStep(2, 9), new AniStep(1, 3)),
                    6, Optional.of("Smalt sample"), Optional.of("ffmpeg testsrc2")), header);
            for (var i = 0; i < 3; i++) {
                var entries = IcoDecoder.entries(MediaSource.of(AniDecoder.frame(MediaSource.of(ani), i,
                        ImageDecoderOptions.defaults())));
                assertEquals(new IcoEntry.Hotspot(4, 5), entries.getFirst().hotspot().orElseThrow());
            }
        }

        @Test
        @DisplayName("decodes every frame to the pixels ffmpeg decodes from its icon file")
        void ffmpeg() throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var ani = Files.readAllBytes(SAMPLE);
            var header = AniDecoder.header(MediaSource.of(ani));
            var frames = read(AniDecoder.openAnimation(MediaSource.of(ani)));
            var directory = Files.createTempDirectory("ani");
            for (var step = 0; step < frames.size(); step++) {
                var icon = AniDecoder.frame(MediaSource.of(ani), header.steps().get(step).frame(), ImageDecoderOptions.defaults());
                // ffmpeg reads only icons, whose directory entries hold 1 plane and 32 bits where cursors hold the hotspot
                icon[2] = 1;
                for (var entry = 0; entry < (icon[4] & 0xFF); entry++) {
                    icon[6 + 16 * entry + 4] = 1;
                    icon[6 + 16 * entry + 5] = 0;
                    icon[6 + 16 * entry + 6] = 32;
                    icon[6 + 16 * entry + 7] = 0;
                }
                var file = Files.write(directory.resolve("frame" + step + ".ico"), icon);
                assertArrayEquals(Oracle.decodeVideo(file, "rgba", 1), RasterTesting.tight(frames.get(step).picture()),
                        "step " + step);
            }
        }
    }

    @Nested
    @DisplayName("the Windows system cursors")
    class SystemCursors {
        static List<Path> cursors() throws IOException {
            var directory = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "Cursors");
            Skip.unless(Files.isDirectory(directory), "platform:windows", "no Windows cursor directory");
            try (var files = Files.list(directory)) {
                var list = files.filter(file -> file.getFileName().toString().toLowerCase().endsWith(".ani")).sorted().toList();
                Skip.when(list.isEmpty(), "data:system-cursors", "no animated cursor");
                return list;
            }
        }

        @Test
        @DisplayName("read as their chunks declare them, every step equal to its icon file decoded alone")
        void systemCursors() throws IOException {
            for (var file : cursors()) {
                var ani = Files.readAllBytes(file);
                var chunks = parse(ani);
                var header = AniDecoder.header(MediaSource.of(ani));
                assertEquals(chunks.icons().size(), header.frameCount(), file.toString());
                assertEquals(chunks.anih(7), header.displayRate());
                var expectedSteps = chunks.seq() != null ? chunks.seq().length : chunks.icons().size();
                assertEquals(expectedSteps, header.steps().size());
                for (var i = 0; i < expectedSteps; i++) {
                    var step = header.steps().get(i);
                    assertEquals(chunks.seq() != null ? chunks.seq()[i] : i, step.frame());
                    assertEquals(chunks.rate() != null ? chunks.rate()[i] : chunks.anih(7), step.jiffies());
                }
                var info = Images.probe(MediaSource.of(ani));
                var probed = Media.probe(MediaSource.of(ani)).image().orElseThrow();
                try (var reader = AniDecoder.openAnimation(MediaSource.of(ani))) {
                    assertEquals(info.width(), reader.width());
                    assertEquals(probed.width(), reader.width());
                    assertEquals(probed.height(), reader.height());
                    assertEquals(expectedSteps, probed.frameCount().orElseThrow());
                    var frames = read(reader);
                    for (var i = 0; i < frames.size(); i++) {
                        var icon = chunks.icons().get(header.steps().get(i).frame());
                        var alone = IcoDecoder.decode(MediaSource.of(icon));
                        assertEquals(reader.width(), alone.picture().width());
                        assertArrayEquals(RasterTesting.tight(alone.picture()), RasterTesting.tight(frames.get(i).picture()),
                                file + " step " + i);
                    }
                }
            }
        }
    }
}
