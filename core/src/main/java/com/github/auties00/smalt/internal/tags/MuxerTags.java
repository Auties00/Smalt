package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Encodes tags in the forms the muxers write when a caller sets them with
 * {@link com.github.auties00.smalt.container.Muxer#setTags(MediaTags)}.
 */
public final class MuxerTags {
    /**
     * The ID3v2 version muxers write.
     */
    private static final int ID3_VERSION = 4;
    private MuxerTags() {
        throw new AssertionError();
    }

    /**
     * Returns whether tags hold nothing a container stores for the whole file.
     *
     * @param tags the tags whose track tags are not counted
     * @return {@code true} when they hold no entry, picture or chapter, whatever their track tags
     */
    public static boolean isGlobalEmpty(MediaTags tags) {
        return tags.entries().isEmpty() && tags.pictures().isEmpty() && tags.chapters().isEmpty();
    }

    /**
     * Encodes an MP4 {@code udta} box holding an iTunes {@code meta}/{@code ilst} and, with
     * chapters, a Nero {@code chpl} box.
     *
     * @param tags the tags and chapters to store
     * @return the box, or an empty array for empty tags
     * @throws com.github.auties00.smalt.MediaException.Unsupported if a value of an iTunes integer
     * atom, such as {@code tmpo}, is not an integer the atom holds
     */
    public static byte[] mp4UserData(MediaTags tags) {
        if (isGlobalEmpty(tags)) {
            return new byte[0];
        }
        var out = new ByteWriter(4096);
        if (!tags.entries().isEmpty() || !tags.pictures().isEmpty()) {
            var meta = new ByteWriter(4096);
            meta.u32be(0);
            meta.bytes(Mp4TagFile.handler());
            meta.bytes(Mp4TagFile.ilst(tags, List.of(), Map.of(), new HashSet<>()));
            Mp4Boxes.box(out, "meta", meta.toByteArray());
        }
        if (!tags.chapters().isEmpty()) {
            out.bytes(Mp4TagFile.chapterBox(tags.chapters()));
        }
        return Mp4Boxes.box("udta", out.toByteArray());
    }

    /**
     * Encodes an ID3v2.4 tag without padding.
     *
     * @param tags the tags, pictures and chapters to store
     * @return the tag, or an empty array for empty tags
     */
    public static byte[] id3v2(MediaTags tags) {
        if (isGlobalEmpty(tags)) {
            return new byte[0];
        }
        return Id3v2Tag.encode(tags, List.of(), null, ID3_VERSION, 0, 0);
    }

    /**
     * Encodes a Vorbis comment block, the pictures as {@code METADATA_BLOCK_PICTURE} fields.
     *
     * @param vendor the vendor string
     * @param tags the tags, pictures and chapters
     * @return the block, without the framing bit Vorbis appends
     */
    public static byte[] vorbisComment(String vendor, MediaTags tags) {
        return VorbisComment.encode(vendor, tags, true);
    }

    /**
     * Encodes the FLAC {@code PICTURE} block bodies of the pictures.
     *
     * @param tags the tags whose pictures are encoded
     * @return one block body per picture
     */
    public static List<byte[]> flacPictures(MediaTags tags) {
        var result = new ArrayList<byte[]>();
        for (var picture : tags.pictures()) {
            result.add(FlacPicture.encode(picture));
        }
        return result;
    }

    /**
     * Replaces the comment and picture blocks of a FLAC stream header.
     *
     * <p>The {@code STREAMINFO}, {@code SEEKTABLE}, {@code APPLICATION} and {@code CUESHEET}
     * blocks are kept in order; the {@code VORBIS_COMMENT} block (keeping its vendor string) and
     * the {@code PICTURE} blocks are rebuilt from the tags, and {@code PADDING} is dropped. The
     * last block gets the last-block flag.
     *
     * @param header the {@code fLaC} marker and well-formed metadata blocks
     * @param tags the tags, pictures and chapters to store
     * @return the new header
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if a block exceeds its 24-bit length
     */
    public static byte[] flacMetadata(byte[] header, MediaTags tags) {
        var blocks = new ArrayList<byte[]>();
        var vendor = VorbisComment.DEFAULT_VENDOR;
        var offset = 4;
        while (offset + 4 <= header.length) {
            var type = header[offset] & 0x7F;
            var length = ByteArrays.u24be(header, offset + 1);
            var end = Math.min(header.length, offset + 4 + length);
            switch (type) {
                case 1, 6 -> {
                }
                case 4 -> vendor = VorbisComment.parse(header, offset + 4, end - offset - 4).vendor();
                default -> blocks.add(java.util.Arrays.copyOfRange(header, offset + 4, end));
            }
            if (type != 1 && type != 4 && type != 6) {
                blocks.set(blocks.size() - 1, withType(type, blocks.getLast()));
            }
            offset = end;
        }
        blocks.add(withType(4, VorbisComment.encode(vendor, tags.toBuilder().pictures(List.of()).build(), false)));
        for (var picture : flacPictures(tags)) {
            blocks.add(withType(6, picture));
        }
        var out = new ByteWriter(header.length + 1024);
        out.bytes(header, 0, 4);
        for (var index = 0; index < blocks.size(); index++) {
            var block = blocks.get(index);
            if (index == blocks.size() - 1) {
                block[0] |= (byte) 0x80;
            }
            out.bytes(block);
        }
        return out.toByteArray();
    }

    /**
     * Prefixes a block body with its header, the last-block flag cleared.
     *
     * @param type the block type
     * @param body the block body
     * @return the block
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the body exceeds 16 MiB
     */
    private static byte[] withType(int type, byte[] body) {
        if (body.length >= 1 << 24) {
            throw new com.github.auties00.smalt.MediaException.LimitExceeded("FLAC metadata block of " + body.length
                    + " bytes exceeds the 16 MiB a block can hold");
        }
        var block = new byte[4 + body.length];
        block[0] = (byte) type;
        block[1] = (byte) (body.length >>> 16);
        block[2] = (byte) (body.length >>> 8);
        block[3] = (byte) body.length;
        System.arraycopy(body, 0, block, 4, body.length);
        return block;
    }

    /**
     * Encodes the Matroska {@code Tags}, {@code Chapters} and {@code Attachments} elements.
     *
     * @param tags the tags, pictures and chapters to store
     * @return the three elements in this order, each empty when there is nothing to write
     */
    public static byte[][] matroska(MediaTags tags) {
        return matroska(tags, List.of());
    }

    /**
     * Encodes the Matroska {@code Tags}, {@code Chapters} and {@code Attachments} elements, the {@code Tags}
     * element holding the given track tags first.
     *
     * @param tags the tags, pictures and chapters to store
     * @param trackTags the complete {@code Tag} elements of the tracks
     * @return the three elements in this order, each empty when there is nothing to write
     */
    public static byte[][] matroska(MediaTags tags, List<byte[]> trackTags) {
        return new byte[][]{
                MatroskaTags.encodeTags(tags, trackTags),
                MatroskaTags.encodeChapters(tags.chapters()),
                MatroskaTags.encodeAttachments(tags.pictures(), List.of())
        };
    }

    /**
     * Encodes the {@code LIST INFO} chunk of an AVI file: the first value of every entry {@code INFO} has an
     * identifier for.
     *
     * @param tags the tags to store
     * @return the complete chunk, or an empty array when no entry fits one
     */
    public static byte[] riffInfo(MediaTags tags) {
        var chunk = RiffTagFile.infoList(tags.entries(), false, new HashSet<>());
        return chunk != null ? chunk : new byte[0];
    }

    /**
     * Encodes a Matroska {@code Tag} element targeting one track.
     *
     * @param trackUid the {@code TrackUID} of the track
     * @param entries the tags of the track
     * @return the complete element
     */
    public static byte[] matroskaTrackTag(long trackUid, List<MediaTags.Entry> entries) {
        return MatroskaTags.encodeTrackTag(trackUid, entries);
    }

    /**
     * Encodes the metadata chunks of a WAV or AIFF file: the text chunks, then an ID3v2 chunk
     * when the tags hold more than the text chunks can.
     *
     * @param tags the tags and pictures to store
     * @param aiff whether the file is AIFF
     * @return the complete chunks, with headers and pad bytes
     */
    public static List<byte[]> riffChunks(MediaTags tags, boolean aiff) {
        var result = new ArrayList<byte[]>();
        if (isGlobalEmpty(tags)) {
            return result;
        }
        var allText = RiffTagFile.encodeText(aiff, aiff, tags, result, new HashSet<>());
        if (!allText || !tags.pictures().isEmpty() || !tags.chapters().isEmpty()) {
            result.add(RiffTagFile.chunk(aiff, aiff ? "ID3 " : "id3 ", id3v2(tags)));
        }
        return result;
    }
}
