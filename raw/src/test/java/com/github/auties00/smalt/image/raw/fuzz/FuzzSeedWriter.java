package com.github.auties00.smalt.image.raw.fuzz;

import com.github.auties00.smalt.image.raw.TinyDng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.vector;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the raw fuzz targets (tools/fuzz/README.md): DNG files built with
 * {@link TinyDng}, CC0 samples from raw.pixls.us (listed in {@code tools/testdata/vectors.tsv}) reduced by
 * {@link RawSeedReducer} to their first {@value #ROWS} sensor rows and tiny previews, and the first {@value #HEAD}
 * bytes of a compressed RAF sample, whose sensor data is not decoded. The CRW, MRW and X3F seeds are written by the
 * synthetic-file builders of their parser tests. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    /** The bytes kept from the start of a vendor sample: its header, directories and maker notes. */
    private static final int HEAD = 65536;

    /** The sensor rows a reduced vendor sample keeps, a multiple of every CFA period (2 for Bayer, 6 for X-Trans). */
    private static final int ROWS = 12;

    @Test
    @DisplayName("writes the DNG seeds")
    void dng() {
        var plain = TinyDng.ramp(24, 16);
        plain.white = 65535;
        write(RawFuzzTest.class, "dng", "valid-uncompressed.dng", plain.write());
        var lossless = TinyDng.ramp(24, 16);
        lossless.compression = 7;
        lossless.jpegComponents = 2;
        lossless.white = 65535;
        write(RawFuzzTest.class, "dng", "valid-lossless-jpeg.dng", lossless.write());
        var deflate = TinyDng.ramp(24, 16);
        deflate.compression = 8;
        deflate.predictor = 34892;
        deflate.orientation = 6;
        deflate.crop = new int[]{2, 2, 20, 12};
        write(RawFuzzTest.class, "dng", "valid-deflate-predictor.dng", deflate.write());
        var packed = TinyDng.ramp(16, 8);
        packed.bits = 12;
        for (var index = 0; index < packed.samples.length; index++) {
            packed.samples[index] &= 0xFFF;
        }
        packed.cfa = "GBRG";
        packed.black = new double[]{64};
        write(RawFuzzTest.class, "dng", "valid-packed-12bit.dng", packed.write());
        var floats = TinyDng.ramp(16, 8);
        floats.floats = new float[16 * 8];
        for (var index = 0; index < floats.floats.length; index++) {
            floats.floats[index] = (index % 97) / 96f;
        }
        floats.floatBytes = 2;
        floats.predictor = 34894;
        floats.compression = 8;
        write(RawFuzzTest.class, "dng", "valid-float16.dng", floats.write());
        var linear = TinyDng.ramp(8, 8);
        linear.samplesPerPixel = 3;
        linear.cfa = null;
        linear.samples = Arrays.copyOf(linear.samples, 8 * 8 * 3);
        write(RawFuzzTest.class, "dng", "valid-linear.dng", linear.write());
    }

    @Test
    @DisplayName("writes the head of the compressed RAF sample and the TIFF/EP seed")
    void heads() throws IOException {
        // compressed RAF sensor data is not decoded, so its seed only needs the header, records and sensor block start
        head("raf", "rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf");
        write(RawFuzzTest.class, "srw", "valid-tiff-ep.srw", tiffEp("SAMSUNG", 16, 8));
    }

    @Test
    @DisplayName("writes the reduced vendor samples")
    void reduced() throws IOException {
        var jpeg = Files.readAllBytes(ffmpeg("jpg", video(16, 16, 1), "-frames:v", "1", "-update", "1", "-q:v", "10"));
        reduce("cr2", "rawpixls-canon-eos-5d-mark-iii.cr2", RawSeedReducer.Layout.CR2, jpeg);
        reduce("cr2", "rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2", RawSeedReducer.Layout.CR2, jpeg);
        reduce("nef", "rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef", RawSeedReducer.Layout.NEF, jpeg);
        reduce("nef", "rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef", RawSeedReducer.Layout.NEF, jpeg);
        reduce("nef", "rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef", RawSeedReducer.Layout.NEF,
                jpeg);
        reduce("arw", "rawpixls-sony-nex-6-12bit-12bit-compressed-3-2.arw", RawSeedReducer.Layout.ARW, jpeg);
        reduce("orf", "rawpixls-olympus-e-10-16bit-4-3.orf", RawSeedReducer.Layout.ORF, jpeg);
        reduce("orf", "rawpixls-olympus-c5060wz-12bit-4-3.orf", RawSeedReducer.Layout.ORF, jpeg);
        reduce("orf", "rawpixls-olympus-e-450-16bit-4-3.orf", RawSeedReducer.Layout.ORF, jpeg);
        reduce("rw2", "rawpixls-panasonic-dmc-lx3-1-1.rw2", RawSeedReducer.Layout.RW2, jpeg);
        reduce("rw2", "rawpixls-panasonic-dc-g9-4-3-2348.rw2", RawSeedReducer.Layout.RW2, jpeg);
        reduce("pef", "rawpixls-pentax-ist-dl-12bit-3-2.pef", RawSeedReducer.Layout.PEF, jpeg);
        reduce("pef", "rawpixls-pentax-k-5-14bit-3-2.pef", RawSeedReducer.Layout.PEF, jpeg);
        reduce("raf", "rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf", RawSeedReducer.Layout.RAF, jpeg);
        reduce("raf", "rawpixls-fujifilm-x-e2-14bit-14bit-uncompressed-3-2.raf", RawSeedReducer.Layout.RAF, jpeg);
        // the CRX sensor data is not decoded, so the reduced CR3 file is a seed but not a valid one
        write(RawFuzzTest.class, "cr3", "reduced-canon-eos-250d-raw-3-2.cr3", RawSeedReducer.reduce(
                Files.readAllBytes(vector("rawpixls-canon-eos-250d-raw-3-2.cr3")), RawSeedReducer.Layout.CR3, ROWS, jpeg));
    }

    private static void reduce(String method, String vector, RawSeedReducer.Layout layout, byte[] jpeg)
            throws IOException {
        var reduced = RawSeedReducer.reduce(Files.readAllBytes(vector(vector)), layout, ROWS, jpeg);
        var extension = vector.substring(vector.lastIndexOf('.') + 1);
        write(RawFuzzTest.class, method, "valid-reduced-" + vector.substring("rawpixls-".length(),
                vector.lastIndexOf('.')) + "." + extension, reduced);
    }

    // a TIFF/EP file with one uncompressed 16-bit RGGB strip, the layout of the generic and Samsung readers
    private static byte[] tiffEp(String make, int width, int height) {
        var makeBytes = Arrays.copyOf(make.getBytes(StandardCharsets.US_ASCII), make.length() + 1);
        var entries = 13;
        var ifd = 8;
        var makeOffset = ifd + 2 + entries * 12 + 4;
        var strip = makeOffset + makeBytes.length + (makeBytes.length & 1);
        var stripBytes = width * height * 2;
        var out = ByteBuffer.allocate(strip + stripBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        out.putShort((short) entries);
        entry(out, 254, 4, 1, 0);
        entry(out, 256, 3, 1, width);
        entry(out, 257, 3, 1, height);
        entry(out, 258, 3, 1, 16);
        entry(out, 259, 3, 1, 1);
        entry(out, 262, 3, 1, 32803);
        entry(out, 271, 2, makeBytes.length, makeOffset);
        entry(out, 273, 4, 1, strip);
        entry(out, 277, 3, 1, 1);
        entry(out, 278, 3, 1, height);
        entry(out, 279, 4, 1, stripBytes);
        out.putShort((short) 33421).putShort((short) 3).putInt(2).putShort((short) 2).putShort((short) 2);
        out.putShort((short) 33422).putShort((short) 1).putInt(4).put(new byte[]{0, 1, 1, 2});
        out.putInt(0);
        out.put(makeBytes);
        out.position(strip);
        for (var index = 0; index < width * height; index++) {
            out.putShort((short) (index * 997));
        }
        return out.array();
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            out.putShort((short) value).putShort((short) 0);
        } else {
            out.putInt(value);
        }
    }

    private static void head(String method, String vector) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(vector(vector))) {
            bytes = in.readNBytes(HEAD);
        }
        var extension = vector.substring(vector.lastIndexOf('.') + 1);
        write(RawFuzzTest.class, method, "head-" + vector.substring("rawpixls-".length(), vector.lastIndexOf('.'))
                + "." + extension, bytes);
    }
}
