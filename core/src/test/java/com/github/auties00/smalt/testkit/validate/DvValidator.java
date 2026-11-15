package com.github.auties00.smalt.testkit.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates raw DV streams (the DIF frames of IEC 61834-2 DV, SMPTE ST 314 DVCPRO and DVCPRO50 and SMPTE ST 370
 * DVCPRO HD), written from SMPTE ST 314:2005 clause 4 and SMPTE ST 370:2013 clause 3 without Smalt's DV code.
 *
 * <p>It checks that the file is whole frames of one size, that every DIF block carries the ID of its place (section
 * type, DIF sequence number, channel and block number, the DIF sequence layout of ST 314 figure 4), that the header
 * blocks' DIF sequence flag and the VAUX source pack agree with the frame size and with each other across the stream,
 * that each frame carries a VAUX source and source control pack, and that every AAUX source pack names a sampling
 * frequency and quantization DV defines, an audio frame size within the capacity of its audio blocks (1600 or 1602
 * samples at 48 kHz in 60 Hz systems, 1920 in 50 Hz systems, when the audio is locked) and the same values in every
 * block of its half of the channel.
 */
public final class DvValidator {
    /** The size of a DIF block in bytes. */
    private static final int BLOCK = 80;

    /** The DIF blocks of a DIF sequence. */
    private static final int BLOCKS = 150;

    /** The size of a DIF sequence in bytes. */
    private static final int SEQUENCE = BLOCK * BLOCKS;

    /** The least samples per audio frame for 48, 44.1 and 32 kHz, in 60 Hz systems and in 50 Hz systems. */
    private static final int[][] MIN_SAMPLES = {{1580, 1452, 1053}, {1896, 1742, 1264}};

    /** The sampling frequencies of the SMP codes 0 to 2. */
    private static final int[] RATES = {48000, 44100, 32000};

    private DvValidator() {
        throw new AssertionError();
    }

    /**
     * Describes one audio half-channel of the stream: the audio blocks of one half of the DIF sequences of a DIF
     * channel, which carry one pair at 16 bits (both halves together) or one pair each at 12 bits.
     *
     * @param channel the DIF channel, 0 to 3
     * @param half the half of the DIF sequences, 0 or 1
     * @param sampleRate the sampling frequency in hertz
     * @param twelveBit whether the samples are 12-bit nonlinear
     * @param samples the samples per channel the frames carry together
     */
    public record Audio(int channel, int half, int sampleRate, boolean twelveBit, long samples) {
    }

    /**
     * What validation learned of a stream.
     *
     * @param frames the number of frames
     * @param frameSize the size of a frame in bytes
     * @param difChannels the DIF channels of a frame
     * @param fiftyHz whether the stream belongs to a 50 Hz system
     * @param signalType the STYPE of the VAUX source pack
     * @param applicationId the track application ID of the header blocks
     * @param wide whether the first frame displays at 16:9
     * @param audio the audio half-channels that carry valid audio in the first frame of their channels, in channel and
     * half order
     */
    public record Summary(int frames, int frameSize, int difChannels, boolean fiftyHz, int signalType,
                          int applicationId, boolean wide, List<Audio> audio) {
    }

    /**
     * Validates a raw DV stream.
     *
     * @param data the stream
     * @param findings receives the problems
     * @return what was learned, or {@code null} when the first frame cannot be read
     */
    public static Summary validate(byte[] data, Findings findings) {
        if (data.length < SEQUENCE) {
            findings.add("file", "shorter than one DIF sequence");
            return null;
        }
        var fifty = (data[3] & 0x80) != 0;
        var sequences = fifty ? 12 : 10;
        var signalType = signalType(data, 0, findings, "frame 0");
        if (signalType < 0) {
            return null;
        }
        var difChannels = switch (signalType) {
            case 0x00 -> 1;
            case 0x04, 0x18 -> 2;
            case 0x14 -> 4;
            default -> {
                findings.add("frame 0 VAUX source pack", "STYPE " + Integer.toHexString(signalType)
                        + " is not a signal type of ST 314 or ST 370");
                yield 0;
            }
        };
        if (difChannels == 0) {
            return null;
        }
        var frameSize = difChannels * sequences * SEQUENCE;
        findings.require(data.length % frameSize == 0, "file", data.length + " bytes is not a whole number of "
                + frameSize + "-byte frames");
        var frames = data.length / frameSize;
        var applicationId = data[4] & 0x07;
        var progressive = signalType == 0x18;
        var audio = new LinkedHashMap<Long, long[]>();
        var formats = new LinkedHashMap<Long, int[]>();
        var wide = false;
        for (var frame = 0; frame < frames; frame++) {
            var offset = frame * frameSize;
            var where = "frame " + frame;
            var base = progressive && (data[offset + 1] & 0x04) == 0 ? 2 : 0;
            for (var channel = 0; channel < difChannels; channel++) {
                var absolute = base + channel;
                for (var sequence = 0; sequence < sequences; sequence++) {
                    var at = offset + (channel * sequences + sequence) * SEQUENCE;
                    checkSequence(data, at, sequence, absolute, signalType == 0x14 || progressive, findings,
                            where + " channel " + absolute + " sequence " + sequence);
                    findings.require(((data[at + 3] & 0x80) != 0) == fifty, where + " header",
                            "the DIF sequence flag changes within the stream");
                    findings.require((data[at + 4] & 0x07) == applicationId, where + " header",
                            "the track application ID changes within the stream");
                }
                for (var half = 0; half < 2; half++) {
                    var format = audioFormat(data, offset + channel * sequences * SEQUENCE, sequences, half, fifty,
                            findings, where + " channel " + absolute + " half " + half);
                    if (format != null) {
                        var key = (long) absolute << 1 | half;
                        var known = formats.putIfAbsent(key, format);
                        if (known != null) {
                            findings.require(known[0] == format[0] && known[1] == format[1], where + " channel "
                                    + absolute + " half " + half, "the audio format changes within the stream");
                        }
                        audio.computeIfAbsent(key, _ -> new long[1])[0] += format[2];
                    }
                }
            }
            var stype = signalType(data, offset, findings, where);
            findings.require(stype == signalType, where + " VAUX source pack", "the signal type changes from "
                    + Integer.toHexString(signalType) + " to " + Integer.toHexString(stype));
            var display = display(data, offset, findings, where);
            if (frame == 0) {
                wide = display == 1 || display == 2;
            }
        }
        var pairs = new ArrayList<Audio>();
        for (Map.Entry<Long, int[]> entry : formats.entrySet()) {
            var key = entry.getKey();
            pairs.add(new Audio((int) (key >> 1), (int) (key & 1), entry.getValue()[0], entry.getValue()[1] == 1,
                    audio.get(key)[0]));
        }
        return new Summary(frames, frameSize, difChannels, fifty, signalType, applicationId, wide, pairs);
    }

    /**
     * Checks the IDs of the blocks of one DIF sequence.
     *
     * @param data the stream
     * @param at the offset of the sequence
     * @param sequence the DIF sequence number expected
     * @param channel the DIF channel expected
     * @param highDefinition whether the FSP bit identifies the channel too (ST 370)
     * @param findings receives the problems
     * @param where the location
     */
    private static void checkSequence(byte[] data, int at, int sequence, int channel, boolean highDefinition,
                                      Findings findings, String where) {
        for (var block = 0; block < BLOCKS; block++) {
            int section;
            int number;
            if (block == 0) {
                section = 0;
                number = 0;
            } else if (block < 3) {
                section = 1;
                number = block - 1;
            } else if (block < 6) {
                section = 2;
                number = block - 3;
            } else if ((block - 6) % 16 == 0) {
                section = 3;
                number = (block - 6) / 16;
            } else {
                section = 4;
                number = (block - 6) / 16 * 15 + (block - 6) % 16 - 1;
            }
            var id = at + block * BLOCK;
            var sct = (data[id] & 0xFF) >>> 5;
            var dseq = (data[id + 1] & 0xFF) >>> 4;
            var fsc = (data[id + 1] >>> 3) & 1;
            var fsp = (data[id + 1] >>> 2) & 1;
            var dbn = data[id + 2] & 0xFF;
            if (sct != section || dseq != sequence || dbn != number || fsc != (channel & 1)
                    || highDefinition && fsp != (channel < 2 ? 1 : 0)) {
                findings.add(where + " block " + block, "ID " + String.format("%02X %02X %02X", data[id],
                        data[id + 1], data[id + 2]) + " where section " + section + ", block number " + number
                        + " of channel " + channel + " belongs");
                return;
            }
        }
    }

    /**
     * Returns the STYPE of the VAUX source pack of a frame's first DIF sequence.
     *
     * @param data the stream
     * @param offset the offset of the frame
     * @param findings receives the problems
     * @param where the location
     * @return the signal type, or -1 when the frame has no VAUX source pack
     */
    private static int signalType(byte[] data, int offset, Findings findings, String where) {
        var pack = vauxPack(data, offset, 0x60);
        if (pack < 0) {
            findings.add(where, "no VAUX source pack");
            return -1;
        }
        var fifty = (data[pack + 3] & 0x20) != 0;
        findings.require(fifty == ((data[offset + 3] & 0x80) != 0), where + " VAUX source pack",
                "the 50/60 flag disagrees with the DIF sequence flag");
        return data[pack + 3] & 0x1F;
    }

    /**
     * Returns the display select mode of the VAUX source control pack of a frame's first DIF sequence.
     *
     * @param data the stream
     * @param offset the offset of the frame
     * @param findings receives the problems
     * @param where the location
     * @return the DISP code, or -1 when the frame has no source control pack
     */
    private static int display(byte[] data, int offset, Findings findings, String where) {
        var pack = vauxPack(data, offset, 0x61);
        if (pack < 0) {
            findings.add(where, "no VAUX source control pack");
            return -1;
        }
        var display = data[pack + 2] & 0x07;
        findings.require(display <= 2, where + " VAUX source control pack", "DISP " + display + " is reserved");
        return display;
    }

    /**
     * Returns the offset of the first pack of a type in the VAUX blocks of a frame's first DIF sequence.
     *
     * @param data the stream
     * @param offset the offset of the frame
     * @param header the pack header
     * @return the offset of the pack's header byte, or -1
     */
    private static int vauxPack(byte[] data, int offset, int header) {
        for (var block = 3; block < 6; block++) {
            for (var pack = 0; pack < 15; pack++) {
                var at = offset + block * BLOCK + 3 + 5 * pack;
                if ((data[at] & 0xFF) == header) {
                    return at;
                }
            }
        }
        return -1;
    }

    /**
     * Reads and checks the AAUX source packs of one half of a DIF channel's sequences.
     *
     * @param data the stream
     * @param channel the offset of the DIF channel's first sequence
     * @param sequences the DIF sequences of a channel
     * @param half the half
     * @param fifty whether the stream belongs to a 50 Hz system
     * @param findings receives the problems
     * @param where the location
     * @return the sampling frequency, the quantization (0 for 16 bits, 1 for 12) and the samples of the audio frame,
     * or {@code null} when the half carries no valid audio
     */
    private static int[] audioFormat(byte[] data, int channel, int sequences, int half, boolean fifty,
                                     Findings findings, String where) {
        int[] found = null;
        for (var sequence = half * sequences / 2; sequence < (half + 1) * sequences / 2; sequence++) {
            for (var block = 0; block < 9; block++) {
                var at = channel + sequence * SEQUENCE + (6 + 16 * block) * BLOCK + 3;
                if ((data[at] & 0xFF) != 0x50) {
                    continue;
                }
                var mode = data[at + 2] & 0x0F;
                if (mode == 0x0F) {
                    continue;
                }
                var locked = (data[at + 1] & 0x80) == 0;
                var size = data[at + 1] & 0x3F;
                findings.require(((data[at + 3] & 0x20) != 0) == fifty, where, "the AAUX 50/60 flag disagrees"
                        + " with the DIF sequence flag");
                var smp = (data[at + 4] >>> 3) & 0x07;
                var quantization = data[at + 4] & 0x07;
                if (smp > 2 || quantization > 1 || quantization == 1 && smp != 2) {
                    findings.add(where, "SMP " + smp + " with QU " + quantization + " is not an audio format of DV");
                    return null;
                }
                var samples = MIN_SAMPLES[fifty ? 1 : 0][smp] + size;
                var capacity = 9 * (sequences / 2) * (quantization == 1 ? 24 : 36);
                findings.require(samples <= capacity, where, samples + " samples exceed the " + capacity
                        + " the audio blocks hold");
                if (locked && smp == 0) {
                    findings.require(fifty ? samples == 1920 : samples == 1600 || samples == 1602, where,
                            samples + " samples in a locked 48 kHz audio frame");
                }
                var format = new int[]{RATES[smp], quantization, samples};
                if (found == null) {
                    found = format;
                } else if (found[0] != format[0] || found[1] != format[1] || found[2] != format[2]) {
                    findings.add(where, "the AAUX source packs of one audio block disagree");
                }
            }
        }
        return found;
    }
}
