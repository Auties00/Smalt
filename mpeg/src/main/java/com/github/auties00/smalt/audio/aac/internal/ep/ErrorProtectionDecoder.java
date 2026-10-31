package com.github.auties00.smalt.audio.aac.internal.ep;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the error protected frames ({@code ep_frame}) of the MPEG-4 audio error protection tool (ISO/IEC 14496-3
 * subclause 1.8) into the classes of the source coder frames they carry.
 *
 * <p>Decoding takes the frame header (the choice of pre-defined set, then the class attributes and the number of
 * stuffing bits), each protected by the basic set of FEC codes or by the extended SRCPC protection, out of the
 * interleaved frame, separates the encoded classes, corrects them (one SRCPC trellis over every SRCPC class in frame
 * order, Viterbi decoded; each shortened Reed-Solomon code separately), checks each class against its CRC and
 * returns the classes in output order. A source coder frame whose header cannot be decoded, whose lengths do not fit
 * the frame, or one of whose classes fails its CRC is reported as not intact.
 *
 * <p>The SRCPC code runs continuously over the SRCPC classes; a terminated class appends four tail steps whatever
 * its rate. The puncturing rate of step {@code t} is that of the class holding step {@code 8 floor(t/8)}, since the
 * puncturing pattern changes only where a period of 8 is complete; Reed-Solomon classes take no SRCPC steps, so the
 * steps and the trellis run on across them. Concatenated Reed-Solomon codes ({@code fec_type} 2) chain along the
 * original pre-defined set's frame order, absent classes included: an absent class of {@code fec_type} 2 passes the
 * code on, an absent class of another type ends it, and the code never takes in an SRCPC class. A class of length zero carries neither data nor CRC, and a class "until
 * the end" whose remaining length is zero is empty. When a Reed-Solomon code continues into the class until the
 * end, that class's length is the one for which the group's information and parity fill the bits that remain.
 *
 * <p>Instances keep every work buffer between frames and are not thread-safe.
 */
public final class ErrorProtectionDecoder {
    /**
     * The interleaving width of SRCPC coded parts.
     */
    private static final int SRCPC_WIDTH = 28;

    /**
     * The SRCPC rate index of the basic protection of headers longer than 16 bits (8/16).
     */
    private static final int HEADER_RATE = 8;

    /**
     * The CRC length of the basic protection of headers longer than 16 bits.
     */
    private static final int HEADER_CRC = 4;

    /**
     * The in-band SRCPC rate indices of {@code class_code_rate} (ISO/IEC 14496-3 Table 1.55).
     */
    private static final int[] IN_BAND_RATES = {0, 3, 4, 6, 8, 12, 16, 24};

    /**
     * The configuration.
     */
    private final ErrorProtectionConfig config;

    /**
     * The limits the work buffers are checked against.
     */
    private final MediaLimits limits;

    /**
     * The SRCPC decoder.
     */
    private final Srcpc srcpc = new Srcpc();

    /**
     * The Reed-Solomon decoder.
     */
    private final ReedSolomon reedSolomon = new ReedSolomon();

    /**
     * The frame bits, then each working copy, one bit per byte.
     */
    private byte[] frame = new byte[0];

    /**
     * The remainder of the frame after the stage being undone.
     */
    private byte[] rest = new byte[0];

    /**
     * Scratch space for interleaving stages.
     */
    private byte[] scratch = new byte[0];

    /**
     * The encoded classes, each at {@link #codedStart}.
     */
    private byte[] coded = new byte[0];

    /**
     * The decoded information and CRC bits of every class, each at {@link #dataStart}.
     */
    private byte[] data = new byte[0];

    /**
     * The received SRCPC outputs, four per step.
     */
    private byte[] received = new byte[0];

    /**
     * The tail flag of each SRCPC step.
     */
    private byte[] tails = new byte[0];

    /**
     * The decoded information bit of each SRCPC step.
     */
    private byte[] decoded = new byte[0];

    /**
     * The rate index in effect at each period of eight SRCPC steps.
     */
    private int[] periodRates = new int[0];

    /**
     * The Reed-Solomon symbols of one codeword.
     */
    private final int[] symbols = new int[255];

    /**
     * The header bits being decoded.
     */
    private byte[] header = new byte[256];

    /**
     * The output class index of each frame position of the current frame.
     */
    private final int[] order;

    /**
     * Whether a Reed-Solomon code continuing past the class at each frame position of the current frame reaches the
     * class at the next position.
     */
    private final boolean[] chained;

    /**
     * The classes of each unit: one per frame position, or, with concatenated frames, per frame position and frame
     * unless the class is concatenated into one unit.
     */
    private final ErrorProtectionConfig.ClassSpec[] specs;

    /**
     * The output class of each unit.
     */
    private final int[] unitClass;

    /**
     * The source coder frame of each unit, or -1 for a unit that concatenates the class of every frame.
     */
    private final int[] unitFrame;

    /**
     * The information length of each unit, -1 while unknown.
     */
    private final int[] lengths;

    /**
     * The SRCPC rate index, or the number of correctable bytes, of each unit.
     */
    private final int[] rates;

    /**
     * The CRC length of each unit.
     */
    private final int[] crcLengths;

    /**
     * The first SRC step of each unit.
     */
    private final int[] steps;

    /**
     * The number of encoded bits of each unit; for a Reed-Solomon group, held by its first unit.
     */
    private final int[] codedLengths;

    /**
     * The index of each unit's encoded bits in {@link #coded}.
     */
    private final int[] codedStart;

    /**
     * The index of each unit's information in {@link #data}.
     */
    private final int[] dataStart;

    /**
     * The number of units of each Reed-Solomon group, held by its first unit (1 for SRCPC units, 0 for the units a
     * group continues into).
     */
    private final int[] groupSize;

    /**
     * Whether each unit passed its CRC.
     */
    private final boolean[] unitIntact;

    /**
     * Whether a Reed-Solomon code that continues past each unit ({@code fec_type} 2) may continue into the next unit:
     * the next unit is the same class of the next concatenated frame, or a class the code reaches in the original
     * pre-defined set's frame order.
     */
    private final boolean[] continues;

    /**
     * The number of units of the current frame.
     */
    private int units;

    /**
     * The bits of each class of each source coder frame, packed most significant bit first.
     */
    private final byte[][][] classData;

    /**
     * The number of bits of each class of each source coder frame.
     */
    private final int[][] classBits;

    /**
     * Whether each class of each source coder frame passed its CRC.
     */
    private final boolean[][] classIntact;

    /**
     * The number of output classes of the current frame.
     */
    private int classCount;

    /**
     * Whether the last frame's header and lengths decoded.
     */
    private boolean decodedFrame;

    /**
     * The transmitted pre-defined set of the last decoded frame.
     */
    private long choice;

    /**
     * Constructs a decoder.
     *
     * @param config the configuration
     * @param limits the limits the work buffers are checked against
     */
    public ErrorProtectionDecoder(ErrorProtectionConfig config, MediaLimits limits) {
        this.config = config;
        this.limits = limits;
        var frames = config.concatenatedFrames();
        var classes = config.maxClasses();
        var maxUnits = classes * frames;
        this.order = new int[classes];
        this.chained = new boolean[classes];
        this.specs = new ErrorProtectionConfig.ClassSpec[maxUnits];
        this.unitClass = new int[maxUnits];
        this.unitFrame = new int[maxUnits];
        this.lengths = new int[maxUnits];
        this.rates = new int[maxUnits];
        this.crcLengths = new int[maxUnits];
        this.steps = new int[maxUnits + 1];
        this.codedLengths = new int[maxUnits];
        this.codedStart = new int[maxUnits];
        this.dataStart = new int[maxUnits + 1];
        this.groupSize = new int[maxUnits];
        this.unitIntact = new boolean[maxUnits];
        this.continues = new boolean[maxUnits];
        this.classData = new byte[frames][classes][];
        for (var f = 0; f < frames; f++) {
            for (var c = 0; c < classes; c++) {
                classData[f][c] = new byte[0];
            }
        }
        this.classBits = new int[frames][classes];
        this.classIntact = new boolean[frames][classes];
    }

    /**
     * {@return the number of source coder frames each EP frame carries}
     */
    public int frames() {
        return config.concatenatedFrames();
    }

    /**
     * {@return the transmitted pre-defined set ({@code choice_of_pred}) of the last frame whose header decoded}
     */
    public long choice() {
        return choice;
    }

    /**
     * {@return the number of classes of the last decoded frame's pre-defined set, absent optional classes included}
     */
    public int classCount() {
        return classCount;
    }

    /**
     * Returns the bits of one class of the last decoded EP frame.
     *
     * @param frame the source coder frame, below {@link #frames()}
     * @param index the class, in output order, below {@link #classCount()}
     * @return the class bits packed most significant bit first, zero-padded to whole bytes; valid until the next
     * frame
     */
    public byte[] classData(int frame, int index) {
        return classData[frame][index];
    }

    /**
     * Returns the number of bits of one class of the last decoded EP frame.
     *
     * @param frame the source coder frame
     * @param index the class, in output order
     * @return the number of bits, 0 for an absent class
     */
    public int classBits(int frame, int index) {
        return classBits[frame][index];
    }

    /**
     * Returns whether one source coder frame of the last decoded EP frame is intact: its header decoded, its lengths
     * fit the frame and every class passed its CRC after error correction.
     *
     * @param frame the source coder frame
     * @return {@code true} when the frame can be decoded
     */
    public boolean intact(int frame) {
        if (!decodedFrame) {
            return false;
        }
        for (var c = 0; c < classCount; c++) {
            if (!classIntact[frame][c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the bit length of one source coder frame, the sum of its classes.
     *
     * @param frame the source coder frame
     * @return the number of bits
     */
    public long frameBits(int frame) {
        var total = 0L;
        for (var c = 0; c < classCount; c++) {
            total += classBits[frame][c];
        }
        return total;
    }

    /**
     * Writes the classes of one source coder frame back to back, as {@code epConfig} 2 defines the frame.
     *
     * @param frame the source coder frame
     * @param out receives the bits packed most significant bit first, at least {@code (frameBits + 7) / 8} bytes
     * @return the number of bytes written
     */
    public int concatenate(int frame, byte[] out) {
        var bytes = (int) ((frameBits(frame) + 7) >>> 3);
        Arrays.fill(out, 0, bytes, (byte) 0);
        var position = 0L;
        for (var c = 0; c < classCount; c++) {
            var bits = classBits[frame][c];
            var source = classData[frame][c];
            for (var i = 0; i < bits; i++, position++) {
                if ((source[i >>> 3] & (0x80 >>> (i & 7))) != 0) {
                    out[(int) (position >>> 3)] |= (byte) (0x80 >>> (position & 7));
                }
            }
        }
        return bytes;
    }

    /**
     * Decodes one EP frame.
     *
     * @param input the array holding the frame
     * @param offset the index of its first byte
     * @param length its number of bytes
     * @throws MediaException.LimitExceeded if the frame needs more memory than the limits allow
     */
    public void decode(byte[] input, int offset, int length) {
        var total = (long) length * 8;
        if (total > Integer.MAX_VALUE / 8) {
            throw new MediaException.LimitExceeded("error protected frame of " + length + " bytes");
        }
        var bits = (int) total;
        frame = grow(frame, bits);
        for (var i = 0; i < bits; i++) {
            frame[i] = (byte) ((input[offset + (i >>> 3)] >>> (7 - (i & 7))) & 1);
        }
        decodedFrame = false;
        classCount = 0;
        if (!decodeFrame(bits)) {
            for (var f = 0; f < frames(); f++) {
                Arrays.fill(classBits[f], 0);
                Arrays.fill(classIntact[f], false);
            }
            return;
        }
        decodedFrame = true;
    }

    /**
     * Decodes the bits in {@link #frame}.
     *
     * @param total the number of bits
     * @return {@code false} when the header cannot be decoded or the lengths do not fit the frame
     */
    private boolean decodeFrame(int total) {
        var interleaved = config.interleaveType() != 0;
        var choiceBits = config.choiceBits();
        var choiceCoded = headerCodedLength(choiceBits);
        if (choiceCoded > total) {
            return false;
        }
        rest = grow(rest, total);
        int remaining;
        if (interleaved) {
            Interleaver.split(frame, 0, total, choiceCoded, headerWidth(choiceBits, choiceCoded), header, 0, rest, 0);
        } else {
            System.arraycopy(frame, 0, header, 0, choiceCoded);
            System.arraycopy(frame, choiceCoded, rest, 0, total - choiceCoded);
        }
        remaining = total - choiceCoded;
        if (!decodeHeader(choiceBits)) {
            return false;
        }
        var value = 0L;
        for (var i = 0; i < choiceBits; i++) {
            value = (value << 1) | header[i];
        }
        if (value >= config.setCount()) {
            return false;
        }
        choice = value;
        var set = config.originalSet(choice);
        var setClasses = config.classes(set);
        classCount = setClasses.length;
        var positions = config.frameOrder(choice, order, chained);
        var attributeBits = config.bitStuffing() ? 3 : 0;
        for (var j = 0; j < positions; j++) {
            var spec = setClasses[order[j]];
            attributeBits += (spec.lengthEscape() ? spec.lengthBits() : 0) + (spec.rateEscape() ? 3 : 0)
                    + (spec.crcEscape() ? 3 : 0);
        }
        var attributeCoded = headerCodedLength(attributeBits);
        if (attributeCoded > remaining) {
            return false;
        }
        header = grow(header, attributeCoded);
        if (interleaved) {
            scratch = grow(scratch, remaining);
            System.arraycopy(rest, 0, scratch, 0, remaining);
            Interleaver.split(scratch, 0, remaining, attributeCoded, headerWidth(attributeBits, attributeCoded),
                    header, 0, rest, 0);
        } else {
            System.arraycopy(rest, 0, header, 0, attributeCoded);
            System.arraycopy(rest, attributeCoded, rest, 0, remaining - attributeCoded);
        }
        remaining -= attributeCoded;
        if (!decodeHeader(attributeBits)) {
            return false;
        }
        var cursor = 0;
        var frames = frames();
        units = 0;
        for (var j = 0; j < positions; j++) {
            var spec = setClasses[order[j]];
            var length = spec.length();
            if (spec.lengthEscape()) {
                length = spec.lengthBits() == 0 ? -1 : read(cursor, spec.lengthBits());
                cursor += spec.lengthBits();
            }
            var rate = spec.rate();
            if (spec.rateEscape()) {
                rate = IN_BAND_RATES[read(cursor, 3)];
                cursor += 3;
                if (spec.fecType() != 0 && rate > ReedSolomon.MAX_CORRECTABLE) {
                    return false;
                }
            }
            var crc = spec.crcLength();
            if (spec.crcEscape()) {
                crc = EpCrc.inBandLengthOf(read(cursor, 3));
                cursor += 3;
            }
            if (frames == 1 || spec.concatenate()) {
                addUnit(spec, order[j], frames == 1 ? 0 : -1, length < 0 ? -1 : length * (spec.concatenate()
                        ? frames : 1), rate, crc);
            } else {
                for (var f = 0; f < frames; f++) {
                    addUnit(spec, order[j], f, length, rate, crc);
                    continues[units - 1] = true;
                }
            }
            continues[units - 1] = chained[j];
        }
        var stuffing = config.bitStuffing() ? read(cursor, 3) : 0;
        return decodePayload(remaining - stuffing);
    }

    /**
     * Adds a unit to the current frame.
     *
     * @param spec the class
     * @param outputClass the output class index
     * @param sourceFrame the source coder frame, or -1 for a concatenated class
     * @param length the information length, -1 for a class until the end
     * @param rate its SRCPC rate index, or the number of correctable bytes of a Reed-Solomon class
     * @param crc the CRC length
     */
    private void addUnit(ErrorProtectionConfig.ClassSpec spec, int outputClass, int sourceFrame, int length, int rate,
                         int crc) {
        specs[units] = spec;
        unitClass[units] = outputClass;
        unitFrame[units] = sourceFrame;
        lengths[units] = length;
        rates[units] = rate;
        crcLengths[units] = length == 0 ? 0 : crc;
        units++;
    }

    /**
     * Reads a field of the decoded class attributes.
     *
     * @param position the index of the field's first bit in {@link #header}
     * @param count the number of bits
     * @return the field value
     */
    private int read(int position, int count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            value = (value << 1) | header[position + i];
        }
        return value;
    }

    /**
     * Returns whether a unit is protected by SRCPC.
     *
     * @param unit the unit's index in the current frame
     * @return {@code true} for an SRCPC unit
     */
    private boolean srcpcUnit(int unit) {
        return specs[unit].fecType() == 0;
    }

    /**
     * Returns the number of tail steps of a unit.
     *
     * @param unit the unit's index in the current frame
     * @return 4 for a terminated SRCPC unit holding data, 0 otherwise
     */
    private int tailSteps(int unit) {
        return srcpcUnit(unit) && specs[unit].termination() && lengths[unit] > 0 ? 4 : 0;
    }

    /**
     * Returns the rate index in effect at an SRCPC step.
     *
     * @param step the SRC step index within the frame
     * @return the rate of the unit holding the first step of the step's period
     */
    private int rateAt(int step) {
        return periodRates[step >>> 3];
    }

    /**
     * Separates, corrects and checks the encoded classes in {@link #rest}.
     *
     * @param payload the number of payload bits, stuffing excluded
     * @return {@code false} when the lengths do not fit the frame
     */
    private boolean decodePayload(int payload) {
        if (payload < 0) {
            return false;
        }
        if (units == 0) {
            return payload == 0;
        }
        var ute = lengths[units - 1] < 0 ? units - 1 : -1;
        var stepCount = 0L;
        for (var u = 0; u < units; u++) {
            groupSize[u] = 0;
        }
        for (var u = 0; u < units; u++) {
            if (u == ute) {
                break;
            }
            if (lengths[u] + (long) crcLengths[u] + tailSteps(u) > Integer.MAX_VALUE / 8) {
                return false;
            }
            stepCount += lengths[u] + crcLengths[u] + tailSteps(u);
        }
        if (stepCount > payload) {
            return false;
        }
        var maxSteps = (int) stepCount + payload + 4;
        periodRates = growInts(periodRates, maxSteps / 8 + 2);
        var known = 0L;
        var step = 0;
        for (var u = 0; u < units; u++) {
            steps[u] = step;
            if (u == ute) {
                break;
            }
            var span = lengths[u] + crcLengths[u] + tailSteps(u);
            if (srcpcUnit(u)) {
                groupSize[u] = 1;
                assignRates(step, span, rates[u]);
                var count = 0;
                for (var t = step; t < step + span; t++) {
                    count += Srcpc.width(rateAt(t), t);
                }
                codedLengths[u] = count;
                known += count;
            } else {
                var first = u;
                var information = 0L;
                while (true) {
                    information += lengths[u] + crcLengths[u];
                    if (specs[u].fecType() != 2 || !continues[u] || u + 1 >= units || srcpcUnit(u + 1)) {
                        break;
                    }
                    u++;
                    steps[u] = step;
                    if (u == ute) {
                        break;
                    }
                }
                if (u == ute) {
                    if (!sizeGroupUntilTheEnd(first, (int) information, payload - (int) known)) {
                        return false;
                    }
                    known += codedLengths[first];
                    ute = -1;
                    break;
                }
                groupSize[first] = u - first + 1;
                for (var g = first + 1; g <= u; g++) {
                    groupSize[g] = 0;
                }
                span = 0;
                codedLengths[first] = (int) information + parityBits((int) information, rates[first]);
                known += codedLengths[first];
            }
            step += span;
            if (known > payload) {
                return false;
            }
        }
        if (ute >= 0) {
            var left = payload - (int) known;
            if (!sizeUntilTheEnd(ute, step, left)) {
                return false;
            }
            known += codedLengths[ute];
            step += lengths[ute] + crcLengths[ute] + tailSteps(ute);
        }
        if (known != payload) {
            return false;
        }
        steps[units] = step;
        coded = grow(coded, payload);
        var position = 0;
        for (var u = 0; u < units; u++) {
            if (groupSize[u] > 0) {
                codedStart[u] = position;
                position += codedLengths[u];
            }
        }
        if (!separate(payload)) {
            return false;
        }
        var dataBits = 0;
        for (var u = 0; u < units; u++) {
            dataStart[u] = dataBits;
            dataBits += lengths[u] + crcLengths[u];
        }
        dataStart[units] = dataBits;
        data = grow(data, dataBits);
        decodeSrcpc();
        decodeReedSolomon();
        for (var u = 0; u < units; u++) {
            unitIntact[u] = EpCrc.matches(data, dataStart[u], lengths[u], crcLengths[u]);
        }
        publish();
        return true;
    }

    /**
     * Records the rate of a unit's steps for the periods that begin within it.
     *
     * @param from the unit's first step
     * @param span its number of steps
     * @param rate its rate
     */
    private void assignRates(int from, int span, int rate) {
        for (var period = (from + 7) >>> 3; period * 8 < from + span; period++) {
            periodRates[period] = rate;
        }
    }

    /**
     * Computes the number of parity bits of a Reed-Solomon code.
     *
     * @param information the number of information bits
     * @param correctable the number of correctable bytes, 0 for none
     * @return the parity bits of every part
     */
    private static int parityBits(int information, int correctable) {
        if (correctable == 0) {
            return 0;
        }
        var part = 255 - 2 * correctable;
        var parts = ((information + 7) / 8 + part - 1) / part;
        return parts * 2 * correctable * 8;
    }

    /**
     * Derives the information length of the class until the end from the bits that remain.
     *
     * @param unit the last unit
     * @param step its first SRCPC step
     * @param left the encoded bits it holds
     * @return {@code false} when no length gives exactly that many encoded bits
     */
    private boolean sizeUntilTheEnd(int unit, int step, int left) {
        if (left < 0) {
            return false;
        }
        groupSize[unit] = 1;
        codedLengths[unit] = left;
        if (left == 0) {
            lengths[unit] = 0;
            crcLengths[unit] = 0;
            return true;
        }
        if (srcpcUnit(unit)) {
            var tail = specs[unit].termination() ? 4 : 0;
            assignRates(step, left, rates[unit]);
            var count = 0;
            var span = 0;
            while (count < left) {
                count += Srcpc.width(rateAt(step + span), step + span);
                span++;
            }
            if (count != left || span < crcLengths[unit] + tail + 1) {
                return false;
            }
            lengths[unit] = span - crcLengths[unit] - tail;
            return true;
        }
        var correctable = rates[unit];
        for (var parts = correctable == 0 ? 0 : 1; parts <= 1 + left / 2040; parts++) {
            var information = left - parts * 2 * correctable * 8;
            if (information > crcLengths[unit] && parityBits(information, correctable) == parts * 2 * correctable * 8) {
                lengths[unit] = information - crcLengths[unit];
                return true;
            }
            if (correctable == 0) {
                break;
            }
        }
        return false;
    }

    /**
     * Derives the information length of the class until the end when a Reed-Solomon group continues into it: the
     * group's information and parity together fill the bits that remain.
     *
     * @param first the group's first unit; the class until the end is the last unit
     * @param known the information bits of the group's other units, CRCs included
     * @param left the encoded bits the group holds
     * @return {@code false} when no length gives exactly that many encoded bits
     */
    private boolean sizeGroupUntilTheEnd(int first, int known, int left) {
        var last = units - 1;
        if (left < known) {
            return false;
        }
        var correctable = rates[first];
        for (var parts = 0; parts <= (correctable == 0 ? 0 : 1 + left / 2040); parts++) {
            var information = left - parts * 2 * correctable * 8;
            var own = information - known;
            if (own < 0 || parityBits(information, correctable) != parts * 2 * correctable * 8) {
                continue;
            }
            if (own == 0) {
                lengths[last] = 0;
                crcLengths[last] = 0;
            } else if (own > crcLengths[last]) {
                lengths[last] = own - crcLengths[last];
            } else {
                continue;
            }
            groupSize[first] = last - first + 1;
            for (var g = first + 1; g <= last; g++) {
                groupSize[g] = 0;
            }
            codedLengths[first] = left;
            return true;
        }
        return false;
    }

    /**
     * Undoes the interleaving of the payload, leaving each group's encoded bits in {@link #coded}.
     *
     * @param payload the number of payload bits
     * @return {@code false} when the interleaved lengths are inconsistent
     */
    private boolean separate(int payload) {
        if (config.interleaveType() == 0) {
            System.arraycopy(rest, 0, coded, 0, payload);
            return true;
        }
        var unprotected = 0;
        for (var u = 0; u < units; u++) {
            if (groupSize[u] > 0 && specs[u].interleaveSwitch() == 0) {
                unprotected += codedLengths[u];
            }
        }
        var tail = payload - unprotected;
        for (var u = 0; u < units; u++) {
            if (groupSize[u] > 0 && specs[u].interleaveSwitch() == 0) {
                System.arraycopy(rest, tail, coded, codedStart[u], codedLengths[u]);
                tail += codedLengths[u];
            }
        }
        var length = payload - unprotected;
        scratch = grow(scratch, payload);
        for (var u = 0; u < units; u++) {
            var mode = specs[u].interleaveSwitch();
            if (groupSize[u] == 0 || mode == 0 || mode == 3) {
                continue;
            }
            var xLength = codedLengths[u];
            if (xLength > length) {
                return false;
            }
            System.arraycopy(rest, 0, scratch, 0, length);
            if (srcpcUnit(u)) {
                Interleaver.split(scratch, 0, length, xLength, mode == 1 ? Math.max(1, xLength) : SRCPC_WIDTH, coded,
                        codedStart[u], rest, 0);
            } else {
                var information = groupInformation(u);
                Interleaver.splitBytes(scratch, 0, length, information, xLength - information, coded, codedStart[u],
                        rest, 0);
            }
            length -= xLength;
        }
        var cursor = 0;
        for (var u = 0; u < units; u++) {
            if (groupSize[u] > 0 && specs[u].interleaveSwitch() == 3) {
                if (cursor + codedLengths[u] > length) {
                    return false;
                }
                System.arraycopy(rest, cursor, coded, codedStart[u], codedLengths[u]);
                cursor += codedLengths[u];
            }
        }
        return cursor == length;
    }

    /**
     * Returns the information bits of a Reed-Solomon group, CRCs included.
     *
     * @param first the group's first unit
     * @return the number of bits
     */
    private int groupInformation(int first) {
        var information = 0;
        for (var g = first; g < first + groupSize[first]; g++) {
            information += lengths[g] + crcLengths[g];
        }
        return information;
    }

    /**
     * Decodes every SRCPC unit into {@link #data}, through one trellis over all of them.
     */
    private void decodeSrcpc() {
        var total = 0;
        for (var u = 0; u < units; u++) {
            if (srcpcUnit(u) && groupSize[u] > 0) {
                total += lengths[u] + crcLengths[u] + tailSteps(u);
            }
        }
        if (total == 0) {
            return;
        }
        received = grow(received, total * 4);
        tails = grow(tails, total);
        decoded = grow(decoded, total);
        var index = 0;
        var parity = false;
        var state = 0;
        var consistent = true;
        var terminated = false;
        for (var u = 0; u < units; u++) {
            if (!srcpcUnit(u) || groupSize[u] == 0) {
                continue;
            }
            var span = lengths[u] + crcLengths[u] + tailSteps(u);
            var information = lengths[u] + crcLengths[u];
            var in = codedStart[u];
            for (var s = 0; s < span; s++, index++) {
                var t = steps[u] + s;
                var rate = rateAt(t);
                var base = index * 4;
                var systematic = coded[in++];
                received[base] = systematic;
                var tail = s >= information;
                tails[index] = (byte) (tail ? 1 : 0);
                var next = Srcpc.step(state, tail ? -1 : systematic);
                if (tail && ((next >>> 3) & 1) != systematic) {
                    consistent = false;
                }
                for (var j = 0; j < 3; j++) {
                    if (Srcpc.sent(rate, j, t)) {
                        var bit = coded[in++];
                        received[base + 1 + j] = bit;
                        parity = true;
                        if (((next >>> (2 - j)) & 1) != bit) {
                            consistent = false;
                        }
                    } else {
                        received[base + 1 + j] = -1;
                    }
                }
                state = next >>> 4;
            }
            terminated = tailSteps(u) > 0;
        }
        if (parity && !consistent) {
            srcpc.decode(received, total, tails, terminated, decoded);
        } else {
            for (var i = 0; i < total; i++) {
                decoded[i] = received[i * 4];
            }
        }
        index = 0;
        for (var u = 0; u < units; u++) {
            if (!srcpcUnit(u) || groupSize[u] == 0) {
                continue;
            }
            var information = lengths[u] + crcLengths[u];
            System.arraycopy(decoded, index, data, dataStart[u], information);
            index += information + tailSteps(u);
        }
    }

    /**
     * Decodes every Reed-Solomon group into {@link #data}, correcting what each code can.
     */
    private void decodeReedSolomon() {
        for (var u = 0; u < units; u++) {
            if (srcpcUnit(u) || groupSize[u] == 0) {
                continue;
            }
            var information = groupInformation(u);
            var correctable = rates[u];
            var start = codedStart[u];
            if (correctable > 0) {
                var partSymbols = 255 - 2 * correctable;
                var totalSymbols = (information + 7) / 8;
                var parityStart = start + information;
                for (var first = 0; first < totalSymbols; first += partSymbols) {
                    var count = Math.min(partSymbols, totalSymbols - first);
                    for (var i = 0; i < count; i++) {
                        symbols[i] = symbol(coded, start, information, first + i);
                    }
                    for (var i = 0; i < 2 * correctable; i++) {
                        symbols[count + i] = symbol(coded, parityStart, Integer.MAX_VALUE, i);
                    }
                    if (reedSolomon.decode(symbols, 0, count + 2 * correctable, correctable) > 0) {
                        for (var i = 0; i < count; i++) {
                            putSymbol(coded, start, information, first + i, symbols[i]);
                        }
                    }
                    parityStart += 2 * correctable * 8;
                }
            }
            System.arraycopy(coded, start, data, dataStart[u], information);
        }
    }

    /**
     * Reads one Reed-Solomon symbol: eight bits, the first transmitted bit the least significant, bits past the end
     * of the run read as zero.
     *
     * @param bits the run's bits, one per byte
     * @param start the index of the run's first bit
     * @param length the run's length
     * @param index the symbol index
     * @return the symbol
     */
    private static int symbol(byte[] bits, int start, int length, int index) {
        var value = 0;
        for (var i = 0; i < 8; i++) {
            var position = index * 8 + i;
            if (position < length && bits[start + position] != 0) {
                value |= 1 << i;
            }
        }
        return value;
    }

    /**
     * Writes one Reed-Solomon symbol back, leaving the bits past the end of the run alone.
     *
     * @param bits the run's bits, one per byte
     * @param start the index of the run's first bit
     * @param length the run's length
     * @param index the symbol index
     * @param value the symbol
     */
    private static void putSymbol(byte[] bits, int start, int length, int index, int value) {
        for (var i = 0; i < 8; i++) {
            var position = index * 8 + i;
            if (position < length) {
                bits[start + position] = (byte) ((value >>> i) & 1);
            }
        }
    }

    /**
     * Copies the decoded units into the classes of each source coder frame.
     */
    private void publish() {
        var frames = frames();
        for (var f = 0; f < frames; f++) {
            Arrays.fill(classBits[f], 0, classCount, 0);
            Arrays.fill(classIntact[f], 0, classCount, true);
        }
        for (var u = 0; u < units; u++) {
            var c = unitClass[u];
            if (unitFrame[u] >= 0) {
                store(unitFrame[u], c, dataStart[u], lengths[u], unitIntact[u]);
            } else {
                var each = lengths[u] / frames;
                for (var f = 0; f < frames; f++) {
                    store(f, c, dataStart[u] + f * each, each, unitIntact[u]);
                }
            }
        }
    }

    /**
     * Stores one class of one source coder frame.
     *
     * @param sourceFrame the frame
     * @param index the output class
     * @param from the index of its first bit in {@link #data}
     * @param bits its number of bits
     * @param crcPassed whether it passed its CRC
     */
    private void store(int sourceFrame, int index, int from, int bits, boolean crcPassed) {
        var bytes = (bits + 7) >>> 3;
        var target = classData[sourceFrame][index];
        if (target.length < bytes) {
            LimitChecks.checkAllocation(limits, bytes);
            target = new byte[Math.max(bytes, target.length * 2)];
            classData[sourceFrame][index] = target;
        }
        Arrays.fill(target, 0, bytes, (byte) 0);
        for (var i = 0; i < bits; i++) {
            if (data[from + i] != 0) {
                target[i >>> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        classBits[sourceFrame][index] = bits;
        classIntact[sourceFrame][index] = crcPassed;
    }

    /**
     * Returns the encoded length of a header part.
     *
     * @param bits the number of information bits
     * @return the number of encoded bits
     */
    private int headerCodedLength(int bits) {
        if (bits == 0) {
            return 0;
        }
        if (bits <= 2) {
            return bits * 3;
        }
        if (bits <= 16) {
            return bits + HeaderCode.forBits(bits).parityBits();
        }
        var rate = config.headerProtection() ? config.headerRate() : HEADER_RATE;
        var crc = config.headerProtection() ? config.headerCrcLength() : HEADER_CRC;
        var count = 0;
        for (var t = 0; t < bits + crc + 4; t++) {
            count += Srcpc.width(rate, t);
        }
        return count;
    }

    /**
     * Returns the interleaving width of a header part.
     *
     * @param bits the number of information bits
     * @param coded the number of encoded bits
     * @return the width
     */
    private static int headerWidth(int bits, int coded) {
        if (bits <= 2) {
            return 3;
        }
        return bits <= 16 ? coded : SRCPC_WIDTH;
    }

    /**
     * Decodes a header part in {@link #header} in place, leaving its information bits first.
     *
     * @param bits the number of information bits
     * @return {@code false} when the part cannot be decoded
     */
    private boolean decodeHeader(int bits) {
        if (bits == 0) {
            return true;
        }
        if (bits <= 2) {
            for (var i = 0; i < bits; i++) {
                var votes = header[3 * i] + header[3 * i + 1] + header[3 * i + 2];
                header[i] = (byte) (votes >= 2 ? 1 : 0);
            }
            return true;
        }
        if (bits <= 16) {
            return HeaderCode.forBits(bits).decode(header, 0, bits);
        }
        var rate = config.headerProtection() ? config.headerRate() : HEADER_RATE;
        var crc = config.headerProtection() ? config.headerCrcLength() : HEADER_CRC;
        var span = bits + crc + 4;
        received = grow(received, span * 4);
        tails = grow(tails, span);
        decoded = grow(decoded, span);
        var in = 0;
        var state = 0;
        var consistent = true;
        for (var t = 0; t < span; t++) {
            var base = t * 4;
            var systematic = header[in++];
            received[base] = systematic;
            var tail = t >= bits + crc;
            tails[t] = (byte) (tail ? 1 : 0);
            var next = Srcpc.step(state, tail ? -1 : systematic);
            if (tail && ((next >>> 3) & 1) != systematic) {
                consistent = false;
            }
            for (var j = 0; j < 3; j++) {
                if (Srcpc.sent(rate, j, t)) {
                    var bit = header[in++];
                    received[base + 1 + j] = bit;
                    if (((next >>> (2 - j)) & 1) != bit) {
                        consistent = false;
                    }
                } else {
                    received[base + 1 + j] = -1;
                }
            }
            state = next >>> 4;
        }
        if (consistent) {
            for (var t = 0; t < bits + crc; t++) {
                decoded[t] = received[t * 4];
            }
        } else {
            srcpc.decode(received, span, tails, true, decoded);
        }
        if (!EpCrc.matches(decoded, 0, bits, crc)) {
            return false;
        }
        System.arraycopy(decoded, 0, header, 0, bits);
        return true;
    }

    /**
     * Returns a byte array of at least a given length, reusing the current one when it suffices.
     *
     * @param array the current array
     * @param length the needed length
     * @return the array
     */
    private byte[] grow(byte[] array, int length) {
        if (array.length >= length) {
            return array;
        }
        LimitChecks.checkAllocation(limits, length);
        return new byte[(int) Math.max(length, Math.min(Integer.MAX_VALUE - 8, array.length * 2L))];
    }

    /**
     * Returns an int array of at least a given length, reusing the current one when it suffices.
     *
     * @param array the current array
     * @param length the needed length
     * @return the array
     */
    private int[] growInts(int[] array, int length) {
        if (array.length >= length) {
            return array;
        }
        LimitChecks.checkAllocation(limits, 4L * length);
        return new int[Math.max(length, array.length * 2)];
    }
}
