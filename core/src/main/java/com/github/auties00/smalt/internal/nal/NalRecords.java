package com.github.auties00.smalt.internal.nal;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Takes apart the configuration records of the NAL-unit video codecs: the H.264
 * {@code AVCDecoderConfigurationRecord} ({@code avcC}, ISO/IEC 14496-15 clause 5.3.3) and the HEVC
 * {@code HEVCDecoderConfigurationRecord} ({@code hvcC}, clause 8.3.3).
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
        return walkHevc(config, false);
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
        return walkHevc(config, true);
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
     * Walks the arrays of an {@code hvcC} record whose fixed header is present.
     *
     * @param config the record
     * @param strict whether a truncation throws rather than returns {@code null}, and whether
     * empty units are kept
     * @return the parsed record, or {@code null} when it is truncated and {@code strict} is
     * {@code false}
     */
    private static Parsed walkHevc(byte[] config, boolean strict) {
        var sets = new ArrayList<byte[]>();
        var arrays = config[22] & 0xFF;
        var position = HEVC_HEADER;
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
        return new Parsed((config[21] & 3) + 1, List.copyOf(sets));
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
