package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.property.OracleEncoders;
import com.github.auties00.smalt.property.Streams;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.assertRejected;
import static com.github.auties00.smalt.hostile.Hostile.be16;
import static com.github.auties00.smalt.hostile.Hostile.be32;

/**
 * Decompression bombs for every video decoder. A bitstream header (a parameter set, a sequence header, a key frame
 * header or a frame header) declares a picture over the side or area limit while the container claims 16x16, so the
 * decoder has to check what the bitstream declares; and a real frame within those limits but whose buffers exceed
 * the buffer limit must be rejected before they are allocated, and decode once the limit allows it.
 */
@DisplayName("Video decompression bombs")
class VideoBombTest {
    private static final int[] SIDE = {5008, 16};

    private static final int[] AREA = {3008, 3008};

    private static final int LARGE = 1104;

    /**
     * A 16x16 VC-1 advanced profile stream: a sequence header, an entry point and an I picture.
     */
    private static final byte[] VC1_STREAM = {
            0x00, 0x00, 0x01, 0x0F, (byte) 0xD2, 0x00, 0x00, 0x70, 0x07, 0x08, (byte) 0x80, 0x00, 0x00, 0x01, 0x0E,
            0x40, 0x00, (byte) 0x80, 0x00, 0x00, 0x01, 0x0D, (byte) 0xC3, 0x00, 0x21, 0x2D, (byte) 0xCE
    };

    record Forged(Codec codec, byte[] config, List<byte[]> packets) {
    }

    @FunctionalInterface
    interface Forger {
        Forged forge(int width, int height);
    }

    record Bomb(Codec codec, Forger forger, int[] area) {
        Bomb(Codec codec, Forger forger) {
            this(codec, forger, AREA);
        }

        @Override
        public String toString() {
            return codec.name();
        }
    }

    static Stream<Arguments> bombs() {
        var bombs = List.of(
                new Bomb(Codec.H264, (width, height) -> {
                    var stream = ForgedHeaders.h264(width, height);
                    return new Forged(Codec.H264, stream.config(), List.of(stream.packet()));
                }),
                new Bomb(Codec.HEVC, (width, height) -> {
                    var stream = ForgedHeaders.hevc(width, height);
                    return new Forged(Codec.HEVC, stream.config(), List.of(stream.packet()));
                }),
                new Bomb(Codec.AV1, (width, height) -> new Forged(Codec.AV1, null,
                        List.of(ForgedHeaders.av1TemporalUnit(width, height)))),
                new Bomb(Codec.MPEG4_PART2, (width, height) -> {
                    var stream = ForgedHeaders.mpeg4(width, height);
                    return new Forged(Codec.MPEG4_PART2, stream.config(), List.of(stream.packet()));
                }),
                new Bomb(Codec.MPEG2_VIDEO, (width, height) -> {
                    var stream = ForgedHeaders.mpeg2(width, height);
                    return new Forged(Codec.MPEG2_VIDEO, stream.config(), List.of(stream.packet()));
                }),
                // the MAX_CODED_WIDTH and MAX_CODED_HEIGHT of an advanced profile sequence header, before an
                // entry point and an I picture of DC-only intra blocks
                new Bomb(Codec.VC1, (width, height) -> {
                    var stream = VC1_STREAM.clone();
                    patchBits(stream, 48, 12, width / 2 - 1);
                    patchBits(stream, 60, 12, height / 2 - 1);
                    return new Forged(Codec.VC1, null, List.of(stream));
                }),
                new Bomb(Codec.VP8, (width, height) -> patched(Codec.VP8, (packet) -> {
                    Hostile.le16(packet, 6, width & 0x3FFF);
                    Hostile.le16(packet, 8, height & 0x3FFF);
                })),
                new Bomb(Codec.VP9, (width, height) -> patched(Codec.VP9, (packet) -> {
                    if ((packet[1] & 0xFF) != 0x49 || (packet[2] & 0xFF) != 0x83 || (packet[3] & 0xFF) != 0x42
                            || (packet[4] & 0xE0) == 0xE0) {
                        throw new AssertionError("the first VP9 packet is not a profile 0 YUV key frame");
                    }
                    patchBits(packet, 36, 16, width - 1);
                    patchBits(packet, 52, 16, height - 1);
                })),
                new Bomb(Codec.MJPEG, (width, height) -> patched(Codec.MJPEG, (packet) -> {
                    var patched = ImageBombTest.jpeg(packet, width, height);
                    System.arraycopy(patched, 0, packet, 0, packet.length);
                })),
                // the SIZ segment of the frame's codestream, its single tile resized with the image
                new Bomb(Codec.JPEG2000, (width, height) -> patched(Codec.JPEG2000, (packet) -> {
                    var siz = 0;
                    while ((packet[siz] & 0xFF) != 0xFF || (packet[siz + 1] & 0xFF) != 0x51) {
                        siz++;
                    }
                    be32(packet, siz + 6, width);
                    be32(packet, siz + 10, height);
                    be32(packet, siz + 22, width);
                    be32(packet, siz + 26, height);
                })),
                new Bomb(Codec.PRORES, (width, height) -> patched(Codec.PRORES, (packet) -> {
                    be16(packet, 16, width);
                    be16(packet, 18, height);
                })),
                // A VC-3 header of 640 bytes indexes at most 68 macroblock rows, so the area bomb is short and wide.
                new Bomb(Codec.DNXHD, (width, height) -> patched(Codec.DNXHD, (packet) -> {
                    be16(packet, 0x18, height);
                    be16(packet, 0x1A, width);
                    be16(packet, 0x16C, (height + 15) >> 4);
                }), new int[]{4096, 1088}),
                new Bomb(Codec.THEORA, VideoBombTest::theora));
        var arguments = new ArrayList<Arguments>();
        for (var bomb : bombs) {
            arguments.add(Arguments.of(bomb, "side", SIDE));
            arguments.add(Arguments.of(bomb, "area", bomb.area()));
        }
        return arguments.stream();
    }

    /**
     * Resizes the identification header of a 16x16 Theora stream: the frame size in macro blocks and the picture size,
     * in the first of the three Xiph-laced headers of the configuration.
     */
    private static Forged theora(int width, int height) {
        var encoded = Streams.video(Codec.THEORA, 16, 16, 1, options(Codec.THEORA));
        var config = ((VideoTrack) encoded.track()).codecConfig().orElseThrow().clone();
        var offset = 1;
        for (var lace = 0; lace < 2; lace++) {
            while ((config[offset] & 0xFF) == 255) {
                offset++;
            }
            offset++;
        }
        if (config[offset] != (byte) 0x80 || config[offset + 1] != 't') {
            throw new AssertionError("the first Theora header is not the identification header");
        }
        be16(config, offset + 10, (width + 15) >> 4);
        be16(config, offset + 12, (height + 15) >> 4);
        patchBits(config, (offset + 14) * 8, 24, width);
        patchBits(config, (offset + 17) * 8, 24, height);
        return new Forged(Codec.THEORA, config, List.of(encoded.packets().getFirst().data()));
    }

    @FunctionalInterface
    interface Patch {
        void apply(byte[] packet);
    }

    private static Forged patched(Codec codec, Patch patch) {
        var encoded = Streams.video(codec, 16, 16, 1, options(codec));
        var packet = encoded.packets().getFirst().data().clone();
        patch.apply(packet);
        var config = encoded.track() instanceof VideoTrack video ? video.codecConfig().orElse(null) : null;
        return new Forged(codec, config, List.of(packet));
    }

    private static UnaryOperator<com.github.auties00.smalt.video.VideoEncoderOptions.Builder> options(Codec codec) {
        return builder -> builder.realtime(true).threads(1);
    }

    static void patchBits(byte[] data, int offset, int bits, long value) {
        for (var bit = 0; bit < bits; bit++) {
            var position = offset + bit;
            var mask = 0x80 >>> (position & 7);
            if ((value >>> (bits - 1 - bit) & 1) != 0) {
                data[position >> 3] |= (byte) mask;
            } else {
                data[position >> 3] &= (byte) ~mask;
            }
        }
    }

    private static void decode(VideoTrack track, List<byte[]> packets, MediaLimits limits) {
        var options = VideoDecoderOptions.builder().limits(limits).threads(1).build();
        try (var decoder = VideoDecoders.create(track, options)) {
            var frames = new int[1];
            for (var index = 0; index < packets.size(); index++) {
                var pts = index * 3600L;
                decoder.decode(new Packet(1, packets.get(index), pts, pts, 3600, index == 0), frame -> frames[0]++);
            }
            decoder.flush(frame -> frames[0]++);
        }
    }

    private static VideoTrack track(Codec codec, byte[] config, int width, int height) {
        var builder = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 90_000)).width(width)
                .height(height);
        if (config != null) {
            builder.codecConfig(config);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("a bitstream declaring a picture over the limits behind a 16x16 track")
    class Dimensions {
        @ParameterizedTest(name = "{0} over the {1} limit")
        @MethodSource("com.github.auties00.smalt.hostile.VideoBombTest#bombs")
        @DisplayName("is rejected with LimitExceeded before the picture is allocated")
        void rejected(Bomb bomb, String limit, int[] size) {
            var forged = bomb.forger().forge(size[0], size[1]);
            assertRejected(bomb + " " + limit, MediaException.LimitExceeded.class,
                    () -> decode(track(bomb.codec(), forged.config(), 16, 16), forged.packets(), LIMITS));
        }

        @ParameterizedTest(name = "{0} over the {1} limit")
        @MethodSource("com.github.auties00.smalt.hostile.VideoBombTest#bombs")
        @DisplayName("is rejected with LimitExceeded when the track declares the same size")
        void declared(Bomb bomb, String limit, int[] size) {
            var forged = bomb.forger().forge(size[0], size[1]);
            assertRejected(bomb + " " + limit, MediaException.LimitExceeded.class,
                    () -> decode(track(bomb.codec(), forged.config(), size[0], size[1]), forged.packets(), LIMITS));
        }
    }

    static Stream<Codec> encodable() {
        return Stream.of(Codec.H264, Codec.AV1, Codec.VP8, Codec.VP9, Codec.MJPEG, Codec.JPEG2000, Codec.PRORES,
                Codec.DNXHD, Codec.HEVC, Codec.MPEG4_PART2, Codec.MPEG2_VIDEO, Codec.THEORA, Codec.FFV1);
    }

    @Nested
    @DisplayName("a real frame whose buffers exceed the buffer limit")
    class Buffers {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.VideoBombTest#encodable")
        @DisplayName("is rejected before its buffers are allocated, and decodes once the limit allows it")
        void rejected(Codec codec) {
            var encoded = large(codec);
            var track = (VideoTrack) encoded.track();
            var packets = encoded.packets().stream().map(Packet::data).toList();
            var tight = LIMITS.toBuilder().maxBytes((long) LARGE * LARGE / 2).build();
            assertRejected(codec + " buffer", MediaException.LimitExceeded.class, () -> decode(track, packets, tight));
            decode(track, packets, LIMITS.toBuilder().maxBytes(256L << 20).build());
        }

        private static Streams.Encoded large(Codec codec) {
            return switch (codec) {
                case HEVC -> ffmpeg(codec, "libx265", "mp4", List.of("-x265-params", "log-level=error"));
                case MPEG2_VIDEO -> ffmpeg(codec, "mpeg2video", "ts", List.of());
                default -> Streams.video(codec, LARGE, LARGE, 2, options(codec));
            };
        }

        private static Streams.Encoded ffmpeg(Codec codec, String encoder, String extension, List<String> extra) {
            OracleEncoders.assumeFfmpegEncoder(encoder);
            var output = OracleEncoders.path("large-" + encoder + "." + extension);
            var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i",
                    "testsrc2=size=" + LARGE + "x" + LARGE + ":rate=25:duration=0.08", "-c:v", encoder,
                    "-pix_fmt", "yuv420p"));
            arguments.addAll(extra);
            OracleEncoders.ffmpeg(arguments, output);
            return Streams.demuxed(output, codec, 1);
        }
    }
}
