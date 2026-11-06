package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and edits the metadata of a JPEG 2000 file (ISO/IEC 15444-1 annex I, ISO/IEC 15444-2 annex M): the EXIF
 * block, XMP packet and IPTC-IIM data of its top-level {@code uuid} boxes, an XMP packet of an {@code xml } box,
 * and the ICC profile of the color specification of its JP2 header box.
 *
 * <p>An edit replaces the metadata boxes: a changed kind loses every box that held it and gains one {@code uuid}
 * box where the first one was, or after the JP2 header box when the file had none (before the first codestream box
 * of a file without one). A changed ICC profile rewrites the JP2 header box: a new profile takes the place of the
 * first color specification as a restricted ICC one (method 2) and replaces every ICC color specification, keeping
 * the enumerated ones after it; a removed profile drops the ICC color specifications, and an enumerated sRGB, or
 * grayscale for fewer than three components, takes their place when none is left. The codestreams and every other
 * structural box are copied unchanged; an edit that removes other metadata drops the remaining top-level boxes (an
 * {@code xml } box that is not XMP, a {@code uuid} box of another kind, the {@code uinf} and {@code asoc} boxes).
 * Comments and text cannot be stored, nor can the metadata of a raw codestream or of a file whose codestream is held
 * in fragment tables, whose offsets an edit would move.
 */
final class Jp2File implements ImageFile {
    /**
     * The UUID of the box that holds an EXIF block, {@code JpgTiffExif->JP2}.
     */
    private static final byte[] EXIF_UUID = "JpgTiffExif->JP2".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The UUID of the box that holds an XMP packet, {@code BE7ACFCB-97A9-42E8-9C71-999491E3AFAC}.
     */
    private static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /**
     * The UUID of the box that holds IPTC-IIM data sets, {@code 33C7A4D2-B81D-4723-A0BA-F1A3E097AD38}.
     */
    private static final byte[] IPTC_UUID = {0x33, (byte) 0xC7, (byte) 0xA4, (byte) 0xD2, (byte) 0xB8, 0x1D, 0x47, 0x23,
            (byte) 0xA0, (byte) 0xBA, (byte) 0xF1, (byte) 0xA3, (byte) 0xE0, (byte) 0x97, (byte) 0xAD, 0x38};

    /**
     * The top-level box types that carry or describe the image rather than metadata, kept by every edit.
     */
    private static final Set<String> STRUCTURAL = Set.of("jP  ", "ftyp", "rreq", "jp2h", "jp2c", "jp2i", "jpch",
            "jplh", "cgrp", "ftbl", "comp", "dtbl", "jclx");

    /**
     * The enumerated color space sRGB.
     */
    private static final int SRGB = 16;

    /**
     * The enumerated color space grayscale.
     */
    private static final int GRAYSCALE = 17;

    /**
     * The largest number of top-level boxes read.
     */
    private static final int MAX_BOXES = 1 << 16;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * Whether the source is a raw codestream rather than a box-structured file.
     */
    private final boolean raw;

    /**
     * The top-level boxes, in file order.
     */
    private final List<Box> boxes = new ArrayList<>();

    /**
     * The content of the first JP2 header box, or {@code null}.
     */
    private byte[] header;

    /**
     * Whether the file has a fragment table box.
     */
    private boolean fragmented;

    /**
     * The number of components of the image header box, or 0 without one.
     */
    private int components;

    /**
     * The EXIF block of the first EXIF box, or {@code null}.
     */
    private byte[] exif;

    /**
     * The XMP packet of the first XMP box, or {@code null}.
     */
    private byte[] xmp;

    /**
     * The IPTC-IIM data of the first IPTC box, or {@code null}.
     */
    private byte[] iptc;

    /**
     * The ICC profile of the color specification in use, or {@code null}.
     */
    private byte[] icc;

    /**
     * Reads the file's top-level boxes and the content of its JP2 header and metadata boxes.
     *
     * @param source the JPEG 2000 file or codestream
     * @param limits the resource bounds
     * @throws MediaException.Malformed if a box header is truncated or a box size invalid
     * @throws MediaException.LimitExceeded if a box read exceeds the allocation limit or the file holds too many
     * boxes
     */
    Jp2File(MediaSource source, MediaLimits limits) {
        this.source = source;
        var start = ImageFiles.readAvailable(source, 0, 4);
        this.raw = start.length == 4 && (start[0] & 0xFF) == 0xFF && (start[1] & 0xFF) == 0x4F;
        if (raw) {
            return;
        }
        var size = source.size();
        var offset = 0L;
        while (offset < size) {
            if (boxes.size() == MAX_BOXES) {
                throw new MediaException.LimitExceeded("JPEG 2000 file of more than " + MAX_BOXES + " boxes");
            }
            var head = ImageFiles.readAvailable(source, offset, 16);
            if (head.length < 8) {
                throw new MediaException.Malformed("truncated JPEG 2000 box header at offset " + offset);
            }
            var length = ByteArrays.u32be(head, 0);
            var type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            var headerLength = 8;
            long boxSize;
            if (length == 1) {
                if (head.length < 16) {
                    throw new MediaException.Malformed("truncated JPEG 2000 box header at offset " + offset);
                }
                boxSize = ByteArrays.i64be(head, 8);
                headerLength = 16;
            } else if (length == 0) {
                boxSize = size - offset;
            } else {
                boxSize = length;
            }
            if (boxSize < headerLength || boxSize > size - offset) {
                throw new MediaException.Malformed("invalid JPEG 2000 box size " + boxSize + " at offset " + offset);
            }
            var kind = Kind.OTHER;
            var contentLength = boxSize - headerLength;
            var content = offset + headerLength;
            switch (type) {
                case "uuid" -> {
                    if (contentLength >= 16) {
                        var id = ImageFiles.read(source, content, 16, limits);
                        if (Arrays.equals(id, EXIF_UUID)) {
                            kind = Kind.EXIF;
                            if (exif == null) {
                                var data = ImageFiles.read(source, content + 16, contentLength - 16, limits);
                                var prefix = TiffExif.prefixLength(data, 0);
                                if (data.length - prefix >= 8) {
                                    exif = Arrays.copyOfRange(data, prefix, data.length);
                                }
                            }
                        } else if (Arrays.equals(id, XMP_UUID)) {
                            kind = Kind.XMP;
                            if (xmp == null) {
                                xmp = ImageFiles.read(source, content + 16, contentLength - 16, limits);
                            }
                        } else if (Arrays.equals(id, IPTC_UUID)) {
                            kind = Kind.IPTC;
                            if (iptc == null) {
                                iptc = ImageFiles.read(source, content + 16, contentLength - 16, limits);
                            }
                        }
                    }
                }
                case "xml " -> {
                    var data = ImageFiles.read(source, content, contentLength, limits);
                    var text = new String(data, StandardCharsets.ISO_8859_1);
                    if (text.contains("x:xmpmeta") || text.contains("adobe:ns:meta/")) {
                        kind = Kind.XMP;
                        if (xmp == null) {
                            xmp = data;
                        }
                    }
                }
                case "jp2h" -> {
                    if (header == null) {
                        header = ImageFiles.read(source, content, contentLength, limits);
                        readHeader();
                    }
                }
                case "ftbl" -> fragmented = true;
                default -> {
                }
            }
            boxes.add(new Box(type, offset, boxSize, kind));
            offset += boxSize;
        }
    }

    /**
     * Reads the image header and the color specifications of the JP2 header box.
     *
     * @throws MediaException.Malformed if a box of the header is truncated
     */
    private void readHeader() {
        var bestPrecedence = Integer.MIN_VALUE;
        for (var child : children(header)) {
            var at = (int) (child.offset() + child.headerLength());
            var end = (int) (child.offset() + child.size());
            if (child.type().equals("ihdr") && end - at >= 10) {
                components = ByteArrays.u16be(header, at + 8);
            } else if (child.type().equals("colr") && end - at >= 3) {
                var method = header[at] & 0xFF;
                var precedence = header[at + 1];
                if (method >= 1 && method <= 3 && precedence > bestPrecedence) {
                    bestPrecedence = precedence;
                    icc = method == 1 || end - at == 3 ? null : Arrays.copyOfRange(header, at + 3, end);
                }
            }
        }
    }

    @Override
    public ImageMetadata metadata() {
        XmpPacket packet = null;
        if (xmp != null) {
            try {
                packet = XmpPacket.parse(xmp);
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var data = iptc == null ? null : IptcData.parse(iptc);
        return new ImageMetadata(ImageFormat.JP2, exif, packet, data != null && data.isEmpty() ? null : data,
                icc != null ? icc.clone() : null, List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if ((target.comments() instanceof ImageMetadataEdit.Change.Replace && !target.commentList().isEmpty())
                || (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty())) {
            throw ImageFiles.unsupported(ImageFormat.JP2, "comments or text");
        }
        var copy = EditPlan.builder().copy(0, source.size()).build();
        if (target.keepsAll()) {
            return copy;
        }
        var iptcData = target.iptcData() == null || target.iptcData().isEmpty() ? null : target.iptcData().toBytes();
        var adds = target.exifBytes() != null || target.xmpPacket() != null || iptcData != null
                || target.iccProfile() != null;
        if (raw) {
            if (adds) {
                throw new MediaException.Unsupported("jp2", "a raw JPEG 2000 codestream holds no metadata boxes");
            }
            return copy;
        }
        var changeIcc = !(target.icc() instanceof ImageMetadataEdit.Change.Keep)
                && (target.iccProfile() != null || icc != null);
        if (changeIcc && header == null) {
            throw new MediaException.Unsupported("jp2", "the file has no JP2 header box to hold an ICC profile");
        }
        var pending = new byte[Kind.values().length][];
        var change = new boolean[Kind.values().length];
        change[Kind.EXIF.ordinal()] = !(target.exif() instanceof ImageMetadataEdit.Change.Keep);
        change[Kind.XMP.ordinal()] = !(target.xmp() instanceof ImageMetadataEdit.Change.Keep);
        change[Kind.IPTC.ordinal()] = !(target.iptc() instanceof ImageMetadataEdit.Change.Keep);
        pending[Kind.EXIF.ordinal()] = target.exifBytes() == null ? null : uuid(EXIF_UUID, target.exifBytes());
        pending[Kind.XMP.ordinal()] = target.xmpPacket() == null ? null : uuid(XMP_UUID, target.xmpPacket().toBytes());
        pending[Kind.IPTC.ordinal()] = iptcData == null ? null : uuid(IPTC_UUID, iptcData);
        var present = new boolean[Kind.values().length];
        for (var box : boxes) {
            present[box.kind().ordinal()] = true;
        }
        var plan = EditPlan.builder();
        var inserted = false;
        for (var box : boxes) {
            var kind = box.kind();
            var codestream = box.type().equals("jp2c") || box.type().equals("jpch") || box.type().equals("ftbl");
            if (codestream && !inserted) {
                insert(plan, pending, present);
                inserted = true;
            }
            if (kind != Kind.OTHER && change[kind.ordinal()]) {
                if (pending[kind.ordinal()] != null) {
                    plan.bytes(pending[kind.ordinal()]);
                    pending[kind.ordinal()] = null;
                }
            } else if (box.type().equals("jp2h") && changeIcc && box.offset() == firstHeader()) {
                plan.bytes(rewriteHeader(target.iccProfile()));
            } else if (kind != Kind.OTHER || STRUCTURAL.contains(box.type()) || !target.removeOther()) {
                plan.copy(box.offset(), box.size());
            }
            if (box.type().equals("jp2h") && !inserted) {
                insert(plan, pending, present);
                inserted = true;
            }
        }
        if (!inserted) {
            insert(plan, pending, present);
        }
        var result = plan.build();
        if (fragmented && !result.isIdentity(source.size())) {
            throw new MediaException.Unsupported("jp2", "the metadata of a JPEG 2000 file whose codestream is held in "
                    + "fragment tables cannot be edited");
        }
        return result;
    }

    /**
     * Writes the new boxes of the kinds the file has no box of, in the order EXIF, IPTC, XMP.
     *
     * @param plan the plan being built
     * @param pending the new box of each kind, cleared once written
     * @param present whether the file has a box of each kind
     */
    private static void insert(EditPlan.Builder plan, byte[][] pending, boolean[] present) {
        for (var kind : List.of(Kind.EXIF, Kind.IPTC, Kind.XMP)) {
            var index = kind.ordinal();
            if (!present[index] && pending[index] != null) {
                plan.bytes(pending[index]);
                pending[index] = null;
            }
        }
    }

    /**
     * {@return the offset of the first JP2 header box}
     */
    private long firstHeader() {
        for (var box : boxes) {
            if (box.type().equals("jp2h")) {
                return box.offset();
            }
        }
        return -1;
    }

    /**
     * Rebuilds the JP2 header box with a new ICC profile, or without its ICC color specifications.
     *
     * @param profile the new profile, or {@code null} to remove the profile
     * @return the box
     */
    private byte[] rewriteHeader(byte[] profile) {
        var out = new ByteArrayOutputStream(header.length + (profile != null ? profile.length + 16 : 16));
        var children = children(header);
        var enumerated = false;
        for (var child : children) {
            var at = (int) (child.offset() + child.headerLength());
            enumerated |= child.type().equals("colr") && child.size() - child.headerLength() >= 3
                    && (header[at] & 0xFF) != 2 && (header[at] & 0xFF) != 3;
        }
        var placed = false;
        for (var child : children) {
            if (child.type().equals("colr")) {
                if (!placed) {
                    placed = true;
                    if (profile != null) {
                        var content = new byte[3 + profile.length];
                        content[0] = 2;
                        System.arraycopy(profile, 0, content, 3, profile.length);
                        out.writeBytes(box("colr", content));
                    } else if (!enumerated) {
                        out.writeBytes(enumerated(components >= 3 ? SRGB : GRAYSCALE));
                    }
                }
                var at = (int) (child.offset() + child.headerLength());
                var method = child.size() - child.headerLength() >= 3 ? header[at] & 0xFF : 0;
                if (method == 2 || method == 3) {
                    continue;
                }
            }
            out.write(header, (int) child.offset(), (int) child.size());
        }
        if (!placed) {
            out.writeBytes(profile != null ? box("colr", concat(new byte[]{2, 0, 0}, profile))
                    : enumerated(components >= 3 ? SRGB : GRAYSCALE));
        }
        return box("jp2h", out.toByteArray());
    }

    /**
     * Builds an enumerated color specification box.
     *
     * @param space the enumerated color space
     * @return the box
     */
    private static byte[] enumerated(int space) {
        var content = new byte[7];
        content[0] = 1;
        ByteArrays.put32be(content, 3, space);
        return box("colr", content);
    }

    /**
     * Lists the boxes of a superbox's content.
     *
     * @param content the superbox's content
     * @return the boxes, their offsets relative to the content
     * @throws MediaException.Malformed if a box header is truncated or a box size invalid
     */
    private static List<Box> children(byte[] content) {
        var result = new ArrayList<Box>();
        var offset = 0;
        while (offset < content.length) {
            if (content.length - offset < 8) {
                throw new MediaException.Malformed("truncated JPEG 2000 box header in the JP2 header box");
            }
            var length = ByteArrays.u32be(content, offset);
            var type = new String(content, offset + 4, 4, StandardCharsets.ISO_8859_1);
            var headerLength = 8;
            long size;
            if (length == 1) {
                if (content.length - offset < 16) {
                    throw new MediaException.Malformed("truncated JPEG 2000 box header in the JP2 header box");
                }
                size = ByteArrays.i64be(content, offset + 8);
                headerLength = 16;
            } else if (length == 0) {
                size = content.length - offset;
            } else {
                size = length;
            }
            if (size < headerLength || size > content.length - offset) {
                throw new MediaException.Malformed("invalid JPEG 2000 box size " + size + " in the JP2 header box");
            }
            result.add(new Box(type, offset, size, headerLength, Kind.OTHER));
            offset += (int) size;
        }
        return result;
    }

    /**
     * Builds a {@code uuid} box.
     *
     * @param uuid the 16-byte identifier
     * @param payload the data after it
     * @return the box
     */
    private static byte[] uuid(byte[] uuid, byte[] payload) {
        return box("uuid", concat(uuid, payload));
    }

    /**
     * Concatenates two arrays.
     *
     * @param first the first array
     * @param second the second array
     * @return the concatenation
     */
    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Builds a box, with a 64-bit size when it does not fit a 32-bit one.
     *
     * @param type the four-character box type
     * @param content the box content after its header
     * @return the box
     */
    private static byte[] box(String type, byte[] content) {
        var large = content.length + 8L > 0xFFFF_FFFFL;
        var headerLength = large ? 16 : 8;
        var result = new byte[headerLength + content.length];
        ByteArrays.put32be(result, 0, large ? 1 : content.length + 8L);
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, result, 4, 4);
        if (large) {
            ByteArrays.put64be(result, 8, content.length + 16L);
        }
        System.arraycopy(content, 0, result, headerLength, content.length);
        return result;
    }

    /**
     * Classifies the metadata a box holds.
     */
    private enum Kind {
        /**
         * An EXIF {@code uuid} box.
         */
        EXIF,

        /**
         * An XMP {@code uuid} box, or an {@code xml } box holding an XMP packet.
         */
        XMP,

        /**
         * An IPTC {@code uuid} box.
         */
        IPTC,

        /**
         * Any other box.
         */
        OTHER
    }

    /**
     * Holds one box.
     *
     * @param type the box type
     * @param offset the offset of the box
     * @param size the size of the box, header included
     * @param headerLength the length of the box header
     * @param kind the metadata it holds
     */
    private record Box(String type, long offset, long size, int headerLength, Kind kind) {
        /**
         * Constructs a top-level box, whose header length does not matter.
         *
         * @param type the box type
         * @param offset the offset of the box
         * @param size the size of the box, header included
         * @param kind the metadata it holds
         */
        Box(String type, long offset, long size, Kind kind) {
            this(type, offset, size, 0, kind);
        }
    }
}
