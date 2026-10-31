package com.github.auties00.smalt.internal.header;

import java.util.Arrays;

/**
 * Writes and reads the structure of DIF frames around the video: the block IDs, the header section, the time
 * code of the subcode section and the source packs of the VAUX section (SMPTE ST 314 clauses 4.3 to 4.5, SMPTE
 * ST 370 clauses 3.3 to 3.5).
 *
 * <p>This class is thread-safe.
 */
public final class DifFrames {
    /**
     * The pack that marks the absence of information, all bits set.
     */
    private static final byte NO_INFO = (byte) 0xFF;

    /**
     * Prevents instantiation.
     */
    private DifFrames() {
        throw new AssertionError();
    }

    /**
     * Writes every section of a frame but the video data: the IDs of all blocks, the header blocks, the subcode
     * blocks with the time code of the frame, the VAUX blocks with the source and source control packs, and audio
     * blocks without audio.
     *
     * <p>The payload of every video block is zeroed but its first byte, which is left for the compressed
     * macroblock.
     *
     * @param data the array receiving the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param wide whether the picture displays at 16:9
     * @param secondHalf for a 720-line profile, whether the frame is the second of the two video frames of a DIF
     * frame, whose DIF channels are 2 and 3; ignored otherwise
     * @param frame the index of the frame in the stream, which gives its time code
     */
    public static void writeSkeleton(byte[] data, int offset, DifProfile profile, boolean wide, boolean secondHalf,
                                     long frame) {
        Arrays.fill(data, offset, offset + profile.frameSize(), NO_INFO);
        var timecode = timecode(profile, frame);
        for (var channel = 0; channel < profile.channels(); channel++) {
            var difChannel = profile.progressive() && secondHalf ? channel + 2 : channel;
            for (var sequence = 0; sequence < profile.sequences(); sequence++) {
                var base = offset + profile.blockOffset(channel, sequence, 0);
                writeId(data, base, DifProfile.SECTION_HEADER, sequence, difChannel, 0, profile);
                writeHeader(data, base, profile);
                for (var block = 0; block < 2; block++) {
                    var at = base + (1 + block) * DifProfile.BLOCK;
                    writeId(data, at, DifProfile.SECTION_SUBCODE, sequence, difChannel, block, profile);
                    writeSubcode(data, at, profile, sequence, block, timecode);
                }
                for (var block = 0; block < 3; block++) {
                    var at = base + (3 + block) * DifProfile.BLOCK;
                    writeId(data, at, DifProfile.SECTION_VAUX, sequence, difChannel, block, profile);
                }
                writeVaux(data, base, profile, sequence, wide);
                for (var block = 0; block < 9; block++) {
                    var at = base + DifProfile.audioBlock(block) * DifProfile.BLOCK;
                    writeId(data, at, DifProfile.SECTION_AUDIO, sequence, difChannel, block, profile);
                    Arrays.fill(data, at + 8, at + DifProfile.BLOCK, (byte) 0);
                }
                for (var block = 0; block < 135; block++) {
                    var at = base + DifProfile.videoBlock(block) * DifProfile.BLOCK;
                    writeId(data, at, DifProfile.SECTION_VIDEO, sequence, difChannel, block, profile);
                    Arrays.fill(data, at + 3, at + DifProfile.BLOCK, (byte) 0);
                }
            }
        }
    }

    /**
     * Writes the three ID bytes of a block.
     *
     * @param data the array holding the frame
     * @param at the index of the block's first byte
     * @param section the section type
     * @param sequence the DIF sequence number
     * @param channel the DIF channel, from 0 to 3
     * @param number the block number within its section
     * @param profile the DV format
     */
    private static void writeId(byte[] data, int at, int section, int sequence, int channel, int number,
                                DifProfile profile) {
        data[at] = (byte) (section << 5 | 0x1F);
        var fsc = channel & 1;
        var fsp = profile.highDefinition() ? (channel < 2 ? 1 : 0) : 1;
        data[at + 1] = (byte) (sequence << 4 | fsc << 3 | fsp << 2 | 0x03);
        data[at + 2] = (byte) number;
    }

    /**
     * Writes the payload of a header block (SMPTE ST 314 table 6).
     *
     * @param data the array holding the frame
     * @param at the index of the block's first byte
     * @param profile the DV format
     */
    private static void writeHeader(byte[] data, int at, DifProfile profile) {
        var application = profile.applicationId();
        data[at + 3] = (byte) ((profile.fiftyHz() ? 0x80 : 0x00) | 0x3F);
        data[at + 4] = (byte) (0xF8 | application);
        data[at + 5] = (byte) (0x78 | application);
        data[at + 6] = (byte) (0x78 | application);
        data[at + 7] = (byte) (0x78 | application);
    }

    /**
     * Writes the payload of a subcode block: six of the twelve subcode sync blocks of the sequence. The SMPTE ST 314
     * and ST 370 formats carry the time code pack in syncs 3, 5, 9 and 11 of the first half of the sequences and 3
     * and 9 of the second (SMPTE ST 314 table 9); the IEC 61834 formats in every sync of the first half and syncs 0,
     * 3, 6 and 9 of the second, as consumer equipment writes it. The other syncs hold no information.
     *
     * @param data the array holding the frame
     * @param at the index of the block's first byte
     * @param profile the DV format
     * @param sequence the DIF sequence number
     * @param block the subcode block, 0 or 1
     * @param timecode the time code pack, five bytes
     */
    private static void writeSubcode(byte[] data, int at, DifProfile profile, int sequence, int block,
                                     byte[] timecode) {
        var firstHalf = sequence < profile.sequences() / 2;
        var application = profile.applicationId();
        for (var sync = 0; sync < 6; sync++) {
            var number = block * 6 + sync;
            var ssyb = at + 3 + 8 * sync;
            int id0;
            if (application == 0) {
                id0 = 0x0F;
            } else if (number == 0 || number == 6 || number == 11) {
                id0 = application << 4 | 0x0F;
            } else {
                id0 = 0x7F;
            }
            data[ssyb] = (byte) ((firstHalf ? 0x80 : 0x00) | id0);
            data[ssyb + 1] = (byte) (0xF0 | number);
            data[ssyb + 2] = NO_INFO;
            boolean code;
            if (application == 0) {
                code = firstHalf || number % 3 == 0;
            } else {
                code = number == 3 || number == 9 || firstHalf && (number == 5 || number == 11);
            }
            if (code) {
                System.arraycopy(timecode, 0, data, ssyb + 3, 5);
            }
        }
    }

    /**
     * Writes the VAUX packs of a sequence: the source pack and the source control pack at packs 39 and 40 of an
     * even sequence and 0 and 1 of an odd one (SMPTE ST 314 table 12).
     *
     * @param data the array holding the frame
     * @param base the index of the sequence's first byte
     * @param profile the DV format
     * @param sequence the DIF sequence number
     * @param wide whether the picture displays at 16:9
     */
    private static void writeVaux(byte[] data, int base, DifProfile profile, int sequence, boolean wide) {
        var first = sequence % 2 == 0 ? 39 : 0;
        var source = packAt(base, first);
        data[source] = (byte) DifProfile.PACK_VAUX_SOURCE;
        data[source + 1] = NO_INFO;
        data[source + 2] = NO_INFO;
        data[source + 3] = (byte) (0xC0 | (profile.fiftyHz() ? 0x20 : 0x00) | profile.signalType());
        data[source + 4] = (byte) (profile.highDefinition() ? 0x7F : 0xFF);
        var control = packAt(base, first + 1);
        data[control] = (byte) DifProfile.PACK_VAUX_CONTROL;
        data[control + 1] = 0x3F;
        // display select 010 marks 16:9, the code IEC 61834 defines and readers of every DV format accept
        data[control + 2] = (byte) (0xC8 | (wide || profile.highDefinition() ? 0x02 : 0x00));
        data[control + 3] = (byte) 0xFC;
        data[control + 4] = NO_INFO;
    }

    /**
     * Returns the index of a VAUX pack of a sequence.
     *
     * @param base the index of the sequence's first byte
     * @param pack the video pack number, from 0 to 44
     * @return the index of the pack's header byte
     */
    private static int packAt(int base, int pack) {
        return base + (3 + pack / 15) * DifProfile.BLOCK + 3 + 5 * (pack % 15);
    }

    /**
     * Builds the time code pack of a frame: SMPTE ST 12 time code counting from zero, drop frame in the 60 Hz
     * systems, the two frames of a 720-line DIF frame sharing one count.
     *
     * @param profile the DV format
     * @param frame the index of the frame in the stream
     * @return the pack, five bytes
     */
    static byte[] timecode(DifProfile profile, long frame) {
        var count = profile.progressive() ? frame / 2 : frame;
        var drop = !profile.fiftyHz();
        var rate = profile.fiftyHz() ? 25 : 30;
        if (drop) {
            var tenMinutes = 17982L;
            var blocks = count / tenMinutes;
            var rest = count % tenMinutes;
            count += 18 * blocks + (rest < 2 ? 0 : 2 * ((rest - 2) / 1798));
        }
        var frames = (int) (count % rate);
        var seconds = (int) (count / rate % 60);
        var minutes = (int) (count / rate / 60 % 60);
        var hours = (int) (count / rate / 3600 % 24);
        return new byte[]{
                DifProfile.PACK_TIMECODE,
                (byte) ((drop ? 0x40 : 0x00) | (frames / 10) << 4 | frames % 10),
                (byte) ((seconds / 10) << 4 | seconds % 10),
                (byte) ((minutes / 10) << 4 | minutes % 10),
                (byte) ((hours / 10) << 4 | hours % 10)};
    }

    /**
     * Reads the time code of a frame from the first time code pack of its subcode.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @return the hours, minutes, seconds and frames, and 1 for drop frame or 0, or {@code null} when the frame
     * carries no valid time code
     */
    public static int[] readTimecode(byte[] data, int offset, DifProfile profile) {
        for (var sequence = 0; sequence < profile.sequences(); sequence++) {
            for (var block = 1; block <= 2; block++) {
                var at = offset + profile.blockOffset(0, sequence, block);
                if (((data[at] & 0xFF) >>> 5) != DifProfile.SECTION_SUBCODE) {
                    continue;
                }
                for (var sync = 0; sync < 6; sync++) {
                    var pack = at + 3 + 8 * sync + 3;
                    if ((data[pack] & 0xFF) != DifProfile.PACK_TIMECODE) {
                        continue;
                    }
                    var frames = bcd(data[pack + 1] & 0x3F);
                    var seconds = bcd(data[pack + 2] & 0x7F);
                    var minutes = bcd(data[pack + 3] & 0x7F);
                    var hours = bcd(data[pack + 4] & 0x3F);
                    if (frames < 0 || seconds < 0 || minutes < 0 || hours < 0 || frames >= 30 || seconds >= 60
                            || minutes >= 60 || hours >= 24) {
                        continue;
                    }
                    var drop = !profile.fiftyHz() && (data[pack + 1] & 0x40) != 0 ? 1 : 0;
                    return new int[]{hours, minutes, seconds, frames, drop};
                }
            }
        }
        return null;
    }

    /**
     * Decodes a binary-coded decimal byte.
     *
     * @param value the byte
     * @return the number, or -1 when a digit exceeds 9
     */
    private static int bcd(int value) {
        var tens = value >>> 4;
        var units = value & 0x0F;
        return tens > 9 || units > 9 ? -1 : tens * 10 + units;
    }
}
