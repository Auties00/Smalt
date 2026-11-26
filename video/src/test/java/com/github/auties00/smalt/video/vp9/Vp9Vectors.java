package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.vp9.internal.Vp9Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** The libvpx VP9 test vectors (test/test_vectors.cc), read from the hosted test data, and their readers. */
final class Vp9Vectors {
    /** The vector names; each has its per-frame MD5 reference in {@code <name>.md5}. */
    private static final String MANIFEST = """
            vp90-2-00-quantizer-00.webm
            vp90-2-00-quantizer-01.webm
            vp90-2-00-quantizer-02.webm
            vp90-2-00-quantizer-03.webm
            vp90-2-00-quantizer-04.webm
            vp90-2-00-quantizer-05.webm
            vp90-2-00-quantizer-06.webm
            vp90-2-00-quantizer-07.webm
            vp90-2-00-quantizer-08.webm
            vp90-2-00-quantizer-09.webm
            vp90-2-00-quantizer-10.webm
            vp90-2-00-quantizer-11.webm
            vp90-2-00-quantizer-12.webm
            vp90-2-00-quantizer-13.webm
            vp90-2-00-quantizer-14.webm
            vp90-2-00-quantizer-15.webm
            vp90-2-00-quantizer-16.webm
            vp90-2-00-quantizer-17.webm
            vp90-2-00-quantizer-18.webm
            vp90-2-00-quantizer-19.webm
            vp90-2-00-quantizer-20.webm
            vp90-2-00-quantizer-21.webm
            vp90-2-00-quantizer-22.webm
            vp90-2-00-quantizer-23.webm
            vp90-2-00-quantizer-24.webm
            vp90-2-00-quantizer-25.webm
            vp90-2-00-quantizer-26.webm
            vp90-2-00-quantizer-27.webm
            vp90-2-00-quantizer-28.webm
            vp90-2-00-quantizer-29.webm
            vp90-2-00-quantizer-30.webm
            vp90-2-00-quantizer-31.webm
            vp90-2-00-quantizer-32.webm
            vp90-2-00-quantizer-33.webm
            vp90-2-00-quantizer-34.webm
            vp90-2-00-quantizer-35.webm
            vp90-2-00-quantizer-36.webm
            vp90-2-00-quantizer-37.webm
            vp90-2-00-quantizer-38.webm
            vp90-2-00-quantizer-39.webm
            vp90-2-00-quantizer-40.webm
            vp90-2-00-quantizer-41.webm
            vp90-2-00-quantizer-42.webm
            vp90-2-00-quantizer-43.webm
            vp90-2-00-quantizer-44.webm
            vp90-2-00-quantizer-45.webm
            vp90-2-00-quantizer-46.webm
            vp90-2-00-quantizer-47.webm
            vp90-2-00-quantizer-48.webm
            vp90-2-00-quantizer-49.webm
            vp90-2-00-quantizer-50.webm
            vp90-2-00-quantizer-51.webm
            vp90-2-00-quantizer-52.webm
            vp90-2-00-quantizer-53.webm
            vp90-2-00-quantizer-54.webm
            vp90-2-00-quantizer-55.webm
            vp90-2-00-quantizer-56.webm
            vp90-2-00-quantizer-57.webm
            vp90-2-00-quantizer-58.webm
            vp90-2-00-quantizer-59.webm
            vp90-2-00-quantizer-60.webm
            vp90-2-00-quantizer-61.webm
            vp90-2-00-quantizer-62.webm
            vp90-2-00-quantizer-63.webm
            vp90-2-01-sharpness-1.webm
            vp90-2-01-sharpness-2.webm
            vp90-2-01-sharpness-3.webm
            vp90-2-01-sharpness-4.webm
            vp90-2-01-sharpness-5.webm
            vp90-2-01-sharpness-6.webm
            vp90-2-01-sharpness-7.webm
            vp90-2-02-size-08x08.webm
            vp90-2-02-size-08x10.webm
            vp90-2-02-size-08x16.webm
            vp90-2-02-size-08x18.webm
            vp90-2-02-size-08x32.webm
            vp90-2-02-size-08x34.webm
            vp90-2-02-size-08x64.webm
            vp90-2-02-size-08x66.webm
            vp90-2-02-size-10x08.webm
            vp90-2-02-size-10x10.webm
            vp90-2-02-size-10x16.webm
            vp90-2-02-size-10x18.webm
            vp90-2-02-size-10x32.webm
            vp90-2-02-size-10x34.webm
            vp90-2-02-size-10x64.webm
            vp90-2-02-size-10x66.webm
            vp90-2-02-size-130x132.webm
            vp90-2-02-size-132x130.webm
            vp90-2-02-size-132x132.webm
            vp90-2-02-size-16x08.webm
            vp90-2-02-size-16x10.webm
            vp90-2-02-size-16x16.webm
            vp90-2-02-size-16x18.webm
            vp90-2-02-size-16x32.webm
            vp90-2-02-size-16x34.webm
            vp90-2-02-size-16x64.webm
            vp90-2-02-size-16x66.webm
            vp90-2-02-size-178x180.webm
            vp90-2-02-size-180x178.webm
            vp90-2-02-size-180x180.webm
            vp90-2-02-size-18x08.webm
            vp90-2-02-size-18x10.webm
            vp90-2-02-size-18x16.webm
            vp90-2-02-size-18x18.webm
            vp90-2-02-size-18x32.webm
            vp90-2-02-size-18x34.webm
            vp90-2-02-size-18x64.webm
            vp90-2-02-size-18x66.webm
            vp90-2-02-size-32x08.webm
            vp90-2-02-size-32x10.webm
            vp90-2-02-size-32x16.webm
            vp90-2-02-size-32x18.webm
            vp90-2-02-size-32x32.webm
            vp90-2-02-size-32x34.webm
            vp90-2-02-size-32x64.webm
            vp90-2-02-size-32x66.webm
            vp90-2-02-size-34x08.webm
            vp90-2-02-size-34x10.webm
            vp90-2-02-size-34x16.webm
            vp90-2-02-size-34x18.webm
            vp90-2-02-size-34x32.webm
            vp90-2-02-size-34x34.webm
            vp90-2-02-size-34x64.webm
            vp90-2-02-size-34x66.webm
            vp90-2-02-size-64x08.webm
            vp90-2-02-size-64x10.webm
            vp90-2-02-size-64x16.webm
            vp90-2-02-size-64x18.webm
            vp90-2-02-size-64x32.webm
            vp90-2-02-size-64x34.webm
            vp90-2-02-size-64x64.webm
            vp90-2-02-size-64x66.webm
            vp90-2-02-size-66x08.webm
            vp90-2-02-size-66x10.webm
            vp90-2-02-size-66x16.webm
            vp90-2-02-size-66x18.webm
            vp90-2-02-size-66x32.webm
            vp90-2-02-size-66x34.webm
            vp90-2-02-size-66x64.webm
            vp90-2-02-size-66x66.webm
            vp90-2-02-size-lf-1920x1080.webm
            vp90-2-03-deltaq.webm
            vp90-2-03-size-196x196.webm
            vp90-2-03-size-196x198.webm
            vp90-2-03-size-196x200.webm
            vp90-2-03-size-196x202.webm
            vp90-2-03-size-196x208.webm
            vp90-2-03-size-196x210.webm
            vp90-2-03-size-196x224.webm
            vp90-2-03-size-196x226.webm
            vp90-2-03-size-198x196.webm
            vp90-2-03-size-198x198.webm
            vp90-2-03-size-198x200.webm
            vp90-2-03-size-198x202.webm
            vp90-2-03-size-198x208.webm
            vp90-2-03-size-198x210.webm
            vp90-2-03-size-198x224.webm
            vp90-2-03-size-198x226.webm
            vp90-2-03-size-200x196.webm
            vp90-2-03-size-200x198.webm
            vp90-2-03-size-200x200.webm
            vp90-2-03-size-200x202.webm
            vp90-2-03-size-200x208.webm
            vp90-2-03-size-200x210.webm
            vp90-2-03-size-200x224.webm
            vp90-2-03-size-200x226.webm
            vp90-2-03-size-202x196.webm
            vp90-2-03-size-202x198.webm
            vp90-2-03-size-202x200.webm
            vp90-2-03-size-202x202.webm
            vp90-2-03-size-202x208.webm
            vp90-2-03-size-202x210.webm
            vp90-2-03-size-202x224.webm
            vp90-2-03-size-202x226.webm
            vp90-2-03-size-208x196.webm
            vp90-2-03-size-208x198.webm
            vp90-2-03-size-208x200.webm
            vp90-2-03-size-208x202.webm
            vp90-2-03-size-208x208.webm
            vp90-2-03-size-208x210.webm
            vp90-2-03-size-208x224.webm
            vp90-2-03-size-208x226.webm
            vp90-2-03-size-210x196.webm
            vp90-2-03-size-210x198.webm
            vp90-2-03-size-210x200.webm
            vp90-2-03-size-210x202.webm
            vp90-2-03-size-210x208.webm
            vp90-2-03-size-210x210.webm
            vp90-2-03-size-210x224.webm
            vp90-2-03-size-210x226.webm
            vp90-2-03-size-224x196.webm
            vp90-2-03-size-224x198.webm
            vp90-2-03-size-224x200.webm
            vp90-2-03-size-224x202.webm
            vp90-2-03-size-224x208.webm
            vp90-2-03-size-224x210.webm
            vp90-2-03-size-224x224.webm
            vp90-2-03-size-224x226.webm
            vp90-2-03-size-226x196.webm
            vp90-2-03-size-226x198.webm
            vp90-2-03-size-226x200.webm
            vp90-2-03-size-226x202.webm
            vp90-2-03-size-226x208.webm
            vp90-2-03-size-226x210.webm
            vp90-2-03-size-226x224.webm
            vp90-2-03-size-226x226.webm
            vp90-2-05-resize.ivf
            vp90-2-06-bilinear.webm
            vp90-2-07-frame_parallel.webm
            vp90-2-07-frame_parallel-1.webm
            vp90-2-08-tile-4x1.webm
            vp90-2-08-tile-4x4.webm
            vp90-2-08-tile_1x2.webm
            vp90-2-08-tile_1x2_frame_parallel.webm
            vp90-2-08-tile_1x4.webm
            vp90-2-08-tile_1x4_frame_parallel.webm
            vp90-2-08-tile_1x8.webm
            vp90-2-08-tile_1x8_frame_parallel.webm
            vp90-2-09-aq2.webm
            vp90-2-09-lf_deltas.webm
            vp90-2-09-subpixel-00.ivf
            vp90-2-10-show-existing-frame.webm
            vp90-2-10-show-existing-frame2.webm
            vp90-2-11-size-351x287.webm
            vp90-2-11-size-351x288.webm
            vp90-2-11-size-352x287.webm
            vp90-2-12-droppable_1.ivf
            vp90-2-12-droppable_3.ivf
            vp90-2-13-largescaling.webm
            vp90-2-14-resize-10frames-fp-tiles-1-2-4-8.webm
            vp90-2-14-resize-10frames-fp-tiles-1-2.webm
            vp90-2-14-resize-10frames-fp-tiles-1-4.webm
            vp90-2-14-resize-10frames-fp-tiles-1-8.webm
            vp90-2-14-resize-10frames-fp-tiles-2-1.webm
            vp90-2-14-resize-10frames-fp-tiles-2-4.webm
            vp90-2-14-resize-10frames-fp-tiles-2-8.webm
            vp90-2-14-resize-10frames-fp-tiles-4-1.webm
            vp90-2-14-resize-10frames-fp-tiles-4-2.webm
            vp90-2-14-resize-10frames-fp-tiles-4-8.webm
            vp90-2-14-resize-10frames-fp-tiles-8-1.webm
            vp90-2-14-resize-10frames-fp-tiles-8-2.webm
            vp90-2-14-resize-10frames-fp-tiles-8-4-2-1.webm
            vp90-2-14-resize-10frames-fp-tiles-8-4.webm
            vp90-2-14-resize-fp-tiles-1-16.webm
            vp90-2-14-resize-fp-tiles-1-2-4-8-16.webm
            vp90-2-14-resize-fp-tiles-1-2.webm
            vp90-2-14-resize-fp-tiles-1-4.webm
            vp90-2-14-resize-fp-tiles-1-8.webm
            vp90-2-14-resize-fp-tiles-16-1.webm
            vp90-2-14-resize-fp-tiles-16-2.webm
            vp90-2-14-resize-fp-tiles-16-4.webm
            vp90-2-14-resize-fp-tiles-16-8-4-2-1.webm
            vp90-2-14-resize-fp-tiles-16-8.webm
            vp90-2-14-resize-fp-tiles-2-1.webm
            vp90-2-14-resize-fp-tiles-2-16.webm
            vp90-2-14-resize-fp-tiles-2-4.webm
            vp90-2-14-resize-fp-tiles-2-8.webm
            vp90-2-14-resize-fp-tiles-4-1.webm
            vp90-2-14-resize-fp-tiles-4-16.webm
            vp90-2-14-resize-fp-tiles-4-2.webm
            vp90-2-14-resize-fp-tiles-4-8.webm
            vp90-2-14-resize-fp-tiles-8-1.webm
            vp90-2-14-resize-fp-tiles-8-16.webm
            vp90-2-14-resize-fp-tiles-8-2.webm
            vp90-2-14-resize-fp-tiles-8-4.webm
            vp90-2-15-segkey.webm
            vp90-2-15-segkey_adpq.webm
            vp90-2-16-intra-only.webm
            vp90-2-17-show-existing-frame.webm
            vp90-2-18-resize.ivf
            vp90-2-19-skip-01.webm
            vp90-2-19-skip-02.webm
            vp90-2-19-skip.webm
            vp90-2-20-big_superframe-01.webm
            vp90-2-20-big_superframe-02.webm
            vp90-2-21-resize_inter_1280x720_5_1-2.webm
            vp90-2-21-resize_inter_1280x720_5_3-4.webm
            vp90-2-21-resize_inter_1280x720_7_1-2.webm
            vp90-2-21-resize_inter_1280x720_7_3-4.webm
            vp90-2-21-resize_inter_1920x1080_5_1-2.webm
            vp90-2-21-resize_inter_1920x1080_5_3-4.webm
            vp90-2-21-resize_inter_1920x1080_7_1-2.webm
            vp90-2-21-resize_inter_1920x1080_7_3-4.webm
            vp90-2-21-resize_inter_320x180_5_1-2.webm
            vp90-2-21-resize_inter_320x180_5_3-4.webm
            vp90-2-21-resize_inter_320x180_7_1-2.webm
            vp90-2-21-resize_inter_320x180_7_3-4.webm
            vp90-2-21-resize_inter_320x240_5_1-2.webm
            vp90-2-21-resize_inter_320x240_5_3-4.webm
            vp90-2-21-resize_inter_320x240_7_1-2.webm
            vp90-2-21-resize_inter_320x240_7_3-4.webm
            vp90-2-21-resize_inter_640x360_5_1-2.webm
            vp90-2-21-resize_inter_640x360_5_3-4.webm
            vp90-2-21-resize_inter_640x360_7_1-2.webm
            vp90-2-21-resize_inter_640x360_7_3-4.webm
            vp90-2-21-resize_inter_640x480_5_1-2.webm
            vp90-2-21-resize_inter_640x480_5_3-4.webm
            vp90-2-21-resize_inter_640x480_7_1-2.webm
            vp90-2-21-resize_inter_640x480_7_3-4.webm
            vp90-2-22-svc_1280x720_1.webm
            vp90-2-22-svc_1280x720_3.ivf
            vp91-2-04-yuv422.webm
            vp91-2-04-yuv440.webm
            vp91-2-04-yuv444.webm
            vp92-2-20-10bit-yuv420.webm
            vp92-2-20-12bit-yuv420.webm
            vp93-2-20-10bit-yuv422.webm
            vp93-2-20-10bit-yuv440.webm
            vp93-2-20-10bit-yuv444.webm
            vp93-2-20-12bit-yuv422.webm
            vp93-2-20-12bit-yuv440.webm
            vp93-2-20-12bit-yuv444.webm
            """;

    /** The vectors in manifest order. */
    static final List<String> NAMES = MANIFEST.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();

    private Vp9Vectors() {
        throw new AssertionError();
    }

    static Path stream(String name) {
        return Conformance.vector(name);
    }

    static List<String> expectedMd5(String name) throws IOException {
        var path = Conformance.vector(name + ".md5");
        var expected = new ArrayList<String>();
        for (var line : Files.readString(path, StandardCharsets.US_ASCII).split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                expected.add(trimmed.split("\\s+")[0]);
            }
        }
        return expected;
    }

    /** Returns the packets of an IVF or WebM vector, in decoding order. */
    static List<byte[]> packets(String name, byte[] bytes) {
        var packets = new ArrayList<byte[]>();
        if (name.endsWith(".ivf")) {
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            // libvpx's IVF reader skips a 32-byte header whatever its header size field says
            var position = 32;
            while (position + 12 <= bytes.length) {
                var size = buffer.getInt(position);
                position += 12;
                if (size < 0 || position + size > bytes.length) {
                    break;
                }
                var data = new byte[size];
                System.arraycopy(bytes, position, data, 0, size);
                packets.add(data);
                position += size;
            }
            return packets;
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes), DemuxerOptions.builder()
                .limits(Vp9ConformanceTest.LIMITS).build())) {
            var track = demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst().orElseThrow();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet.data());
                }
            }
        }
        return packets;
    }

    /** Returns the MD5 of the visible planes of a frame in libvpx's test layout (16-bit little-endian samples above 8 bits). */
    static String md5(Vp9Frame frame) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            for (var plane = 0; plane < 3; plane++) {
                var w = frame.cropWidths[plane];
                var h = frame.cropHeights[plane];
                var stride = frame.strides[plane];
                if (frame.highBitDepth()) {
                    var row = new byte[w * 2];
                    var samples = frame.planes16[plane];
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            var v = samples[y * stride + x];
                            row[2 * x] = (byte) v;
                            row[2 * x + 1] = (byte) (v >> 8);
                        }
                        digest.update(row);
                    }
                } else {
                    var samples = frame.planes8[plane];
                    for (var y = 0; y < h; y++) {
                        digest.update(samples, y * stride, w);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
