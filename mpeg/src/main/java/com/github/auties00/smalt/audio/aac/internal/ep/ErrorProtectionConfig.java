package com.github.auties00.smalt.audio.aac.internal.ep;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Holds the out-of-band configuration of the MPEG-4 audio error protection tool, the
 * {@code ErrorProtectionSpecificConfig} of ISO/IEC 14496-3 subclause 1.8.2.1, with the pre-defined sets its optional
 * classes expand to (subclause 1.8.4.2).
 *
 * <p>Each original pre-defined set lists its classes in the order the EP decoder outputs them, with the order of
 * the classes in the EP frame given by {@code class_output_order} (identity when the set is not reordered): the
 * class at frame position {@code j} is class {@code class_output_order[j]}. A set with {@code N} optional classes
 * stands for {@code 2^N} transmitted sets, from "every optional class present" to "every optional class absent";
 * bit {@code k} of the index within the expansion removes the {@code k}-th optional class.
 */
public final class ErrorProtectionConfig {
    /**
     * The largest number of transmitted pre-defined sets this decoder addresses.
     */
    private static final long MAX_SETS = 1L << 24;

    /**
     * The interleaving type: 0 none, 1 intra-frame, 2 class-wise.
     */
    private final int interleaveType;

    /**
     * Whether the frame header signals stuffing bits for byte alignment.
     */
    private final boolean bitStuffing;

    /**
     * The number of source coder frames one EP frame concatenates, from 1 to 7.
     */
    private final int concatenatedFrames;

    /**
     * Whether a header longer than 16 bits uses the signaled SRCPC rate and CRC length.
     */
    private final boolean headerProtection;

    /**
     * The SRCPC rate index of the extended header protection.
     */
    private final int headerRate;

    /**
     * The CRC length of the extended header protection.
     */
    private final int headerCrcLength;

    /**
     * The classes of each original pre-defined set, in output order.
     */
    private final ClassSpec[][] classes;

    /**
     * The frame order of each original pre-defined set: the class index at each frame position.
     */
    private final int[][] frameOrders;

    /**
     * The indices of the optional classes of each original pre-defined set, in class order.
     */
    private final int[][] optionalClasses;

    /**
     * The index of the first transmitted set of each original set.
     */
    private final long[] firstSet;

    /**
     * The number of transmitted pre-defined sets.
     */
    private final long setCount;

    /**
     * Constructs a configuration from its parsed parts.
     *
     * @param interleaveType the interleaving type
     * @param bitStuffing whether stuffing is signaled
     * @param concatenatedFrames the number of concatenated source coder frames
     * @param headerProtection whether the extended header protection applies
     * @param headerRate the SRCPC rate of the extended header protection
     * @param headerCrcLength the CRC length of the extended header protection
     * @param classes the classes of each original set
     * @param frameOrders the frame order of each original set
     */
    private ErrorProtectionConfig(int interleaveType, boolean bitStuffing, int concatenatedFrames,
                                  boolean headerProtection, int headerRate, int headerCrcLength, ClassSpec[][] classes,
                                  int[][] frameOrders) {
        this.interleaveType = interleaveType;
        this.bitStuffing = bitStuffing;
        this.concatenatedFrames = concatenatedFrames;
        this.headerProtection = headerProtection;
        this.headerRate = headerRate;
        this.headerCrcLength = headerCrcLength;
        this.classes = classes;
        this.frameOrders = frameOrders;
        this.optionalClasses = new int[classes.length][];
        this.firstSet = new long[classes.length];
        var total = 0L;
        for (var set = 0; set < classes.length; set++) {
            var optional = 0;
            for (var spec : classes[set]) {
                if (spec.optional()) {
                    optional++;
                }
            }
            var indices = new int[optional];
            var next = 0;
            for (var i = 0; i < classes[set].length; i++) {
                if (classes[set][i].optional()) {
                    indices[next++] = i;
                }
            }
            optionalClasses[set] = indices;
            firstSet[set] = total;
            if (optional >= 40) {
                throw new MediaException.Unsupported("aac ep sets",
                        "error protection configuration with " + optional + " optional classes in one set");
            }
            total += 1L << optional;
            if (total > MAX_SETS) {
                throw new MediaException.Unsupported("aac ep sets",
                        "error protection configuration expanding to more than " + MAX_SETS + " pre-defined sets");
            }
        }
        this.setCount = total;
    }

    /**
     * Reads an {@code ErrorProtectionSpecificConfig}.
     *
     * @param reader the reader, at the configuration's first bit
     * @return the configuration
     * @throws MediaException.Malformed if the configuration uses a reserved value or is inconsistent
     * @throws MediaException.Unsupported if its optional classes expand to more pre-defined sets than this decoder
     * addresses
     */
    public static ErrorProtectionConfig read(AacBitReader reader) {
        var predefinedSets = reader.read(8);
        var interleaveType = reader.read(2);
        var bitStuffing = reader.read(3);
        var concatenated = reader.read(3);
        if (predefinedSets == 0) {
            throw new MediaException.Malformed("error protection configuration without pre-defined sets");
        }
        if (interleaveType == 3) {
            throw new MediaException.Malformed("reserved error protection interleave_type 3");
        }
        if (concatenated == 0) {
            throw new MediaException.Malformed("reserved number_of_concatenated_frame 0");
        }
        var classes = new ClassSpec[predefinedSets][];
        var orders = new int[predefinedSets][];
        for (var set = 0; set < predefinedSets; set++) {
            // a set without classes is a frame without data (the enhancement layers of the ER CELP streams use one)
            var count = reader.read(6);
            var specs = new ClassSpec[count];
            for (var j = 0; j < count; j++) {
                specs[j] = readClass(reader, interleaveType, concatenated);
            }
            var order = new int[count];
            if (reader.readFlag()) {
                var seen = new boolean[count];
                for (var j = 0; j < count; j++) {
                    var index = reader.read(6);
                    if (index >= count || seen[index]) {
                        throw new MediaException.Malformed("error protection class_output_order is not a permutation");
                    }
                    seen[index] = true;
                    order[j] = index;
                }
            } else {
                for (var j = 0; j < count; j++) {
                    order[j] = j;
                }
            }
            for (var j = 0; j < count - 1; j++) {
                if (specs[order[j]].untilTheEnd()) {
                    throw new MediaException.Malformed("error protection class until the end before the last class");
                }
            }
            if (concatenated > 1) {
                for (var spec : specs) {
                    if (spec.lengthEscape() || spec.rateEscape() || spec.crcEscape()) {
                        throw new MediaException.Malformed("escaped class parameter with concatenated frames");
                    }
                }
            }
            classes[set] = specs;
            orders[set] = order;
        }
        var headerProtection = reader.readFlag();
        var headerRate = 0;
        var headerCrc = 0;
        if (headerProtection) {
            headerRate = reader.read(5);
            headerCrc = EpCrc.lengthOf(reader.read(5));
            if (headerRate > Srcpc.MAX_RATE || headerCrc < 0) {
                throw new MediaException.Malformed("reserved error protection header_rate or header_crclen");
            }
        }
        reader.checkNotOverrun();
        return new ErrorProtectionConfig(interleaveType, bitStuffing != 0, concatenated, headerProtection, headerRate,
                headerCrc, classes, orders);
    }

    /**
     * Reads the attributes of one class.
     *
     * @param reader the reader at the class's first field
     * @param interleaveType the interleaving type
     * @param concatenated the number of concatenated frames
     * @return the class
     */
    private static ClassSpec readClass(AacBitReader reader, int interleaveType, int concatenated) {
        var lengthEscape = reader.readFlag();
        var rateEscape = reader.readFlag();
        var crcEscape = reader.readFlag();
        var concatenate = concatenated != 1 && reader.readFlag();
        var fecType = reader.read(2);
        if (fecType == 3) {
            throw new MediaException.Malformed("reserved error protection fec_type 3");
        }
        var termination = fecType == 0 && reader.readFlag();
        var interleaveSwitch = interleaveType == 2 ? reader.read(2) : interleaveType == 1 ? 2 : 0;
        if (interleaveType == 1 && fecType != 0) {
            interleaveSwitch = 1;
        }
        if (interleaveSwitch == 2 && fecType != 0) {
            throw new MediaException.Malformed("error protection interleave_switch 2 on a Reed-Solomon class");
        }
        var optional = reader.readFlag();
        var lengthBits = 0;
        var length = 0;
        if (lengthEscape) {
            lengthBits = reader.read(4);
        } else {
            length = reader.read(16);
        }
        var rate = 0;
        if (!rateEscape) {
            rate = reader.read(fecType != 0 ? 7 : 5);
            if (fecType == 0 ? rate > Srcpc.MAX_RATE : rate > ReedSolomon.MAX_CORRECTABLE) {
                throw new MediaException.Malformed("reserved error protection class_rate " + rate);
            }
        }
        var crcLength = 0;
        if (!crcEscape) {
            crcLength = EpCrc.lengthOf(reader.read(5));
            if (crcLength < 0) {
                throw new MediaException.Malformed("reserved error protection class_crclen");
            }
        }
        return new ClassSpec(lengthEscape, rateEscape, crcEscape, concatenate, fecType, termination, interleaveSwitch,
                optional, lengthBits, length, rate, crcLength);
    }

    /**
     * {@return the interleaving type: 0 none, 1 intra-frame, 2 class-wise}
     */
    int interleaveType() {
        return interleaveType;
    }

    /**
     * {@return whether the frame header signals stuffing bits}
     */
    boolean bitStuffing() {
        return bitStuffing;
    }

    /**
     * {@return the number of source coder frames one EP frame concatenates}
     */
    public int concatenatedFrames() {
        return concatenatedFrames;
    }

    /**
     * {@return whether headers longer than 16 bits use the extended protection}
     */
    boolean headerProtection() {
        return headerProtection;
    }

    /**
     * {@return the SRCPC rate index of the extended header protection}
     */
    int headerRate() {
        return headerRate;
    }

    /**
     * {@return the CRC length of the extended header protection}
     */
    int headerCrcLength() {
        return headerCrcLength;
    }

    /**
     * {@return the number of transmitted pre-defined sets}
     */
    long setCount() {
        return setCount;
    }

    /**
     * {@return the number of bits of {@code choice_of_pred}, {@code ceil(log2(setCount()))}}
     */
    int choiceBits() {
        return 64 - Long.numberOfLeadingZeros(setCount - 1);
    }

    /**
     * {@return the largest number of classes an original pre-defined set has}
     */
    public int maxClasses() {
        var max = 0;
        for (var set : classes) {
            max = Math.max(max, set.length);
        }
        return max;
    }

    /**
     * Returns the original set a transmitted pre-defined set expands from.
     *
     * @param choice the transmitted set index, below {@link #setCount()}
     * @return the original set index
     */
    int originalSet(long choice) {
        var set = classes.length - 1;
        while (firstSet[set] > choice) {
            set--;
        }
        return set;
    }

    /**
     * Returns the classes of an original pre-defined set, in output order.
     *
     * @param set the original set index
     * @return the classes; not to be modified
     */
    ClassSpec[] classes(int set) {
        return classes[set];
    }

    /**
     * Writes the frame order of a transmitted pre-defined set: the output class index at each frame position,
     * leaving out the optional classes the set removes.
     *
     * @param choice the transmitted set index
     * @param order receives the class indices
     * @return the number of classes the set holds
     */
    int frameOrder(long choice, int[] order) {
        return frameOrder(choice, order, null);
    }

    /**
     * Writes the frame order of a transmitted pre-defined set, as {@link #frameOrder(long, int[])} does, and whether a
     * Reed-Solomon code that continues past each class ({@code fec_type} 2) reaches the class at the next position.
     *
     * <p>The chain of concatenated Reed-Solomon codes follows the original set's frame order, absent classes
     * included: an absent class with {@code fec_type} 2 passes the code on, an absent class with another
     * {@code fec_type} ends it, and it never reaches an SRCPC class.
     *
     * @param choice the transmitted set index
     * @param order receives the class indices
     * @param continues receives, at each frame position, whether the code of the class there continues into the class
     * at the next position ({@code false} at the last position), or {@code null}
     * @return the number of classes the set holds
     */
    int frameOrder(long choice, int[] order, boolean[] continues) {
        var set = originalSet(choice);
        var mask = choice - firstSet[set];
        var optional = optionalClasses[set];
        var specs = classes[set];
        var count = 0;
        var open = false;
        for (var index : frameOrders[set]) {
            var absent = false;
            for (var k = 0; k < optional.length; k++) {
                if (optional[k] == index && (mask & (1L << k)) != 0) {
                    absent = true;
                    break;
                }
            }
            var fecType = specs[index].fecType();
            if (absent) {
                open &= fecType == 2;
                continue;
            }
            if (continues != null && count > 0) {
                continues[count - 1] = open && fecType != 0;
            }
            order[count++] = index;
            open = fecType == 2;
        }
        if (continues != null && count > 0) {
            continues[count - 1] = false;
        }
        return count;
    }

    /**
     * Describes one class of a pre-defined set.
     *
     * @param lengthEscape whether the class length is signaled in band
     * @param rateEscape whether the SRCPC rate is signaled in band
     * @param crcEscape whether the CRC length is signaled in band
     * @param concatenate whether the class of every concatenated frame is coded as one class
     * @param fecType 0 for SRCPC, 1 for a Reed-Solomon code ending here, 2 for a Reed-Solomon code continuing into
     * the next class
     * @param termination whether the SRC code is terminated after the class
     * @param interleaveSwitch how the class is interleaved: 0 not, 1 with the class length as width, 2 with width 28,
     * 3 concatenated into the next interleaving stage
     * @param optional whether the class may be absent ({@code class_optional})
     * @param lengthBits the number of bits of the in-band class length, 0 for a class "until the end"
     * @param length the fixed class length in bits
     * @param rate the fixed SRCPC rate index, or the number of correctable bytes of a Reed-Solomon class
     * @param crcLength the fixed CRC length in bits
     */
    record ClassSpec(boolean lengthEscape, boolean rateEscape, boolean crcEscape, boolean concatenate, int fecType,
                     boolean termination, int interleaveSwitch, boolean optional, int lengthBits, int length, int rate,
                     int crcLength) {
        /**
         * {@return whether the class length is what remains of the frame ("until the end")}
         */
        boolean untilTheEnd() {
            return lengthEscape && lengthBits == 0;
        }
    }
}
