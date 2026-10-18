package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.image.webp.internal.riff.FourCc;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.image.webp.internal.riff.WebpWriter;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and edits the chunk structure of WebP files without touching their pixels, for metadata
 * edits such as replacing the EXIF block.
 *
 * <p>Every method works on a complete file in memory and validates the RIFF header: the
 * {@code RIFF} tag, a size that the input holds (bytes after the declared end are ignored) and
 * the {@code WEBP} form type. Chunk sizes are checked against the declared end before any chunk
 * is read, so hostile sizes cannot cause out-of-bounds reads or large allocations. This class
 * is thread-safe.
 */
public final class WebpContainer {
    /**
     * Prevents instantiation.
     */
    private WebpContainer() {
        throw new AssertionError();
    }

    /**
     * Describes one top-level chunk.
     *
     * @param fourCc the four-character tag, for example {@code "VP8X"} or {@code "EXIF"}
     * @param offset the index in the file of the chunk header
     * @param size the payload size in bytes, without the padding byte
     */
    public record Chunk(String fourCc, int offset, int size) {
        /**
         * Validates the description.
         *
         * @throws IllegalArgumentException if a value is negative
         */
        public Chunk {
            Objects.requireNonNull(fourCc, "fourCc");
            if (offset < 0 || size < 0) {
                throw new IllegalArgumentException("invalid chunk at " + offset + " of " + size + " bytes");
            }
        }

        /**
         * Returns the index of the first payload byte.
         *
         * @return {@code offset + 8}
         */
        public int payloadOffset() {
            return offset + 8;
        }

        /**
         * Returns the index just past the chunk, including its padding byte.
         *
         * @return the end offset
         */
        public int end() {
            return offset + 8 + size + (size & 1);
        }
    }

    /**
     * Lists the top-level chunks of a WebP file.
     *
     * <p>The offsets locate any part of the file without decoding it: for example, the prefix
     * of an animated file that holds everything needed to show its first frame ends at the
     * {@link Chunk#end()} of its first {@code ANMF} chunk.
     *
     * @param file the complete WebP file
     * @return the chunks in file order
     * @throws MediaException.Malformed if the RIFF header is invalid, the file is truncated, or a
     * chunk overruns the declared end
     */
    public static List<Chunk> parse(byte[] file) {
        Objects.requireNonNull(file, "file");
        var end = riffEnd(file);
        var chunks = new ArrayList<Chunk>();
        var pos = 12;
        while (pos < end) {
            if (end - pos < 8) {
                throw new MediaException.Malformed("truncated chunk header at offset " + pos);
            }
            var size = ByteArrays.u32le(file, pos + 4);
            if (size > end - pos - 8) {
                throw new MediaException.Malformed(tag(file, pos) + " chunk of " + size + " bytes overruns the RIFF payload");
            }
            chunks.add(new Chunk(tag(file, pos), pos, (int) size));
            var next = pos + 8 + size + (size & 1);
            if (next > end) {
                if (next == end + 1) {
                    break;
                }
                throw new MediaException.Malformed("chunk padding overruns the RIFF payload");
            }
            pos = (int) next;
        }
        return chunks;
    }

    /**
     * Returns the payload of the first {@code EXIF} chunk of a WebP file.
     *
     * <p>The payload is returned as stored: a TIFF structure, possibly preceded by the
     * {@code Exif\0\0} prefix some writers add.
     *
     * @param file the WebP file
     * @return a copy of the EXIF payload, or empty when the file has no {@code EXIF} chunk
     * @throws MediaException.Malformed if the chunk structure is invalid
     */
    public static Optional<byte[]> exif(byte[] file) {
        return Optional.ofNullable(payload(file, "EXIF"));
    }

    /**
     * Returns whether a file is an animated WebP: an extended file whose {@code VP8X} chunk,
     * right after the RIFF header, has the animation flag set.
     *
     * @param file the complete WebP file
     * @return whether the animation flag is set; {@code false} for anything that is not an
     * extended WebP file
     */
    public static boolean isAnimated(byte[] file) {
        Objects.requireNonNull(file, "file");
        return file.length >= 30 && isTag(file, 0, "RIFF") && isTag(file, 8, "WEBP") && isTag(file, 12, "VP8X")
                && (file[20] & WebpParser.FLAG_ANIMATION) != 0;
    }

    /**
     * Returns a copy of a file with its EXIF metadata replaced.
     *
     * <p>A simple file (a lone {@code VP8 } or {@code VP8L} chunk) is converted to the extended
     * format: a {@code VP8X} chunk is added with the canvas size read from the bitstream and the
     * transparency flag set for a lossless image whose header declares alpha. Every existing
     * {@code EXIF} chunk is removed, the new one is placed after the image data (or the
     * animation frames) and before any {@code XMP } chunk, as the specification orders them, and
     * the {@code VP8X} EXIF flag is set. The other chunks keep their relative order, with unknown
     * chunks moved to the end. {@link #removeExif(byte[])} removes the EXIF metadata.
     *
     * @param file the WebP file
     * @param exifPayload the new EXIF payload
     * @return the new file
     * @throws MediaException.Malformed if the file structure or its bitstream header is invalid, or
     * the file holds no image
     * @throws MediaException.LimitExceeded if the result would exceed the RIFF size limit
     */
    public static byte[] withExif(byte[] file, byte[] exifPayload) {
        Objects.requireNonNull(exifPayload, "exifPayload");
        return rebuild(file, true, exifPayload, false, null, false, null);
    }

    /**
     * Returns a copy of a file with its ICC profile replaced.
     *
     * <p>The file is converted to the extended format as {@link #withExif(byte[], byte[])}
     * describes. Every existing {@code ICCP} chunk is removed, the new one is placed right after
     * the {@code VP8X} chunk, as the specification requires, and the {@code VP8X} ICC flag is set.
     * The other chunks keep their relative order, with unknown chunks moved to the end.
     * {@link #removeIccProfile(byte[])} removes the profile.
     *
     * @param file the WebP file
     * @param profile the new ICC profile
     * @return the new file
     * @throws MediaException.Malformed if the file structure or its bitstream header is invalid, or
     * the file holds no image
     * @throws MediaException.LimitExceeded if the result would exceed the RIFF size limit
     */
    public static byte[] withIccProfile(byte[] file, byte[] profile) {
        Objects.requireNonNull(profile, "profile");
        return rebuild(file, false, null, true, profile, false, null);
    }

    /**
     * Returns the payload of the first {@code XMP } chunk of a WebP file.
     *
     * @param file the WebP file
     * @return a copy of the XMP packet bytes, or empty when the file has no {@code XMP } chunk
     * @throws MediaException.Malformed if the chunk structure is invalid
     */
    public static Optional<byte[]> xmp(byte[] file) {
        return Optional.ofNullable(payload(file, "XMP "));
    }

    /**
     * Returns the payload of the first {@code ICCP} chunk of a WebP file.
     *
     * @param file the WebP file
     * @return a copy of the ICC profile, or empty when the file has no {@code ICCP} chunk
     * @throws MediaException.Malformed if the chunk structure is invalid
     */
    public static Optional<byte[]> iccProfile(byte[] file) {
        return Optional.ofNullable(payload(file, "ICCP"));
    }

    /**
     * Returns a copy of a WebP file whose XMP packet is replaced or removed.
     *
     * <p>Behaves like {@link #withExif(byte[], byte[])} for the {@code XMP } chunk: a simple file
     * is converted to the extended format, the chunk is placed last, after any {@code EXIF} chunk,
     * and the {@code VP8X} XMP flag is set. Image data is copied unchanged.
     * {@link #removeXmp(byte[])} removes the packet.
     *
     * @param file the WebP file
     * @param xmp the serialized XMP packet
     * @return the rebuilt file
     * @throws MediaException.Malformed if the RIFF structure is invalid or holds no image data
     */
    public static byte[] withXmp(byte[] file, byte[] xmp) {
        Objects.requireNonNull(xmp, "xmp");
        return rebuild(file, false, null, false, null, true, xmp);
    }

    /**
     * Returns a copy of a WebP file without EXIF metadata.
     *
     * <p>The file is converted to the extended format and its chunks ordered as
     * {@link #withExif(byte[], byte[])} describes; every {@code EXIF} chunk is removed and the
     * {@code VP8X} EXIF flag cleared. Image data is copied unchanged.
     *
     * @param file the WebP file
     * @return the new file
     * @throws MediaException.Malformed if the file structure or its bitstream header is
     * invalid, or the file holds no image
     * @throws MediaException.LimitExceeded if the result would exceed the RIFF size limit
     */
    public static byte[] removeExif(byte[] file) {
        return rebuild(file, true, null, false, null, false, null);
    }

    /**
     * Returns a copy of a WebP file without an XMP packet.
     *
     * <p>The file is converted to the extended format and its chunks ordered as
     * {@link #withExif(byte[], byte[])} describes; every {@code XMP } chunk is removed and the
     * {@code VP8X} XMP flag cleared. Image data is copied unchanged.
     *
     * @param file the WebP file
     * @return the new file
     * @throws MediaException.Malformed if the file structure or its bitstream header is
     * invalid, or the file holds no image
     * @throws MediaException.LimitExceeded if the result would exceed the RIFF size limit
     */
    public static byte[] removeXmp(byte[] file) {
        return rebuild(file, false, null, false, null, true, null);
    }

    /**
     * Returns a copy of a WebP file without an ICC profile.
     *
     * <p>The file is converted to the extended format and its chunks ordered as
     * {@link #withExif(byte[], byte[])} describes; every {@code ICCP} chunk is removed and the
     * {@code VP8X} ICC flag cleared. Image data is copied unchanged.
     *
     * @param file the WebP file
     * @return the new file
     * @throws MediaException.Malformed if the file structure or its bitstream header is
     * invalid, or the file holds no image
     * @throws MediaException.LimitExceeded if the result would exceed the RIFF size limit
     */
    public static byte[] removeIccProfile(byte[] file) {
        return rebuild(file, false, null, true, null, false, null);
    }

    /**
     * Returns a copy of the payload of the first chunk of a type.
     *
     * @param file the WebP file
     * @param fourCc the chunk type
     * @return the payload, or {@code null} when absent
     */
    private static byte[] payload(byte[] file, String fourCc) {
        for (var chunk : parse(file)) {
            if (chunk.fourCc().equals(fourCc)) {
                return Arrays.copyOfRange(file, chunk.payloadOffset(), chunk.payloadOffset() + chunk.size());
            }
        }
        return null;
    }

    /**
     * Rebuilds a file in the extended format, replacing any of its EXIF, ICC profile and XMP
     * chunks.
     *
     * @param file the WebP file
     * @param replaceExif whether the EXIF chunk is replaced
     * @param exifPayload the new EXIF payload, or {@code null} for none
     * @param replaceIcc whether the ICC profile chunk is replaced
     * @param icc the new ICC profile, or {@code null} for none
     * @param replaceXmp whether the XMP chunk is replaced
     * @param xmpPayload the new XMP packet, or {@code null} for none
     * @return the new file
     */
    private static byte[] rebuild(byte[] file, boolean replaceExif, byte[] exifPayload, boolean replaceIcc, byte[] icc,
                                  boolean replaceXmp, byte[] xmpPayload) {
        var chunks = parse(file);
        if (chunks.isEmpty()) {
            throw new MediaException.Malformed("WebP file holds no chunk");
        }
        var first = chunks.getFirst();
        int flags;
        int width;
        int height;
        List<Chunk> rest;
        if (first.fourCc().equals("VP8X")) {
            if (first.size() < WebpParser.VP8X_SIZE) {
                throw new MediaException.Malformed("VP8X chunk of " + first.size() + " bytes is too short");
            }
            var p = first.payloadOffset();
            flags = (int) ByteArrays.u32le(file, p);
            width = 1 + ByteArrays.u24le(file, p + 4);
            height = 1 + ByteArrays.u24le(file, p + 7);
            rest = chunks.subList(1, chunks.size());
        } else if (first.fourCc().equals("VP8 ") || first.fourCc().equals("VP8L")) {
            var p = first.payloadOffset();
            if (first.fourCc().equals("VP8L")) {
                var header = Vp8lDecoder.readHeader(file, p, first.size());
                width = header[0];
                height = header[1];
                flags = header[2] != 0 ? WebpParser.FLAG_ALPHA : 0;
            } else {
                if (first.size() < 10) {
                    throw new MediaException.Malformed("VP8 chunk of " + first.size() + " bytes is shorter than its header");
                }
                var dims = WebpParser.vp8Dimensions(Arrays.copyOfRange(file, p, p + 10), first.size());
                width = dims[0];
                height = dims[1];
                flags = 0;
            }
            rest = chunks;
        } else {
            throw new MediaException.Malformed("WebP file starts with an unexpected " + first.fourCc() + " chunk");
        }
        var iccp = new ArrayList<byte[]>();
        var anim = new ArrayList<byte[]>();
        var image = new ArrayList<byte[]>();
        var exif = new ArrayList<byte[]>();
        var xmp = new ArrayList<byte[]>();
        var unknown = new ArrayList<byte[]>();
        for (var chunk : rest) {
            var bytes = Arrays.copyOfRange(file, chunk.offset(), Math.min(chunk.end(), file.length));
            if (bytes.length != 8 + chunk.size() + (chunk.size() & 1)) {
                bytes = Arrays.copyOf(bytes, 8 + chunk.size() + (chunk.size() & 1));
            }
            switch (chunk.fourCc()) {
                case "VP8X" -> throw new MediaException.Malformed("second VP8X chunk");
                case "ICCP" -> {
                    if (!replaceIcc) {
                        iccp.add(bytes);
                    }
                }
                case "ANIM" -> anim.add(bytes);
                case "ALPH", "VP8 ", "VP8L", "ANMF" -> image.add(bytes);
                case "EXIF" -> {
                    if (!replaceExif) {
                        exif.add(bytes);
                    }
                }
                case "XMP " -> {
                    if (!replaceXmp) {
                        xmp.add(bytes);
                    }
                }
                default -> unknown.add(bytes);
            }
        }
        if (image.isEmpty()) {
            throw new MediaException.Malformed("WebP file holds no image data");
        }
        if (replaceExif) {
            if (exifPayload != null) {
                flags |= WebpParser.FLAG_EXIF;
                exif.add(WebpWriter.chunk(FourCc.EXIF, exifPayload));
            } else {
                flags &= ~WebpParser.FLAG_EXIF;
            }
        }
        if (replaceIcc) {
            if (icc != null) {
                flags |= WebpParser.FLAG_ICCP;
                iccp.add(WebpWriter.chunk(FourCc.ICCP, icc));
            } else {
                flags &= ~WebpParser.FLAG_ICCP;
            }
        }
        if (replaceXmp) {
            if (xmpPayload != null) {
                flags |= WebpParser.FLAG_XMP;
                xmp.add(WebpWriter.chunk(FourCc.XMP, xmpPayload));
            } else {
                flags &= ~WebpParser.FLAG_XMP;
            }
        }
        var out = new ArrayList<byte[]>();
        out.add(WebpWriter.vp8x(flags, width, height));
        out.addAll(iccp);
        out.addAll(anim);
        out.addAll(image);
        out.addAll(exif);
        out.addAll(xmp);
        out.addAll(unknown);
        return WebpWriter.riff(out);
    }

    /**
     * Validates the RIFF header and returns the end of the RIFF payload.
     *
     * @param file the complete WebP file
     * @return the offset one past the declared RIFF payload
     */
    private static int riffEnd(byte[] file) {
        if (file.length < 12) {
            throw new MediaException.Malformed("WebP file of " + file.length + " bytes is shorter than its RIFF header");
        }
        if (!isTag(file, 0, "RIFF") || !isTag(file, 8, "WEBP")) {
            throw new MediaException.Malformed("not a RIFF WebP file");
        }
        var riffSize = ByteArrays.u32le(file, 4);
        if (riffSize < 4) {
            throw new MediaException.Malformed("RIFF size " + riffSize + " is too small");
        }
        if (riffSize > file.length - 8L) {
            throw new MediaException.Malformed("WebP file truncated: RIFF declares " + (riffSize + 8)
                    + " bytes, the input holds " + file.length);
        }
        return (int) (riffSize + 8);
    }

    /**
     * Returns whether the bytes at an offset spell a tag.
     *
     * @param file the array to read
     * @param offset the index of the first byte of the tag
     * @param tag the four-character tag
     * @return whether they match
     */
    private static boolean isTag(byte[] file, int offset, String tag) {
        for (var i = 0; i < 4; i++) {
            if (file[offset + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the tag at an offset as text.
     *
     * @param file the array to read
     * @param offset the index of the first byte of the tag
     * @return the four characters
     */
    private static String tag(byte[] file, int offset) {
        return new String(file, offset, 4, StandardCharsets.ISO_8859_1);
    }
}
