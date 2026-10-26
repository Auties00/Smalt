package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

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
     * @return {@code true} when they hold no entry, picture, chapter or XMP packet, whatever their track tags
     */
    public static boolean isGlobalEmpty(MediaTags tags) {
        return tags.entries().isEmpty() && tags.pictures().isEmpty() && tags.chapters().isEmpty()
                && tags.xmp().isEmpty();
    }

    /**
     * Encodes the boxes of the tags at the end of an MP4 {@code moov}: a {@code udta} box holding an iTunes
     * {@code meta}/{@code ilst}, the 3GPP {@code loci} boxes of the {@code loci} key, a Nero {@code chpl} box of the
     * chapters and, in a QuickTime movie, an {@code XMP_} atom of the XMP packet; then a QuickTime keyed metadata
     * {@code meta} box (handler {@code mdta}) holding the reverse-DNS keys, such as {@code com.apple.quicktime.make}.
     *
     * @param tags the tags and chapters to store
     * @param quickTime whether the movie is a QuickTime movie rather than an ISO file
     * @return the boxes, or an empty array for empty tags
     * @throws com.github.auties00.smalt.MediaException.Unsupported if a value of an iTunes integer
     * atom, such as {@code tmpo}, is not an integer the atom holds, or a value of the {@code loci} key is not ISO 6709
     * coordinates
     */
    public static byte[] mp4UserData(MediaTags tags, boolean quickTime) {
        if (isGlobalEmpty(tags)) {
            return new byte[0];
        }
        return Mp4TagFile.movieBoxes(tags, quickTime);
    }

    /**
     * Encodes the top-level {@code uuid} box (XMP Specification Part 3) holding the XMP packet of an ISO file.
     *
     * @param tags the tags whose packet is encoded
     * @return the box, or an empty array without a packet
     */
    public static byte[] mp4Xmp(MediaTags tags) {
        return Mp4TagFile.xmpBox(tags);
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
     * <p>The {@code STREAMINFO}, {@code SEEKTABLE} and {@code APPLICATION} blocks are kept in
     * order; the {@code VORBIS_COMMENT} block (keeping its vendor string) and the {@code PICTURE}
     * blocks are rebuilt from the tags and follow them, and the {@code PADDING} blocks come last, so
     * that a header whose seek table grows into its padding keeps its length once tagged. The
     * {@code CUESHEET} of the header is dropped: chapters, besides their {@code CHAPTERxxx} comments,
     * get one built from them ({@link FlacCuesheet}) when there are at most
     * {@value FlacCuesheet#MAX_CHAPTERS}. The last block gets the last-block flag.
     *
     * @param header the {@code fLaC} marker and well-formed metadata blocks
     * @param tags the tags, pictures and chapters to store
     * @return the new header
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if a block exceeds its 24-bit length
     */
    public static byte[] flacMetadata(byte[] header, MediaTags tags) {
        var blocks = new ArrayList<byte[]>();
        var padding = new ArrayList<byte[]>();
        var vendor = VorbisComment.DEFAULT_VENDOR;
        byte[] cuesheet = null;
        if (header.length >= 8 + 18 && (header[4] & 0x7F) == 0) {
            var rate = (ByteArrays.u24be(header, 8 + 10) >>> 4) & 0xFFFFF;
            var total = ((header[8 + 13] & 0x0FL) << 32) | ByteArrays.u32be(header, 8 + 14);
            cuesheet = FlacCuesheet.encode(tags.chapters(), rate, total);
        }
        var offset = 4;
        while (offset + 4 <= header.length) {
            var type = header[offset] & 0x7F;
            var length = ByteArrays.u24be(header, offset + 1);
            var end = Math.min(header.length, offset + 4 + length);
            if (type == 5) {
                offset = end;
                continue;
            }
            switch (type) {
                case 1 -> padding.add(withType(1, java.util.Arrays.copyOfRange(header, offset + 4, end)));
                case 6 -> {
                }
                case 4 -> vendor = VorbisComment.parse(header, offset + 4, end - offset - 4).vendor();
                default -> blocks.add(withType(type, java.util.Arrays.copyOfRange(header, offset + 4, end)));
            }
            offset = end;
        }
        if (cuesheet != null) {
            blocks.add(withType(5, cuesheet));
        }
        blocks.add(withType(4, VorbisComment.encode(vendor, tags.toBuilder().pictures(List.of()).build(), false)));
        for (var picture : flacPictures(tags)) {
            blocks.add(withType(6, picture));
        }
        blocks.addAll(padding);
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
        return matroska(tags, List.of(), false);
    }

    /**
     * Encodes the Matroska {@code Tags}, {@code Chapters} and {@code Attachments} elements, the {@code Tags}
     * element holding the given track tags first.
     *
     * @param tags the tags, pictures and chapters to store
     * @param trackTags the complete {@code Tag} elements of the tracks
     * @param webm whether the elements are for a WebM file, which stores the chapters in their
     * {@linkplain MatroskaTags#webmEditions WebM form} and no attachment that is not a picture
     * @return the three elements in this order, each empty when there is nothing to write
     */
    public static byte[][] matroska(MediaTags tags, List<byte[]> trackTags, boolean webm) {
        return new byte[][]{
                MatroskaTags.encodeTags(tags, trackTags),
                MatroskaTags.encodeEditions(tags.editions(), webm),
                MatroskaTags.encodeAttachments(tags.pictures(), webm ? List.of() : tags.attachments(), List.of())
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
     * Encodes the metadata chunks of a WAV or AIFF file: the text chunks, an ID3v2 chunk when the tags hold
     * pictures or more than the text chunks can, the {@code _PMX} or {@code APPL} chunk of the XMP packet, then the
     * descriptive chunks {@link RiffMetadataChunks} writes
     * (the Broadcast Wave and sampler chunks and the cue points of a WAV file, the markers and instrument of an
     * AIFF file), which hold the chapters.
     *
     * @param tags the tags, pictures and chapters to store
     * @param aiff whether the file is AIFF
     * @param sampleRate the sampling rate of the audio, in hertz, or 0 when unknown, which writes no chapters
     * @return the complete chunks, with headers and pad bytes
     * @throws IllegalArgumentException if a value of a key of a descriptive chunk is malformed
     */
    public static List<byte[]> riffChunks(MediaTags tags, boolean aiff, int sampleRate) {
        var result = new ArrayList<byte[]>();
        var plain = RiffMetadataChunks.withoutOwned(tags, aiff).toBuilder().noXmp().build();
        if (!plain.entries().isEmpty() || !plain.pictures().isEmpty()) {
            var allText = RiffTagFile.encodeText(aiff, aiff, plain, result, new HashSet<>());
            if (!allText || !plain.pictures().isEmpty()) {
                result.add(RiffTagFile.chunk(aiff, aiff ? "ID3 " : "id3 ",
                        id3v2(plain.toBuilder().chapters(List.of()).build())));
            }
        }
        if (tags.xmp().isPresent()) {
            result.add(RiffTagFile.xmpChunk(aiff, aiff, tags.xmp().get()));
        }
        if (aiff) {
            result.addAll(RiffMetadataChunks.encodeMarkers(tags.chapters(), tags, sampleRate));
        } else {
            result.addAll(RiffMetadataChunks.waveChunks(tags, true, sampleRate));
        }
        return result;
    }
}
