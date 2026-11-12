package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.jpeg.internal.JpegExif;
import com.github.auties00.smalt.image.jpeg.internal.JpegIcc;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the metadata segments of a JPEG file.
 *
 * <p>The segments between the start-of-image marker and the first start-of-scan marker are
 * parsed; everything from the start-of-scan marker on (the entropy-coded data, the end-of-image
 * marker and whatever follows it, such as the secondary images of a Multi-Picture Format file) is
 * copied unchanged. A rewrite replaces the segments of each changed kind at the position of the
 * first of them, and inserts the segments of a new kind after the {@code APP0} segments that open
 * the file. The offsets of a kept {@code APP2 MPF} segment, relative to the segment itself, are
 * corrected when the bytes between it and the scan change size.
 */
final class JpegFile implements ImageFile {
    /**
     * The identifier this editor writes before the EXIF block of an {@code APP1} segment.
     */
    static final byte[] EXIF_ID = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifier of the XMP {@code APP1} segment.
     */
    static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifier of the Extended XMP {@code APP1} segments.
     */
    static final byte[] EXTENDED_XMP_ID = "http://ns.adobe.com/xmp/extension/\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifier of the Photoshop {@code APP13} segment.
     */
    static final byte[] PHOTOSHOP_ID = "Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The identifier of the Multi-Picture Format {@code APP2} segment.
     */
    static final byte[] MPF_ID = "MPF\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The largest payload of a segment, bounded by its 16-bit length field.
     */
    static final int MAX_PAYLOAD = 65533;

    /**
     * The largest number of segments read.
     */
    private static final int MAX_SEGMENTS = 1 << 16;

    /**
     * The kinds of segment the editor tells apart.
     */
    enum Kind {
        /**
         * {@code APP0 JFIF} or {@code JFXX}, which must open the file.
         */
        JFIF,

        /**
         * {@code APP1 Exif}.
         */
        EXIF,

        /**
         * The main XMP packet in {@code APP1}.
         */
        XMP,

        /**
         * A chunk of an Extended XMP packet in {@code APP1}.
         */
        XMP_EXTENDED,

        /**
         * A chunk of an ICC profile in {@code APP2}.
         */
        ICC,

        /**
         * The Multi-Picture Format index in {@code APP2}.
         */
        MPF,

        /**
         * Photoshop image resources in {@code APP13}, which carry IPTC-IIM data.
         */
        PHOTOSHOP,

        /**
         * {@code APP14 Adobe}, which tells how to convert the colors.
         */
        ADOBE,

        /**
         * Another application segment.
         */
        OTHER_APP,

        /**
         * A comment.
         */
        COMMENT,

        /**
         * A segment of the coding structure (tables, frame header, restart interval).
         */
        STRUCTURE
    }

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The segments before the first scan, in order.
     */
    private final List<Segment> segments;

    /**
     * The offset of the first start-of-scan marker, or of the end of the file.
     */
    private final long scanStart;

    /**
     * Parses the segments before the first scan.
     *
     * @param source the JPEG file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the file does not start with a start-of-image marker or a
     * segment is corrupt
     */
    JpegFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var head = ImageFiles.readAvailable(source, 0, 2);
        if (head.length < 2 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("JPEG file without a start-of-image marker");
        }
        var list = new ArrayList<Segment>();
        var position = 2L;
        var total = 0L;
        long scan = size;
        while (position < size) {
            if (list.size() >= MAX_SEGMENTS) {
                throw new MediaException.Malformed("more than " + MAX_SEGMENTS + " JPEG segments before the scan");
            }
            var start = position;
            var bytes = ImageFiles.readAvailable(source, position, 2);
            if (bytes.length < 2 || (bytes[0] & 0xFF) != 0xFF) {
                throw new MediaException.Malformed("expected a JPEG marker at offset " + position);
            }
            position++;
            var marker = bytes[1] & 0xFF;
            while (marker == 0xFF) {
                position++;
                var next = ImageFiles.readAvailable(source, position, 1);
                if (next.length == 0) {
                    throw new MediaException.Malformed("JPEG file ends inside a marker");
                }
                marker = next[0] & 0xFF;
            }
            position++;
            if (marker == 0xDA || marker == 0xD9) {
                scan = start;
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                list.add(new Segment(Kind.STRUCTURE, start, position - start, null));
                continue;
            }
            var lengthBytes = ImageFiles.readAvailable(source, position, 2);
            if (lengthBytes.length < 2) {
                throw new MediaException.Malformed("JPEG file ends inside a segment header");
            }
            var length = ByteArrays.u16be(lengthBytes, 0);
            if (length < 2 || position + length > size) {
                throw new MediaException.Malformed("JPEG segment of " + length + " bytes at offset " + start + " is invalid");
            }
            byte[] payload = null;
            if ((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE) {
                total += length;
                LimitChecks.checkAllocation(limits, total);
                payload = ImageFiles.read(source, position + 2, length - 2, limits);
            }
            position += length;
            list.add(new Segment(classify(marker, payload), start, position - start, payload));
        }
        this.segments = List.copyOf(list);
        this.scanStart = scan;
    }

    /**
     * Classifies a segment.
     *
     * @param marker the marker code
     * @param payload the payload of an application or comment segment, or {@code null}
     * @return the kind
     */
    private static Kind classify(int marker, byte[] payload) {
        return switch (marker) {
            case 0xE0 -> startsWith(payload, "JFIF\0") || startsWith(payload, "JFXX\0") ? Kind.JFIF : Kind.OTHER_APP;
            case 0xE1 -> payload != null && JpegExif.prefixLength(payload, 0, payload.length) != 0 ? Kind.EXIF
                    : startsWith(payload, XMP_ID) ? Kind.XMP
                    : startsWith(payload, EXTENDED_XMP_ID) ? Kind.XMP_EXTENDED : Kind.OTHER_APP;
            case 0xE2 -> payload != null && JpegIcc.matches(payload, 0, payload.length) ? Kind.ICC
                    : startsWith(payload, MPF_ID) ? Kind.MPF : Kind.OTHER_APP;
            case 0xED -> startsWith(payload, PHOTOSHOP_ID) ? Kind.PHOTOSHOP : Kind.OTHER_APP;
            case 0xEE -> startsWith(payload, "Adobe") ? Kind.ADOBE : Kind.OTHER_APP;
            case 0xFE -> Kind.COMMENT;
            default -> marker >= 0xE0 && marker <= 0xEF ? Kind.OTHER_APP : Kind.STRUCTURE;
        };
    }

    /**
     * Returns whether a payload starts with an identifier.
     *
     * @param payload the payload, or {@code null}
     * @param prefix the identifier
     * @return {@code true} on a match
     */
    private static boolean startsWith(byte[] payload, byte[] prefix) {
        return payload != null && payload.length >= prefix.length && Arrays.equals(payload, 0, prefix.length, prefix, 0,
                prefix.length);
    }

    /**
     * Returns whether a payload starts with an ISO-8859-1 identifier.
     *
     * @param payload the payload, or {@code null}
     * @param prefix the identifier
     * @return {@code true} on a match
     */
    private static boolean startsWith(byte[] payload, String prefix) {
        return startsWith(payload, prefix.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override
    public ImageMetadata metadata() {
        byte[] exif = null;
        byte[] xmpBytes = null;
        var extended = new ArrayList<byte[]>();
        var icc = new JpegIcc();
        var resources = new ArrayList<PhotoshopResources.Resource>();
        var comments = new ArrayList<String>();
        for (var segment : segments) {
            var payload = segment.payload();
            switch (segment.kind()) {
                case EXIF -> {
                    if (exif == null) {
                        exif = Arrays.copyOfRange(payload, JpegExif.PREFIX, payload.length);
                    }
                }
                case XMP -> {
                    if (xmpBytes == null) {
                        xmpBytes = Arrays.copyOfRange(payload, XMP_ID.length, payload.length);
                    }
                }
                case XMP_EXTENDED -> extended.add(payload);
                case ICC -> icc.add(payload, 0, payload.length);
                case PHOTOSHOP -> resources.addAll(PhotoshopResources.parse(payload, PHOTOSHOP_ID.length, payload.length));
                case COMMENT -> comments.add(ImageFiles.text(payload, 0, payload.length));
                default -> {
                }
            }
        }
        var xmp = xmpBytes != null ? ExtendedXmp.merge(xmpBytes, extended, limits) : null;
        var iptc = resources.isEmpty() ? null : PhotoshopResources.iptc(resources);
        return new ImageMetadata(ImageFormat.JPEG, exif, xmp, iptc, icc.profile(), comments, Map.of());
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the file ends before a scan or an end-of-image marker, so that it holds
     * no image an edited copy could keep
     */
    @Override
    public EditPlan plan(Resolved target) {
        if (scanStart >= source.size()) {
            throw new MediaException.Malformed("JPEG file ends before its image data");
        }
        var replacements = new EnumMap<Kind, List<byte[]>>(Kind.class);
        if (!(target.exif() instanceof ImageMetadataEdit.Change.Keep)) {
            replacements.put(Kind.EXIF, target.exifBytes() == null ? List.of()
                    : List.of(segment(0xE1, EXIF_ID, target.exifBytes(), 0, target.exifBytes().length)));
        }
        if (!(target.xmp() instanceof ImageMetadataEdit.Change.Keep)) {
            replacements.put(Kind.XMP, target.xmpPacket() == null ? List.of() : ExtendedXmp.segments(target.xmpPacket()));
        }
        if (!(target.icc() instanceof ImageMetadataEdit.Change.Keep)) {
            replacements.put(Kind.ICC, target.iccProfile() == null ? List.of() : JpegIcc.segments(target.iccProfile()));
        }
        if (!(target.iptc() instanceof ImageMetadataEdit.Change.Keep) || (target.removeOther() && hasKind(Kind.PHOTOSHOP))) {
            replacements.put(Kind.PHOTOSHOP, photoshopSegments(target));
        }
        if (!(target.comments() instanceof ImageMetadataEdit.Change.Keep)) {
            var list = new ArrayList<byte[]>();
            for (var comment : target.commentList()) {
                var bytes = comment.getBytes(StandardCharsets.UTF_8);
                list.add(segment(0xFE, new byte[0], bytes, 0, bytes.length));
            }
            replacements.put(Kind.COMMENT, list);
        }
        var pieces = new ArrayList<Piece>();
        pieces.add(new Copy(0, 2));
        var emitted = EnumSet.noneOf(Kind.class);
        var anchored = false;
        var mpfPiece = -1;
        for (var segment : segments) {
            if (!anchored && segment.kind() != Kind.JFIF) {
                emitNew(replacements, emitted, pieces);
                anchored = true;
            }
            var kind = segment.kind() == Kind.XMP_EXTENDED ? Kind.XMP : segment.kind();
            if (replacements.containsKey(kind)) {
                if (emitted.add(kind)) {
                    addAll(pieces, replacements.get(kind));
                }
                continue;
            }
            if (target.removeOther() && kind == Kind.OTHER_APP) {
                continue;
            }
            if (kind == Kind.MPF && mpfPiece < 0) {
                mpfPiece = pieces.size();
            }
            pieces.add(new Copy(segment.offset(), segment.length()));
        }
        if (!anchored) {
            emitNew(replacements, emitted, pieces);
        }
        if (mpfPiece >= 0) {
            patchMultiPicture(pieces, mpfPiece);
        }
        var plan = EditPlan.builder();
        for (var piece : pieces) {
            switch (piece) {
                case Bytes(var bytes) -> plan.bytes(bytes);
                case Copy(var offset, var length) -> plan.copy(offset, length);
            }
        }
        plan.copy(scanStart, source.size() - scanStart);
        return plan.build();
    }

    /**
     * Returns whether the file has a segment of a kind.
     *
     * @param kind the kind of segment to look for
     * @return {@code true} when present
     */
    private boolean hasKind(Kind kind) {
        for (var segment : segments) {
            if (segment.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits the replacement segments of the kinds the file lacks, in canonical order.
     *
     * @param replacements the replacement segments by kind
     * @param emitted the kinds already emitted, updated
     * @param pieces the output pieces
     */
    private void emitNew(Map<Kind, List<byte[]>> replacements, EnumSet<Kind> emitted, List<Piece> pieces) {
        for (var kind : new Kind[]{Kind.EXIF, Kind.XMP, Kind.ICC, Kind.PHOTOSHOP, Kind.COMMENT}) {
            if (replacements.containsKey(kind) && !hasKind(kind) && !(kind == Kind.XMP && hasKind(Kind.XMP_EXTENDED))
                    && emitted.add(kind)) {
                addAll(pieces, replacements.get(kind));
            }
        }
    }

    /**
     * Builds the Photoshop segments for the resolved IPTC data.
     *
     * @param target the resolved metadata
     * @return the segments, empty when nothing remains
     * @throws MediaException.LimitExceeded if the resources exceed one segment
     */
    private List<byte[]> photoshopSegments(Resolved target) {
        var resources = new ArrayList<PhotoshopResources.Resource>();
        for (var segment : segments) {
            if (segment.kind() == Kind.PHOTOSHOP) {
                resources.addAll(PhotoshopResources.parse(segment.payload(), PHOTOSHOP_ID.length, segment.payload().length));
            }
        }
        List<PhotoshopResources.Resource> updated = switch (target.iptc()) {
            case ImageMetadataEdit.Change.Keep<IptcData> _ -> resources;
            case ImageMetadataEdit.Change.Remove<IptcData> _ -> PhotoshopResources.withIptc(resources, null);
            case ImageMetadataEdit.Change.Replace<IptcData>(var iptc) -> PhotoshopResources.withIptc(resources, iptc);
        };
        if (target.removeOther()) {
            updated = target.iptc() instanceof ImageMetadataEdit.Change.Replace
                    ? PhotoshopResources.withIptc(List.of(), target.iptcData()) : List.of();
        }
        if (updated.isEmpty()) {
            return List.of();
        }
        var bytes = PhotoshopResources.encode(updated);
        if (bytes.length + PHOTOSHOP_ID.length > MAX_PAYLOAD) {
            throw new MediaException.LimitExceeded("Photoshop resources of " + bytes.length + " bytes exceed a JPEG segment");
        }
        return List.of(segment(0xED, PHOTOSHOP_ID, bytes, 0, bytes.length));
    }

    /**
     * Appends new segments to the output pieces.
     *
     * @param pieces the output pieces
     * @param segments the segments, marker included
     */
    private static void addAll(List<Piece> pieces, List<byte[]> segments) {
        for (var segment : segments) {
            pieces.add(new Bytes(segment));
        }
    }

    /**
     * Builds a segment.
     *
     * @param marker the marker code
     * @param prefix the identifier written before the data
     * @param data the bytes holding the data
     * @param offset the index of the data
     * @param length the length of the data
     * @return the segment, marker included
     * @throws MediaException.LimitExceeded if the payload exceeds a segment
     */
    static byte[] segment(int marker, byte[] prefix, byte[] data, int offset, int length) {
        var payload = prefix.length + length;
        if (payload > MAX_PAYLOAD) {
            throw new MediaException.LimitExceeded("JPEG segment payload of " + payload + " bytes exceeds " + MAX_PAYLOAD);
        }
        var segment = new byte[4 + payload];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) marker;
        segment[2] = (byte) ((payload + 2) >>> 8);
        segment[3] = (byte) (payload + 2);
        System.arraycopy(prefix, 0, segment, 4, prefix.length);
        System.arraycopy(data, offset, segment, 4 + prefix.length, length);
        return segment;
    }

    /**
     * Corrects the image offsets of a kept Multi-Picture Format segment by the change in size of
     * the bytes between it and the scan.
     *
     * @param pieces the output pieces, the MPF segment being a copied range
     * @param index the index of the MPF piece
     */
    private void patchMultiPicture(List<Piece> pieces, int index) {
        var range = (Copy) pieces.get(index);
        var after = 0L;
        for (var i = index + 1; i < pieces.size(); i++) {
            after += pieces.get(i).length();
        }
        var delta = after - (scanStart - range.offset() - range.length());
        if (delta == 0) {
            return;
        }
        var segment = ImageFiles.read(source, range.offset(), range.length(), limits);
        var markerEnd = 0;
        while (markerEnd < segment.length && (segment[markerEnd] & 0xFF) == 0xFF) {
            markerEnd++;
        }
        var header = markerEnd + 1 + 2 + MPF_ID.length;
        if (MultiPicture.shift(segment, header, delta)) {
            pieces.set(index, new Bytes(segment));
        }
    }

    /**
     * Holds one segment.
     *
     * @param kind what the segment holds
     * @param offset the offset of the segment, fill bytes included
     * @param length the length of the segment, marker and fill bytes included
     * @param payload the payload of an application or comment segment, or {@code null}
     */
    private record Segment(Kind kind, long offset, long length, byte[] payload) {
    }

    /**
     * One part of the rewritten file before the scan.
     */
    private sealed interface Piece permits Bytes, Copy {
        /**
         * {@return the number of bytes the piece adds to the output}
         */
        long length();
    }

    /**
     * New bytes, a segment written by the editor.
     *
     * @param data the bytes, marker included
     */
    private record Bytes(byte[] data) implements Piece {
        @Override
        public long length() {
            return data.length;
        }
    }

    /**
     * A range of the source copied unchanged.
     *
     * @param offset the offset of the first byte
     * @param length the number of bytes
     */
    private record Copy(long offset, long length) implements Piece {
    }
}
