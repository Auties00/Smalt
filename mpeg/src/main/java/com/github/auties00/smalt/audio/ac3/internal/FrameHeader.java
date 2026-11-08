package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.Ac3Configs;

/**
 * Holds the synchronization information and bit stream information of one AC-3 or E-AC-3 sync
 * frame, parsed by {@link #parse(MsbBitReader, int)}.
 *
 * <p>AC-3 frames ({@code bsid} up to 10) follow A/52 sections 5.3.1 and 5.3.2 and, for
 * {@code bsid} 6, the alternate syntax of Annex D; E-AC-3 frames ({@code bsid} 11 to 16) follow
 * Annex E section 2.2.2. The fields keep their specification names in camel case; fields a syntax
 * lacks hold the value the specification implies. An instance is reused from frame to frame.
 * Instances are not thread-safe.
 */
public final class FrameHeader {
    /**
     * Whether the frame uses the E-AC-3 syntax.
     */
    public boolean enhanced;

    /**
     * The bit stream identification {@code bsid}.
     */
    public int bsid;

    /**
     * The stream type {@code strmtyp}: 0 independent, 1 dependent, 2 converted from AC-3; always 0
     * for AC-3.
     */
    public int streamType;

    /**
     * The substream identification {@code substreamid}.
     */
    public int substreamId;

    /**
     * The frame size in bytes.
     */
    public int frameBytes;

    /**
     * The sample rate code {@code fscod}.
     */
    public int fscod;

    /**
     * The row of the hearing threshold table: {@code fscod}, or {@code fscod2} at a reduced rate.
     */
    public int rateIndex;

    /**
     * The sampling rate in hertz.
     */
    public int sampleRate;

    /**
     * The number of audio blocks in the frame.
     */
    public int blocks;

    /**
     * The block count code {@code numblkscod}; 3 for AC-3.
     */
    public int numblkscod;

    /**
     * The frame size code {@code frmsizecod} of AC-3, or {@code -1}.
     */
    public int frmsizecod;

    /**
     * The bit stream mode {@code bsmod}.
     */
    public int bsmod;

    /**
     * The audio coding mode {@code acmod}.
     */
    public int acmod;

    /**
     * The number of full-bandwidth channels {@code nfchans}.
     */
    public int channels;

    /**
     * Whether the low-frequency effects channel is present.
     */
    public boolean lfe;

    /**
     * The center mix level code {@code cmixlev} of AC-3, or {@code -1}.
     */
    public int centerMixLevel;

    /**
     * The surround mix level code {@code surmixlev} of AC-3, or {@code -1}.
     */
    public int surroundMixLevel;

    /**
     * The Dolby Surround mode {@code dsurmod}.
     */
    public int surroundMode;

    /**
     * The dialogue normalization {@code dialnorm} of each program channel (two in dual mono).
     */
    public final int[] dialogueNormalization = new int[2];

    /**
     * The heavy compression word {@code compr} of each program channel, or {@code -1} when absent.
     */
    public final int[] compression = new int[2];

    /**
     * The custom channel map {@code chanmap} of a dependent substream, or {@code -1}.
     */
    public int channelMap;

    /**
     * The preferred stereo downmix {@code dmixmod}, {@code 0} when not indicated.
     */
    public int downmixMode;

    /**
     * The Lt/Rt center mix level code {@code ltrtcmixlev}, or {@code -1}.
     */
    public int ltrtCenterMixLevel;

    /**
     * The Lt/Rt surround mix level code {@code ltrtsurmixlev}, or {@code -1}.
     */
    public int ltrtSurroundMixLevel;

    /**
     * The Lo/Ro center mix level code {@code lorocmixlev}, or {@code -1}.
     */
    public int loroCenterMixLevel;

    /**
     * The Lo/Ro surround mix level code {@code lorosurmixlev}, or {@code -1}.
     */
    public int loroSurroundMixLevel;

    /**
     * The LFE mix level code {@code lfemixlevcod}, or {@code -1} when the LFE is not mixed.
     */
    public int lfeMixLevel;

    /**
     * The copyright bit {@code copyrightb}, or {@code -1} when absent.
     */
    public int copyright;

    /**
     * The original bit stream bit {@code origbs}, or {@code -1} when absent.
     */
    public int original;

    /**
     * The Dolby Surround EX mode {@code dsurexmod}, or {@code -1} when absent.
     */
    public int surroundExMode;

    /**
     * The Dolby Headphone mode {@code dheadphonmod}, or {@code -1} when absent.
     */
    public int headphoneMode;

    /**
     * The converter type {@code adconvtyp}, or {@code -1} when absent.
     */
    public int converterType;

    /**
     * The mixing level code {@code mixlevel}, or {@code -1} when absent.
     */
    public int mixingLevel;

    /**
     * The room type {@code roomtyp}, or {@code -1} when absent.
     */
    public int roomType;

    /**
     * The program scale factor code {@code pgmscl}, or {@code -1} when absent.
     */
    public int programScale;

    /**
     * The external program scale factor code {@code extpgmscl}, or {@code -1} when absent.
     */
    public int externalProgramScale;

    /**
     * The source sample rate code {@code sourcefscod}, or {@code -1} when absent.
     */
    public int sourceRate;

    /**
     * Whether the frame carries E-AC-3 mixing metadata ({@code mixmdate}).
     */
    public boolean mixingMetadata;

    /**
     * Whether the frame carries E-AC-3 informational metadata ({@code infomdate}).
     */
    public boolean informationalMetadata;

    /**
     * Whether the {@code addbsi} field carries the E-AC-3 object audio extension flag
     * {@code flag_ec3_extension_type_a} (ETSI TS 103 420 section 8.3).
     */
    public boolean objectAudio;

    /**
     * The complexity index {@code complexity_index_type_a} of the object audio extension.
     */
    public int objectComplexity;

    /**
     * Creates an empty header.
     */
    public FrameHeader() {
    }

    /**
     * Parses the header of the frame at the reader's position, which must be the sync word.
     *
     * @param reader the reader, positioned on the sync word; left after the bit stream
     * information
     * @param available the bytes available from the sync word
     * @return {@code true} when the header is valid and the frame fits {@code available};
     * {@code false} for a bad sync word, a reserved code or a truncated frame
     */
    public boolean parse(MsbBitReader reader, int available) {
        if (reader.read(16) != 0x0B77) {
            return false;
        }
        var peek = reader.peek(32);
        var id = (peek >>> 3) & 0x1F;
        reset();
        bsid = id;
        if (id <= 10) {
            return parseAc3(reader, available);
        }
        if (id > 16) {
            return false;
        }
        return parseEac3(reader, available);
    }

    /**
     * Resets the optional fields before a parse.
     */
    private void reset() {
        centerMixLevel = -1;
        surroundMixLevel = -1;
        surroundMode = 0;
        compression[0] = -1;
        compression[1] = -1;
        dialogueNormalization[1] = 31;
        channelMap = -1;
        downmixMode = 0;
        ltrtCenterMixLevel = -1;
        ltrtSurroundMixLevel = -1;
        loroCenterMixLevel = -1;
        loroSurroundMixLevel = -1;
        lfeMixLevel = -1;
        copyright = -1;
        original = -1;
        surroundExMode = -1;
        headphoneMode = -1;
        converterType = -1;
        mixingLevel = -1;
        roomType = -1;
        programScale = -1;
        externalProgramScale = -1;
        sourceRate = -1;
        mixingMetadata = false;
        informationalMetadata = false;
        objectAudio = false;
        objectComplexity = 0;
        bsmod = 0;
        streamType = 0;
        substreamId = 0;
        frmsizecod = -1;
    }

    /**
     * Parses the AC-3 synchronization and bit stream information (A/52 sections 5.3.1 and 5.3.2,
     * Annex D for {@code bsid} 6).
     *
     * @param reader the reader, after the sync word
     * @param available the bytes available from the sync word
     * @return whether the header is valid
     */
    private boolean parseAc3(MsbBitReader reader, int available) {
        enhanced = false;
        reader.skip(16);
        fscod = reader.read(2);
        frmsizecod = reader.read(6);
        if (fscod == 3 || frmsizecod >= 38) {
            return false;
        }
        frameBytes = Ac3Configs.FRAME_WORDS[frmsizecod][fscod] * 2;
        if (frameBytes > available) {
            return false;
        }
        // bsid 9 and 10 are the half- and quarter-rate variants of AC-3.
        var shift = Math.max(0, bsid - 8);
        sampleRate = Ac3Configs.RATES[fscod] >> shift;
        rateIndex = fscod;
        blocks = 6;
        numblkscod = 3;
        reader.skip(5);
        bsmod = reader.read(3);
        acmod = reader.read(3);
        channels = Ac3Tables.CHANNELS[acmod];
        if ((acmod & 1) != 0 && acmod != 1) {
            centerMixLevel = reader.read(2);
        }
        if ((acmod & 4) != 0) {
            surroundMixLevel = reader.read(2);
        }
        if (acmod == 2) {
            surroundMode = reader.read(2);
        }
        lfe = reader.read(1) != 0;
        dialogueNormalization[0] = reader.read(5);
        if (reader.read(1) != 0) {
            compression[0] = reader.read(8);
        }
        if (reader.read(1) != 0) {
            reader.skip(8);
        }
        if (reader.read(1) != 0) {
            mixingLevel = reader.read(5);
            roomType = reader.read(2);
        }
        if (acmod == 0) {
            dialogueNormalization[1] = reader.read(5);
            if (reader.read(1) != 0) {
                compression[1] = reader.read(8);
            }
            if (reader.read(1) != 0) {
                reader.skip(8);
            }
            if (reader.read(1) != 0) {
                reader.skip(7);
            }
        }
        copyright = reader.read(1);
        original = reader.read(1);
        if (bsid == 6) {
            if (reader.read(1) != 0) {
                downmixMode = reader.read(2);
                ltrtCenterMixLevel = reader.read(3);
                ltrtSurroundMixLevel = reader.read(3);
                loroCenterMixLevel = reader.read(3);
                loroSurroundMixLevel = reader.read(3);
            }
            if (reader.read(1) != 0) {
                surroundExMode = reader.read(2);
                headphoneMode = reader.read(2);
                converterType = reader.read(1);
                reader.skip(9);
            }
        } else {
            if (reader.read(1) != 0) {
                reader.skip(14);
            }
            if (reader.read(1) != 0) {
                reader.skip(14);
            }
        }
        if (reader.read(1) != 0) {
            additionalInformation(reader);
        }
        return !reader.overrun();
    }

    /**
     * Parses the E-AC-3 bit stream information (A/52 Annex E section 2.2.2).
     *
     * @param reader the reader, after the sync word
     * @param available the bytes available from the sync word
     * @return whether the header is valid
     */
    private boolean parseEac3(MsbBitReader reader, int available) {
        enhanced = true;
        streamType = reader.read(2);
        substreamId = reader.read(3);
        frameBytes = (reader.read(11) + 1) * 2;
        if (streamType == 3 || frameBytes > available || frameBytes < 8) {
            return false;
        }
        fscod = reader.read(2);
        if (fscod == 3) {
            var reduced = reader.read(2);
            if (reduced == 3) {
                return false;
            }
            rateIndex = reduced;
            sampleRate = Ac3Tables.REDUCED_RATES[reduced];
            numblkscod = 3;
        } else {
            rateIndex = fscod;
            sampleRate = Ac3Configs.RATES[fscod];
            numblkscod = reader.read(2);
        }
        blocks = Ac3Configs.BLOCKS[numblkscod];
        acmod = reader.read(3);
        channels = Ac3Tables.CHANNELS[acmod];
        lfe = reader.read(1) != 0;
        reader.skip(5);
        dialogueNormalization[0] = reader.read(5);
        if (reader.read(1) != 0) {
            compression[0] = reader.read(8);
        }
        if (acmod == 0) {
            dialogueNormalization[1] = reader.read(5);
            if (reader.read(1) != 0) {
                compression[1] = reader.read(8);
            }
        }
        if (streamType == 1 && reader.read(1) != 0) {
            channelMap = reader.read(16);
        }
        if (reader.read(1) != 0) {
            mixingMetadata = true;
            mixingMetadata(reader);
        }
        if (reader.read(1) != 0) {
            informationalMetadata = true;
            bsmod = reader.read(3);
            copyright = reader.read(1);
            original = reader.read(1);
            if (acmod == 2) {
                surroundMode = reader.read(2);
                headphoneMode = reader.read(2);
            }
            if (acmod >= 6) {
                surroundExMode = reader.read(2);
            }
            if (reader.read(1) != 0) {
                mixingLevel = reader.read(5);
                roomType = reader.read(2);
                converterType = reader.read(1);
            }
            if (acmod == 0 && reader.read(1) != 0) {
                reader.skip(8);
            }
            if (fscod < 3) {
                sourceRate = reader.read(1);
            }
        }
        if (streamType == 0 && numblkscod != 3) {
            reader.skip(1);
        }
        if (streamType == 2) {
            var blockId = numblkscod == 3 || reader.read(1) != 0;
            if (blockId) {
                reader.skip(6);
            }
        }
        if (reader.read(1) != 0) {
            additionalInformation(reader);
        }
        return !reader.overrun();
    }

    /**
     * Parses the mixing metadata of an E-AC-3 frame (the {@code mixmdate} branch).
     *
     * @param reader the frame reader, at the first field of the branch
     */
    private void mixingMetadata(MsbBitReader reader) {
        if (acmod > 2) {
            downmixMode = reader.read(2);
        }
        if ((acmod & 1) != 0 && acmod > 2) {
            ltrtCenterMixLevel = reader.read(3);
            loroCenterMixLevel = reader.read(3);
        }
        if ((acmod & 4) != 0) {
            ltrtSurroundMixLevel = reader.read(3);
            loroSurroundMixLevel = reader.read(3);
        }
        if (lfe && reader.read(1) != 0) {
            lfeMixLevel = reader.read(5);
        }
        if (streamType != 0) {
            return;
        }
        if (reader.read(1) != 0) {
            programScale = reader.read(6);
        }
        if (acmod == 0 && reader.read(1) != 0) {
            reader.skip(6);
        }
        if (reader.read(1) != 0) {
            externalProgramScale = reader.read(6);
        }
        switch (reader.read(2)) {
            case 1 -> reader.skip(5);
            case 2 -> reader.skip(12);
            case 3 -> reader.skip(8L * (reader.read(5) + 2));
            default -> {
            }
        }
        if (acmod < 2) {
            if (reader.read(1) != 0) {
                reader.skip(14);
            }
            if (acmod == 0 && reader.read(1) != 0) {
                reader.skip(14);
            }
        }
        if (reader.read(1) != 0) {
            if (numblkscod == 0) {
                reader.skip(5);
            } else {
                for (var block = 0; block < blocks; block++) {
                    if (reader.read(1) != 0) {
                        reader.skip(5);
                    }
                }
            }
        }
    }

    /**
     * Parses the additional bit stream information, recognizing the object audio extension of
     * ETSI TS 103 420 section 8.3 (7 reserved bits, the extension flag, an 8-bit complexity
     * index) when the field holds at least two bytes.
     *
     * @param reader the reader, before {@code addbsil}
     */
    private void additionalInformation(MsbBitReader reader) {
        var length = reader.read(6) + 1;
        if (enhanced && length >= 2) {
            reader.skip(7);
            objectAudio = reader.read(1) != 0;
            objectComplexity = reader.read(8);
            reader.skip(8L * (length - 2));
            return;
        }
        reader.skip(8L * length);
    }
}
