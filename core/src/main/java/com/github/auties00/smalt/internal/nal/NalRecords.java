package com.github.auties00.smalt.internal.nal;

import com.github.auties00.smalt.MediaException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Takes apart the configuration records of the NAL-unit video codecs: the H.264
 * {@code AVCDecoderConfigurationRecord} ({@code avcC}, ISO/IEC 14496-15 clause 5.3.3) and the HEVC
 * {@code HEVCDecoderConfigurationRecord} ({@code hvcC}, clause 8.3.3) with the
 * {@code LHEVCDecoderConfigurationRecord} ({@code lhvC}, clause 9.6.3) of the layers above the base layer.
 *
 * <p>Each record has two parsers over one walk. The strict parsers serve decoders: they throw
 * {@link MediaException.Malformed} for a truncated record and reject the length prefix size 3,
 * which no sample format uses. The lenient parsers serve muxers and demuxers that only repeat the
 * parameter sets: they return {@code null} instead of throwing and accept every prefix size.
 * Parameter sets are whole NAL units, header included, with their emulation prevention bytes.
 * This class is thread-safe.
 */
public final class NalRecords {
    /**
     * The size of the fixed header of an {@code AVCDecoderConfigurationRecord}.
     */
    private static final int AVC_HEADER = 7;

    /**
     * The size of the fixed header of an {@code HEVCDecoderConfigurationRecord}.
     */
    private static final int HEVC_HEADER = 23;

    /**
     * Prevents instantiation.
     */
    private NalRecords() {
        throw new AssertionError();
    }

    /**
     * Holds the NAL units of a configuration record and the size of the length prefix its
     * samples use.
     *
     * @param lengthSize the size of the NAL unit length prefix, from 1 to 4 bytes (never 3 from a
     * strict parser)
     * @param parameterSets the NAL units in record order (for H.264 the sequence then the picture
     * parameter sets; for HEVC the units of every array, in array order); unmodifiable
     */
    public record Parsed(int lengthSize, List<byte[]> parameterSets) {
    }

    /**
     * Returns whether a codec configuration looks like an {@code AVCDecoderConfigurationRecord}
     * rather than an Annex B byte stream.
     *
     * @param config the codec configuration
     * @return {@code true} when it holds the fixed header and starts with version 1
     */
    public static boolean looksLikeAvc(byte[] config) {
        return config.length >= AVC_HEADER && config[0] == 1;
    }

    /**
     * Returns whether a codec configuration looks like an {@code HEVCDecoderConfigurationRecord}
     * rather than an Annex B byte stream.
     *
     * @param config the codec configuration
     * @return {@code true} when it holds the fixed header and starts with version 1
     */
    public static boolean looksLikeHevc(byte[] config) {
        return config.length >= HEVC_HEADER && config[0] == 1;
    }

    /**
     * Takes an {@code AVCDecoderConfigurationRecord} apart, tolerating damage.
     *
     * <p>Empty parameter sets are dropped; a record that ends after its sequence parameter sets has
     * no picture parameter sets.
     *
     * @param config the record, or {@code null}
     * @return the parsed record, or {@code null} when {@code config} is {@code null}, truncated or
     * not version 1
     */
    public static Parsed parseAvc(byte[] config) {
        if (config == null || config.length < AVC_HEADER || config[0] != 1) {
            return null;
        }
        return walkAvc(config, false);
    }

    /**
     * Takes an {@code AVCDecoderConfigurationRecord} apart for a decoder.
     *
     * <p>Empty parameter sets are dropped; a record that ends after its sequence parameter sets has
     * no picture parameter sets.
     *
     * @param config the record
     * @return the parsed record
     * @throws MediaException.Malformed if the record is truncated, is not version 1 or declares a
     * length prefix size of 3
     */
    public static Parsed parseAvcStrict(byte[] config) {
        if (config.length < AVC_HEADER || config[0] != 1) {
            throw new MediaException.Malformed("invalid AVCDecoderConfigurationRecord");
        }
        if ((config[4] & 3) + 1 == 3) {
            throw new MediaException.Malformed("invalid NAL unit length size 3");
        }
        return walkAvc(config, true);
    }

    /**
     * Takes an {@code HEVCDecoderConfigurationRecord} apart, tolerating damage.
     *
     * <p>Empty NAL units are dropped. The version byte is not checked.
     *
     * @param config the record, or {@code null}
     * @return the parsed record, or {@code null} when {@code config} is {@code null} or truncated
     */
    public static Parsed parseHevc(byte[] config) {
        if (config == null || config.length < HEVC_HEADER) {
            return null;
        }
        return walkHevc(config, HEVC_HEADER, false);
    }

    /**
     * Takes an {@code HEVCDecoderConfigurationRecord} apart for a decoder.
     *
     * <p>Empty NAL units are kept, as empty arrays. The version byte is not checked.
     *
     * @param config the record
     * @return the parsed record
     * @throws MediaException.Malformed if the record is truncated or declares a length prefix size
     * of 3
     */
    public static Parsed parseHevcStrict(byte[] config) {
        if (config.length < HEVC_HEADER) {
            throw new MediaException.Malformed("truncated HEVCDecoderConfigurationRecord");
        }
        if ((config[21] & 3) + 1 == 3) {
            throw new MediaException.Malformed("invalid hvcC NAL unit length size 3");
        }
        return walkHevc(config, HEVC_HEADER, true);
    }

    /**
     * Takes an {@code LHEVCDecoderConfigurationRecord} ({@code lhvC}, ISO/IEC 14496-15 clause
     * 9.6.3) apart for a decoder.
     *
     * <p>Empty NAL units are kept, as empty arrays. The version byte is not checked.
     *
     * @param config the record
     * @return the parsed record
     * @throws MediaException.Malformed if the record is truncated or declares a length prefix size
     * of 3
     */
    public static Parsed parseLayeredHevcStrict(byte[] config) {
        if (config.length < LHEVC_HEADER) {
            throw new MediaException.Malformed("truncated LHEVCDecoderConfigurationRecord");
        }
        if ((config[LHEVC_HEADER - 2] & 3) + 1 == 3) {
            throw new MediaException.Malformed("invalid lhvC NAL unit length size 3");
        }
        return walkHevc(config, LHEVC_HEADER, true);
    }

    /**
     * Walks the parameter set groups of an {@code avcC} record whose fixed header is present.
     *
     * @param config the record
     * @param strict whether a truncation throws rather than returns {@code null}
     * @return the parsed record, or {@code null} when it is truncated and {@code strict} is
     * {@code false}
     */
    private static Parsed walkAvc(byte[] config, boolean strict) {
        var sets = new ArrayList<byte[]>();
        var position = 5;
        for (var group = 0; group < 2; group++) {
            if (position >= config.length) {
                break;
            }
            var count = group == 0 ? config[position] & 0x1F : config[position] & 0xFF;
            position++;
            for (var index = 0; index < count; index++) {
                if (position + 2 > config.length) {
                    return fail(strict, "truncated AVCDecoderConfigurationRecord");
                }
                var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                position += 2;
                if (position + length > config.length) {
                    return fail(strict, "truncated AVCDecoderConfigurationRecord");
                }
                if (length > 0) {
                    sets.add(Arrays.copyOfRange(config, position, position + length));
                }
                position += length;
            }
        }
        return new Parsed((config[4] & 3) + 1, List.copyOf(sets));
    }

    /**
     * Walks the arrays of an {@code hvcC} or {@code lhvC} record whose fixed header is present.
     *
     * @param config the record
     * @param header the size of the record's fixed header, which ends with the length size byte
     * and {@code numOfArrays}
     * @param strict whether a truncation throws rather than returns {@code null}, and whether
     * empty units are kept
     * @return the parsed record, or {@code null} when it is truncated and {@code strict} is
     * {@code false}
     */
    private static Parsed walkHevc(byte[] config, int header, boolean strict) {
        var sets = new ArrayList<byte[]>();
        var arrays = config[header - 1] & 0xFF;
        var position = header;
        for (var array = 0; array < arrays; array++) {
            if (position + 3 > config.length) {
                return fail(strict, "truncated hvcC array");
            }
            var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
            position += 3;
            for (var index = 0; index < count; index++) {
                if (position + 2 > config.length) {
                    return fail(strict, "truncated hvcC NAL unit length");
                }
                var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                position += 2;
                if (length > config.length - position) {
                    return fail(strict, "hvcC NAL unit exceeds the record");
                }
                if (strict || length > 0) {
                    sets.add(Arrays.copyOfRange(config, position, position + length));
                }
                position += length;
            }
        }
        return new Parsed((config[header - 2] & 3) + 1, List.copyOf(sets));
    }

    /**
     * The size of the fixed header of an {@code LHEVCDecoderConfigurationRecord}, up to and
     * including {@code numOfArrays}.
     */
    private static final int LHEVC_HEADER = 6;

    /**
     * Appends the NAL unit arrays of an {@code LHEVCDecoderConfigurationRecord} ({@code lhvC},
     * ISO/IEC 14496-15 clause 9.6.3), which carries the parameter sets of the layers above the base
     * layer of a multi-layer HEVC track, to the arrays of the track's {@code hvcC} record.
     *
     * <p>The result is an {@code hvcC} record whose header is the base record's and whose arrays are
     * the base record's followed by the layered record's, so a decoder that loads every array gets
     * the parameter sets of every layer. {@link #splitLayeredHevc(byte[])} takes it apart again.
     *
     * @param hvcC the base layer's record
     * @param lhvC the layered record
     * @return the merged record, or {@code hvcC} itself when either record is truncated or the arrays
     * do not fit one record
     */
    public static byte[] appendLayeredHevc(byte[] hvcC, byte[] lhvC) {
        if (hvcC.length < HEVC_HEADER || lhvC.length < LHEVC_HEADER) {
            return hvcC;
        }
        var baseArrays = hvcC[22] & 0xFF;
        var layeredArrays = lhvC[5] & 0xFF;
        var baseEnd = arraysEnd(hvcC, HEVC_HEADER, baseArrays);
        var layeredEnd = arraysEnd(lhvC, LHEVC_HEADER, layeredArrays);
        if (baseEnd < 0 || layeredEnd < 0 || baseArrays + layeredArrays > 255) {
            return hvcC;
        }
        var merged = new byte[baseEnd + layeredEnd - LHEVC_HEADER];
        System.arraycopy(hvcC, 0, merged, 0, baseEnd);
        System.arraycopy(lhvC, LHEVC_HEADER, merged, baseEnd, layeredEnd - LHEVC_HEADER);
        merged[22] = (byte) (baseArrays + layeredArrays);
        return merged;
    }

    /**
     * Splits the NAL units of an {@code hvcC} record by layer: the record of the base layer's NAL
     * units and, when the record also carries NAL units of other layers ({@code nuh_layer_id}
     * above 0), an {@code LHEVCDecoderConfigurationRecord} holding those, with the base record's
     * temporal and length fields.
     *
     * @param hvcC the record, which {@link #appendLayeredHevc(byte[], byte[])} may have merged
     * @return the base record, then the layered record or {@code null} when every NAL unit belongs
     * to the base layer or the record is truncated
     */
    public static byte[][] splitLayeredHevc(byte[] hvcC) {
        if (hvcC.length < HEVC_HEADER || arraysEnd(hvcC, HEVC_HEADER, hvcC[22] & 0xFF) < 0) {
            return new byte[][]{hvcC, null};
        }
        var base = new ByteArrayOutputStream();
        var layered = new ByteArrayOutputStream();
        var baseArrays = 0;
        var layeredArrays = 0;
        var position = HEVC_HEADER;
        for (var array = 0; array < (hvcC[22] & 0xFF); array++) {
            var head = hvcC[position];
            var count = ((hvcC[position + 1] & 0xFF) << 8) | (hvcC[position + 2] & 0xFF);
            position += 3;
            var baseUnits = new ByteArrayOutputStream();
            var layeredUnits = new ByteArrayOutputStream();
            var baseCount = 0;
            var layeredCount = 0;
            for (var index = 0; index < count; index++) {
                var length = ((hvcC[position] & 0xFF) << 8) | (hvcC[position + 1] & 0xFF);
                var layer = length >= 2 ? (((hvcC[position + 2] & 0xFF) << 8 | (hvcC[position + 3] & 0xFF)) >> 3) & 0x3F
                        : 0;
                var target = layer == 0 ? baseUnits : layeredUnits;
                target.write(hvcC, position, 2 + length);
                if (layer == 0) {
                    baseCount++;
                } else {
                    layeredCount++;
                }
                position += 2 + length;
            }
            if (baseCount > 0 || layeredCount == 0) {
                base.write(head);
                base.write(baseCount >> 8);
                base.write(baseCount);
                base.writeBytes(baseUnits.toByteArray());
                baseArrays++;
            }
            if (layeredCount > 0) {
                layered.write(head);
                layered.write(layeredCount >> 8);
                layered.write(layeredCount);
                layered.writeBytes(layeredUnits.toByteArray());
                layeredArrays++;
            }
        }
        if (layeredArrays == 0) {
            return new byte[][]{hvcC, null};
        }
        var baseRecord = new byte[HEVC_HEADER + base.size()];
        System.arraycopy(hvcC, 0, baseRecord, 0, HEVC_HEADER - 1);
        baseRecord[22] = (byte) baseArrays;
        System.arraycopy(base.toByteArray(), 0, baseRecord, HEVC_HEADER, base.size());
        var layeredRecord = new byte[LHEVC_HEADER + layered.size()];
        layeredRecord[0] = 1;
        // min_spatial_segmentation_idc and parallelismType from the base record, reserved bits set
        layeredRecord[1] = hvcC[13];
        layeredRecord[2] = hvcC[14];
        layeredRecord[3] = hvcC[15];
        // numTemporalLayers, temporalIdNested and lengthSizeMinusOne, the low six bits of byte 21
        layeredRecord[4] = (byte) (0xC0 | (hvcC[21] & 0x3F));
        layeredRecord[5] = (byte) layeredArrays;
        System.arraycopy(layered.toByteArray(), 0, layeredRecord, LHEVC_HEADER, layered.size());
        return new byte[][]{baseRecord, layeredRecord};
    }

    /**
     * Returns the index one past the last NAL unit array of a record.
     *
     * @param record the {@code hvcC} or {@code lhvC} record
     * @param start the index of the first array
     * @param arrays the number of arrays
     * @return the end index, or -1 when an array runs past the record
     */
    private static int arraysEnd(byte[] record, int start, int arrays) {
        var position = start;
        for (var array = 0; array < arrays; array++) {
            if (position + 3 > record.length) {
                return -1;
            }
            var count = ((record[position + 1] & 0xFF) << 8) | (record[position + 2] & 0xFF);
            position += 3;
            for (var index = 0; index < count; index++) {
                if (position + 2 > record.length) {
                    return -1;
                }
                var length = ((record[position] & 0xFF) << 8) | (record[position + 1] & 0xFF);
                position += 2 + length;
                if (position > record.length) {
                    return -1;
                }
            }
        }
        return position;
    }

    /**
     * Reports a truncated record.
     *
     * @param strict whether to throw
     * @param message the message of the exception
     * @return {@code null} when {@code strict} is {@code false}
     * @throws MediaException.Malformed if {@code strict} is {@code true}
     */
    private static Parsed fail(boolean strict, String message) {
        if (strict) {
            throw new MediaException.Malformed(message);
        }
        return null;
    }
}
