package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.HeifBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Builds small synthetic files of the camera raw layouts the metadata reader handles (Olympus ORF,
 * Panasonic RW2, Fujifilm RAF, Canon CR3, Minolta MRW, DNG and a TIFF-signature vendor raw), a HEIF
 * image sequence, an EXIF block with the extended types and a JPEG file with an uncompressed EXIF
 * thumbnail, from the published descriptions of their structures; the metadata tests read them and
 * the fuzzing seed writer stores them as seeds.
 */
public final class MetadataSeeds {
    /** The user type of the Canon metadata box of a CR3 file. */
    static final byte[] CANON_UUID = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F, 0x11,
            (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /** The user type of the XMP box of an ISO-BMFF file. */
    static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    private MetadataSeeds() {
    }

    /** The descriptive IFD0 entries every synthetic raw file carries. */
    public static Map<Integer, ExifValue> camera(String make, String model) {
        var ifd0 = new LinkedHashMap<Integer, ExifValue>();
        ifd0.put(0x010F, new ExifValue.Ascii(make));
        ifd0.put(0x0110, new ExifValue.Ascii(model));
        ifd0.put(ExifData.ORIENTATION, ExifValue.ofShort(1));
        return ifd0;
    }

    /** The Exif directory entries every synthetic raw file carries. */
    public static Map<Integer, ExifValue> shot() {
        var exif = new LinkedHashMap<Integer, ExifValue>();
        exif.put(0x829A, ExifValue.ofRational(1, 250));
        exif.put(0x8827, ExifValue.ofShort(400));
        exif.put(0x9003, new ExifValue.Ascii("2026:10:22 12:00:00"));
        return exif;
    }

    /** A GPS directory holding a latitude. */
    public static Map<Integer, ExifValue> position() {
        var gps = new LinkedHashMap<Integer, ExifValue>();
        gps.put(0x0001, new ExifValue.Ascii("N"));
        gps.put(0x0002, new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{45, 27, 51}, new long[]{1, 1, 1}));
        return gps;
    }

    static byte[] tiff(Map<Integer, ExifValue> ifd0, Map<Integer, ExifValue> exif, Map<Integer, ExifValue> gps,
                       ByteOrder order) {
        return ExifWriter.write(new ExifData(ifd0, exif, gps, Map.of(), Map.of(), Optional.empty()), order);
    }

    static byte[] withPadding(byte[] head, int imageBytes, long seed) {
        var image = new byte[imageBytes];
        new Random(seed).nextBytes(image);
        return concat(head, image);
    }

    /** An Olympus ORF file: a little-endian TIFF structure whose version field reads {@code RO}. */
    public static byte[] orf() {
        var tiff = tiff(camera("OLYMPUS IMAGING CORP.", "E-SEED"), shot(), Map.of(), ByteOrder.LITTLE_ENDIAN);
        tiff[2] = 'R';
        tiff[3] = 'O';
        return withPadding(tiff, 256, 1);
    }

    /** A Panasonic RW2 file: version field {@code U}, with sensor tags below 254 in IFD0. */
    public static byte[] rw2() {
        var ifd0 = camera("Panasonic", "DMC-SEED");
        ifd0.put(0x0002, ExifValue.ofShort(4000));
        ifd0.put(0x0003, ExifValue.ofShort(3000));
        ifd0.put(0x0017, ExifValue.ofShort(200));
        var tiff = tiff(ifd0, shot(), Map.of(), ByteOrder.LITTLE_ENDIAN);
        tiff[2] = 'U';
        tiff[3] = 0;
        return withPadding(tiff, 256, 2);
    }

    /** A vendor raw file with a TIFF signature, told from a TIFF file by its color filter array photometric. */
    public static byte[] tiffRaw() {
        var ifd0 = camera("NIKON CORPORATION", "NIKON SEED");
        ifd0.put(0x0106, ExifValue.ofShort(32803));
        ifd0.put(0x011A, ExifValue.ofRational(300, 1));
        ifd0.put(0x011B, ExifValue.ofRational(300, 1));
        ifd0.put(0x0128, ExifValue.ofShort(2));
        return withPadding(tiff(ifd0, shot(), position(), ByteOrder.BIG_ENDIAN), 256, 3);
    }

    /** A DNG file: a TIFF structure whose IFD0 holds {@code DNGVersion} and {@code UniqueCameraModel}. */
    public static byte[] dng() {
        var ifd0 = camera("Canon", "Canon EOS SEED");
        ifd0.put(50706, new ExifValue.Integers(ExifValue.Type.BYTE, new long[]{1, 4, 0, 0}));
        ifd0.put(50708, new ExifValue.Ascii("Canon EOS SEED"));
        ifd0.put(0x011A, ExifValue.ofRational(72, 1));
        ifd0.put(0x011B, ExifValue.ofRational(72, 1));
        ifd0.put(0x0128, ExifValue.ofShort(2));
        return withPadding(tiff(ifd0, shot(), Map.of(), ByteOrder.LITTLE_ENDIAN), 256, 4);
    }

    /** The EXIF block and XMP packet of the JPEG preview of {@link #raf()}. */
    public static ExifData rafExif() {
        return new ExifData(camera("FUJIFILM", "X-SEED"), shot(), Map.of(), Map.of(), Map.of(), Optional.empty());
    }

    /** A Fujifilm RAF file: a 160-byte header pointing at a JPEG preview that carries EXIF and XMP. */
    public static byte[] raf() {
        var preview = JpegEncoder.encode(RgbaPicture.allocate(16, 16, false), JpegEncoderOptions.ofQuality(80));
        var jpeg = ImageMetadataEditor.apply(preview, ImageMetadataEdit.keepAll().toBuilder().exif(rafExif())
                .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "RAF seed")).build());
        var header = ByteBuffer.allocate(160);
        header.put("FUJIFILMCCD-RAW 0201FF383501".getBytes(StandardCharsets.US_ASCII));
        header.put("X-SEED".getBytes(StandardCharsets.US_ASCII));
        header.putInt(84, 160).putInt(88, jpeg.length);
        header.putInt(92, 160 + jpeg.length).putInt(96, 0);
        return withPadding(concat(header.array(), jpeg), 256, 5);
    }

    static byte[] box(String type, byte[]... payload) {
        var body = concat(payload);
        var out = ByteBuffer.allocate(8 + body.length);
        out.putInt(8 + body.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body);
        return out.array();
    }

    /** A Canon CR3 file: CMT1, CMT2, CMT3 and CMT4 TIFF structures in the Canon uuid box, and an XMP uuid box. */
    public static byte[] cr3() {
        var ftyp = box("ftyp", "crx ".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 0, 0, 1},
                "crx isom".getBytes(StandardCharsets.US_ASCII));
        var canon = box("uuid", CANON_UUID, box("CNCV", "CanonCR3_001/00.10.00/00.00.00".getBytes(StandardCharsets.US_ASCII)),
                box("CMT1", tiff(camera("Canon", "Canon EOS SEED"), Map.of(), Map.of(), ByteOrder.LITTLE_ENDIAN)),
                box("CMT2", tiff(shot(), Map.of(), Map.of(), ByteOrder.LITTLE_ENDIAN)),
                box("CMT3", tiff(Map.of(0x0001, ExifValue.ofShort(7)), Map.of(), Map.of(), ByteOrder.LITTLE_ENDIAN)),
                box("CMT4", tiff(position(), Map.of(), Map.of(), ByteOrder.LITTLE_ENDIAN)));
        var moov = box("moov", canon, box("trak", box("tkhd", new byte[84])));
        var xmp = box("uuid", XMP_UUID, XmpPacket.empty().with(XmpPacket.DC, "title", "CR3 seed").toBytes());
        var data = new byte[128];
        new Random(6).nextBytes(data);
        return concat(ftyp, moov, xmp, box("mdat", data));
    }

    /** A Minolta MRW file: an MRM block holding a PRD block and a TTW block with a TIFF structure. */
    public static byte[] mrw() {
        var tiff = tiff(camera("KONICA MINOLTA", "DYNAX SEED"), shot(), Map.of(), ByteOrder.BIG_ENDIAN);
        var prd = concat(new byte[]{0, 'P', 'R', 'D'}, u32(24), new byte[24]);
        var ttw = concat(new byte[]{0, 'T', 'T', 'W'}, u32(tiff.length), tiff);
        var blocks = concat(prd, ttw);
        return withPadding(concat(new byte[]{0, 'M', 'R', 'M'}, u32(blocks.length), blocks), 256, 7);
    }

    /** An EXIF block with UTF-8 text, a type 13 pointer and an uncompressed RGB thumbnail. */
    public static byte[] extendedExif(ByteOrder order) {
        var ifd1 = new LinkedHashMap<Integer, ExifValue>();
        ifd1.put(0x0100, ExifValue.ofShort(4));
        ifd1.put(0x0101, ExifValue.ofShort(2));
        ifd1.put(0x0102, ExifValue.ofShort(8, 8, 8));
        ifd1.put(0x0106, ExifValue.ofShort(2));
        ifd1.put(0x0115, ExifValue.ofShort(3));
        var samples = new byte[4 * 2 * 3];
        new Random(8).nextBytes(samples);
        var data = new ExifData(Map.of(0x013B, ExifValue.ofUtf8("Caffè 日本"), 0x010F, new ExifValue.Ascii("Smalt")),
                Map.of(0x9286, ExifValue.ofUtf8("été")), position(), Map.of(), ifd1, Optional.empty())
                .withUncompressedThumbnail(samples);
        return ExifWriter.write(data, order);
    }

    /** A JPEG file whose EXIF segment holds the uncompressed RGB thumbnail of {@link #extendedExif}. */
    public static byte[] uncompressedThumbnailJpeg() {
        var picture = RgbaPicture.allocate(24, 16, false);
        new Random(10).nextBytes(picture.pixels());
        var image = JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(75));
        var block = extendedExif(ByteOrder.LITTLE_ENDIAN);
        var segment = ByteBuffer.allocate(10 + block.length).put((byte) 0xFF).put((byte) 0xE1)
                .putShort((short) (block.length + 8)).put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(block).array();
        return concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, segment, java.util.Arrays.copyOfRange(image, 2, image.length));
    }

    /**
     * A HEIF image sequence: a still primary item with an ICC property, then a {@code moov} box whose
     * {@code stco} offsets point into the item data.
     */
    public static byte[] heifSequence() {
        var builder = new HeifBuilder();
        var data = new byte[200];
        new Random(9).nextBytes(data);
        var id = builder.item("av01", data, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.plain(HeifBuilder.nclx(1, 13, 6, true)), HeifBuilder.plain(HeifBuilder.icc(new byte[40])));
        var still = builder.primary(id).build();
        var mdat = indexOf(still, "mdat".getBytes(StandardCharsets.US_ASCII)) + 4;
        var stco = HeifBuilder.fullBox("stco", 0, 0, concat(u32(2), u32(mdat), u32(mdat + 100)));
        return concat(still, box("moov", box("trak", box("mdia", box("minf", box("stbl", stco))))));
    }

    static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (var i = 0; i + needle.length <= data.length; i++) {
            for (var j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static byte[] u32(long value) {
        return ByteBuffer.allocate(4).putInt((int) value).array();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
