package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the comment extensions and the XMP application extension of a GIF file.
 *
 * <p>The XMP application extension ({@code XMP DataXMP}) stores the packet raw after its
 * identifier, followed by a 258-byte "magic trailer" that makes any GIF reader walking the
 * sub-blocks land on the block terminator. A rewrite drops the blocks of every changed kind and
 * writes the new ones before the first graphic control extension or image; image data is copied
 * unchanged.
 */
final class GifFile implements ImageFile {
    /**
     * The application identifier and authentication code of the XMP extension.
     */
    private static final byte[] XMP_IDENTIFIER = "XMP DataXMP".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The largest number of blocks read.
     */
    private static final int MAX_BLOCKS = 1 << 20;

    /**
     * The size of the read buffer.
     */
    private static final int BUFFER = 1 << 16;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The offset of the first block, after the header, screen descriptor and color table.
     */
    private final long blocksStart;

    /**
     * The blocks, in order.
     */
    private final List<Block> blocks;

    /**
     * The offset where the blocks end, after the trailer.
     */
    private final long blocksEnd;

    /**
     * The read buffer.
     */
    private byte[] buffer;

    /**
     * The file offset of the buffer.
     */
    private long bufferStart = -1;

    /**
     * The number of valid bytes in the buffer.
     */
    private int bufferLength;

    /**
     * Parses the block structure.
     *
     * @param source the GIF file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the header is invalid, a block is unknown or the file
     *                                  ends inside a block
     */
    GifFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var header = ImageFiles.readAvailable(source, 0, 13);
        if (header.length < 13 || header[0] != 'G' || header[1] != 'I' || header[2] != 'F') {
            throw new MediaException.Malformed("GIF file without its header");
        }
        var packed = header[10] & 0xFF;
        var position = 13L + ((packed & 0x80) != 0 ? 3L * (1 << ((packed & 0x07) + 1)) : 0);
        this.blocksStart = position;
        var list = new ArrayList<Block>();
        while (true) {
            if (list.size() >= MAX_BLOCKS) {
                throw new MediaException.Malformed("more than " + MAX_BLOCKS + " GIF blocks");
            }
            var introducer = byteAt(position);
            if (introducer == 0x3B) {
                position++;
                break;
            }
            var start = position;
            if (introducer == 0x21) {
                var label = byteAt(position + 1);
                var identifier = label == 0xFF && byteAt(position + 2) == 11 ? bytes(position + 3, 11) : null;
                position = skipSubBlocks(position + 2);
                list.add(new Block(start, position, label, identifier));
            } else if (introducer == 0x2C) {
                var descriptor = bytes(position + 1, 9);
                var local = descriptor[8] & 0xFF;
                position += 10 + ((local & 0x80) != 0 ? 3L * (1 << ((local & 0x07) + 1)) : 0);
                position = skipSubBlocks(position + 1);
                list.add(new Block(start, position, 0x2C, null));
            } else {
                throw new MediaException.Malformed("unknown GIF block introducer " + introducer + " at offset " + position);
            }
        }
        this.blocks = List.copyOf(list);
        this.blocksEnd = position;
    }

    /**
     * Reads one byte.
     *
     * @param position the offset
     * @return the byte, from 0 to 255
     * @throws MediaException.Malformed if the file ends there
     */
    private int byteAt(long position) {
        if (position < bufferStart || position >= bufferStart + bufferLength) {
            if (buffer == null) {
                buffer = new byte[BUFFER];
            }
            bufferStart = position;
            bufferLength = (int) Math.max(0, Math.min(BUFFER, source.size() - position));
            if (bufferLength == 0) {
                throw new MediaException.Malformed("GIF file ends inside a block at offset " + position);
            }
            source.readFully(position, buffer, 0, bufferLength);
        }
        return buffer[(int) (position - bufferStart)] & 0xFF;
    }

    /**
     * Reads a range.
     *
     * @param position the offset
     * @param length   the number of bytes
     * @return the bytes
     */
    private byte[] bytes(long position, int length) {
        return ImageFiles.read(source, position, length, limits);
    }

    /**
     * Skips a sequence of sub-blocks.
     *
     * @param position the offset of the first sub-block's length byte
     * @return the offset after the block terminator
     */
    private long skipSubBlocks(long position) {
        while (true) {
            var length = byteAt(position);
            position += 1 + length;
            if (length == 0) {
                return position;
            }
        }
    }

    @Override
    public ImageMetadata metadata() {
        var comments = new ArrayList<String>();
        XmpPacket xmp = null;
        for (var block : blocks) {
            if (block.label() == 0xFE) {
                var data = subBlockData(block.start() + 2, block.end());
                comments.add(ImageFiles.text(data, 0, data.length));
            } else if (isXmp(block) && xmp == null) {
                var raw = bytes(block.start() + 14, (int) (block.end() - block.start() - 14));
                var text = new String(raw, StandardCharsets.UTF_8);
                var end = text.lastIndexOf("<?xpacket end");
                var close = end >= 0 ? text.indexOf("?>", end) : -1;
                try {
                    xmp = XmpPacket.parse(close >= 0 ? text.substring(0, close + 2) : text);
                } catch (MediaException ignored) {
                    // a packet that does not parse is left out
                }
            }
        }
        return new ImageMetadata(ImageFormat.GIF, null, xmp, null, null, comments, Map.of());
    }

    /**
     * Concatenates the data of the sub-blocks of a block.
     *
     * @param position the offset of the first sub-block's length byte
     * @param end      the end of the block
     * @return the data
     */
    private byte[] subBlockData(long position, long end) {
        var out = new ByteArrayOutputStream();
        while (position < end) {
            var length = byteAt(position);
            if (length == 0) {
                break;
            }
            out.writeBytes(bytes(position + 1, length));
            position += 1 + length;
        }
        return out.toByteArray();
    }

    /**
     * Returns whether a block is the XMP application extension.
     *
     * @param block the extension block to test
     * @return {@code true} for an application extension identified {@code XMP DataXMP}
     */
    private static boolean isXmp(Block block) {
        return block.label() == 0xFF && java.util.Arrays.equals(block.identifier(), XMP_IDENTIFIER);
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (target.exif() instanceof ImageMetadataEdit.Change.Replace) {
            throw ImageFiles.unsupported(ImageFormat.GIF, "EXIF");
        }
        if (target.icc() instanceof ImageMetadataEdit.Change.Replace) {
            throw ImageFiles.unsupported(ImageFormat.GIF, "ICC profiles");
        }
        if (target.iptc() instanceof ImageMetadataEdit.Change.Replace && target.iptcData() != null && !target.iptcData().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.GIF, "IPTC-IIM data");
        }
        if (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.GIF, "text");
        }
        var inserted = new ArrayList<byte[]>();
        if (target.xmp() instanceof ImageMetadataEdit.Change.Replace) {
            inserted.add(xmpExtension(target.xmpPacket()));
        }
        if (target.comments() instanceof ImageMetadataEdit.Change.Replace) {
            for (var comment : target.commentList()) {
                inserted.add(commentExtension(comment));
            }
        }
        var plan = EditPlan.builder().copy(0, blocksStart);
        var done = false;
        for (var block : blocks) {
            if (!done && (block.label() == 0x2C || block.label() == 0xF9)) {
                for (var bytes : inserted) {
                    plan.bytes(bytes);
                }
                done = true;
            }
            var drop = (block.label() == 0xFE && !(target.comments() instanceof ImageMetadataEdit.Change.Keep))
                    || (isXmp(block) && !(target.xmp() instanceof ImageMetadataEdit.Change.Keep))
                    || (target.removeOther() && block.label() == 0xFF && !isXmp(block) && !isLoop(block));
            if (!drop) {
                plan.copy(block.start(), block.end() - block.start());
            }
        }
        if (!done) {
            for (var bytes : inserted) {
                plan.bytes(bytes);
            }
        }
        plan.copy(blocksEnd - 1, source.size() - blocksEnd + 1);
        return plan.build();
    }

    /**
     * Returns whether a block is the looping application extension.
     *
     * @param block the extension block to test
     * @return {@code true} for {@code NETSCAPE2.0} and {@code ANIMEXTS1.0}
     */
    private static boolean isLoop(Block block) {
        if (block.identifier() == null) {
            return false;
        }
        var name = new String(block.identifier(), StandardCharsets.ISO_8859_1);
        return name.equals("NETSCAPE2.0") || name.equals("ANIMEXTS1.0");
    }

    /**
     * Builds the XMP application extension.
     *
     * @param packet the XMP packet to embed
     * @return the extension
     */
    private static byte[] xmpExtension(XmpPacket packet) {
        var out = new ByteArrayOutputStream();
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.writeBytes(XMP_IDENTIFIER);
        out.writeBytes(packet.toBytes());
        out.write(0x01);
        for (var value = 0xFF; value >= 0; value--) {
            out.write(value);
        }
        out.write(0);
        return out.toByteArray();
    }

    /**
     * Builds a comment extension.
     *
     * @param comment the comment, UTF-8 encoded
     * @return the extension
     */
    private static byte[] commentExtension(String comment) {
        var data = comment.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream(data.length + data.length / 255 + 4);
        out.write(0x21);
        out.write(0xFE);
        for (var offset = 0; offset < data.length; offset += 255) {
            var length = Math.min(255, data.length - offset);
            out.write(length);
            out.write(data, offset, length);
        }
        out.write(0);
        return out.toByteArray();
    }

    /**
     * Holds one block.
     *
     * @param start      the offset of the introducer
     * @param end        the offset after the block
     * @param label      the extension label, or {@code 0x2C} for an image
     * @param identifier the application identifier of an application extension, or {@code null}
     */
    private record Block(long start, long end, int label, byte[] identifier) {
    }
}
