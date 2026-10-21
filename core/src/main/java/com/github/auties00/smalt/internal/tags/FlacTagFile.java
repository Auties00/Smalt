package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the metadata blocks of a native FLAC file.
 *
 * <p>The file is an optional ID3v2 prefix, the {@code fLaC} marker, metadata blocks (each a
 * four-byte header: a last-block flag, a seven-bit type and a 24-bit length) and the audio
 * frames. The rewrite keeps {@code STREAMINFO} and {@code SEEKTABLE} (whose offsets are relative
 * to the first frame and so survive any metadata change), keeps {@code APPLICATION},
 * {@code CUESHEET} and unknown blocks when unknown items are preserved, and replaces
 * {@code VORBIS_COMMENT}, {@code PICTURE} and {@code PADDING}. When the new blocks fit the space
 * of the old ones, a padding block fills the rest and the frames do not move.
 *
 * <p>Tags after the frames are read after the metadata blocks: an APE tag and then an ID3v1 tag
 * add the keys the Vorbis comments lack, and the APE cover art counts when there is no
 * {@code PICTURE} block. They follow their {@link TagWriteOptions} modes on a rewrite; Lyrics3v2
 * blocks and ID3v2 tags, leading or appended, are kept unchanged and not read, a Lyrics3v1 block
 * is kept while an ID3v1 tag follows it, and an enhanced {@code TAG+} block is dropped.
 */
final class FlacTagFile implements TagFile {
    /**
     * The {@code STREAMINFO} block type.
     */
    private static final int STREAMINFO = 0;

    /**
     * The {@code PADDING} block type.
     */
    private static final int PADDING = 1;

    /**
     * The {@code SEEKTABLE} block type.
     */
    private static final int SEEKTABLE = 3;

    /**
     * The {@code VORBIS_COMMENT} block type.
     */
    private static final int VORBIS_COMMENT = 4;

    /**
     * The {@code PICTURE} block type.
     */
    private static final int PICTURE = 6;

    /**
     * The largest block body, bounded by its 24-bit length field.
     */
    private static final int MAX_BLOCK = (1 << 24) - 1;

    /**
     * The largest number of metadata blocks read.
     */
    private static final int MAX_BLOCKS = 4096;

    /**
     * The offset of the {@code fLaC} marker.
     */
    private final long markerOffset;

    /**
     * The offset of the first audio frame.
     */
    private final long audioStart;

    /**
     * The metadata blocks in file order.
     */
    private final List<Block> blocks;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The offset where the frames end and the trailing tags start.
     */
    private final long audioEnd;

    /**
     * The trailing tags, in file order.
     */
    private final List<TrailingTags.Region> trailing;

    /**
     * The APE tag, or {@code null}.
     */
    private final ApeTag ape;

    /**
     * The ID3v1 tag, or {@code null}.
     */
    private final byte[] id3v1;

    /**
     * Parses the metadata blocks.
     *
     * @param source the FLAC file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the marker or the first block is missing, or a block runs past the end of the
     * file
     */
    FlacTagFile(MediaSource source, MediaLimits limits) {
        var size = source.size();
        this.source = source;
        this.limits = limits;
        var position = 0L;
        for (var count = 0; count < 64 && size - position >= Id3v2Tag.HEADER_SIZE; count++) {
            var header = TagIo.read(source, position, Id3v2Tag.HEADER_SIZE, limits);
            var tagSize = Id3v2Tag.size(header, 0);
            if (tagSize < 0) {
                break;
            }
            position += tagSize;
        }
        if (position + 8 > size || !TagIo.matches(TagIo.read(source, position, 4, limits), 0, "fLaC")) {
            throw new MediaException.Malformed("FLAC marker missing at offset " + position);
        }
        this.markerOffset = position;
        position += 4;
        var parsed = new ArrayList<Block>();
        var last = false;
        while (!last) {
            if (parsed.size() >= MAX_BLOCKS) {
                throw new MediaException.Malformed("more than " + MAX_BLOCKS + " FLAC metadata blocks");
            }
            if (size - position < 4) {
                throw new MediaException.Malformed("FLAC metadata block header truncated at offset " + position);
            }
            var header = TagIo.read(source, position, 4, limits);
            last = (header[0] & 0x80) != 0;
            var type = header[0] & 0x7F;
            var length = ByteArrays.u24be(header, 1);
            if (type == 127) {
                throw new MediaException.Malformed("invalid FLAC metadata block type 127");
            }
            if (length > size - position - 4) {
                throw new MediaException.Malformed("FLAC metadata block of " + length + " bytes runs past the file");
            }
            var body = type == PADDING ? null : TagIo.read(source, position + 4, length, limits);
            parsed.add(new Block(type, length, body));
            position += 4L + length;
        }
        if (parsed.isEmpty() || parsed.getFirst().type() != STREAMINFO || parsed.getFirst().length() < 34) {
            throw new MediaException.Malformed("FLAC file does not start with a STREAMINFO block");
        }
        this.blocks = List.copyOf(parsed);
        this.audioStart = position;
        this.trailing = TrailingTags.regions(source, audioStart, size);
        this.audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
        ApeTag apeTag = null;
        byte[] v1 = null;
        for (var region : trailing) {
            if (region.kind() == TrailingTags.Kind.ID3V1) {
                v1 = TagIo.read(source, region.offset(), region.length(), limits);
            } else if (region.kind() == TrailingTags.Kind.APE && apeTag == null) {
                apeTag = ApeTag.parse(TagIo.read(source, region.offset(), region.length(), limits));
            }
        }
        this.ape = apeTag;
        this.id3v1 = v1;
    }

    @Override
    public MediaTags read() {
        var duration = durationMicros();
        MediaTags comments = MediaTags.empty();
        for (var block : blocks) {
            if (block.type() == VORBIS_COMMENT) {
                var parsed = VorbisComment.parse(block.body(), 0, block.body().length);
                comments = VorbisComment.decode(parsed.fields(), duration);
                break;
            }
        }
        var builder = comments.toBuilder();
        var pictures = new ArrayList<AttachedPicture>();
        for (var block : blocks) {
            if (block.type() == PICTURE) {
                var picture = FlacPicture.decode(block.body(), 0, block.body().length);
                if (picture != null) {
                    pictures.add(picture);
                }
            }
        }
        pictures.addAll(comments.pictures());
        var keys = new HashSet<String>();
        for (var entry : comments.entries()) {
            keys.add(entry.key().toLowerCase(Locale.ROOT));
        }
        if (ape != null) {
            var content = ape.decode();
            addMissing(builder, keys, content.entries());
            if (pictures.isEmpty()) {
                pictures.addAll(content.pictures());
            }
        }
        if (id3v1 != null) {
            addMissing(builder, keys, Id3v1Tag.parse(id3v1, 0));
        }
        return builder.pictures(pictures).build();
    }

    /**
     * Adds the entries whose key was not read before.
     *
     * @param builder the tags being read
     * @param keys the lower-case keys read so far, which receives the keys added
     * @param entries the entries of one tag
     */
    private static void addMissing(MediaTags.Builder builder, Set<String> keys, List<MediaTags.Entry> entries) {
        var added = new HashSet<String>();
        for (var entry : entries) {
            var key = entry.key().toLowerCase(Locale.ROOT);
            if (!keys.contains(key)) {
                builder.add(entry.key(), entry.value());
                added.add(key);
            }
        }
        keys.addAll(added);
    }

    /**
     * {@return the duration recorded in {@code STREAMINFO}, in microseconds, or {@code -1} when the total is unknown}
     */
    private long durationMicros() {
        var info = blocks.getFirst().body();
        var rate = (ByteArrays.u24be(info, 10) >>> 4) & 0xFFFFF;
        var samples = ((info[13] & 0x0FL) << 32) | ByteArrays.u32be(info, 14);
        if (rate == 0 || samples == 0) {
            return -1;
        }
        return samples * 1_000_000L / rate;
    }

    @Override
    public EditPlan plan(MediaTags tags, TagWriteOptions options) {
        var current = blockPictures();
        var vendor = VorbisComment.DEFAULT_VENDOR;
        var hadComment = false;
        for (var block : blocks) {
            if (block.type() == VORBIS_COMMENT) {
                vendor = VorbisComment.parse(block.body(), 0, block.body().length).vendor();
                hadComment = true;
                break;
            }
        }
        var commentTags = tags.toBuilder().pictures(List.of()).build();
        var keepPictures = current.equals(tags.pictures());
        var out = new ByteWriter(4096);
        var pending = new ArrayList<Block>();
        for (var block : blocks) {
            switch (block.type()) {
                case STREAMINFO, SEEKTABLE -> pending.add(block);
                case VORBIS_COMMENT, PADDING -> {
                }
                case PICTURE -> {
                    if (keepPictures) {
                        pending.add(block);
                    }
                }
                default -> {
                    if (options.preserveUnknown()) {
                        pending.add(block);
                    }
                }
            }
        }
        var insertAt = 1;
        while (insertAt < pending.size() && pending.get(insertAt).type() == SEEKTABLE) {
            insertAt++;
        }
        if (hadComment || !commentTags.isEmpty()) {
            var comment = VorbisComment.encode(vendor, commentTags, false);
            pending.add(insertAt, new Block(VORBIS_COMMENT, comment.length, comment));
        }
        if (!keepPictures) {
            for (var picture : tags.pictures()) {
                var body = FlacPicture.encode(picture);
                pending.add(new Block(PICTURE, body.length, body));
            }
        }
        for (var block : pending) {
            if (block.length() > MAX_BLOCK) {
                throw new MediaException.LimitExceeded("FLAC metadata block of " + block.length()
                        + " bytes exceeds the 16 MiB a block can hold");
            }
        }
        var size = 0L;
        for (var block : pending) {
            size += 4L + block.length();
        }
        var available = audioStart - markerOffset - 4;
        long padding;
        if (size == available) {
            padding = -1;
        } else if (size + 4 <= available) {
            padding = available - size - 4;
        } else {
            padding = options.padding() > 0 ? Math.min(MAX_BLOCK, options.padding()) : -1;
        }
        for (var index = 0; index < pending.size(); index++) {
            var block = pending.get(index);
            var last = index == pending.size() - 1 && padding < 0;
            writeHeader(out, block.type(), block.length(), last);
            out.bytes(block.body());
        }
        if (padding >= 0) {
            var remaining = padding + 4;
            while (remaining > 0) {
                var body = Math.min(MAX_BLOCK, remaining - 4);
                var leftover = remaining - 4 - body;
                if (leftover > 0 && leftover < 4) {
                    body -= 4;
                    leftover += 4;
                }
                writeHeader(out, PADDING, (int) body, leftover == 0);
                out.zeros((int) body);
                remaining = leftover;
            }
        }
        var plan = EditPlan.builder()
                .copy(0, markerOffset + 4)
                .bytes(out.toByteArray())
                .copy(audioStart, audioEnd - audioStart);
        writeTrailing(plan, tags, options);
        return plan.build();
    }

    /**
     * {@return the pictures of the {@code PICTURE} blocks and the Vorbis comments, which a rewrite keeps unchanged
     * when the new tags hold the same pictures}
     */
    private List<AttachedPicture> blockPictures() {
        var pictures = new ArrayList<AttachedPicture>();
        var comments = MediaTags.empty();
        for (var block : blocks) {
            if (block.type() == VORBIS_COMMENT) {
                comments = VorbisComment.decode(VorbisComment.parse(block.body(), 0, block.body().length).fields(), -1);
                break;
            }
        }
        for (var block : blocks) {
            if (block.type() == PICTURE) {
                var picture = FlacPicture.decode(block.body(), 0, block.body().length);
                if (picture != null) {
                    pictures.add(picture);
                }
            }
        }
        pictures.addAll(comments.pictures());
        return pictures;
    }

    /**
     * Plans the tags after the frames: the APE and ID3v1 tags by their modes, Lyrics3 blocks and
     * appended ID3v2 tags unchanged, except a Lyrics3v1 block left without the ID3v1 tag it needs.
     *
     * @param plan the plan, positioned after the frames
     * @param tags the tags to write
     * @param options the write options
     */
    private void writeTrailing(EditPlan.Builder plan, MediaTags tags, TagWriteOptions options) {
        var preserved = ape != null && options.preserveUnknown() ? ape.decode().opaque() : List.<ApeTag.Item>of();
        var apeBytes = switch (options.apeMode()) {
            case REMOVE -> null;
            case UPDATE_EXISTING -> ape != null ? ApeTag.encode(tags, preserved) : null;
            case ALWAYS -> ApeTag.encode(tags, preserved);
        };
        if (apeBytes != null && apeBytes.length == 0) {
            apeBytes = null;
        }
        var writeV1 = switch (options.id3v1Mode()) {
            case REMOVE -> false;
            case UPDATE_EXISTING -> id3v1 != null && Id3v1Tag.hasFields(tags);
            case ALWAYS -> Id3v1Tag.hasFields(tags);
        };
        var apeWritten = false;
        if (ape == null && apeBytes != null) {
            plan.bytes(apeBytes);
            apeWritten = true;
        }
        for (var region : trailing) {
            switch (region.kind()) {
                case APE -> {
                    if (!apeWritten && apeBytes != null) {
                        plan.bytes(apeBytes);
                    }
                    apeWritten = true;
                }
                case LYRICS3, ID3V2 -> {
                    var block = TagIo.read(source, region.offset(), region.length(), limits);
                    if (writeV1 || region.kind() == TrailingTags.Kind.ID3V2 || !TagIo.isLyrics3v1(block)) {
                        plan.bytes(block);
                    }
                }
                default -> {
                }
            }
        }
        if (writeV1) {
            plan.bytes(Id3v1Tag.encode(tags));
        }
    }

    /**
     * Writes a metadata block header.
     *
     * @param out the destination
     * @param type the block type
     * @param length the body length
     * @param last whether the block is the last metadata block
     */
    private static void writeHeader(ByteWriter out, int type, int length, boolean last) {
        out.u8((last ? 0x80 : 0) | type);
        out.u24be(length);
    }

    /**
     * Holds one metadata block.
     *
     * @param type the block type
     * @param length the body length
     * @param body the body, or {@code null} for padding
     */
    private record Block(int type, int length, byte[] body) {
    }
}
