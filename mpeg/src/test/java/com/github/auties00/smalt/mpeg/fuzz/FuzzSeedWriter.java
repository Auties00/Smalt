package com.github.auties00.smalt.mpeg.fuzz;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.sei.SeiWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HevcAccessUnit;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.tone;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the mpeg fuzz targets (tools/fuzz/README.md): short streams encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, raw elementary streams and H.264 parameter sets made by
 * ffmpeg (and from a separate-color-plane test stream), HEIF stills built with the test kit's
 * {@link HeifBuilder} around x265 and x264 pictures and uncompressed pixels, AAC LTP, ER AAC LTP and SSR streams written bit by bit, and
 * the scalable and alpha H.264 test streams, one NAL unit per packet. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    @Test
    @DisplayName("writes the seeds of the video decoder targets")
    void videos() {
        seed("h264", "valid-baseline-mp4.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264",
                "-preset", "ultrafast", "-profile:v", "baseline"));
        seed("h264", "valid-high-bframes-cabac.bin", Codec.H264, ffmpeg("mp4", video(48, 32, 4), "-c:v", "libx264",
                "-preset", "medium", "-bf", "2", "-refs", "2", "-x264-params", "weightb=1:b-pyramid=normal"));
        seed("h264", "valid-annexb-interlaced.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v", "libx264",
                "-preset", "ultrafast", "-flags", "+ildct+ilme", "-x264-params", "tff=1"));
        seed("h264", "valid-high10-8x8.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 2), "-c:v", "libx264",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x264-params", "8x8dct=1"));
        seed("h264", "valid-high422-cabac.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264",
                "-preset", "fast", "-pix_fmt", "yuv422p", "-bf", "1", "-x264-params", "8x8dct=1:weightb=1"));
        seed("h264", "valid-high422-10-mbaff-cavlc.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v",
                "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv422p10le", "-flags", "+ildct+ilme",
                "-x264-params", "tff=1:cabac=0"));
        seed("h264", "valid-high444-cabac-8x8-wp.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 4), "-c:v",
                "libx264", "-preset", "fast", "-pix_fmt", "yuv444p", "-bf", "2", "-x264-params",
                "8x8dct=1:weightp=2:weightb=1"));
        seed("h264", "valid-high444-10-mbaff-cavlc.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v",
                "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv444p10le", "-flags", "+ildct+ilme",
                "-x264-params", "tff=1:cabac=0"));
        seed("h264", "valid-lossless-rgb.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264rgb",
                "-preset", "fast", "-bf", "1", "-qp", "0"));
        seed("h264", "valid-lossless-420-cavlc.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v",
                "libx264", "-preset", "fast", "-pix_fmt", "yuv420p", "-qp", "0", "-x264-params", "cabac=0"));
        write(MpegFuzzTest.class, "h264", "valid-separate-planes.bin",
                FuzzPackets.video(0, 96, 64, null, separatePlaneUnits(3)));
        seed("hevc", "valid-main-mp4.bin", Codec.HEVC, ffmpeg("mp4", video(64, 64, 3), "-c:v", "libx265",
                "-preset", "ultrafast", "-x265-params", "log-level=0:bframes=1"));
        seed("hevc", "valid-main10-annexb.bin", Codec.HEVC, ffmpeg("ts", video(64, 32, 2), "-c:v", "libx265",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x265-params", "log-level=0:sao=1:amp=1"));
        seed("mpeg4", "valid-asp-avi.bin", Codec.MPEG4_PART2, ffmpeg("avi", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-bf", "1"));
        seed("mpeg4", "valid-qpel-mp4.bin", Codec.MPEG4_PART2, ffmpeg("mp4", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-flags", "+qpel+mv4"));
        seed("h263", "valid-sqcif.bin", Codec.H263, ffmpeg("3gp", video(128, 96, 3), "-c:v", "h263", "-q:v", "20"));
        seed("h263", "valid-plus-annexes.bin", Codec.H263, ffmpeg("avi", video(176, 144, 2), "-c:v", "h263p",
                "-q:v", "20", "-flags", "+aic+loop", "-umv", "1", "-aiv", "1"));
    }

    @Test
    @DisplayName("writes the seeds of the audio decoder targets")
    void audios() {
        seed("aac", "valid-lc-m4a.bin", Codec.AAC, ffmpeg("m4a", tone(8000, 0.3), "-c:a", "aac", "-b:a", "16k"));
        seed("aac", "valid-lc-stereo-adts.bin", Codec.AAC, ffmpeg("aac", tone(44100, 0.1), "-ac", "2",
                "-c:a", "aac", "-b:a", "64k"));
        seed("aac", "valid-stereo-tools.bin", Codec.AAC, ffmpeg("m4a", tone(16000, 0.2), "-ac", "2", "-c:a",
                "aac", "-b:a", "24k", "-aac_pns", "1", "-aac_is", "1", "-aac_ms", "1", "-aac_tns", "1"));
        seed("amrNb", "valid-nb.bin", Codec.AMR_NB, ffmpeg("amr", tone(8000, 0.2), "-c:a", "libopencore_amrnb",
                "-ar", "8000", "-b:a", "12.2k"));
        seed("amrWb", "valid-wb.bin", Codec.AMR_WB, ffmpeg("amr", tone(16000, 0.2), "-c:a", "libvo_amrwbenc",
                "-ar", "16000"));
        // the frame-blocks of the multichannel behavior samples (tools/testdata/behavior/make-amr-mc-samples.py)
        var amr = Path.of("..", "core", "src", "test", "resources", "samples", "amr");
        seed("amrNb", "valid-nb-stereo.bin", Codec.AMR_NB, amr.resolve("nb-stereo.amr"));
        seed("amrWb", "valid-wb-3ch.bin", Codec.AMR_WB, amr.resolve("wb-3ch.awb"));
        seed("ac3", "valid-stereo.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.1), "-ac", "2", "-c:a", "ac3",
                "-b:a", "96k"));
        seed("ac3", "valid-surround.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.06), "-ac", "6", "-c:a", "ac3",
                "-b:a", "192k"));
        seed("eac3", "valid-stereo.bin", Codec.EAC3, ffmpeg("eac3", tone(48000, 0.1), "-ac", "2", "-c:a", "eac3",
                "-b:a", "96k"));
    }

    @Test
    @DisplayName("writes the AAC Main, LTP, ER AAC LTP and SSR seeds of the AAC target")
    void aacObjectTypes() {
        // ffmpeg's encoder sets prediction_used from the fourth frame of this mono tone on
        seed("aac", "valid-main-prediction.bin", Codec.AAC, ffmpeg("m4a", tone(44100, 0.15), "-c:a", "aac",
                "-strict", "-2", "-profile:a", "aac_main", "-aac_pred", "1", "-b:a", "24k"));
        // no available encoder writes long term prediction data or SSR gain control data, so these
        // streams are built bit by bit: 44.1 kHz, 39 zero bands and a noise-substituted band 39
        var ltp = new ArrayList<FuzzPackets.Part>();
        var errorResilientLtp = new ArrayList<FuzzPackets.Part>();
        var ssr = new ArrayList<FuzzPackets.Part>();
        for (var frame = 0; frame < 8; frame++) {
            ltp.add(new FuzzPackets.Part(ltpPair(frame, false), true));
            errorResilientLtp.add(new FuzzPackets.Part(ltpPair(frame, true), true));
            ssr.add(new FuzzPackets.Part(ssrSingle(frame), true));
        }
        // AudioSpecificConfigs: object type 4 (LTP), 19 (ER LTP with the extension flag, no resilience
        // tools and epConfig 0) or 3 (SSR), sampling frequency index 4, channel configuration 2 or 1
        write(MpegFuzzTest.class, "aac", "valid-ltp-pair.bin",
                FuzzPackets.audio(0, 44100, 2, 0, new byte[]{0x22, 0x10}, ltp));
        write(MpegFuzzTest.class, "aac", "valid-er-ltp-pair.bin",
                FuzzPackets.audio(0, 44100, 2, 0, new byte[]{(byte) 0x9A, 0x11, 0x00}, errorResilientLtp));
        write(MpegFuzzTest.class, "aac", "valid-ssr-gain-control.bin",
                FuzzPackets.audio(0, 44100, 1, 0, new byte[]{0x1A, 0x08}, ssr));
    }

    /**
     * Builds a {@code raw_data_block} (or an {@code er_raw_data_block}) holding one channel pair with a common
     * long window, long term prediction in all 40 bands of both channels and an order 4 TNS filter.
     */
    private static byte[] ltpPair(int frame, boolean errorResilient) {
        var writer = new MsbBitWriter();
        if (!errorResilient) {
            writer.write(1, 3);
        }
        writer.write(0, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writer.write(frame & 1, 1);
        writer.write(40, 6);
        writer.write(1, 1);
        if (!errorResilient) {
            ltpData(writer, frame, 0);
            ltpData(writer, frame, 1);
        }
        writer.write(0, 2);
        for (var channel = 0; channel < 2; channel++) {
            if (errorResilient) {
                ltpData(writer, frame, channel);
            }
            writer.write(100, 8);
            noiseBand(writer, 256 + 40 + channel);
            writer.write(0, 1);
            writer.write(1, 1);
            if (!errorResilient) {
                tnsData(writer);
            }
            writer.write(0, 1);
            if (errorResilient) {
                tnsData(writer);
            }
        }
        if (!errorResilient) {
            writer.write(7, 3);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes {@code ltp_data_present} set and an {@code ltp_data} covering 40 bands, sweeping the lag and keeping the
     * coefficient below 1.
     */
    private static void ltpData(MsbBitWriter writer, int frame, int channel) {
        writer.write(1, 1);
        writer.write((256 + frame * 37 + channel * 911) % 2048, 11);
        writer.write((frame + channel) & 3, 3);
        for (var band = 0; band < 40; band++) {
            writer.write(1, 1);
        }
    }

    /** Writes the sections and scalefactors of 39 zero long bands and one noise band. */
    private static void noiseBand(MsbBitWriter writer, int energy) {
        writer.write(0, 4);
        writer.write(31, 5);
        writer.write(8, 5);
        writer.write(13, 4);
        writer.write(1, 5);
        writer.write(energy, 9);
    }

    /** Writes the TNS data of a long window: one order 4 filter over 40 bands. */
    private static void tnsData(MsbBitWriter writer) {
        writer.write(1, 2);
        writer.write(1, 1);
        writer.write(40, 6);
        writer.write(4, 5);
        writer.write(0, 2);
        writer.write(0x5C39, 16);
    }

    /**
     * Builds a {@code raw_data_block} holding one SSR single channel element with gain control data in the three
     * gain-controlled PQF bands, cycling through every window sequence in an order ISO/IEC 14496-3 allows.
     */
    private static byte[] ssrSingle(int frame) {
        int[] sequences = {0, 1, 2, 2, 3, 0, 1, 3};
        var sequence = sequences[frame];
        var writer = new MsbBitWriter();
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(100, 8);
        writer.write(0, 1);
        writer.write(sequence, 2);
        writer.write(frame >> 1 & 1, 1);
        if (sequence == 2) {
            // 14 short bands in one window group: 13 zero bands, then one noise band
            writer.write(14, 4);
            writer.write(0x7F, 7);
            writer.write(0, 4);
            writer.write(7, 3);
            writer.write(6, 3);
            writer.write(13, 4);
            writer.write(1, 3);
            writer.write(256 + 30, 9);
        } else {
            writer.write(40, 6);
            writer.write(0, 1);
            noiseBand(writer, 256 + 40);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(3, 2);
        var windows = sequence == 0 ? 1 : sequence == 2 ? 8 : 2;
        for (var band = 1; band <= 3; band++) {
            for (var window = 0; window < windows; window++) {
                var bits = switch (sequence) {
                    case 0 -> 5;
                    case 2 -> 2;
                    case 1 -> window == 0 ? 4 : 2;
                    default -> window == 0 ? 4 : 5;
                };
                // two increasing locations of each width, the 4-bit ones below the reserved codes 14 and 15
                int[] locations = bits == 2 ? new int[]{1, 3} : bits == 4 ? new int[]{3, 12} : new int[]{4, 20};
                var changes = (frame + band + window) % 3;
                writer.write(changes, 3);
                for (var change = 0; change < changes; change++) {
                    writer.write((frame * 3 + band + window + change) & 15, 4);
                    writer.write(locations[change], bits);
                }
            }
        }
        writer.write(7, 3);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("writes the seeds of the HEIC target")
    void heic() {
        write(MpegFuzzTest.class, "heic", "valid-still.heic", still(ffmpeg("mp4", video(64, 64, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=0")));
        write(MpegFuzzTest.class, "heic", "valid-10bit.heic", still(ffmpeg("mp4", video(32, 32, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params",
                "log-level=0")));
        write(MpegFuzzTest.class, "heic", "valid-avc.heic", avcStill(ffmpeg("mp4", video(32, 32, 1), "-frames:v",
                "1", "-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "baseline")));
        var random = new java.util.Random(7);
        var rgb = new byte[8 * 8 * 3];
        random.nextBytes(rgb);
        write(MpegFuzzTest.class, "heic", "valid-unci-rgb3.heic", unci(rgb, 8, 8,
                HeifBuilder.fullBox("uncC", 1, 0, new byte[]{'r', 'g', 'b', '3'})));
        var v210 = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                java.time.Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=48x4:rate=1", "-frames:v", "1", "-pix_fmt", "yuv422p10le", "-c:v", "v210", "-f",
                        "rawvideo", "-"));
        // multi-Y 4:2:2 (Cb Y Cr Y) of 10-bit values in reversed little-endian blocks of 4 bytes, rows of 128 bytes
        write(MpegFuzzTest.class, "heic", "valid-unci-v210.heic", unci(v210, 48, 4,
                uncC(new int[]{1, 0, 2, 0}, 10, 1, 5, 4, 0x30, 128), cmpd(1, 2, 3)));
        var gray = new byte[16 * 8];
        for (var i = 0; i < gray.length; i++) {
            gray[i] = (byte) ((i % 16) * 7 + (i / 16) * 3);
        }
        var deflater = new java.util.zip.Deflater(9);
        deflater.setInput(gray);
        deflater.finish();
        var compressed = new byte[1024];
        var size = deflater.deflate(compressed);
        deflater.end();
        write(MpegFuzzTest.class, "heic", "valid-unci-zlib.heic", unci(Arrays.copyOf(compressed, size), 16, 8,
                uncC(new int[]{0}, 8, 0, 0, 0, 0, 0), cmpd(0),
                HeifBuilder.fullBox("cmpC", 0, 0, new byte[]{'z', 'l', 'i', 'b', 0})));
    }

    private static byte[] avcStill(Path mp4) {
        try (var source = MediaSource.of(mp4); var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packet = demuxer.read();
            var builder = new HeifBuilder().brands("avci", "avci", "mif1", "miaf");
            var id = builder.item("avc1", packet.data(),
                    HeifBuilder.plain(HeifBuilder.ispe(track.width(), track.height())),
                    HeifBuilder.essential(HeifBuilder.box("avcC", track.codecConfig().orElseThrow())));
            return builder.primary(id).build();
        }
    }

    private static byte[] unci(byte[] data, int width, int height, byte[]... properties) {
        var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
        var all = new HeifBuilder.Property[properties.length + 1];
        all[0] = HeifBuilder.plain(HeifBuilder.ispe(width, height));
        for (var i = 0; i < properties.length; i++) {
            all[i + 1] = HeifBuilder.essential(properties[i]);
        }
        return builder.primary(builder.item("unci", data, all)).build();
    }

    private static byte[] uncC(int[] indexes, int depth, int sampling, int interleave, int blockSize, int flags,
                               int rowAlign) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, indexes.length);
        for (var index : indexes) {
            HeifBuilder.u16(out, index);
            out.write(depth - 1);
            out.write(0);
            out.write(0);
        }
        out.write(sampling);
        out.write(interleave);
        out.write(blockSize);
        out.write(flags);
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, rowAlign);
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, 0);
        return HeifBuilder.fullBox("uncC", 0, 0, out.toByteArray());
    }

    private static byte[] cmpd(int... types) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, types.length);
        for (var type : types) {
            HeifBuilder.u16(out, type);
        }
        return HeifBuilder.box("cmpd", out.toByteArray());
    }

    @Test
    @DisplayName("writes the seeds of the elementary stream and H.264 parameter set targets")
    void streams() {
        // the m4v extension selects ffmpeg's ipod muxer, so the raw stream muxer is named
        copy(MpegFuzzTest.class, "elementaryStream", "valid-mpeg4.m4v", ffmpeg("m4v", video(32, 32, 3),
                "-c:v", "mpeg4", "-q:v", "10", "-bf", "1", "-f", "m4v"));
        copy(MpegFuzzTest.class, "elementaryStream", "valid-h263.h263", ffmpeg("h263", video(128, 96, 3),
                "-c:v", "h263", "-q:v", "20"));
        // the optional modes of H.263 and the object tools of MPEG-4 Visual, from the committed test vectors
        for (var name : List.of("tmn-pb-sac", "tmn-snr", "smalt-b-rru", "smalt-erps-b", "smalt-isd-rect")) {
            write(MpegFuzzTest.class, "elementaryStream", "valid-h263-" + name + ".h263",
                    resource("/com/github/auties00/smalt/video/h263/annex/" + name + ".263"));
        }
        for (var name : List.of("shape-only-ip", "shape-sadct", "sprite-shape", "nbit4")) {
            write(MpegFuzzTest.class, "elementaryStream", "valid-mpeg4-" + name + ".m4v",
                    resource("/com/github/auties00/smalt/video/mpeg4/object/" + name + ".m4v"));
        }
        var mp4 = ffmpeg("mp4", video(48, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-vf", "crop=46:30");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-cropped.bin", firstTrack(mp4).codecConfig().orElseThrow());
        var interlaced = ffmpeg("mp4", video(32, 64, 2), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv422p10le", "-flags", "+ildct+ilme", "-x264-params", "tff=1");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-high422-interlaced.bin",
                firstTrack(interlaced).codecConfig().orElseThrow());
        var annexB = ffmpeg("h264", video(32, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-r", "25");
        write(MpegFuzzTest.class, "h264Info", "valid-annexb.bin", Fixtures.read(annexB));
        var config = firstTrack(mp4).codecConfig().orElseThrow();
        // avcC: 5 header bytes, the SPS count byte, a 16-bit length, then the first SPS NAL unit
        var spsLength = (config[6] & 0xFF) << 8 | config[7] & 0xFF;
        write(MpegFuzzTest.class, "h264Info", "valid-sps.bin", Arrays.copyOfRange(config, 8, 8 + spsLength));
        var lossless = ffmpeg("mp4", video(32, 32, 1), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv444p", "-qp", "0");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-high444-lossless.bin",
                firstTrack(lossless).codecConfig().orElseThrow());
        var planes = Fixtures.read(separatePlanes());
        // the stream starts with a 4-byte start code and the SPS, which the zero bytes of the next start code end
        var end = 4;
        while (planes[end] != 0 || planes[end + 1] != 0 || planes[end + 2] != 1) {
            end++;
        }
        while (planes[end - 1] == 0) {
            end--;
        }
        write(MpegFuzzTest.class, "h264Info", "valid-sps-separate-planes.bin", Arrays.copyOfRange(planes, 4, end));
    }

    /**
     * Splits the first access units of the separate-color-plane test stream, whose pictures are written as the
     * slices of the Y, Cb and Cr planes in turn, into Annex B packets.
     *
     * @param count the number of access units
     * @return the packets, the first holding the parameter sets
     */
    private static List<FuzzPackets.Part> separatePlaneUnits(int count) {
        var data = Fixtures.read(separatePlanes());
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i + 3);
            }
        }
        var parts = new ArrayList<FuzzPackets.Part>();
        var unit = new ByteArrayOutputStream();
        var started = false;
        var idr = false;
        var planeStarts = 0;
        for (var n = 0; n < starts.size() && parts.size() < count; n++) {
            var from = starts.get(n);
            var to = n + 1 < starts.size() ? starts.get(n + 1) - 3 : data.length;
            while (to > from && data[to - 1] == 0) {
                to--;
            }
            var type = data[from] & 0x1F;
            // a slice whose first_mb_in_slice is 0 starts a plane, and every third plane starts a picture
            if ((type == 1 || type == 5) && (data[from + 1] & 0x80) != 0 && planeStarts++ % 3 == 0 && started) {
                parts.add(new FuzzPackets.Part(unit.toByteArray(), idr));
                unit.reset();
                idr = false;
            }
            started |= type == 1 || type == 5;
            idr |= type == 5;
            unit.writeBytes(new byte[]{0, 0, 0, 1});
            unit.write(data, from, to - from);
        }
        return parts;
    }

    @Test
    @DisplayName("writes the H.264 seeds of the Extended profile tools from the JM test streams")
    void extended() {
        extendedSeed("dp-slices", "valid-extended-partitions.bin", 3, 176, 144);
        extendedSeed("sp-switch", "valid-extended-switching-sp.bin", 4, 176, 144);
        extendedSeed("si-model", "valid-extended-si.bin", 2, 32, 32);
    }

    /**
     * Writes the first access units of a stream of the H.264 Extended profile test as a seed: slice data
     * partitions, switching SP slices and SI macroblocks.
     */
    private static void extendedSeed(String stream, String name, int units, int width, int height) {
        try {
            var file = Path.of(FuzzSeedWriter.class.getResource(
                    "/com/github/auties00/smalt/video/h264/extended/" + stream + ".264").toURI());
            write(MpegFuzzTest.class, "h264", name, FuzzPackets.video(0, width, height, null,
                    h264AccessUnits(Fixtures.read(file)).subList(0, units)));
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] resource(String path) {
        try {
            return Fixtures.read(Path.of(FuzzSeedWriter.class.getResource(path).toURI()));
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path separatePlanes() {
        try {
            return Path.of(FuzzSeedWriter.class.getResource(
                    "/com/github/auties00/smalt/video/h264/chroma444/m8-cabac-slices-sep.264").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static VideoTrack firstTrack(Path file) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    private static byte[] still(Path mp4) {
        try (var source = MediaSource.of(mp4); var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packet = demuxer.read();
            var builder = new HeifBuilder().brands("heic", "heic", "mif1");
            var id = builder.item("hvc1", packet.data(),
                    HeifBuilder.plain(HeifBuilder.ispe(track.width(), track.height())),
                    HeifBuilder.essential(HeifBuilder.box("hvcC", track.codecConfig().orElseThrow())));
            return builder.primary(id).build();
        }
    }

    @Test
    @DisplayName("writes the HEVC seeds of field coding and of a start at a recovery point")
    void hevcRandomAccess() {
        var vectors = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video", "hevc",
                "random");
        var fields = Fixtures.read(vectors.resolve("field-ld-bff-10.hevc"));
        write(MpegFuzzTest.class, "hevc", "valid-field-annexb.bin",
                FuzzPackets.video(1, 128, 128, null, accessUnits(fields, 6)));
        // the low delay stream cut at its second recovery point, its parameter sets kept
        var recovery = Fixtures.read(vectors.resolve("rp-ld.hevc"));
        var starts = nalStarts(recovery);
        var parameterSets = 0;
        while (nalType(recovery, starts.get(parameterSets)) >= 32
                && nalType(recovery, starts.get(parameterSets)) <= 34) {
            parameterSets++;
        }
        var recoveryPoints = 0;
        var cut = -1;
        for (var start : starts) {
            if (nalType(recovery, start) == 39 && (recovery[payloadOffset(recovery, start)] & 0xFF) == 6
                    && ++recoveryPoints == 2) {
                cut = start;
                break;
            }
        }
        var stream = new byte[starts.get(parameterSets) + recovery.length - cut];
        System.arraycopy(recovery, 0, stream, 0, starts.get(parameterSets));
        System.arraycopy(recovery, cut, stream, starts.get(parameterSets), recovery.length - cut);
        write(MpegFuzzTest.class, "hevc", "valid-recovery-cut-annexb.bin",
                FuzzPackets.video(0, 128, 96, null, accessUnits(stream, 6)));
    }

    @Test
    @DisplayName("writes the seeds of the layered H.264 target from the scalable and alpha test streams")
    void h264Layers() {
        var vectors = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video", "h264");
        for (var name : List.of("svc-cgs-temporal", "svc-mgs-keys", "svc-spatial")) {
            write(MpegFuzzTest.class, "h264Layers", "valid-" + name + ".bin", FuzzPackets.video(0, 0, 0, null,
                    nalUnitParts(Fixtures.read(vectors.resolve("svc").resolve(name + ".264")))));
        }
        write(MpegFuzzTest.class, "h264Layers", "valid-aux9-cabac-slices.bin", FuzzPackets.video(0, 0, 0, null,
                nalUnitParts(Fixtures.read(vectors.resolve("auxiliary").resolve("aux9-cabac-slices.264")))));
    }

    /**
     * Returns one part per NAL unit of an Annex B stream, its start code included; only the first is flagged as a
     * keyframe.
     */
    private static List<FuzzPackets.Part> nalUnitParts(byte[] stream) {
        var starts = nalStarts(stream);
        var parts = new ArrayList<FuzzPackets.Part>();
        for (var index = 0; index < starts.size(); index++) {
            var end = index + 1 < starts.size() ? starts.get(index + 1) : stream.length;
            parts.add(new FuzzPackets.Part(Arrays.copyOfRange(stream, starts.get(index), end), index == 0));
        }
        return parts;
    }

    @Test
    @DisplayName("writes the HEVC seeds of screen content coding and of separate color planes")
    void hevcSccAndPlanes() {
        var vectors = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video", "hevc");
        write(MpegFuzzTest.class, "hevc", "valid-scc-annexb.bin", FuzzPackets.video(0, 192, 128, null,
                accessUnits(Fixtures.read(vectors.resolve("scc").resolve("scc-ld-444-8.hevc")), 6)));
        write(MpegFuzzTest.class, "hevc", "valid-scc-lossless-annexb.bin", FuzzPackets.video(0, 192, 128, null,
                accessUnits(Fixtures.read(vectors.resolve("scc").resolve("scc-ld-444-8-lossless.hevc")), 3)));
        write(MpegFuzzTest.class, "hevc", "valid-scc-422-annexb.bin", FuzzPackets.video(0, 192, 128, null,
                accessUnits(Fixtures.read(vectors.resolve("scc").resolve("scc-ld-422-10.hevc")), 3)));
        // the first slice segment of every plane starts a part, so 18 parts hold six pictures
        write(MpegFuzzTest.class, "hevc", "valid-separate-planes-annexb.bin", FuzzPackets.video(1, 128, 96, null,
                accessUnits(Fixtures.read(vectors.resolve("planes").resolve("planes-ra-10.bit")), 18)));
    }

    @Test
    @DisplayName("writes the seeds of the multi-layer HEVC target and a layered HEIC image")
    void hevcLayers() {
        var vectors = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "video", "hevc",
                "multilayer");
        // the options select an output layer set in bits 1 to 3 (7 for the last one); bit 0 asks for high bit depth
        write(MpegFuzzTest.class, "hevcLayers", "valid-shvc-2x.bin", FuzzPackets.video(2, 128, 96, null,
                accessUnits(Fixtures.read(vectors.resolve("shvc-2x-ld.hevc")), 8)));
        write(MpegFuzzTest.class, "hevcLayers", "valid-shvc-1.5x-ra.bin", FuzzPackets.video(14, 96, 72, null,
                accessUnits(Fixtures.read(vectors.resolve("shvc-1.5x-ra.hevc")), 10)));
        write(MpegFuzzTest.class, "hevcLayers", "valid-shvc-snr-8-10.bin", FuzzPackets.video(3, 128, 96, null,
                accessUnits(Fixtures.read(vectors.resolve("shvc-snr-8-10.hevc")), 6)));
        write(MpegFuzzTest.class, "hevcLayers", "valid-mvhevc.bin", FuzzPackets.video(2, 128, 96, null,
                accessUnits(Fixtures.read(vectors.resolve("mvhevc-ld.hevc")), 6)));
        // output layer set 0 with the alpha layer attached to the primary pictures
        write(MpegFuzzTest.class, "hevcLayers", "valid-alpha.bin", FuzzPackets.video(0, 128, 96, null,
                accessUnits(Fixtures.read(vectors.resolve("alpha-ld.hevc")), 6)));
        var unit = HevcAccessUnit.first(Fixtures.read(vectors.resolve("shvc-2x-ld.hevc")));
        var builder = new HeifBuilder().brands("heic", "heic", "mif1");
        var base = builder.item("hvc1", HevcAccessUnit.lengthPrefixed(unit.slices(layer -> layer == 0)),
                HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.essential(HeifBuilder.box("hvcC", unit.hvcC())));
        var layered = builder.item("lhv1", HevcAccessUnit.lengthPrefixed(unit.slices(layer -> true)),
                HeifBuilder.plain(HeifBuilder.ispe(128, 96)),
                HeifBuilder.essential(HeifBuilder.box("lhvC", unit.lhvC())),
                HeifBuilder.essential(HeifBuilder.fullBox("tols", 0, 0, new byte[]{0, 1})));
        builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 100, layered, base))).primary(base);
        write(MpegFuzzTest.class, "heic", "valid-lhv1.heic", builder.build());
    }

    /**
     * Splits an Annex B HEVC stream into its first access units, each from the start code (with its leading zero
     * byte) of its first NAL unit; a new access unit starts at a parameter set, prefix SEI or access unit delimiter
     * or a first slice segment once the current one holds a slice. Only the first is flagged as a keyframe.
     */
    private static List<FuzzPackets.Part> accessUnits(byte[] stream, int limit) {
        var starts = nalStarts(stream);
        var boundaries = new ArrayList<Integer>();
        var slice = false;
        for (var start : starts) {
            var type = nalType(stream, start);
            var vcl = type < 32;
            var first = vcl && (stream[payloadOffset(stream, start)] & 0x80) != 0;
            if (boundaries.isEmpty() || slice && (first || type >= 32 && type <= 39)) {
                boundaries.add(start);
                slice = false;
            }
            slice |= vcl;
        }
        boundaries.add(stream.length);
        var parts = new ArrayList<FuzzPackets.Part>();
        for (var index = 0; index + 1 < boundaries.size() && parts.size() < limit; index++) {
            parts.add(new FuzzPackets.Part(Arrays.copyOfRange(stream, boundaries.get(index), boundaries.get(index + 1)),
                    index == 0));
        }
        return parts;
    }

    /**
     * Returns the index of the start code of every NAL unit, at its leading zero byte for a four-byte start code.
     */
    private static List<Integer> nalStarts(byte[] stream) {
        var starts = new ArrayList<Integer>();
        for (var index = 0; index + 2 < stream.length; index++) {
            if (stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1) {
                starts.add(index > 0 && stream[index - 1] == 0 ? index - 1 : index);
                index += 2;
            }
        }
        return starts;
    }

    private static int payloadOffset(byte[] stream, int start) {
        return start + (stream[start + 2] == 1 ? 3 : 4) + 2;
    }

    private static int nalType(byte[] stream, int start) {
        return (stream[start + (stream[start + 2] == 1 ? 3 : 4)] >> 1) & 0x3F;
    }

    @Test
    @DisplayName("writes the H.264 and HEVC seeds of the film grain, frame packing and display orientation messages")
    void displaySei() {
        var h264 = Fixtures.read(ffmpeg("h264", video(64, 64, 4), "-c:v", "libx264", "-bf", "1", "-x264-params",
                "frame-packing=3:colorprim=bt2020:transfer=bt2020-10:colormatrix=bt2020nc:"
                        + "alternative-transfer=arib-std-b67"));
        var grain = List.of(List.of(new SeiWriter.Interval(0, 255, 60, 8, 6)),
                List.of(new SeiWriter.Interval(0, 127, 40, 6, 6), new SeiWriter.Interval(128, 255, 90, 12, 3)),
                List.<SeiWriter.Interval>of());
        var annotated = SeiWriter.insert(h264, false, false, picture -> List.of(
                SeiWriter.nal(false, 19, SeiWriter.filmGrain(false, 3, picture == 0 ? 1 : 0, grain)),
                SeiWriter.nal(false, 47, SeiWriter.displayOrientation(false, true, false, 16384, 1))));
        write(MpegFuzzTest.class, "h264", "valid-sei-display-grain.bin",
                FuzzPackets.video(0, 64, 64, null, h264AccessUnits(annotated)));
        var hevc = Fixtures.read(ffmpeg("hevc", video(64, 64, 4), "-c:v", "libx265", "-x265-params",
                "log-level=error:bframes=1"));
        var annotatedHevc = SeiWriter.insert(hevc, true, false, picture -> List.of(
                SeiWriter.nal(true, 19, SeiWriter.filmGrain(true, 3, picture == 0 ? 1 : 0, grain)),
                SeiWriter.nal(true, 45, SeiWriter.framePacking(true, 0, 4, false, 1, false, false, false, false,
                        true, true, new int[]{0, 0, 0, 0}, 1, false)),
                SeiWriter.nal(true, 47, SeiWriter.displayOrientation(true, false, true, 32768, 0))));
        write(MpegFuzzTest.class, "hevc", "valid-sei-display-grain.bin",
                FuzzPackets.video(0, 64, 64, null, accessUnits(annotatedHevc, 6)));
    }

    /**
     * Splits an Annex B H.264 stream into its access units, each from the start code of its first NAL unit; a new
     * access unit starts at an SEI, parameter set or access unit delimiter NAL unit, or a first slice or slice data
     * partition A, once the current one holds a slice. Only the first is flagged as a keyframe.
     */
    private static List<FuzzPackets.Part> h264AccessUnits(byte[] stream) {
        var starts = nalStarts(stream);
        var boundaries = new ArrayList<Integer>();
        var slice = false;
        for (var start : starts) {
            var header = start + (stream[start + 2] == 1 ? 3 : 4);
            var type = stream[header] & 0x1F;
            var vcl = type == 1 || type == 2 || type == 5;
            var first = vcl && (stream[header + 1] & 0x80) != 0;
            if (boundaries.isEmpty() || slice && (first || type == 6 || type == 7 || type == 8 || type == 9)) {
                boundaries.add(start);
                slice = false;
            }
            slice |= type >= 1 && type <= 5;
        }
        boundaries.add(stream.length);
        var parts = new ArrayList<FuzzPackets.Part>();
        for (var index = 0; index + 1 < boundaries.size(); index++) {
            parts.add(new FuzzPackets.Part(Arrays.copyOfRange(stream, boundaries.get(index), boundaries.get(index + 1)),
                    index == 0));
        }
        return parts;
    }

    private static void seed(String method, String name, Codec codec, Path file) {
        write(MpegFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, 0, 6));
    }

    @Test
    @DisplayName("writes the E-AC-3 seeds of Smalt's encoder tools: dependent substreams, pre-noise data, enhanced coupling, metadata")
    void eac3Tools() {
        for (var name : com.github.auties00.smalt.audio.ac3.Eac3ToolStreams.NAMES) {
            var parts = new ArrayList<FuzzPackets.Part>();
            var stream = com.github.auties00.smalt.audio.ac3.Eac3ToolStreams.stream(name);
            var position = 0;
            var start = 0;
            // access units: an independent frame of substream 0 with the dependent frames after it
            while (position + 6 <= stream.length && parts.size() < 6) {
                var words = (((stream[position + 2] & 0x07) << 8) | (stream[position + 3] & 0xFF)) + 1;
                var next = position + 2 * words;
                var independent = next + 6 > stream.length || ((stream[next + 2] & 0xFF) >> 6) != 1;
                if (independent) {
                    parts.add(new FuzzPackets.Part(Arrays.copyOfRange(stream, start, next), true));
                    start = next;
                }
                position = next;
            }
            var channels = name.startsWith("surround") ? 12 : 2;
            write(MpegFuzzTest.class, "eac3", "valid-" + name + ".bin",
                    FuzzPackets.audio(0, 48000, channels, 0, null, parts));
        }
    }

    @Test
    @DisplayName("writes the seeds of the HEIC gain map target: an iPhone-style portrait with an Apple gain map")
    void gainMaps() {
        var portrait = com.github.auties00.smalt.image.heic.HeicGainMapTest.portrait(32, 24,
                com.github.auties00.smalt.image.heic.HeicGainMapTest.exif(1, 2, 3, 100));
        com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write(MpegFuzzTest.class, "heicGainMap", "valid-portrait.heic",
                portrait);
    }
}
