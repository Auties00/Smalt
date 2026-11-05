/* Ports libjxl 0.11.2 (lib/jxl/jpeg/enc_jpeg_data.cc: EncodeJPEGData, DetectIccProfile, DetectBlobs,
   ParseChunkedMarker; lib/jxl/jpeg/jpeg_data.cc: JPEGData::VisitFields). Copyright the JPEG XL Project Authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the payload of a {@code jbrd} box from a parsed JPEG file, and extracts the ICC profile, EXIF and XMP
 * segments that move to the codestream and to their own boxes.
 */
final class JbrdWriter {
    private static final byte[] ICC_TAG = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] EXIF_TAG = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] XMP_TAG = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    private final JpegReader jpeg;

    private final int[] appTypes;

    byte[] icc;

    byte[] exif;

    byte[] xmp;

    JbrdWriter(JpegReader jpeg) {
        this.jpeg = jpeg;
        this.appTypes = new int[jpeg.app.size()];
        detectIcc();
        detectBlobs();
    }

    private void detectIcc() {
        var numIcc = 0;
        var numChunks = 0;
        var parts = new ByteArrayOutputStream();
        for (var i = 0; i < jpeg.app.size(); i++) {
            var app = jpeg.app.get(i);
            if ((app[0] & 0xFF) != 0xE2) {
                continue;
            }
            var tagLength = ((app[1] & 0xFF) << 8) | (app[2] & 0xFF);
            if (tagLength < 2 + ICC_TAG.length || !startsWith(app, 3, ICC_TAG)) {
                continue;
            }
            var chunkId = app[3 + ICC_TAG.length] & 0xFF;
            var chunks = app[4 + ICC_TAG.length] & 0xFF;
            if (chunkId != numIcc + 1) {
                continue;
            }
            if (numChunks == 0) {
                numChunks = chunks;
            }
            if (numChunks != chunks) {
                continue;
            }
            numIcc++;
            appTypes[i] = 1;
            parts.write(app, 5 + ICC_TAG.length, app.length - 5 - ICC_TAG.length);
        }
        if (numIcc != numChunks) {
            for (var i = 0; i < appTypes.length; i++) {
                if (appTypes[i] == 1) {
                    appTypes[i] = 0;
                }
            }
            return;
        }
        if (numIcc > 0) {
            icc = parts.toByteArray();
        }
    }

    private void detectBlobs() {
        for (var i = 0; i < jpeg.app.size(); i++) {
            var app = jpeg.app.get(i);
            if ((app[0] & 0xFF) != 0xE1 || app.length < 3) {
                continue;
            }
            var size = ((app[1] & 0xFF) << 8) | (app[2] & 0xFF);
            if (size != app.length - 1) {
                continue;
            }
            var payload = app.length - 3;
            if (exif == null && payload > EXIF_TAG.length && startsWith(app, 3, EXIF_TAG)) {
                appTypes[i] = 2;
                exif = Arrays.copyOfRange(app, 3 + EXIF_TAG.length, app.length);
            }
            if (xmp == null && payload >= XMP_TAG.length && startsWith(app, 3, XMP_TAG)) {
                appTypes[i] = 3;
                xmp = Arrays.copyOfRange(app, 3 + XMP_TAG.length, app.length);
            }
        }
    }

    private static boolean startsWith(byte[] data, int offset, byte[] tag) {
        if (data.length < offset + tag.length) {
            return false;
        }
        for (var i = 0; i < tag.length; i++) {
            if (data[offset + i] != tag[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the box payload: the reconstruction bundle, byte-aligned, then the Brotli stream of the unknown
     * APP segments, the COM segments, the bytes between markers and the bytes after EOI.
     *
     * @return the payload
     */
    byte[] payload() {
        var w = new JxlBitWriter(1024);
        w.writeBool(jpeg.components.size() == 1);
        var hasDri = false;
        for (var marker : jpeg.markers) {
            w.write(6, marker - 0xC0);
            hasDri |= marker == 0xDD;
        }
        for (var i = 0; i < jpeg.app.size(); i++) {
            w.writeU32(appTypes[i], 0, 0, 1, 0, 2, 1, 4, 2);
            w.write(16, jpeg.app.get(i).length - 1);
        }
        for (var c : jpeg.com) {
            w.write(16, c.length - 1);
        }
        w.write(2, jpeg.quant.size() - 1);
        for (var q : jpeg.quant) {
            w.write(1, q.precision);
            w.write(2, q.index);
            w.writeBool(q.last);
        }
        var comps = jpeg.components;
        int type;
        if (comps.size() == 1 && comps.get(0).id == 1) {
            type = 0;
        } else if (comps.size() == 3 && comps.get(0).id == 1 && comps.get(1).id == 2 && comps.get(2).id == 3) {
            type = 1;
        } else if (comps.size() == 3 && comps.get(0).id == 'R' && comps.get(1).id == 'G'
                && comps.get(2).id == 'B') {
            type = 2;
        } else {
            type = 3;
        }
        w.write(2, type);
        if (type == 3) {
            w.write(2, comps.size() - 1);
            for (var c : comps) {
                w.write(8, c.id);
            }
        }
        for (var c : comps) {
            w.write(2, c.quantIndex);
        }
        w.writeU32(jpeg.huffman.size(), 4, 0, 2, 3, 10, 4, 26, 6);
        for (var h : jpeg.huffman) {
            w.writeBool((h.slot & 0x10) != 0);
            w.write(2, h.slot & 0x0F);
            w.writeBool(h.last);
            for (var i = 0; i < 17; i++) {
                w.writeU32(h.counts[i], 0, 0, 1, 0, 2, 3, 0, 8);
            }
            var total = h.total();
            for (var i = 0; i < total; i++) {
                w.writeU32(h.values[i], 0, 2, 4, 2, 8, 4, 1, 8);
            }
        }
        for (var s : jpeg.scans) {
            w.write(2, s.count - 1);
            w.write(6, s.ss);
            w.write(6, s.se);
            w.write(4, s.al);
            w.write(4, s.ah);
            for (var i = 0; i < s.count; i++) {
                w.write(2, s.component[i]);
                w.write(2, s.acTable[i]);
                w.write(2, s.dcTable[i]);
            }
            w.writeU32(0, 0, 0, 1, 0, 2, 0, 3, 3);
        }
        if (hasDri) {
            w.write(16, jpeg.restartInterval);
        }
        for (var s : jpeg.scans) {
            w.writeU32(s.resetPoints.size(), 0, 0, 1, 2, 4, 4, 20, 16);
            var last = -1;
            for (var p : s.resetPoints) {
                w.writeU32(last < 0 ? p : p - last - 1, 0, 0, 1, 3, 9, 5, 41, 28);
                last = p;
            }
            w.writeU32(s.extraZeroRuns.size(), 0, 0, 1, 2, 4, 4, 20, 16);
            last = -1;
            for (var run : s.extraZeroRuns) {
                w.writeU32(run[1], 1, 0, 2, 2, 5, 4, 20, 8);
                w.writeU32(last < 0 ? run[0] : run[0] - last - 1, 0, 0, 1, 3, 9, 5, 41, 28);
                last = run[0];
            }
        }
        for (var d : jpeg.intermarker) {
            w.write(16, d.length);
        }
        w.writeU32(jpeg.tail.length, 0, 0, 1, 8, 257, 16, 65793, 22);
        w.writeBool(jpeg.hasZeroPaddingBit);
        if (jpeg.hasZeroPaddingBit) {
            w.write(24, jpeg.paddingBits.size());
            for (var bit : jpeg.paddingBits) {
                w.write(1, bit);
            }
        }
        w.zeroPadToByte();
        var data = new ByteArrayOutputStream();
        for (var i = 0; i < jpeg.app.size(); i++) {
            if (appTypes[i] == 0) {
                data.writeBytes(jpeg.app.get(i));
            }
        }
        for (var c : jpeg.com) {
            data.writeBytes(c);
        }
        for (var d : jpeg.intermarker) {
            data.writeBytes(d);
        }
        data.writeBytes(jpeg.tail);
        var header = w.toByteArray();
        var brotli = BrotliWriter.compress(data.toByteArray());
        var out = Arrays.copyOf(header, header.length + brotli.length);
        System.arraycopy(brotli, 0, out, header.length, brotli.length);
        return out;
    }
}
