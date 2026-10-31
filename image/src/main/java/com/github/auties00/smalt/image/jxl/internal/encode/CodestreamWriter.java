/* Ports libjxl 0.11.2 (lib/jxl/enc_frame.cc: the section layout and the merged single-section case; enc_toc.cc:
   WriteGroupOffsets; encode.cc: the container box layout). Copyright the JPEG XL Project Authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Assembles frames into a codestream and codestreams into ISO-BMFF containers.
 */
public final class CodestreamWriter {
    private static final byte[] SIGNATURE_BOX = {0, 0, 0, 12, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    private CodestreamWriter() {
        throw new AssertionError();
    }

    /**
     * Writes a frame: its header, its table of contents and its sections.
     *
     * @param out the codestream, byte-aligned
     * @param frame the frame header fields
     * @param image the image header
     * @param sections the sections in TOC order: LF global, the LF groups, HF global and the pass groups; when
     * the frame has one group and one pass they are merged into one section
     * @param numGroups the number of groups
     * @param numPasses the number of passes
     */
    public static void writeFrame(JxlBitWriter out, HeaderWriter.Frame frame, ImageHeader image,
                                  List<JxlBitWriter> sections, int numGroups, int numPasses) {
        HeaderWriter.writeFrameHeader(out, frame, image);
        byte[][] data;
        if (numGroups == 1 && numPasses == 1) {
            var merged = new JxlBitWriter(1024);
            for (var s : sections) {
                merged.append(s);
            }
            merged.zeroPadToByte();
            data = new byte[][]{merged.toByteArray()};
        } else {
            data = new byte[sections.size()][];
            for (var i = 0; i < sections.size(); i++) {
                var s = sections.get(i);
                s.zeroPadToByte();
                data[i] = s.toByteArray();
            }
        }
        var sizes = new int[data.length];
        for (var i = 0; i < data.length; i++) {
            sizes[i] = data[i].length;
        }
        HeaderWriter.writeToc(out, sizes);
        for (var d : data) {
            out.appendBytes(d, 0, d.length);
        }
    }

    /**
     * Returns the conformance level (ISO/IEC 18181-2 clause 6) a codestream needs: 10 when its modular samples need
     * more than 16-bit buffers, a side exceeds 2^18 pixels or the area 2^28, the ICC profile exceeds 4 MiB, it has
     * more than 4 extra channels or a black ink channel; 5 otherwise.
     *
     * @param codestream the bare codestream
     * @param iccSize the size of its ICC profile in bytes, or 0 for none
     * @return 5 or 10
     */
    public static int level(byte[] codestream, int iccSize) {
        var header = ImageHeader.read(new JxlBitReader(codestream, 0, codestream.length));
        var width = header.width();
        var height = header.height();
        var level10 = !header.modular16Bit() || width > 1 << 18 || height > 1 << 18
                || (long) width * height > 1L << 28 || iccSize > 1 << 22 || header.extraChannels().size() > 4;
        for (var info : header.extraChannels()) {
            level10 |= info.type() == ExtraChannelInfo.TYPE_BLACK;
        }
        return level10 ? 10 : 5;
    }

    /**
     * Wraps a codestream in a container.
     *
     * @param codestream the bare codestream
     * @param exif the EXIF block (a TIFF header and its directories), or {@code null} for none
     * @param xmp the XMP packet, or {@code null} for none
     * @param level the conformance level, 5 or 10; 10 adds a {@code jxll} box
     * @param frameIndex the payload of a {@code jxli} box, or {@code null} for none
     * @param jbrd the payload of a {@code jbrd} box, or {@code null} for none
     * @return the file
     */
    public static byte[] container(byte[] codestream, byte[] exif, byte[] xmp, int level, byte[] frameIndex,
                                   byte[] jbrd) {
        var out = new ByteArrayOutputStream(codestream.length + 256);
        out.writeBytes(SIGNATURE_BOX);
        box(out, "ftyp", new byte[]{'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x', 'l', ' '});
        if (level == 10) {
            box(out, "jxll", new byte[]{10});
        }
        if (jbrd != null) {
            box(out, "jbrd", jbrd);
        }
        if (exif != null) {
            var payload = new byte[exif.length + 4];
            System.arraycopy(exif, 0, payload, 4, exif.length);
            box(out, "Exif", payload);
        }
        if (xmp != null) {
            box(out, "xml ", xmp);
        }
        if (frameIndex != null) {
            box(out, "jxli", frameIndex);
        }
        box(out, "jxlc", codestream);
        return out.toByteArray();
    }

    private static void box(ByteArrayOutputStream out, String type, byte[] payload) {
        long size = 8L + payload.length;
        if (size > 0xFFFF_FFFFL) {
            writeInt(out, 1);
            out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
            writeInt(out, (int) ((size + 8) >>> 32));
            writeInt(out, (int) (size + 8));
        } else {
            writeInt(out, (int) size);
            out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes(payload);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }
}
