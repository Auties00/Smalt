package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries JPEG XS video through the video codec registries and the containers that map it: MPEG-2 transport streams
 * (H.222.0 Annex W), MP4 ({@code jxsm}), MXF (SMPTE ST 2124) and Matroska ({@code V_QUICKTIME} with a {@code jxsm}
 * sample description). Every container written is checked by the structural validators of the test kit, read back
 * and decoded; the Matroska files are also read by ffprobe and remuxed by ffmpeg as black boxes.
 */
class JpegXsContainersTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final Rational RATE = new Rational(25, 1);

    private static List<YuvPicture> pictures() {
        var pictures = new ArrayList<YuvPicture>();
        for (var index = 0; index < 4; index++) {
            pictures.add(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 40 + index));
        }
        return pictures;
    }

    private static double psnr(byte[] expected, byte[] actual) {
        var error = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var difference = (expected[i] & 0xFF) - (actual[i] & 0xFF);
            error += difference * difference;
        }
        error /= expected.length;
        return error == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / error);
    }

    private record Encoded(VideoTrack track, List<Packet> packets) {
    }

    private static Encoded encode(long bitrate) {
        var encoder = VideoEncoders.create(Codec.JPEG_XS, VideoEncoderOptions.of(WIDTH, HEIGHT, RATE, bitrate));
        var packets = new ArrayList<Packet>();
        for (var picture : pictures()) {
            encoder.encode(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true), packet -> packets.add(new Packet(1,
                    packet.data(), packet.pts(), packet.dts(), packet.duration(), true)));
        }
        encoder.flush(packets::add);
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(encoder.timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(RATE).codecConfig(encoder.codecConfig().orElseThrow()).build();
        return new Encoded(track, packets);
    }

    private static List<YuvPicture> decode(VideoTrack track, List<Packet> packets) {
        var pictures = new ArrayList<YuvPicture>();
        try (var decoder = VideoDecoders.create(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> {
                    assertTrue(frame.keyframe());
                    pictures.add(assertInstanceOf(YuvPicture.class, frame.picture()));
                });
            }
            decoder.flush(frame -> pictures.add((YuvPicture) frame.picture()));
        }
        return pictures;
    }

    @Test
    @DisplayName("encodes through the registry to 4:2:2 key frames the registry decodes near the source")
    void registry() {
        var encoded = encode(60_000_000);
        assertEquals(4, encoded.packets().size());
        var config = encoded.track().codecConfig().orElseThrow();
        // jxpl box at offset 22: size, type, then Ppih and Plev
        assertEquals(JpegXsProfile.MAIN_422_10.code(), (config[30] & 0xFF) << 8 | config[31] & 0xFF);
        var decoded = decode(encoded.track(), encoded.packets());
        var sources = pictures();
        assertEquals(4, decoded.size());
        for (var index = 0; index < 4; index++) {
            var picture = decoded.get(index);
            assertEquals(WIDTH, picture.width());
            assertEquals(HEIGHT, picture.height());
            assertEquals(ChromaFormat.YUV422, picture.chromaFormat());
            var quality = psnr(sources.get(index).y(), picture.y());
            assertTrue(quality > 35, "frame " + index + " luma PSNR " + quality);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContainerFormat.class, names = {"MPEG_TS", "MP4", "MXF", "MATROSKA"})
    @DisplayName("muxes valid files that demux to the same JPEG XS packets and decode")
    void containers(ContainerFormat format) {
        var encoded = encode(30_000_000);
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(format, sink)) {
            muxer.addTrack(encoded.track());
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var file = sink.toByteArray();
        Validators.assertValid("jpeg-xs-" + format.name().toLowerCase() + "." + switch (format) {
            case MPEG_TS -> "ts";
            case MP4 -> "mp4";
            case MATROSKA -> "mkv";
            default -> "mxf";
        }, file);
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        assertEquals(Codec.JPEG_XS, track.codec());
        assertEquals(WIDTH, track.width());
        assertEquals(HEIGHT, track.height());
        if (format != ContainerFormat.MXF) {
            // the jpvi and jxpl boxes (MP4, Matroska) or the jxes header (MPEG-TS) carry the whole configuration
            assertArrayEquals(encoded.track().codecConfig().orElseThrow(), track.codecConfig().orElseThrow());
            assertEquals(RATE, track.frameRate().orElseThrow().reduce());
        }
        assertEquals(encoded.packets().size(), packets.size());
        for (var index = 0; index < packets.size(); index++) {
            assertTrue(packets.get(index).keyframe(), "packet " + index);
            assertArrayEquals(encoded.packets().get(index).data(), packets.get(index).data(), "packet " + index);
        }
        assertEquals(encoded.packets().size(), decode(track, packets).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("probes a JXS file and a bare codestream to their format, size and depth")
    void probe(boolean file) {
        var picture = JxsSamples.yuv16(98, 50, ChromaFormat.YUV422, 10, 3);
        var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.defaults().toBuilder().fileFormat(file).build());
        var info = Media.probe(MediaSource.of(data));
        assertEquals(ImageFormat.JPEG_XS, info.imageFormat().orElseThrow());
        var image = info.image().orElseThrow();
        assertEquals(98, image.width());
        assertEquals(50, image.height());
        assertEquals(10, image.bitDepth().orElseThrow());
        var mosaic = JpegXsEncoder.encode(JxsSamples.bayer(32, 16, 12, JpegXsImage.CfaPattern.RGGB, 5),
                JpegXsEncoderOptions.defaults().toBuilder().fileFormat(file).build());
        var bayer = Media.probe(MediaSource.of(mosaic)).image().orElseThrow();
        assertEquals(32, bayer.width());
        assertEquals(16, bayer.height());
    }

    @Test
    @DisplayName("decodes a sequence of frames of changing content and size as each decodes alone")
    void sequence() {
        var options = JpegXsEncoderOptions.defaults().toBuilder().fileFormat(false).build();
        var frames = List.of(JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 1), options),
                JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 2), options),
                JpegXsEncoder.encode(JxsSamples.yuv(48, 32, ChromaFormat.YUV420, false, 3), options),
                JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 4), options));
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(RATE.invert()).width(WIDTH)
                .height(HEIGHT).build();
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < frames.size(); index++) {
            packets.add(new Packet(1, frames.get(index), index, index, 1, true));
        }
        var decoded = decode(track, packets);
        assertEquals(frames.size(), decoded.size());
        for (var index = 0; index < frames.size(); index++) {
            var alone = assertInstanceOf(YuvPicture.class, JpegXsDecoder.decode(MediaSource.of(frames.get(index)))
                    .picture());
            var picture = decoded.get(index);
            assertEquals(alone.width(), picture.width(), "frame " + index);
            assertEquals(alone.chromaFormat(), picture.chromaFormat(), "frame " + index);
            assertArrayEquals(alone.y(), picture.y(), "luma of frame " + index);
            assertArrayEquals(alone.u(), picture.u(), "Cb of frame " + index);
            assertArrayEquals(alone.v(), picture.v(), "Cr of frame " + index);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Interlacing.class, names = {"TOP_FIELD_FIRST", "BOTTOM_FIELD_FIRST"})
    @DisplayName("weaves the two field codestreams of a packet in the track's field order")
    void fields(Interlacing scan) {
        int[] sx = {1, 2, 2};
        int[] sy = {1, 1, 1};
        var first = JxsSamples.image(WIDTH, HEIGHT / 2, 8, sx, sy, 7);
        var second = JxsSamples.image(WIDTH, HEIGHT / 2, 8, sx, sy, 8);
        var options = JpegXsEncoderOptions.forLossless().toBuilder().fileFormat(false).build();
        var a = JpegXsEncoder.encode(first, options);
        var b = JpegXsEncoder.encode(second, options);
        var data = new byte[a.length + b.length];
        System.arraycopy(a, 0, data, 0, a.length);
        System.arraycopy(b, 0, data, a.length, b.length);
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(RATE.invert()).width(WIDTH)
                .height(HEIGHT).interlacing(scan).build();
        var pictures = decode(track, List.of(new Packet(1, data, 0, 0, 1, true)));
        var picture = pictures.getFirst();
        assertEquals(HEIGHT, picture.height());
        var top = scan == Interlacing.TOP_FIELD_FIRST ? first : second;
        var bottom = scan == Interlacing.TOP_FIELD_FIRST ? second : first;
        for (var y = 0; y < HEIGHT; y++) {
            var field = (y & 1) == 0 ? top : bottom;
            var samples = field.components().getFirst().samples();
            for (var x = 0; x < WIDTH; x++) {
                assertEquals(samples[(y >> 1) * WIDTH + x], picture.y()[y * picture.yStride() + x] & 0xFF,
                        "sample " + x + "," + y);
            }
        }
    }

    private static byte[] mux(ContainerFormat format, VideoTrack track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(format, sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private record Demuxed(VideoTrack track, List<Packet> packets) {
    }

    private static Demuxed demux(byte[] file) {
        var packets = new ArrayList<Packet>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Demuxed(track, packets);
        }
    }

    /**
     * {@return the offset of the only track's {@code CodecPrivate}: the QuickTime sample description whose type,
     * {@code jxsm}, follows its size}
     */
    private static int description(byte[] file) {
        var found = -1;
        for (var i = 4; i + 4 <= file.length; i++) {
            if (file[i] == 'j' && file[i + 1] == 'x' && file[i + 2] == 's' && file[i + 3] == 'm') {
                assertEquals(-1, found, "a second jxsm type");
                found = i - 4;
            }
        }
        assertTrue(found > 0, "no jxsm sample description");
        return found;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    @Test
    @DisplayName("stores a Matroska track as V_QUICKTIME with the jxsm sample description and its jpvi and jxpl boxes")
    void matroskaDescription() {
        var encoded = encode(30_000_000);
        var file = mux(ContainerFormat.MATROSKA, encoded.track(), encoded.packets());
        var text = new String(file, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("V_QUICKTIME"), "codec identifier");
        var at = description(file);
        var config = encoded.track().codecConfig().orElseThrow();
        assertEquals(86 + config.length, u32(file, at), "sample description size");
        // the CodecPrivate element (ID 0x63A2) holds exactly the description, its size a one-byte EBML length
        assertEquals(0x63, file[at - 3] & 0xFF);
        assertEquals(0xA2, file[at - 2] & 0xFF);
        assertEquals(0x80 | 86 + config.length, file[at - 1] & 0xFF);
        assertEquals(1, u16(file, at + 14), "data reference index");
        assertEquals(WIDTH, u16(file, at + 32));
        assertEquals(HEIGHT, u16(file, at + 34));
        assertEquals(0x00480000, u32(file, at + 36), "horizontal resolution");
        assertEquals(1, u16(file, at + 48), "frame count");
        assertEquals(7, file[at + 50], "compressor name length");
        assertEquals("JPEG XS", text.substring(at + 51, at + 58));
        assertEquals(24, u16(file, at + 82), "depth");
        assertEquals(0xFFFF, u16(file, at + 84), "color table ID");
        assertArrayEquals(config, Arrays.copyOfRange(file, at + 86, at + 86 + config.length));
    }

    @Test
    @DisplayName("derives the jpvi and jxpl boxes of a Matroska track without configuration from its first codestream")
    void matroskaDerived() {
        var encoded = encode(30_000_000);
        var bare = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(encoded.track().timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(RATE).build();
        var file = mux(ContainerFormat.MATROSKA, bare, encoded.packets());
        Validators.assertValid("jpeg-xs-derived.mkv", file);
        var demuxed = demux(file);
        // the encoder's configuration but for the bit rate (jpvi brat), which no codestream states
        var expected = encoded.track().codecConfig().orElseThrow().clone();
        Arrays.fill(expected, 8, 12, (byte) 0);
        assertArrayEquals(expected, demuxed.track().codecConfig().orElseThrow());
        assertEquals(JpegXsProfile.MAIN_422_10.code(), u16(demuxed.track().codecConfig().orElseThrow(), 30));
        assertEquals(encoded.packets().size(), decode(demuxed.track(), demuxed.packets()).size());
    }

    @Test
    @DisplayName("takes a Matroska track's frame rate and field order from its jpvi box when the track declares none")
    void matroskaVideoInformation() {
        var encoded = encode(30_000_000);
        var config = encoded.track().codecConfig().orElseThrow().clone();
        // frat: top field first (1 at bit 30), denominator code 2 (1.001, at bit 24), numerator 60
        var frat = 1 << 30 | 2 << 24 | 60;
        config[12] = (byte) (frat >>> 24);
        config[13] = (byte) (frat >>> 16);
        config[14] = (byte) (frat >>> 8);
        config[15] = (byte) frat;
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(encoded.track().timeBase()).width(WIDTH)
                .height(HEIGHT).codecConfig(config).build();
        var demuxed = demux(mux(ContainerFormat.MATROSKA, track, encoded.packets()));
        assertArrayEquals(config, demuxed.track().codecConfig().orElseThrow());
        assertEquals(new Rational(60_000, 1001), demuxed.track().frameRate().orElseThrow());
        assertEquals(Interlacing.TOP_FIELD_FIRST, demuxed.track().interlacing().orElseThrow());
        // a declared rate and scan win over the box
        var declared = demux(mux(ContainerFormat.MATROSKA, track.toBuilder().frameRate(RATE)
                .interlacing(Interlacing.BOTTOM_FIELD_FIRST).build(), encoded.packets())).track();
        assertEquals(RATE, declared.frameRate().orElseThrow().reduce());
        assertEquals(Interlacing.BOTTOM_FIELD_FIRST, declared.interlacing().orElseThrow());
    }

    @Test
    @DisplayName("ffprobe reads the jxsm sample description and ffmpeg copies the packets of a Matroska track unchanged")
    void matroskaFfmpeg() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assume(Oracle.Tool.FFPROBE);
        var encoded = encode(30_000_000);
        var directory = Scratch.directory("jpeg-xs-matroska");
        var input = directory.resolve("smalt.mkv");
        Files.write(input, mux(ContainerFormat.MATROSKA, encoded.track(), encoded.packets()));
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-count_packets", "-show_entries", "stream=codec_tag_string,width,height,r_frame_rate,"
                        + "extradata_size,nb_read_packets", "-of", "default=noprint_wrappers=1", input.toString())),
                StandardCharsets.UTF_8);
        for (var line : List.of("codec_tag_string=jxsm", "width=" + WIDTH, "height=" + HEIGHT, "r_frame_rate=25/1",
                "extradata_size=" + (86 + encoded.track().codecConfig().orElseThrow().length),
                "nb_read_packets=" + encoded.packets().size())) {
            assertTrue(probe.lines().anyMatch(line::equals), line + " in " + probe);
        }
        // ffmpeg's packet hashes are those of the codestreams written
        var hashes = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                input.toString(), "-map", "0", "-c", "copy", "-f", "framemd5", "-hash", "md5", "-")),
                StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        assertEquals(encoded.packets().size(), hashes.size(), String.join(" | ", hashes));
        for (var index = 0; index < hashes.size(); index++) {
            var fields = hashes.get(index).split(",");
            assertEquals(HexFormat.of().formatHex(md5(encoded.packets().get(index).data())),
                    fields[fields.length - 1].trim(), "packet " + index);
        }
        // ffmpeg 7.1 and 8 copy the track as V_MS/VFW/FOURCC of FourCC jxsm, the sample description after the
        // BITMAPINFOHEADER; it reads back the same
        var remuxed = directory.resolve("ffmpeg.mkv");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", input.toString(),
                "-map", "0", "-c", "copy", remuxed.toString()));
        var copied = Files.readAllBytes(remuxed);
        var identifiers = new String(copied, StandardCharsets.ISO_8859_1);
        assertTrue(identifiers.contains("V_MS/VFW/FOURCC") || identifiers.contains("V_QUICKTIME"), "codec identifier");
        var demuxed = demux(copied);
        assertEquals(Codec.JPEG_XS, demuxed.track().codec());
        assertArrayEquals(encoded.track().codecConfig().orElseThrow(), demuxed.track().codecConfig().orElseThrow());
        assertEquals(encoded.packets().size(), demuxed.packets().size());
        for (var index = 0; index < demuxed.packets().size(); index++) {
            assertArrayEquals(encoded.packets().get(index).data(), demuxed.packets().get(index).data(),
                    "packet " + index);
        }
        assertEquals(encoded.packets().size(), decode(demuxed.track(), demuxed.packets()).size());
    }

    private static byte[] ebml(long id, byte[]... children) {
        var payload = new java.io.ByteArrayOutputStream();
        for (var child : children) {
            payload.writeBytes(child);
        }
        var out = new java.io.ByteArrayOutputStream();
        var idLength = (64 - Long.numberOfLeadingZeros(id) + 7) / 8;
        for (var i = idLength - 1; i >= 0; i--) {
            out.write((int) (id >>> (8 * i)));
        }
        // an eight-byte EBML size: 0x01 marker and seven size bytes
        long size = payload.size();
        out.write(0x01);
        for (var i = 6; i >= 0; i--) {
            out.write((int) (size >>> (8 * i)));
        }
        out.writeBytes(payload.toByteArray());
        return out.toByteArray();
    }

    private static byte[] ebmlUint(long id, long value) {
        return ebml(id, new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /**
     * {@return a Matroska file of one video track without {@code DefaultDuration} or {@code FieldOrder}, of the
     * given codec identifier and private, holding the packets as simple blocks}
     */
    private static byte[] handMade(String codecId, byte[] codecPrivate, List<Packet> packets) {
        var header = ebml(0x1A45DFA3, ebml(0x4282, "matroska".getBytes(StandardCharsets.US_ASCII)),
                ebmlUint(0x4287, 4), ebmlUint(0x4285, 2));
        var info = ebml(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000));
        var track = ebml(0xAE, ebmlUint(0xD7, 1), ebmlUint(0x73C5, 1), ebmlUint(0x83, 1),
                ebml(0x86, codecId.getBytes(StandardCharsets.US_ASCII)), ebml(0x63A2, codecPrivate),
                ebml(0xE0, ebmlUint(0xB0, WIDTH), ebmlUint(0xBA, HEIGHT)));
        var blocks = new java.io.ByteArrayOutputStream();
        blocks.writeBytes(ebmlUint(0xE7, 0));
        for (var index = 0; index < packets.size(); index++) {
            var timecode = index * 40;
            blocks.writeBytes(ebml(0xA3, new byte[]{(byte) 0x81, (byte) (timecode >>> 8), (byte) timecode,
                    (byte) 0x80}, packets.get(index).data()));
        }
        return concat(header, ebml(0x18538067, info, ebml(0x1654AE6B, track),
                ebml(0x1F43B675, blocks.toByteArray())));
    }

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** {@return a 40-byte {@code BITMAPINFOHEADER} of FourCC {@code jxsm} followed by the extra bytes} */
    private static byte[] bitmapInfo(byte[] extra) {
        var info = java.nio.ByteBuffer.allocate(40 + extra.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        info.putInt(40 + extra.length).putInt(WIDTH).putInt(HEIGHT).putShort((short) 1).putShort((short) 24)
                .put("jxsm".getBytes(StandardCharsets.US_ASCII)).putInt(WIDTH * HEIGHT * 3).putInt(0).putInt(0)
                .putInt(0).putInt(0).put(extra);
        return info.array();
    }

    @Test
    @DisplayName("reads hand-made V_MS/VFW/FOURCC and V_QUICKTIME jxsm tracks, complete, bare, truncated and padded")
    void matroskaCompatibility() {
        var encoded = encode(30_000_000);
        var config = encoded.track().codecConfig().orElseThrow();
        var muxed = mux(ContainerFormat.MATROSKA, encoded.track(), encoded.packets());
        var at = description(muxed);
        var description = Arrays.copyOfRange(muxed, at, at + 86 + config.length);

        // the layout ffmpeg copies a V_QUICKTIME jxsm track to: the description after the BITMAPINFOHEADER; no
        // DefaultDuration, so the rate comes from jpvi
        var vfw = demux(handMade("V_MS/VFW/FOURCC", bitmapInfo(description), encoded.packets()));
        assertEquals(Codec.JPEG_XS, vfw.track().codec());
        assertArrayEquals(config, vfw.track().codecConfig().orElseThrow());
        assertEquals(RATE, vfw.track().frameRate().orElseThrow().reduce());
        assertTrue(vfw.track().interlacing().isEmpty(), "progressive jpvi");
        assertEquals(encoded.packets().size(), vfw.packets().size());
        for (var index = 0; index < vfw.packets().size(); index++) {
            assertArrayEquals(encoded.packets().get(index).data(), vfw.packets().get(index).data(), "packet " + index);
        }
        assertEquals(encoded.packets().size(), decode(vfw.track(), vfw.packets()).size());

        // a bare BITMAPINFOHEADER, and extra bytes that are not a jxsm description, give no configuration
        var bare = demux(handMade("V_MS/VFW/FOURCC", bitmapInfo(new byte[0]), encoded.packets())).track();
        assertEquals(Codec.JPEG_XS, bare.codec());
        assertTrue(bare.codecConfig().isEmpty(), "configuration of a bare header");
        assertTrue(bare.frameRate().isEmpty(), "frame rate of a bare header");
        var other = description.clone();
        other[4] = 'm';
        other[5] = 'j';
        other[6] = 'p';
        other[7] = '2';
        var foreign = demux(handMade("V_MS/VFW/FOURCC", bitmapInfo(other), encoded.packets())).track();
        assertEquals(Codec.JPEG_XS, foreign.codec());
        assertTrue(foreign.codecConfig().isEmpty(), "configuration of a foreign description");

        // a V_QUICKTIME description cut inside its fixed fields still names JPEG XS, without configuration
        var truncated = demux(handMade("V_QUICKTIME", Arrays.copyOf(description, 40), encoded.packets())).track();
        assertEquals(Codec.JPEG_XS, truncated.codec());
        assertTrue(truncated.codecConfig().isEmpty(), "configuration of a truncated description");

        // a box after the end the description's size declares is not part of it
        // a jxpl box of profile 0x1500 and level 0x40
        var padding = new byte[]{0, 0, 0, 12, 'j', 'x', 'p', 'l', 0x15, 0, 0, 0x40};
        var padded = demux(handMade("V_QUICKTIME", concat(description, padding), encoded.packets())).track();
        assertArrayEquals(config, padded.codecConfig().orElseThrow());
        var vfwPadded = demux(handMade("V_MS/VFW/FOURCC", bitmapInfo(concat(description, padding)),
                encoded.packets())).track();
        assertArrayEquals(config, vfwPadded.codecConfig().orElseThrow());
    }

    @Test
    @DisplayName("derives no boxes, and stays readable, when a Matroska track's first packet is not a JPEG XS codestream")
    void matroskaDerivedGarbage() {
        var encoded = encode(30_000_000);
        var bare = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(encoded.track().timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(RATE).interlacing(Interlacing.TOP_FIELD_FIRST).build();
        var garbage = new Packet(1, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 0, 0, 1, true);
        var file = mux(ContainerFormat.MATROSKA, bare, List.of(garbage));
        var track = demux(file).track();
        assertEquals(Codec.JPEG_XS, track.codec());
        assertTrue(track.codecConfig().isEmpty(), "configuration");
        assertEquals(86, u32(file, description(file)), "a description without boxes");
        // an interlaced track's derived jpvi carries its field order
        var derived = demux(mux(ContainerFormat.MATROSKA, bare, encoded.packets())).track();
        var frat = u32(derived.codecConfig().orElseThrow(), 12);
        assertEquals(1, frat >>> 30, "top field first");
        assertEquals(1 << 24 | 25, frat & 0x3FFFFFFF, "25 over 1");
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @DisplayName("reads a Matroska jxsm description with a box overrunning it or without jxpl as JPEG XS")
    void matroskaMalformed() {
        var encoded = encode(30_000_000);
        var config = encoded.track().codecConfig().orElseThrow();
        var file = mux(ContainerFormat.MATROSKA, encoded.track(), encoded.packets());
        var at = description(file);
        // the jpvi box claims more bytes than the description holds, so no box is read
        var oversized = file.clone();
        oversized[at + 86 + 3] = (byte) 0x7F;
        var track = demux(oversized).track();
        assertEquals(Codec.JPEG_XS, track.codec());
        assertTrue(track.codecConfig().isEmpty(), "configuration");
        // jpvi alone: the profile and level fields stay zero
        var unknown = file.clone();
        unknown[at + 86 + 22 + 4] = 'x';
        var partial = demux(unknown).track().codecConfig().orElseThrow();
        assertArrayEquals(Arrays.copyOf(config, 22), Arrays.copyOf(partial, 22));
        assertEquals(0, u16(partial, 30));
        assertEquals(0, u16(partial, 32));
        assertEquals(encoded.packets().size(), demux(unknown).packets().size());
    }
}
