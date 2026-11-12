package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.jpeg.JpegReconstructor;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and edits the metadata of a JPEG XL file (ISO/IEC 18181-2): the {@code Exif} and
 * {@code xml } boxes of its container (also when Brotli-compressed in {@code brob} boxes) and the
 * ICC profile of its codestream.
 *
 * <p>An edit replaces the EXIF and XMP boxes: a changed kind loses every box that held it, plain
 * or compressed, and gains one plain box where the first one was, or before the codestream when
 * the file had none; a kept kind is copied byte for byte, compressed boxes included. A bare
 * codestream that gains metadata is wrapped in a container (signature, {@code ftyp}, the metadata
 * boxes and a {@code jxlc} box). The codestream, the {@code jbrd} JPEG reconstruction data and the
 * other structural boxes are copied unchanged; an edit that removes other metadata drops the
 * remaining boxes. The ICC profile is part of the codestream header and cannot change (removing
 * a profile from a codestream that embeds none changes nothing), nor can IPTC data, comments or
 * text be stored. Since JPEG reconstruction takes the EXIF and XMP
 * payloads from their boxes and requires the lengths the original JPEG file had, an edit of a
 * file with a {@code jbrd} box must keep such a payload's length.
 */
final class JxlFile implements ImageFile {
    /**
     * The container signature box.
     */
    private static final byte[] SIGNATURE = {0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The file type box of a JPEG XL container: brand {@code jxl }, minor version 0, compatible
     * with {@code jxl }.
     */
    private static final byte[] FTYP = {0, 0, 0, 0x14, 'f', 't', 'y', 'p', 'j', 'x', 'l', ' ', 0, 0, 0, 0, 'j', 'x',
            'l', ' '};

    /**
     * The box types that carry or describe the image rather than metadata, kept by every edit.
     */
    private static final Set<String> STRUCTURAL = Set.of("JXL ", "ftyp", "jxll", "jxlc", "jxlp", "jbrd", "jxli");

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The whole file.
     */
    private final byte[] data;

    /**
     * The parsed codestream headers and container.
     */
    private final JxlCodestream codestream;

    /**
     * Reads the file's container and image header.
     *
     * @param source the JPEG XL file
     * @param limits the resource bounds
     * @throws MediaException.Malformed     if the container or the header is corrupt
     * @throws MediaException.LimitExceeded if the file exceeds the byte limit
     */
    JxlFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var size = source.size();
        LimitChecks.checkAllocation(limits, size);
        if (size < 0 || size > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("JPEG XL file of " + size + " bytes");
        }
        this.data = new byte[(int) size];
        source.readFully(0, data, 0, data.length);
        this.codestream = JxlCodestream.open(data, limits);
    }

    @Override
    public ImageMetadata metadata() {
        var container = codestream.container();
        XmpPacket xmp = null;
        if (container.xmp() != null) {
            try {
                xmp = XmpPacket.parse(container.xmp());
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var icc = codestream.icc();
        return new ImageMetadata(ImageFormat.JXL, container.exif(), xmp, null, icc != null ? icc.clone() : null,
                List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        // removing a profile the codestream does not embed leaves the file as it is
        if (!(target.icc() instanceof ImageMetadataEdit.Change.Keep)
                && (target.iccProfile() != null || codestream.icc() != null)) {
            throw new MediaException.Unsupported("jxl", "the ICC profile of jxl files is part of the codestream and "
                    + "cannot be edited");
        }
        if ((target.iptc() instanceof ImageMetadataEdit.Change.Replace && target.iptcData() != null
                && !target.iptcData().isEmpty())
                || (target.comments() instanceof ImageMetadataEdit.Change.Replace && !target.commentList().isEmpty())
                || (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty())) {
            throw ImageFiles.unsupported(ImageFormat.JXL, "IPTC data, comments or text");
        }
        if (target.keepsAll()) {
            return EditPlan.builder().copy(0, data.length).build();
        }
        var changeExif = !(target.exif() instanceof ImageMetadataEdit.Change.Keep);
        var changeXmp = !(target.xmp() instanceof ImageMetadataEdit.Change.Keep);
        byte[] exifBox = null;
        if (target.exifBytes() != null) {
            var payload = new byte[4 + target.exifBytes().length];
            System.arraycopy(target.exifBytes(), 0, payload, 4, target.exifBytes().length);
            exifBox = box("Exif", payload);
        }
        var xmpBox = target.xmpPacket() != null ? box("xml ", target.xmpPacket().toBytes()) : null;
        checkReconstruction(changeExif, exifBox, changeXmp, xmpBox);
        var plan = EditPlan.builder();
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && data[1] == 0x0A) {
            if (exifBox == null && xmpBox == null) {
                return plan.copy(0, data.length).build();
            }
            plan.bytes(SIGNATURE).bytes(FTYP);
            if (exifBox != null) {
                plan.bytes(exifBox);
            }
            if (xmpBox != null) {
                plan.bytes(xmpBox);
            }
            plan.bytes(boxHeader("jxlc", data.length)).copy(0, data.length);
            return plan.build();
        }
        var boxes = boxes();
        var hasExif = boxes.stream().anyMatch(box -> box.kind().equals("Exif"));
        var hasXmp = boxes.stream().anyMatch(box -> box.kind().equals("xml "));
        var exifPending = exifBox;
        var xmpPending = xmpBox;
        for (var box : boxes) {
            var kind = box.kind();
            if (box.isCodestream()) {
                if (exifPending != null && !hasExif) {
                    plan.bytes(exifPending);
                    exifPending = null;
                }
                if (xmpPending != null && !hasXmp) {
                    plan.bytes(xmpPending);
                    xmpPending = null;
                }
            }
            if (kind.equals("Exif") && changeExif) {
                if (exifPending != null) {
                    plan.bytes(exifPending);
                    exifPending = null;
                }
            } else if (kind.equals("xml ") && changeXmp) {
                if (xmpPending != null) {
                    plan.bytes(xmpPending);
                    xmpPending = null;
                }
            } else if (STRUCTURAL.contains(box.type()) || kind.equals("Exif") || kind.equals("xml ")
                    || !target.removeOther()) {
                plan.copy(box.offset(), box.size());
            }
        }
        return plan.build();
    }

    /**
     * Checks that an edit keeps the metadata lengths the JPEG reconstruction data requires.
     *
     * @param changeExif whether the EXIF box changes
     * @param exifBox the new EXIF box, or {@code null} when removed or unchanged
     * @param changeXmp whether the XMP box changes
     * @param xmpBox the new XMP box, or {@code null} when removed or unchanged
     * @throws MediaException.Unsupported if a changed payload would no longer have the length
     * the reconstruction data records
     */
    private void checkReconstruction(boolean changeExif, byte[] exifBox, boolean changeXmp, byte[] xmpBox) {
        var jbrd = codestream.container().jbrd();
        if (jbrd == null || (!changeExif && !changeXmp)) {
            return;
        }
        long[] lengths;
        try {
            lengths = JpegReconstructor.metadataLengths(jbrd);
        } catch (MediaException.Malformed exception) {
            // reconstruction data that does not parse cannot be reconstructed anyway
            return;
        }
        // the reconstruction takes the Exif payload after its offset field, and the whole xml payload
        if (changeExif && lengths[0] > 0 && (exifBox == null ? 0 : exifBox.length - 12) != lengths[0]) {
            throw new MediaException.Unsupported("jxl", "the EXIF block of a jxl file with JPEG reconstruction data "
                    + "must keep its length of " + lengths[0] + " bytes");
        }
        if (changeXmp && lengths[1] > 0 && (xmpBox == null ? 0 : xmpBox.length - 8) != lengths[1]) {
            throw new MediaException.Unsupported("jxl", "the XMP packet of a jxl file with JPEG reconstruction data "
                    + "must keep its length of " + lengths[1] + " bytes");
        }
    }

    /**
     * Lists the top-level boxes of the container.
     *
     * @return the boxes, in file order
     * @throws MediaException.Malformed if a box header is truncated or invalid
     */
    private List<Box> boxes() {
        var result = new ArrayList<Box>();
        var offset = 0L;
        while (offset < data.length) {
            if (data.length - offset < 8) {
                throw new MediaException.Malformed("truncated JPEG XL box header");
            }
            var at = (int) offset;
            var size = ByteArrays.u32be(data, at);
            var type = new String(data, at + 4, 4, StandardCharsets.ISO_8859_1);
            var header = 8;
            long boxSize;
            if (size == 1) {
                if (data.length - offset < 16) {
                    throw new MediaException.Malformed("truncated JPEG XL box header");
                }
                boxSize = ByteArrays.i64be(data, at + 8);
                header = 16;
            } else if (size == 0) {
                boxSize = data.length - offset;
            } else {
                boxSize = size;
            }
            if (boxSize < header || boxSize > data.length - offset) {
                throw new MediaException.Malformed("invalid JPEG XL box size " + boxSize + " at offset " + offset);
            }
            var kind = type;
            if (type.equals("brob") && boxSize >= header + 4) {
                kind = new String(data, at + header, 4, StandardCharsets.ISO_8859_1);
            }
            result.add(new Box(type, kind, offset, boxSize));
            offset += boxSize;
        }
        return result;
    }

    /**
     * Builds a box.
     *
     * @param type the four-character box type
     * @param payload the box content after its header
     * @return the box
     */
    private static byte[] box(String type, byte[] payload) {
        var header = boxHeader(type, payload.length);
        var result = new byte[header.length + payload.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(payload, 0, result, header.length, payload.length);
        return result;
    }

    /**
     * Builds a box header, with a 64-bit size when the box does not fit a 32-bit one.
     *
     * @param type the four-character box type
     * @param payloadLength the payload length
     * @return the header
     */
    private static byte[] boxHeader(String type, long payloadLength) {
        var large = payloadLength + 8 > 0xFFFF_FFFFL;
        var header = new byte[large ? 16 : 8];
        ByteArrays.put32be(header, 0, large ? 1 : payloadLength + 8);
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, header, 4, 4);
        if (large) {
            ByteArrays.put64be(header, 8, payloadLength + 16);
        }
        return header;
    }

    /**
     * Holds one top-level box.
     *
     * @param type the box type
     * @param kind the box type, or the type of the box a {@code brob} box compresses
     * @param offset the offset of the box
     * @param size the size of the box, header included
     */
    private record Box(String type, String kind, long offset, long size) {
        /**
         * {@return whether the box holds codestream data}
         */
        boolean isCodestream() {
            return type.equals("jxlc") || type.equals("jxlp");
        }
    }
}
