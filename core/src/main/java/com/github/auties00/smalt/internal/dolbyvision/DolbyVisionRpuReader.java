/* The AV1 part ports the ITU-T T.35 and EMDF unwrapping of the dolby_vision crate 3.4.0 of dovi_tool, commit 41dae64
 * (av1/mod.rs, av1/emdf.rs). Copyright quietvoid; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;

/**
 * Finds the RPU of an access unit: an HEVC NAL unit of type 62, or an AV1 metadata OBU of ITU-T T.35 data holding an
 * EMDF container. Damaged units count as absent, so a damaged RPU never fails the packet that holds it.
 *
 * <p>Instances are not thread-safe.
 */
final class DolbyVisionRpuReader {
    /**
     * The HEVC NAL unit type of video parameter sets.
     */
    private static final int HEVC_VPS = 32;

    /**
     * The HEVC NAL unit type of Dolby Vision enhancement layer data.
     */
    private static final int HEVC_ENHANCEMENT_LAYER = 63;

    /**
     * The AV1 OBU type of metadata.
     */
    private static final int OBU_METADATA = 5;

    /**
     * The AV1 metadata type of ITU-T T.35 data.
     */
    private static final int METADATA_ITU_T35 = 4;

    /**
     * The country code of the United States, which leads the T.35 data.
     */
    private static final int T35_COUNTRY = 0xB5;

    /**
     * The T.35 data after the country code up to the EMDF payload size: the provider code 0x003B, the provider
     * oriented code 0x800, and an EMDF container of version 0, key 6, payload 31 with extension 225, without
     * optional fields, discarding unknown payloads. The last byte holds the final 3 bits of those flags in its top
     * bits; its other bits start the payload size.
     */
    private static final byte[] T35_HEADER = {0x00, 0x3B, 0x00, 0x00, 0x08, 0x00, 0x37, (byte) 0xCD, 0x08, 0x20};

    /**
     * The bits of the EMDF header in {@link #T35_HEADER}.
     */
    private static final int T35_HEADER_BITS = 9 * 8 + 3;

    /**
     * The fewest bytes of T.35 data that can hold an RPU.
     */
    private static final int T35_MIN_LENGTH = 34;

    /**
     * Whether packets are AV1 temporal units instead of HEVC access units.
     */
    private final boolean av1;

    /**
     * The NAL unit length field size of the track's configuration, 0 when it has none.
     */
    private final int lengthSize;

    /**
     * Whether a video parameter set seen so far declares more than one layer.
     */
    private boolean layered;

    /**
     * Whether an access unit read so far holds enhancement layer data.
     */
    private boolean enhancementLayer;

    /**
     * The last valid RPU of the packet being read.
     */
    private DolbyVisionRpu found;

    /**
     * Handles the NAL units of each packet.
     */
    private final NalUnits.Sink visitor = this::visitNal;

    /**
     * Constructs a reader.
     *
     * @param codec {@link Codec#HEVC} or {@link Codec#AV1}
     * @param config the track's codec configuration, or {@code null}
     */
    DolbyVisionRpuReader(Codec codec, byte[] config) {
        this.av1 = codec == Codec.AV1;
        var size = 0;
        if (!av1 && config != null && NalRecords.looksLikeHevc(config)) {
            try {
                var record = NalRecords.parseHevc(config);
                if (record != null) {
                    size = record.lengthSize();
                    for (var set : record.parameterSets()) {
                        visitNal(set, 0, set.length);
                    }
                }
            } catch (MediaException exception) {
                size = 0;
            }
        }
        this.lengthSize = size;
    }

    /**
     * {@return whether a video parameter set seen so far declares more than one layer, as the MV-HEVC streams of
     * profile 20 do, whose RPU headers read as profile 5}
     */
    boolean layered() {
        return layered;
    }

    /**
     * {@return whether an access unit read so far holds enhancement layer data}
     */
    boolean enhancementLayer() {
        return enhancementLayer;
    }

    /**
     * Reads the RPU of an access unit.
     *
     * @param data the access unit
     * @return the last valid RPU in it, or {@code null} when it has none
     */
    DolbyVisionRpu read(byte[] data) {
        found = null;
        if (av1) {
            readObus(data);
        } else if (lengthSize > 0 && !(NalUnits.looksLikeAnnexB(data) && !NalUnits.isLengthPrefixed(data, lengthSize))) {
            NalUnits.forEachLengthPrefixed(data, lengthSize, visitor);
        } else if (NalUnits.looksLikeAnnexB(data)) {
            NalUnits.forEachAnnexB(data, visitor);
        } else if (NalUnits.isLengthPrefixed(data, 4)) {
            NalUnits.forEachLengthPrefixed(data, 4, visitor);
        }
        return found;
    }

    /**
     * Handles one HEVC NAL unit.
     *
     * @param data the buffer
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     */
    private void visitNal(byte[] data, int offset, int length) {
        if (length < 3) {
            return;
        }
        var type = NalUnits.type(data[offset], true);
        if (type == HEVC_VPS) {
            // vps_video_parameter_set_id u(4), two flags, vps_max_layers_minus1 u(6) start the payload
            var maxLayersMinus1 = (data[offset + 2] & 0x03) << 4 | (length > 3 ? (data[offset + 3] & 0xF0) >> 4 : 0);
            layered |= maxLayersMinus1 > 0;
        } else if (type == HEVC_ENHANCEMENT_LAYER) {
            enhancementLayer = true;
        } else if (type == DolbyVisionRpu.HEVC_NAL_TYPE) {
            var rpu = DolbyVisionRpu.tryParseHevcNal(data, offset, length);
            if (rpu != null) {
                found = rpu;
            }
        }
    }

    /**
     * Walks the OBUs of a temporal unit.
     *
     * @param data the temporal unit
     */
    private void readObus(byte[] data) {
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            var type = header >> 3 & 0x0F;
            var extension = (header & 0x04) != 0;
            var hasSize = (header & 0x02) != 0;
            position += extension ? 2 : 1;
            int size;
            if (hasSize) {
                var value = 0L;
                var shift = 0;
                while (true) {
                    if (position >= data.length || shift > 28) {
                        return;
                    }
                    var octet = data[position++] & 0xFF;
                    value |= (long) (octet & 0x7F) << shift;
                    shift += 7;
                    if (octet < 0x80) {
                        break;
                    }
                }
                if (value > data.length - position) {
                    return;
                }
                size = (int) value;
            } else {
                size = data.length - position;
            }
            if (size < 0) {
                return;
            }
            if (type == OBU_METADATA) {
                visitMetadata(data, position, size);
            }
            position += size;
        }
    }

    /**
     * Handles one metadata OBU payload.
     *
     * @param data the buffer
     * @param offset the index of the payload
     * @param length the payload length
     */
    private void visitMetadata(byte[] data, int offset, int length) {
        if (length < 2 || data[offset] != METADATA_ITU_T35 || (data[offset + 1] & 0xFF) != T35_COUNTRY) {
            return;
        }
        var start = offset + 2;
        var size = length - 2;
        if (size < T35_MIN_LENGTH - 1) {
            return;
        }
        for (var index = 0; index < T35_HEADER.length - 1; index++) {
            if (data[start + index] != T35_HEADER[index]) {
                return;
            }
        }
        if ((data[start + T35_HEADER.length - 1] & 0xE0) != T35_HEADER[T35_HEADER.length - 1]) {
            return;
        }
        var reader = new MsbBitReader(data, start, size);
        reader.skip(T35_HEADER_BITS);
        var payloadSize = emdfVariableBits(reader);
        if (payloadSize < 0 || payloadSize * 8L > reader.bitsLeft()) {
            return;
        }
        var rpu = new byte[payloadSize + 1];
        rpu[0] = DolbyVisionRpu.PREFIX;
        for (var index = 1; index < rpu.length; index++) {
            rpu[index] = (byte) reader.read(8);
        }
        try {
            found = DolbyVisionRpu.parse(rpu, 0, rpu.length);
        } catch (MediaException.Malformed exception) {
            // a damaged RPU counts as absent
        }
    }

    /**
     * Reads an EMDF {@code variable_bits(8)} value.
     *
     * @param reader the bits
     * @return the value, or -1 when it overflows 16 bits
     */
    private static int emdfVariableBits(MsbBitReader reader) {
        var value = 0;
        while (reader.bitsLeft() >= 9) {
            value += reader.read(8);
            if (value > 0xFFFF) {
                return -1;
            }
            if (!reader.readFlag()) {
                return value;
            }
            value = (value << 8) + (1 << 8);
            if (value > 0xFFFF) {
                return -1;
            }
        }
        return -1;
    }
}
