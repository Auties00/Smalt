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
     * Combines an {@code AVCDecoderConfigurationRecord} with the {@code SVCDecoderConfigurationRecord}s and
     * {@code MVCDecoderConfigurationRecord}s of scalable and multiview layers (ISO/IEC 14496-15 clauses 7 and
     * 8) into one {@code AVCDecoderConfigurationRecord} that holds every parameter set: the sequence parameter
     * sets and subset sequence parameter sets, then the picture parameter sets, each once, in record order.
     *
     * <p>The header and the trailing chroma format and bit depth fields come from {@code avc}, or, without it,
     * the header from the first extension record. Only the first 31 sequence parameter sets and 255 picture
     * parameter sets are kept. Records that are truncated contribute the parameter sets read before the
     * truncation.
     *
     * @param avc the {@code avcC} payload, or {@code null} for a track without one
     * @param extensions the {@code svcC} and {@code mvcC} payloads
     * @return the combined record, or {@code avc} when no extension record contributes a parameter set
     */
    public static byte[] mergeAvc(byte[] avc, List<byte[]> extensions) {
        var sequence = new ArrayList<byte[]>();
        var picture = new ArrayList<byte[]>();
        byte[] header = null;
        var trailer = new byte[0];
        if (avc != null && avc.length >= AVC_HEADER && avc[0] == 1) {
            header = Arrays.copyOf(avc, 5);
            var end = collect(avc, avc[5] & 0x1F, sequence, picture);
            if (end >= 0) {
                trailer = Arrays.copyOfRange(avc, end, avc.length);
            }
        }
        var added = false;
        for (var extension : extensions) {
            if (extension == null || extension.length < AVC_HEADER || extension[0] != 1) {
                continue;
            }
            if (header == null) {
                header = Arrays.copyOf(extension, 5);
            }
            var before = sequence.size() + picture.size();
            // the extension records count their sequence parameter sets in 7 bits; read 5 when the
            // reserved bits of an avcC-style header are set
            var counts = extension[5] & 0xFF;
            collect(extension, (counts & 0x80) != 0 ? counts & 0x1F : counts & 0x7F, sequence, picture);
            added |= sequence.size() + picture.size() > before;
        }
        if (!added || header == null) {
            return avc;
        }
        var out = new java.io.ByteArrayOutputStream();
        header[4] = (byte) (0xFC | (header[4] & 3));
        out.writeBytes(header);
        var sequenceCount = Math.min(sequence.size(), 31);
        out.write(0xE0 | sequenceCount);
        for (var index = 0; index < sequenceCount; index++) {
            writeUnit(out, sequence.get(index));
        }
        var pictureCount = Math.min(picture.size(), 255);
        out.write(pictureCount);
        for (var index = 0; index < pictureCount; index++) {
            writeUnit(out, picture.get(index));
        }
        out.writeBytes(trailer);
        return out.toByteArray();
    }

    /**
     * Adds the parameter sets of a record's two arrays to the lists, skipping those already listed.
     *
     * @param record the decoder configuration record holding the arrays
     * @param sequenceCount the number of sequence parameter sets the record declares
     * @param sequence receives the sequence parameter sets
     * @param picture receives the picture parameter sets
     * @return the index after the picture parameter sets, or -1 when the record is truncated
     */
    private static int collect(byte[] record, int sequenceCount, List<byte[]> sequence, List<byte[]> picture) {
        var position = 6;
        for (var group = 0; group < 2; group++) {
            var count = sequenceCount;
            if (group == 1) {
                if (position >= record.length) {
                    return -1;
                }
                count = record[position++] & 0xFF;
            }
            var target = group == 0 ? sequence : picture;
            for (var index = 0; index < count; index++) {
                if (position + 2 > record.length) {
                    return -1;
                }
                var length = ((record[position] & 0xFF) << 8) | (record[position + 1] & 0xFF);
                position += 2;
                if (position + length > record.length) {
                    return -1;
                }
                var unit = Arrays.copyOfRange(record, position, position + length);
                position += length;
                if (length > 0 && target.stream().noneMatch(known -> Arrays.equals(known, unit))) {
                    target.add(unit);
                }
            }
        }
        return position;
    }

    /**
     * Writes a parameter set preceded by its 16-bit length.
     *
     * @param out the destination
     * @param unit the NAL unit, shorter than 65536 bytes
     */
    private static void writeUnit(java.io.ByteArrayOutputStream out, byte[] unit) {
        out.write(unit.length >> 8);
        out.write(unit.length);
        out.writeBytes(unit);
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
