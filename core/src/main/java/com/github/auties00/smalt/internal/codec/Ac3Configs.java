package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the headers of AC-3 and E-AC-3 sync frames and builds and parses the {@code dac3} and
 * {@code dec3} boxes of the MP4 {@code ac-3} and {@code ec-3} sample entries.
 *
 * <p>Frame headers follow ATSC A/52 sections 5.3.1 and 5.3.2 (AC-3, {@code bsid} up to 10) and
 * Annex E section 2.2.2 (E-AC-3, {@code bsid} 11 to 16). The boxes follow ETSI TS 102 366 Annex F
 * with the object audio extension of ETSI TS 103 420 Annex C. An access unit is one AC-3 sync
 * frame, or an independent E-AC-3 frame of substream 0 (or an AC-3 frame) followed by the frames of
 * its dependent substreams and of the other programs, as MP4, Matroska and MPEG-TS store them.
 *
 * <p>The public tables are shared with the AC-3 codec and are never modified.
 */
public final class Ac3Configs {
    /**
     * The AC-3 frame sizes in 16-bit words by {@code frmsizecod} and {@code fscod} (A/52 table
     * 5.18).
     */
    public static final int[][] FRAME_WORDS = {
            {64, 69, 96}, {64, 70, 96}, {80, 87, 120}, {80, 88, 120}, {96, 104, 144}, {96, 105, 144},
            {112, 121, 168}, {112, 122, 168}, {128, 139, 192}, {128, 140, 192}, {160, 174, 240}, {160, 175, 240},
            {192, 208, 288}, {192, 209, 288}, {224, 243, 336}, {224, 244, 336}, {256, 278, 384}, {256, 279, 384},
            {320, 348, 480}, {320, 349, 480}, {384, 417, 576}, {384, 418, 576}, {448, 487, 672}, {448, 488, 672},
            {512, 557, 768}, {512, 558, 768}, {640, 696, 960}, {640, 697, 960}, {768, 835, 1152},
            {768, 836, 1152}, {896, 975, 1344}, {896, 976, 1344}, {1024, 1114, 1536}, {1024, 1115, 1536},
            {1152, 1253, 1728}, {1152, 1254, 1728}, {1280, 1393, 1920}, {1280, 1394, 1920}};

    /**
     * The nominal AC-3 bit rates in kbit/s by {@code frmsizecod / 2}.
     */
    public static final int[] BITRATES = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448,
            512, 576, 640};

    /**
     * The sampling rates by {@code fscod}.
     */
    public static final int[] RATES = {48000, 44100, 32000};

    /**
     * The audio blocks of an E-AC-3 frame by {@code numblkscod}.
     */
    public static final int[] BLOCKS = {1, 2, 3, 6};

    /**
     * The full-bandwidth speakers by audio coding mode, in coded order (A/52 table 5.8).
     */
    public static final Speaker[][] MODES = {
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT},
            {Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT}};

    /**
     * The speakers of each custom channel map location of a dependent substream (A/52 table
     * E2.5), location 0 first.
     */
    public static final Speaker[][] LOCATIONS = {
            {Speaker.FRONT_LEFT}, {Speaker.FRONT_CENTER}, {Speaker.FRONT_RIGHT}, {Speaker.SIDE_LEFT},
            {Speaker.SIDE_RIGHT}, {Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER},
            {Speaker.BACK_LEFT, Speaker.BACK_RIGHT}, {Speaker.BACK_CENTER}, {Speaker.TOP_CENTER},
            {Speaker.SURROUND_DIRECT_LEFT, Speaker.SURROUND_DIRECT_RIGHT}, {Speaker.WIDE_LEFT, Speaker.WIDE_RIGHT},
            {Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT}, {Speaker.TOP_FRONT_CENTER},
            {Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT}, {Speaker.LOW_FREQUENCY_2}, {Speaker.LOW_FREQUENCY}};

    /**
     * The custom channel map location of each bit of the {@code chan_loc} field of the
     * {@code dec3} box (ETSI TS 102 366 table F.6.1), bit 0 first.
     */
    private static final int[] CHANNEL_LOCATIONS = {5, 6, 7, 8, 9, 10, 11, 12, 14};

    /**
     * Prevents instantiation.
     */
    private Ac3Configs() {
        throw new AssertionError();
    }

    /**
     * Describes one sync frame.
     *
     * @param enhanced whether the frame uses the E-AC-3 syntax
     * @param length the frame length in bytes
     * @param sampleRate the sampling rate
     * @param blocks the audio blocks of the frame
     * @param fscod the sample rate code
     * @param bsid the bit stream identification
     * @param bsmod the bit stream mode
     * @param acmod the audio coding mode
     * @param lfe whether the LFE channel is present
     * @param streamType the E-AC-3 stream type, {@code 0} for AC-3
     * @param substreamId the E-AC-3 substream identification
     * @param channelMap the custom channel map of a dependent substream, or {@code -1}
     * @param frmsizecod the AC-3 frame size code, or {@code -1}
     * @param dialnorm the dialogue normalization value, from 1 to 31
     * @param objectAudio whether the frame signals the E-AC-3 object audio extension
     * @param objectComplexity the complexity index of the object audio extension
     */
    public record Frame(boolean enhanced, int length, int sampleRate, int blocks, int fscod, int bsid, int bsmod,
                        int acmod, boolean lfe, int streamType, int substreamId, int channelMap, int frmsizecod,
                        int dialnorm, boolean objectAudio, int objectComplexity) {
        /**
         * Returns whether the frame starts a program: an AC-3 frame or an independent E-AC-3 frame.
         *
         * @return {@code true} for an independent frame
         */
        public boolean independent() {
            return !enhanced || streamType != 1;
        }

        /**
         * Returns the speakers of the frame's coded channels, LFE last.
         *
         * @return the speakers
         */
        public Speaker[] speakers() {
            var coded = MODES[acmod].length + (lfe ? 1 : 0);
            if (channelMap >= 0) {
                var speakers = new ArrayList<Speaker>();
                for (var bit = 0; bit < 16; bit++) {
                    if ((channelMap & (0x8000 >>> bit)) != 0) {
                        speakers.addAll(List.of(LOCATIONS[bit]));
                    }
                }
                if (speakers.size() == coded) {
                    return speakers.toArray(Speaker[]::new);
                }
            }
            var speakers = new Speaker[coded];
            System.arraycopy(MODES[acmod], 0, speakers, 0, MODES[acmod].length);
            if (lfe) {
                speakers[coded - 1] = Speaker.LOW_FREQUENCY;
            }
            return speakers;
        }
    }

    /**
     * Describes the program an access unit carries.
     *
     * @param codec {@link Codec#AC3}, or {@link Codec#EAC3} when any frame of the unit uses the E-AC-3 syntax
     * @param sampleRate the sampling rate
     * @param layout the layout of every channel of program 0, dependent substreams included, in WAVE order
     * @param samples the samples per channel the unit codes
     * @param bitrate the bit rate of the unit in bits per second
     * @param dialogueLevel the dialogue level of program 0 in dBFS, from -31 to -1
     * @param objectAudio whether a frame of the unit signals object audio (Dolby Atmos joint object coding)
     * @param objectComplexity the complexity index of the object audio extension
     */
    public record Stream(Codec codec, int sampleRate, ChannelLayout layout, int samples, long bitrate,
                         int dialogueLevel, boolean objectAudio, int objectComplexity) {
    }

    /**
     * Returns the length of the sync frame starting at an offset.
     *
     * @param data the bytes
     * @param offset the offset of the candidate sync word
     * @param available the bytes available from the offset
     * @return the frame length, {@code 0} when more bytes are needed to tell, or {@code -1} when the bytes do not start
     * a valid frame
     */
    public static int frameLength(byte[] data, int offset, int available) {
        if (available < 6) {
            return available >= 2 && (data[offset] != 0x0B || data[offset + 1] != 0x77) ? -1 : 0;
        }
        if (data[offset] != 0x0B || data[offset + 1] != 0x77) {
            return -1;
        }
        var bsid = (data[offset + 5] & 0xFF) >> 3;
        if (bsid <= 10) {
            var fscod = (data[offset + 4] & 0xFF) >> 6;
            var frmsizecod = data[offset + 4] & 0x3F;
            if (fscod == 3 || frmsizecod >= 38) {
                return -1;
            }
            return FRAME_WORDS[frmsizecod][fscod] * 2;
        }
        if (bsid > 16 || ((data[offset + 2] & 0xFF) >> 6) == 3) {
            return -1;
        }
        var length = ((((data[offset + 2] & 0x07) << 8) | (data[offset + 3] & 0xFF)) + 1) * 2;
        return length < 8 ? -1 : length;
    }

    /**
     * Returns whether the sync frame whose header starts at an offset starts an access unit.
     *
     * @param data the bytes, holding at least six from {@code offset}
     * @param offset the offset of the sync word
     * @return {@code true} for an AC-3 frame or an independent E-AC-3 frame of substream 0
     */
    public static boolean startsAccessUnit(byte[] data, int offset) {
        if ((data[offset + 5] & 0xFF) >> 3 <= 10) {
            return true;
        }
        return ((data[offset + 2] & 0xFF) >> 6) != 1 && ((data[offset + 2] >> 3) & 0x07) == 0;
    }

    /**
     * Parses the header of the sync frame starting at an offset.
     *
     * @param data the bytes
     * @param offset the offset of the sync word
     * @param length the bytes available from the offset
     * @return the frame, or {@code null} when the bytes do not start a valid frame that fits
     */
    public static Frame parse(byte[] data, int offset, int length) {
        var frameLength = frameLength(data, offset, length);
        if (frameLength <= 0 || frameLength > length) {
            return null;
        }
        var reader = new MsbBitReader(data, offset, frameLength);
        reader.skip(16);
        var bsid = (data[offset + 5] & 0xFF) >> 3;
        Frame frame;
        if (bsid <= 10) {
            frame = parseAc3(reader, frameLength, bsid);
        } else {
            frame = parseEac3(reader, frameLength, bsid);
        }
        return reader.overrun() ? null : frame;
    }

    /**
     * Parses the bit stream information of an AC-3 frame.
     *
     * @param reader the reader, after the sync word
     * @param length the frame length
     * @param bsid the bit stream identification
     * @return the frame
     */
    private static Frame parseAc3(MsbBitReader reader, int length, int bsid) {
        reader.skip(16);
        var fscod = reader.read(2);
        var frmsizecod = reader.read(6);
        reader.skip(5);
        var bsmod = reader.read(3);
        var acmod = reader.read(3);
        if ((acmod & 1) != 0 && acmod != 1) {
            reader.skip(2);
        }
        if ((acmod & 4) != 0) {
            reader.skip(2);
        }
        if (acmod == 2) {
            reader.skip(2);
        }
        var lfe = reader.read(1) != 0;
        var dialnorm = reader.read(5);
        var rate = RATES[fscod] >> Math.max(0, bsid - 8);
        return new Frame(false, length, rate, 6, fscod, bsid, bsmod, acmod, lfe, 0, 0, -1, frmsizecod,
                dialnorm == 0 ? 31 : dialnorm, false, 0);
    }

    /**
     * Parses the bit stream information of an E-AC-3 frame, up to the additional information that
     * may carry the object audio extension.
     *
     * @param reader the reader, after the sync word
     * @param length the frame length
     * @param bsid the bit stream identification
     * @return the frame
     */
    private static Frame parseEac3(MsbBitReader reader, int length, int bsid) {
        var streamType = reader.read(2);
        var substreamId = reader.read(3);
        reader.skip(11);
        var fscod = reader.read(2);
        int rate;
        int numblkscod;
        if (fscod == 3) {
            var reduced = reader.read(2);
            rate = reduced == 3 ? 0 : RATES[reduced] / 2;
            numblkscod = 3;
        } else {
            rate = RATES[fscod];
            numblkscod = reader.read(2);
        }
        var acmod = reader.read(3);
        var lfe = reader.read(1) != 0;
        reader.skip(5);
        var dialnorm = reader.read(5);
        if (reader.read(1) != 0) {
            reader.skip(8);
        }
        if (acmod == 0) {
            reader.skip(5);
            if (reader.read(1) != 0) {
                reader.skip(8);
            }
        }
        var channelMap = -1;
        if (streamType == 1 && reader.read(1) != 0) {
            channelMap = reader.read(16);
        }
        if (reader.read(1) != 0) {
            skipMixing(reader, acmod, lfe, streamType, numblkscod);
        }
        var bsmod = 0;
        if (reader.read(1) != 0) {
            bsmod = reader.read(3);
            reader.skip(2);
            if (acmod == 2) {
                reader.skip(4);
            }
            if (acmod >= 6) {
                reader.skip(2);
            }
            if (reader.read(1) != 0) {
                reader.skip(8);
            }
            if (acmod == 0 && reader.read(1) != 0) {
                reader.skip(8);
            }
            if (fscod < 3) {
                reader.skip(1);
            }
        }
        if (streamType == 0 && numblkscod != 3) {
            reader.skip(1);
        }
        if (streamType == 2 && (numblkscod == 3 || reader.read(1) != 0)) {
            reader.skip(6);
        }
        var objectAudio = false;
        var complexity = 0;
        if (reader.read(1) != 0) {
            var additional = reader.read(6) + 1;
            if (additional >= 2) {
                reader.skip(7);
                objectAudio = reader.read(1) != 0;
                complexity = reader.read(8);
            }
        }
        return new Frame(true, length, rate, BLOCKS[numblkscod], fscod == 3 ? 3 : fscod, bsid, bsmod, acmod, lfe,
                streamType, substreamId, channelMap, -1, dialnorm == 0 ? 31 : dialnorm, objectAudio, complexity);
    }

    /**
     * Skips the mixing metadata of an E-AC-3 frame.
     *
     * @param reader the header reader, positioned at the mixing metadata
     * @param acmod the audio coding mode
     * @param lfe whether the LFE channel is present
     * @param streamType the stream type
     * @param numblkscod the block count code
     */
    private static void skipMixing(MsbBitReader reader, int acmod, boolean lfe, int streamType, int numblkscod) {
        if (acmod > 2) {
            reader.skip(2);
        }
        if ((acmod & 1) != 0 && acmod > 2) {
            reader.skip(6);
        }
        if ((acmod & 4) != 0) {
            reader.skip(6);
        }
        if (lfe && reader.read(1) != 0) {
            reader.skip(5);
        }
        if (streamType != 0) {
            return;
        }
        if (reader.read(1) != 0) {
            reader.skip(6);
        }
        if (acmod == 0 && reader.read(1) != 0) {
            reader.skip(6);
        }
        if (reader.read(1) != 0) {
            reader.skip(6);
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
                for (var block = 0; block < BLOCKS[numblkscod]; block++) {
                    if (reader.read(1) != 0) {
                        reader.skip(5);
                    }
                }
            }
        }
    }

    /**
     * Parses every frame of an access unit.
     *
     * @param data the bytes
     * @param offset the offset of the unit
     * @param length the length of the unit
     * @return the frames, in order; frames that cannot be parsed end the list
     */
    public static List<Frame> frames(byte[] data, int offset, int length) {
        var frames = new ArrayList<Frame>();
        var position = offset;
        var end = offset + length;
        while (position < end) {
            var frame = parse(data, position, end - position);
            if (frame == null) {
                break;
            }
            frames.add(frame);
            position += frame.length();
        }
        return frames;
    }

    /**
     * Describes the program 0 of an access unit.
     *
     * @param data the bytes
     * @param offset the offset of the unit
     * @param length the length of the unit
     * @return the description, or {@code null} when the unit does not start with a valid independent frame
     */
    public static Stream describe(byte[] data, int offset, int length) {
        var frames = frames(data, offset, length);
        if (frames.isEmpty() || !frames.getFirst().independent()) {
            return null;
        }
        var first = frames.getFirst();
        var speakers = new ArrayList<Speaker>(List.of(first.speakers()));
        var enhanced = first.enhanced();
        var objects = first.objectAudio();
        var complexity = first.objectComplexity();
        var bytes = (long) first.length();
        for (var i = 1; i < frames.size(); i++) {
            var frame = frames.get(i);
            if (frame.independent()) {
                break;
            }
            enhanced = true;
            objects |= frame.objectAudio();
            complexity = Math.max(complexity, frame.objectComplexity());
            bytes += frame.length();
            for (var speaker : frame.speakers()) {
                if (speaker == Speaker.UNKNOWN || !speakers.contains(speaker)) {
                    speakers.add(speaker);
                }
            }
        }
        var ordered = new ArrayList<Speaker>();
        for (var speaker : Speaker.values()) {
            for (var candidate : speakers) {
                if (candidate == speaker) {
                    ordered.add(candidate);
                }
            }
        }
        var samples = 256 * first.blocks();
        var bitrate = first.sampleRate() > 0 ? bytes * 8 * first.sampleRate() / samples : 0;
        return new Stream(enhanced ? Codec.EAC3 : Codec.AC3, first.sampleRate(), new ChannelLayout(ordered), samples,
                bitrate, -first.dialnorm(), objects, complexity);
    }

    /**
     * Builds the body of an {@code AC3SpecificBox} ({@code dac3}) from an AC-3 frame.
     *
     * @param frame the first frame of the stream
     * @return the 3-byte body
     */
    public static byte[] dac3(Frame frame) {
        var writer = new MsbBitWriter(3);
        writer.write(frame.fscod(), 2);
        writer.write(frame.bsid(), 5);
        writer.write(frame.bsmod(), 3);
        writer.write(frame.acmod(), 3);
        writer.write(frame.lfe() ? 1 : 0, 1);
        writer.write(Math.max(0, frame.frmsizecod()) >> 1, 5);
        writer.write(0, 5);
        return writer.toByteArray();
    }

    /**
     * Builds the body of an {@code EC3SpecificBox} ({@code dec3}) from an access unit.
     *
     * <p>Every independent substream of the unit is described, with the channel locations its
     * dependent substreams add; the object audio extension is appended when a frame signals it.
     *
     * @param data the bytes
     * @param offset the offset of the unit
     * @param length the length of the unit
     * @return the body, or {@code null} when the unit holds no valid independent frame
     */
    public static byte[] dec3(byte[] data, int offset, int length) {
        var frames = frames(data, offset, length);
        if (frames.isEmpty() || !frames.getFirst().independent()) {
            return null;
        }
        var programs = new ArrayList<List<Frame>>();
        var dataRate = 0L;
        var objects = false;
        var complexity = 0;
        for (var frame : frames) {
            if (frame.independent()) {
                if (programs.size() == 8) {
                    break;
                }
                programs.add(new ArrayList<>());
            }
            programs.getLast().add(frame);
            if (frame.sampleRate() > 0) {
                dataRate += (long) frame.length() * 8 * frame.sampleRate() / (256L * frame.blocks()) / 1000;
            }
            objects |= frame.objectAudio();
            complexity = Math.max(complexity, frame.objectComplexity());
        }
        var writer = new MsbBitWriter(16);
        writer.write((int) Math.min(8191, dataRate), 13);
        writer.write(programs.size() - 1, 3);
        for (var program : programs) {
            var independent = program.getFirst();
            writer.write(Math.min(independent.fscod(), 3), 2);
            writer.write(independent.bsid(), 5);
            writer.write(0, 1);
            writer.write(0, 1);
            writer.write(independent.bsmod(), 3);
            writer.write(independent.acmod(), 3);
            writer.write(independent.lfe() ? 1 : 0, 1);
            writer.write(0, 3);
            var dependents = program.size() - 1;
            writer.write(dependents, 4);
            if (dependents > 0) {
                var locations = 0;
                for (var i = 1; i < program.size(); i++) {
                    var map = program.get(i).channelMap();
                    for (var bit = 0; bit < CHANNEL_LOCATIONS.length && map >= 0; bit++) {
                        if ((map & (0x8000 >>> CHANNEL_LOCATIONS[bit])) != 0) {
                            locations |= 1 << bit;
                        }
                    }
                }
                writer.write(locations, 9);
            } else {
                writer.write(0, 1);
            }
        }
        if (objects) {
            writer.write(0, 7);
            writer.write(1, 1);
            writer.write(complexity, 8);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Builds the body of a {@code dac3} or {@code dec3} box for a track without samples, from its
     * declared sampling rate and layout.
     *
     * @param enhanced whether to build a {@code dec3} body
     * @param sampleRate the sampling rate; rates other than 32, 44.1 and 48 kHz are declared as 48 kHz
     * @param layout the layout, or {@code null} for stereo
     * @return the body
     */
    public static byte[] declared(boolean enhanced, int sampleRate, ChannelLayout layout) {
        var fscod = sampleRate == 44100 ? 1 : sampleRate == 32000 ? 2 : 0;
        var acmod = 2;
        var lfe = false;
        if (layout != null) {
            var speakers = layout.speakers();
            var center = speakers.contains(Speaker.FRONT_CENTER);
            lfe = speakers.contains(Speaker.LOW_FREQUENCY);
            if (!speakers.contains(Speaker.FRONT_LEFT)) {
                acmod = 1;
            } else if (speakers.contains(Speaker.SIDE_LEFT) || speakers.contains(Speaker.BACK_LEFT)) {
                acmod = center ? 7 : 6;
            } else if (speakers.contains(Speaker.BACK_CENTER)) {
                acmod = center ? 5 : 4;
            } else {
                acmod = center ? 3 : 2;
            }
        }
        if (!enhanced) {
            return dac3(new Frame(false, 0, sampleRate, 6, fscod, 8, 0, acmod, lfe, 0, 0, -1, 0, 31, false, 0));
        }
        var writer = new MsbBitWriter(5);
        writer.write(0, 13);
        writer.write(0, 3);
        writer.write(fscod, 2);
        writer.write(16, 5);
        writer.write(0, 5);
        writer.write(acmod, 3);
        writer.write(lfe ? 1 : 0, 1);
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(0, 1);
        return writer.toByteArray();
    }

    /**
     * Describes a stream from the body of an {@code AC3SpecificBox}.
     *
     * @param body the box payload after its header, or {@code null}
     * @return the description, or {@code null} for a truncated or invalid body
     */
    public static Stream parseDac3(byte[] body) {
        if (body == null || body.length < 3) {
            return null;
        }
        var reader = new MsbBitReader(body);
        var fscod = reader.read(2);
        var bsid = reader.read(5);
        reader.skip(3);
        var acmod = reader.read(3);
        var lfe = reader.read(1) != 0;
        var bitRateCode = reader.read(5);
        if (fscod == 3 || bitRateCode >= BITRATES.length) {
            return null;
        }
        var rate = RATES[fscod] >> Math.max(0, bsid - 8);
        return new Stream(Codec.AC3, rate, layout(acmod, lfe, 0), 1536, BITRATES[bitRateCode] * 1000L, -31, false, 0);
    }

    /**
     * Describes the first program of a stream from the body of an {@code EC3SpecificBox}.
     *
     * @param body the box payload after its header, or {@code null}
     * @return the description, or {@code null} for a truncated or invalid body
     */
    public static Stream parseDec3(byte[] body) {
        if (body == null || body.length < 5) {
            return null;
        }
        var reader = new MsbBitReader(body);
        var dataRate = reader.read(13);
        var programs = reader.read(3) + 1;
        ChannelLayout layout = null;
        var rate = 0;
        for (var program = 0; program < programs; program++) {
            var fscod = reader.read(2);
            reader.skip(5 + 1 + 1 + 3);
            var acmod = reader.read(3);
            var lfe = reader.read(1) != 0;
            reader.skip(3);
            var dependents = reader.read(4);
            var locations = 0;
            if (dependents > 0) {
                locations = reader.read(9);
            } else {
                reader.skip(1);
            }
            if (program == 0) {
                rate = fscod == 3 ? 0 : RATES[fscod];
                layout = layout(acmod, lfe, locations);
            }
        }
        var objects = false;
        var complexity = 0;
        if (reader.bitsLeft() >= 16) {
            reader.skip(7);
            objects = reader.read(1) != 0;
            complexity = reader.read(8);
        }
        if (reader.overrun() || layout == null) {
            return null;
        }
        return new Stream(Codec.EAC3, rate, layout, 1536, dataRate * 1000L, -31, objects, complexity);
    }

    /**
     * Returns the layout of an audio coding mode with the channel locations of dependent
     * substreams, in WAVE order.
     *
     * @param acmod the audio coding mode
     * @param lfe whether the LFE channel is present
     * @param locations the {@code chan_loc} bits of the dependent substreams
     * @return the layout
     */
    public static ChannelLayout layout(int acmod, boolean lfe, int locations) {
        var speakers = new ArrayList<Speaker>(List.of(MODES[acmod]));
        if (lfe) {
            speakers.add(Speaker.LOW_FREQUENCY);
        }
        for (var bit = 0; bit < CHANNEL_LOCATIONS.length; bit++) {
            if ((locations & (1 << bit)) != 0) {
                for (var speaker : LOCATIONS[CHANNEL_LOCATIONS[bit]]) {
                    if (speaker == Speaker.UNKNOWN || !speakers.contains(speaker)) {
                        speakers.add(speaker);
                    }
                }
            }
        }
        speakers.sort(null);
        return new ChannelLayout(speakers);
    }
}
