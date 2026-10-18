package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.internal.zlib.Zlib;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Reads and rewrites the metadata chunks of a PNG or APNG file.
 *
 * <p>The metadata lives in {@code eXIf}, {@code iTXt} (the XMP packet under the keyword
 * {@code XML:com.adobe.xmp}), {@code iCCP}, and the {@code tEXt}, {@code zTXt} and {@code iTXt}
 * text chunks. A rewrite drops the chunks of every changed kind, writes a new {@code iCCP} right
 * after {@code IHDR} (where it must precede {@code PLTE}, dropping a {@code sRGB} chunk it
 * contradicts), and writes the other new chunks just before the first {@code fcTL} or
 * {@code IDAT}; every other chunk, the image data included, is copied unchanged.
 */
final class PngFile implements ImageFile {
    /**
     * The PNG signature.
     */
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * The keyword of the XMP packet.
     */
    static final String XMP_KEYWORD = "XML:com.adobe.xmp";

    /**
     * The chunk types whose data is read.
     */
    private static final Set<String> READ = Set.of("IHDR", "eXIf", "iCCP", "iTXt", "tEXt", "zTXt");

    /**
     * The largest number of chunks read.
     */
    private static final int MAX_CHUNKS = 1 << 20;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The chunks, in order.
     */
    private final List<Chunk> chunks;

    /**
     * The offset where the chunks end.
     */
    private final long chunksEnd;

    /**
     * Parses the chunk list.
     *
     * @param source the PNG file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the signature is missing or a chunk runs past the file
     */
    PngFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var signature = ImageFiles.readAvailable(source, 0, 8);
        if (!Arrays.equals(signature, SIGNATURE)) {
            throw new MediaException.Malformed("PNG file without its signature");
        }
        var list = new ArrayList<Chunk>();
        var position = 8L;
        var total = 0L;
        while (size - position >= 12) {
            if (list.size() >= MAX_CHUNKS) {
                throw new MediaException.Malformed("more than " + MAX_CHUNKS + " PNG chunks");
            }
            var header = ImageFiles.read(source, position, 8, limits);
            var length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16) | ((header[2] & 0xFFL) << 8)
                    | (header[3] & 0xFFL);
            var type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            if (length > 0x7FFFFFFFL || length > size - position - 12) {
                throw new MediaException.Malformed("PNG " + type + " chunk of " + length + " bytes runs past the file");
            }
            byte[] data = null;
            if (READ.contains(type)) {
                total += length;
                LimitChecks.checkAllocation(limits, total);
                data = ImageFiles.read(source, position + 8, length, limits);
            }
            list.add(new Chunk(type, position, length, data));
            position += 12 + length;
            if (type.equals("IEND")) {
                break;
            }
        }
        if (list.isEmpty() || !list.getFirst().type().equals("IHDR")) {
            throw new MediaException.Malformed("PNG file does not start with an IHDR chunk");
        }
        this.chunks = List.copyOf(list);
        this.chunksEnd = position;
    }

    @Override
    public ImageMetadata metadata() {
        byte[] exif = null;
        XmpPacket xmp = null;
        byte[] icc = null;
        var comments = new ArrayList<String>();
        var text = new LinkedHashMap<String, String>();
        for (var chunk : chunks) {
            var data = chunk.data();
            switch (chunk.type()) {
                case "eXIf" -> {
                    if (exif == null) {
                        exif = data.length >= 6 && data[0] == 'E' && data[1] == 'x' && data[2] == 'i' && data[3] == 'f'
                                && data[4] == 0 && data[5] == 0 ? Arrays.copyOfRange(data, 6, data.length) : data;
                    }
                }
                case "iCCP" -> {
                    if (icc == null) {
                        var name = terminator(data, 0);
                        if (name >= 0 && name + 2 <= data.length) {
                            icc = inflate(data, name + 2, data.length - name - 2, limits);
                        }
                    }
                }
                case "tEXt", "zTXt", "iTXt" -> {
                    var entry = decodeText(chunk.type(), data);
                    if (entry == null) {
                        continue;
                    }
                    if (entry[0].equals(XMP_KEYWORD)) {
                        if (xmp == null) {
                            try {
                                xmp = XmpPacket.parse(entry[1]);
                            } catch (MediaException ignored) {
                                // a packet that does not parse is left out
                            }
                        }
                    } else if (entry[0].equals(ImageMetadata.COMMENT_KEYWORD)) {
                        comments.add(entry[1]);
                    } else {
                        text.putIfAbsent(entry[0], entry[1]);
                    }
                }
                default -> {
                }
            }
        }
        return new ImageMetadata(ImageFormat.PNG, exif, xmp, null, icc, comments, text);
    }

    /**
     * Decodes a text chunk.
     *
     * @param type the chunk type
     * @param data the chunk data
     * @return the keyword and the text, or {@code null} when the chunk is malformed
     */
    private String[] decodeText(String type, byte[] data) {
        var keywordEnd = terminator(data, 0);
        if (keywordEnd <= 0) {
            return null;
        }
        var keyword = new String(data, 0, keywordEnd, StandardCharsets.ISO_8859_1);
        var position = keywordEnd + 1;
        switch (type) {
            case "tEXt" -> {
                return new String[]{keyword, new String(data, position, data.length - position, StandardCharsets.ISO_8859_1)};
            }
            case "zTXt" -> {
                if (position >= data.length) {
                    return null;
                }
                var inflated = inflate(data, position + 1, data.length - position - 1, limits);
                return inflated == null ? null : new String[]{keyword, new String(inflated, StandardCharsets.ISO_8859_1)};
            }
            default -> {
                if (position + 2 > data.length) {
                    return null;
                }
                var compressed = data[position] != 0;
                position += 2;
                var languageEnd = terminator(data, position);
                if (languageEnd < 0) {
                    return null;
                }
                var translatedEnd = terminator(data, languageEnd + 1);
                if (translatedEnd < 0) {
                    return null;
                }
                position = translatedEnd + 1;
                var bytes = compressed ? inflate(data, position, data.length - position, limits)
                        : Arrays.copyOfRange(data, position, data.length);
                return bytes == null ? null : new String[]{keyword, new String(bytes, StandardCharsets.UTF_8)};
            }
        }
    }

    /**
     * Returns the index of the next NUL byte.
     *
     * @param data  the bytes
     * @param start the index to search from
     * @return the index, or {@code -1} when there is none
     */
    private static int terminator(byte[] data, int start) {
        for (var i = start; i < data.length; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Decompresses zlib data under the allocation limit.
     *
     * @param data   the bytes
     * @param offset the index of the compressed data
     * @param length the length of the compressed data
     * @param limits the resource bounds
     * @return the decompressed bytes, or {@code null} when they are corrupt
     * @throws MediaException.LimitExceeded if the output exceeds the allocation limit
     */
    static byte[] inflate(byte[] data, int offset, int length, MediaLimits limits) {
        if (length < 0) {
            return null;
        }
        var inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            var out = new ByteArrayOutputStream(Math.max(64, length * 2));
            var buffer = new byte[8192];
            while (!inflater.finished()) {
                var count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                out.write(buffer, 0, count);
                LimitChecks.checkAllocation(limits, out.size());
            }
            return out.toByteArray();
        } catch (DataFormatException exception) {
            return null;
        } finally {
            inflater.end();
        }
    }

    @Override
    public EditPlan plan(Resolved target) {
        var iccChunk = target.icc() instanceof ImageMetadataEdit.Change.Replace ? iccChunk(target.iccProfile()) : null;
        var early = new ArrayList<byte[]>();
        if (target.exif() instanceof ImageMetadataEdit.Change.Replace) {
            early.add(chunk("eXIf", target.exifBytes()));
        }
        if (target.xmp() instanceof ImageMetadataEdit.Change.Replace) {
            early.add(textChunk(XMP_KEYWORD, target.xmpPacket().toXml(), true));
        }
        if (target.text() instanceof ImageMetadataEdit.Change.Replace) {
            for (var entry : target.textEntries().entrySet()) {
                early.add(textChunk(entry.getKey(), entry.getValue(), false));
            }
        }
        if (target.comments() instanceof ImageMetadataEdit.Change.Replace) {
            for (var comment : target.commentList()) {
                early.add(textChunk(ImageMetadata.COMMENT_KEYWORD, comment, false));
            }
        }
        if (target.iptc() instanceof ImageMetadataEdit.Change.Replace && target.iptcData() != null && !target.iptcData().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.PNG, "IPTC-IIM data");
        }
        var plan = EditPlan.builder();
        plan.copy(0, 8);
        var earlyDone = false;
        for (var chunk : chunks) {
            var type = chunk.type();
            if (!earlyDone && (type.equals("IDAT") || type.equals("fcTL") || type.equals("IEND"))) {
                for (var bytes : early) {
                    plan.bytes(bytes);
                }
                earlyDone = true;
            }
            if (dropped(chunk, target)) {
                continue;
            }
            plan.copy(chunk.offset(), 12 + chunk.length());
            if (type.equals("IHDR") && iccChunk != null) {
                plan.bytes(iccChunk);
            }
        }
        if (!earlyDone) {
            for (var bytes : early) {
                plan.bytes(bytes);
            }
        }
        plan.copy(chunksEnd, source.size() - chunksEnd);
        return plan.build();
    }

    /**
     * Returns whether a chunk is removed by the rewrite.
     *
     * @param chunk  the chunk of the source file
     * @param target the resolved metadata
     * @return {@code true} when the chunk's kind changes
     */
    private boolean dropped(Chunk chunk, Resolved target) {
        return switch (chunk.type()) {
            case "eXIf" -> !(target.exif() instanceof ImageMetadataEdit.Change.Keep);
            case "iCCP" -> !(target.icc() instanceof ImageMetadataEdit.Change.Keep);
            case "sRGB" -> target.icc() instanceof ImageMetadataEdit.Change.Replace;
            case "tIME" -> target.removeOther();
            case "tEXt", "zTXt", "iTXt" -> {
                var keywordEnd = terminator(chunk.data(), 0);
                var keyword = keywordEnd > 0 ? new String(chunk.data(), 0, keywordEnd, StandardCharsets.ISO_8859_1) : "";
                if (keyword.equals(XMP_KEYWORD)) {
                    yield !(target.xmp() instanceof ImageMetadataEdit.Change.Keep);
                }
                if (keyword.equals(ImageMetadata.COMMENT_KEYWORD)) {
                    yield !(target.comments() instanceof ImageMetadataEdit.Change.Keep);
                }
                yield !(target.text() instanceof ImageMetadataEdit.Change.Keep);
            }
            default -> false;
        };
    }

    /**
     * Builds an {@code iCCP} chunk.
     *
     * @param profile the ICC profile bytes
     * @return the chunk
     */
    private static byte[] iccChunk(byte[] profile) {
        var name = "ICC Profile".getBytes(StandardCharsets.ISO_8859_1);
        var compressed = Zlib.deflate(profile, Deflater.BEST_COMPRESSION);
        var data = new byte[name.length + 2 + compressed.length];
        System.arraycopy(name, 0, data, 0, name.length);
        System.arraycopy(compressed, 0, data, name.length + 2, compressed.length);
        return chunk("iCCP", data);
    }

    /**
     * Builds a text chunk: {@code tEXt} for ISO-8859-1 text, {@code iTXt} otherwise.
     *
     * @param keyword the keyword, cut to 79 ISO-8859-1 characters
     * @param text    the text to store
     * @param forceInternational whether to write {@code iTXt} whatever the text
     * @return the chunk
     */
    private static byte[] textChunk(String keyword, String text, boolean forceInternational) {
        var name = keyword.getBytes(StandardCharsets.ISO_8859_1);
        if (name.length == 0) {
            name = new byte[]{'?'};
        }
        if (name.length > 79) {
            name = Arrays.copyOf(name, 79);
        }
        var latin1 = !forceInternational && StandardCharsets.ISO_8859_1.newEncoder().canEncode(text);
        var out = new ByteArrayOutputStream(name.length + text.length() + 8);
        out.writeBytes(name);
        out.write(0);
        if (latin1) {
            out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
            return chunk("tEXt", out.toByteArray());
        }
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        return chunk("iTXt", out.toByteArray());
    }

    /**
     * Builds a chunk with its length and CRC.
     *
     * @param type the chunk type
     * @param data the chunk payload
     * @return the chunk
     */
    private static byte[] chunk(String type, byte[] data) {
        var chunk = new byte[12 + data.length];
        chunk[0] = (byte) (data.length >>> 24);
        chunk[1] = (byte) (data.length >>> 16);
        chunk[2] = (byte) (data.length >>> 8);
        chunk[3] = (byte) data.length;
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, data.length);
        var crc = new CRC32();
        crc.update(chunk, 4, 4 + data.length);
        var value = crc.getValue();
        chunk[8 + data.length] = (byte) (value >>> 24);
        chunk[9 + data.length] = (byte) (value >>> 16);
        chunk[10 + data.length] = (byte) (value >>> 8);
        chunk[11 + data.length] = (byte) value;
        return chunk;
    }

    /**
     * Holds one chunk.
     *
     * @param type   the four-letter chunk type
     * @param offset the offset of the chunk length field
     * @param length the data length
     * @param data   the data of a metadata chunk, or {@code null}
     */
    private record Chunk(String type, long offset, long length, byte[] data) {
    }
}
