package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.track.Rational;

/**
 * Enumerates the DV formats whose DIF (digital interface) frames Smalt reads and writes: the 25 Mb/s consumer
 * formats of IEC 61834-2, the 25 and 50 Mb/s DV-based formats of SMPTE ST 314 and the 100 Mb/s DVCPRO HD formats of
 * SMPTE ST 370.
 *
 * <p>A DIF frame is a run of DIF sequences of 150 blocks of 80 bytes, {@link #sequences()} per DIF channel and
 * {@link #channels()} channels one after the other. A DVCPRO HD 720-line frame here is one video frame, two DIF
 * channels, as files carry it; the four channels of SMPTE ST 370 hold two such frames.
 */
public enum DifProfile {
    /**
     * IEC 61834 525/60 4:1:1 at 25 Mb/s, application ID 0.
     */
    DV_525(false, 10, 1, 720, 480, Sampling.S411, 0, 0x00),

    /**
     * SMPTE ST 314 525/60 4:1:1 at 25 Mb/s (DVCPRO), application ID 1; the same coding as {@link #DV_525}.
     */
    DVCPRO_525(false, 10, 1, 720, 480, Sampling.S411, 1, 0x00),

    /**
     * IEC 61834 625/50 4:2:0 at 25 Mb/s, application ID 0.
     */
    DV_625(true, 12, 1, 720, 576, Sampling.S420, 0, 0x00),

    /**
     * SMPTE ST 314 625/50 4:1:1 at 25 Mb/s (DVCPRO), application ID 1.
     */
    DVCPRO_625(true, 12, 1, 720, 576, Sampling.S411, 1, 0x00),

    /**
     * SMPTE ST 314 525/60 4:2:2 at 50 Mb/s (DVCPRO50).
     */
    DVCPRO50_525(false, 10, 2, 720, 480, Sampling.S422, 1, 0x04),

    /**
     * SMPTE ST 314 625/50 4:2:2 at 50 Mb/s (DVCPRO50).
     */
    DVCPRO50_625(true, 12, 2, 720, 576, Sampling.S422, 1, 0x04),

    /**
     * SMPTE ST 370 1080/60i at 100 Mb/s, 1280 by 1080 coded samples.
     */
    DVCPRO_HD_1080I60(false, 10, 4, 1280, 1080, Sampling.S422, 1, 0x14),

    /**
     * SMPTE ST 370 1080/50i at 100 Mb/s, 1440 by 1080 coded samples.
     */
    DVCPRO_HD_1080I50(true, 12, 4, 1440, 1080, Sampling.S422, 1, 0x14),

    /**
     * SMPTE ST 370 720/60p at 100 Mb/s, 960 by 720 coded samples.
     */
    DVCPRO_HD_720P60(false, 10, 2, 960, 720, Sampling.S422, 1, 0x18),

    /**
     * SMPTE ST 370 720/50p at 100 Mb/s, 960 by 720 coded samples.
     */
    DVCPRO_HD_720P50(true, 12, 2, 960, 720, Sampling.S422, 1, 0x18);

    /**
     * The chroma sampling of a profile.
     */
    public enum Sampling {
        /**
         * 4:1:1: chroma at a quarter of the luma width.
         */
        S411,

        /**
         * 4:2:0: chroma at half the luma width and height.
         */
        S420,

        /**
         * 4:2:2: chroma at half the luma width.
         */
        S422
    }

    /**
     * The size of a DIF block in bytes.
     */
    public static final int BLOCK = 80;

    /**
     * The number of DIF blocks of a DIF sequence.
     */
    public static final int BLOCKS_PER_SEQUENCE = 150;

    /**
     * The size of a DIF sequence in bytes.
     */
    public static final int SEQUENCE = BLOCK * BLOCKS_PER_SEQUENCE;

    /**
     * The section type of a header block.
     */
    public static final int SECTION_HEADER = 0;

    /**
     * The section type of a subcode block.
     */
    public static final int SECTION_SUBCODE = 1;

    /**
     * The section type of a VAUX block.
     */
    public static final int SECTION_VAUX = 2;

    /**
     * The section type of an audio block.
     */
    public static final int SECTION_AUDIO = 3;

    /**
     * The section type of a video block.
     */
    public static final int SECTION_VIDEO = 4;

    /**
     * The pack header of the VAUX source pack.
     */
    public static final int PACK_VAUX_SOURCE = 0x60;

    /**
     * The pack header of the VAUX source control pack.
     */
    public static final int PACK_VAUX_CONTROL = 0x61;

    /**
     * The pack header of the AAUX source pack.
     */
    public static final int PACK_AAUX_SOURCE = 0x50;

    /**
     * The pack header of the AAUX source control pack.
     */
    public static final int PACK_AAUX_CONTROL = 0x51;

    /**
     * The pack header of the time code pack of the subcode.
     */
    public static final int PACK_TIMECODE = 0x13;

    private final boolean fiftyHz;
    private final int sequences;
    private final int channels;
    private final int width;
    private final int height;
    private final Sampling sampling;
    private final int applicationId;
    private final int signalType;

    DifProfile(boolean fiftyHz, int sequences, int channels, int width, int height, Sampling sampling,
               int applicationId, int signalType) {
        this.fiftyHz = fiftyHz;
        this.sequences = sequences;
        this.channels = channels;
        this.width = width;
        this.height = height;
        this.sampling = sampling;
        this.applicationId = applicationId;
        this.signalType = signalType;
    }

    /**
     * {@return whether the profile belongs to the 50 Hz systems (625/50, 1080/50i, 720/50p)}
     */
    public boolean fiftyHz() {
        return fiftyHz;
    }

    /**
     * {@return the number of DIF sequences of a DIF channel: 10 for 60 Hz systems, 12 for 50 Hz systems}
     */
    public int sequences() {
        return sequences;
    }

    /**
     * {@return the number of DIF channels of a frame}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the size of a frame in bytes}
     */
    public int frameSize() {
        return sequences * channels * SEQUENCE;
    }

    /**
     * {@return the coded picture width in luma samples}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the coded picture height in lines}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the chroma sampling}
     */
    public Sampling sampling() {
        return sampling;
    }

    /**
     * {@return the track application ID the header block carries: 0 for IEC 61834, 1 for SMPTE ST 314 and 370}
     */
    public int applicationId() {
        return applicationId;
    }

    /**
     * {@return the signal type ({@code STYPE}) of the VAUX source pack}
     */
    public int signalType() {
        return signalType;
    }

    /**
     * {@return whether the profile is a DVCPRO HD format}
     */
    public boolean highDefinition() {
        return height > 576;
    }

    /**
     * {@return whether the profile is a 720-line progressive format}
     */
    public boolean progressive() {
        return height == 720;
    }

    /**
     * {@return the frame rate: 30000/1001, 25, 60000/1001 or 50}
     */
    public Rational frameRate() {
        if (progressive()) {
            return fiftyHz ? new Rational(50, 1) : new Rational(60000, 1001);
        }
        return fiftyHz ? new Rational(25, 1) : new Rational(30000, 1001);
    }

    /**
     * Returns the sample aspect ratio of the coded picture.
     *
     * @param wide whether the picture is displayed at 16:9 (ignored by the DVCPRO HD formats, which always are)
     * @return the ratio of the width of a sample to its height
     */
    public Rational sampleAspectRatio(boolean wide) {
        return switch (this) {
            case DV_525, DVCPRO_525, DVCPRO50_525 -> wide ? new Rational(32, 27) : new Rational(8, 9);
            case DV_625, DVCPRO_625, DVCPRO50_625 -> wide ? new Rational(64, 45) : new Rational(16, 15);
            case DVCPRO_HD_1080I60 -> new Rational(3, 2);
            case DVCPRO_HD_1080I50, DVCPRO_HD_720P60, DVCPRO_HD_720P50 -> new Rational(4, 3);
        };
    }

    /**
     * {@return the number of audio channels the frame carries at 16 bits: two per DIF channel}
     */
    public int audioChannels() {
        return 2 * channels;
    }

    /**
     * Identifies the profile of a frame from its header block and its VAUX source pack.
     *
     * <p>The header block must open the data. The VAUX source pack is looked for in the VAUX blocks of the first
     * sequence; without one, the header's DIF sequence flag and application ID and the data length decide.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes available from {@code offset}
     * @return the profile, or {@code null} when the data does not start with a DIF header block or names a signal
     * type this enum does not cover
     */
    public static DifProfile detect(byte[] data, int offset, int length) {
        if (length < 6 * BLOCK || (data[offset] & 0xE0) != 0 || (data[offset + 1] & 0xF0) != 0
                || data[offset + 2] != 0) {
            return null;
        }
        var fifty = (data[offset + 3] & 0x80) != 0;
        var application = data[offset + 4] & 0x07;
        var source = findPack(data, offset, Math.min(length, SEQUENCE), PACK_VAUX_SOURCE, SECTION_VAUX);
        if (source >= 0) {
            var type = data[source + 3] & 0x1F;
            switch (type) {
                case 0x00 -> {
                    if (!fifty) {
                        return application == 0 ? DV_525 : DVCPRO_525;
                    }
                    return application == 0 ? DV_625 : DVCPRO_625;
                }
                case 0x04 -> {
                    return fifty ? DVCPRO50_625 : DVCPRO50_525;
                }
                case 0x14, 0x15 -> {
                    return fifty ? DVCPRO_HD_1080I50 : DVCPRO_HD_1080I60;
                }
                case 0x18 -> {
                    return fifty ? DVCPRO_HD_720P50 : DVCPRO_HD_720P60;
                }
                default -> {
                    return null;
                }
            }
        }
        for (var profile : values()) {
            if (profile.fiftyHz == fifty && profile.frameSize() == length && !profile.highDefinition()
                    && (profile.channels > 1 || profile.applicationId == Math.min(application, 1))) {
                return profile;
            }
        }
        if (length >= (fifty ? DV_625 : DV_525).frameSize()) {
            if (fifty) {
                return application == 0 ? DV_625 : DVCPRO_625;
            }
            return application == 0 ? DV_525 : DVCPRO_525;
        }
        return null;
    }

    /**
     * Returns whether a frame of this profile displays at 16:9, from the display select mode of its VAUX source
     * control pack.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return {@code true} for 16:9 (always for the DVCPRO HD formats), {@code false} for 4:3 or when the frame has
     * no source control pack
     */
    public boolean wide(byte[] data, int offset, int length) {
        if (highDefinition()) {
            return true;
        }
        var control = findPack(data, offset, Math.min(length, SEQUENCE), PACK_VAUX_CONTROL, SECTION_VAUX);
        if (control < 0) {
            return false;
        }
        var display = data[control + 2] & 0x07;
        return display == 1 || display == 2;
    }

    /**
     * Finds a pack in the VAUX or AAUX blocks of a range of a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the first byte of the range, the start of a DIF block
     * @param length the number of bytes of the range
     * @param header the pack header byte
     * @param section the section type of the blocks to search, {@link #SECTION_VAUX} or {@link #SECTION_AUDIO}
     * @return the index of the pack's header byte, or -1 when no block of the range holds it
     */
    public static int findPack(byte[] data, int offset, int length, int header, int section) {
        for (var block = offset; block + BLOCK <= offset + length; block += BLOCK) {
            if (((data[block] & 0xFF) >>> 5) != section) {
                continue;
            }
            if (section == SECTION_AUDIO) {
                if ((data[block + 3] & 0xFF) == header) {
                    return block + 3;
                }
                continue;
            }
            for (var pack = block + 3; pack + 5 <= block + 78; pack += 5) {
                if ((data[pack] & 0xFF) == header) {
                    return pack;
                }
            }
        }
        return -1;
    }

    /**
     * Returns the index of a block of a frame.
     *
     * @param channel the DIF channel
     * @param sequence the DIF sequence within the channel
     * @param block the block within the sequence, from 0 to 149
     * @return the byte offset of the block from the start of the frame
     */
    public int blockOffset(int channel, int sequence, int block) {
        return ((channel * sequences + sequence) * BLOCKS_PER_SEQUENCE + block) * BLOCK;
    }

    /**
     * Returns the position of a video block within a DIF sequence.
     *
     * @param video the video block number, from 0 to 134
     * @return the block number within the sequence
     */
    public static int videoBlock(int video) {
        return 6 + (video / 15) * 16 + 1 + video % 15;
    }

    /**
     * Returns the position of an audio block within a DIF sequence.
     *
     * @param audio the audio block number, from 0 to 8
     * @return the block number within the sequence
     */
    public static int audioBlock(int audio) {
        return 6 + audio * 16;
    }
}
