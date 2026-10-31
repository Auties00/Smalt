package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.codec.VvcSyntax;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Splits an H.264 ({@code stream_type 0x1B}), HEVC ({@code stream_type 0x24}) or VVC
 * ({@code stream_type 0x33}) Annex B byte stream into access units.
 *
 * <p>NAL units are delimited by start codes, whatever the PES packet boundaries. A new access unit
 * starts, once the current one holds a coded slice, at an access unit delimiter, a parameter set,
 * a prefix SEI message or the other NAL unit types the specifications list (ITU-T H.264 clause
 * 7.4.1.2.3, H.265 clause 7.4.2.4.4, H.266 clauses 7.4.2.4.3 and 7.4.2.4.4), or at the first slice
 * of a new picture ({@code first_mb_in_slice} zero, {@code first_slice_segment_in_pic_flag} set,
 * VVC {@code sh_picture_header_in_slice_header_flag} set). For VVC, a NAL unit that would start a
 * picture unit of a layer above every layer of the current access unit's slices stays in it, so an
 * access unit keeps the pictures of all its layers. Each access unit becomes one packet of
 * four-byte length-prefixed NAL units, access unit delimiters removed and every other NAL unit
 * (parameter sets and SEI included) kept, so that parameter set changes reach the decoder.
 *
 * <p>An access unit is a key frame when it holds an IDR picture (H.264), an IRAP picture (HEVC),
 * a base layer picture whose slices are all IRAP or GDR slices (VVC, whose decoders start at a GDR
 * picture and output from its recovery point), or, for H.264, when it holds only intra slices and
 * a recovery point SEI message (the random access points of open-GOP broadcast streams). Access
 * units before the first key frame are
 * dropped: they cannot be decoded. Timestamps are the PES ones on the 90 kHz timeline; an access
 * unit whose PES packet carried none follows the previous one by the previous frame duration.
 * The duration of a packet is the gap to the next access unit's decoding time (the last one
 * repeats the previous duration), so packets leave the parser one access unit late.
 *
 * <p>The track's configuration record ({@code avcC} or {@code hvcC}) is built from the parameter
 * sets met up to the first key frame, which also give the picture size, frame rate and color.
 * The parameter sets met later replace those of the same type and identifier; an IDR (H.264) or
 * IRAP (HEVC) access unit at which the active sequence (and HEVC video) parameter sets differ from
 * those of the configuration last reported carries a record of every active set, ordered by
 * identifier, as its {@link PacketSideData#newCodecConfig() new codec configuration}, so that a
 * stream that changes its resolution or profile (a splice, concatenated recordings) reconfigures
 * its decoder there. A picture parameter set change alone is not reported. The parameter sets stay
 * in the packets too.
 */
final class NalParser extends ElementaryParser {
    /**
     * The 90 kHz time base of video tracks.
     */
    static final Rational TIME_BASE = Rational.timeBaseOf(90_000);

    /**
     * The largest number of bytes buffered for one NAL unit or held for one access unit.
     */
    private static final int CAPACITY = 64 << 20;

    /**
     * The largest number of distinct parameter sets of one type remembered for the configuration.
     */
    private static final int MAX_PARAMETER_SETS = 16;

    /**
     * The codec of the stream: {@link Codec#H264}, {@link Codec#HEVC} or {@link Codec#VVC}.
     */
    private final Codec codec;

    /**
     * Whether the stream is HEVC.
     */
    private final boolean hevc;

    /**
     * Whether the stream is VVC.
     */
    private final boolean vvc;

    /**
     * The buffered stream.
     */
    private final StreamBuffer buffer;

    /**
     * The stream position from which the next start code search begins.
     */
    private long scanFrom;

    /**
     * The stream position of the start code of the NAL unit being collected, or {@code -1}.
     */
    private long unitCode = -1;

    /**
     * The stream position of the first byte of the NAL unit being collected.
     */
    private long unitStart;

    /**
     * The access unit being assembled, or {@code null}.
     */
    private AccessUnit current;

    /**
     * The completed access unit waiting for its successor's decoding time, or {@code null}.
     */
    private AccessUnit held;

    /**
     * The duration of the last emitted packet, in 90 kHz ticks.
     */
    private long lastDuration;

    /**
     * The decoding time of the last completed access unit, or {@link TsClock#NONE}.
     */
    private long lastDts = TsClock.NONE;

    /**
     * Whether a key frame was met, after which every access unit is emitted.
     */
    private boolean seenKey;

    /**
     * Whether access units before the first key frame are emitted too.
     */
    private boolean keepLeading;

    /**
     * The video parameter sets met before the configuration, distinct.
     */
    private final List<byte[]> videoSets = new ArrayList<>();

    /**
     * The sequence parameter sets met before the configuration, distinct.
     */
    private final List<byte[]> sequenceSets = new ArrayList<>();

    /**
     * The picture parameter sets met before the configuration, distinct.
     */
    private final List<byte[]> pictureSets = new ArrayList<>();

    /**
     * The latest parameter set of each type and identifier, keyed by {@code type * 256 + id}.
     */
    private final TreeMap<Integer, byte[]> active = new TreeMap<>();

    /**
     * The parameter sets of the configuration last reported downstream: the track's, then each
     * new configuration a key frame carried; {@code null} until the track is configured.
     */
    private List<byte[]> established;

    /**
     * Whether a parameter set became active since the configuration was last compared with the
     * reported one.
     */
    private boolean activated;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param codec the codec of the stream: {@link Codec#H264}, {@link Codec#HEVC} or {@link Codec#VVC}
     */
    NalParser(Psi.StreamEntry entry, MediaLimits limits, Codec codec) {
        super(entry, limits);
        this.codec = codec;
        this.hevc = codec == Codec.HEVC;
        this.vvc = codec == Codec.VVC;
        this.buffer = new StreamBuffer((int) Math.min(CAPACITY, limits.maxBytes()));
    }

    /**
     * Collects the NAL units of one access unit.
     */
    private static final class AccessUnit {
        /**
         * The length-prefixed NAL units.
         */
        byte[] data = new byte[1024];

        /**
         * The number of bytes of {@link #data} in use.
         */
        int size;

        /**
         * Whether a coded slice was added.
         */
        boolean coded;

        /**
         * Whether an IDR or IRAP slice (VVC: an IRAP or GDR slice of the base layer) was added.
         */
        boolean idr;

        /**
         * Whether a VVC base layer slice that is neither an IRAP nor a GDR slice was added.
         */
        boolean nonRandomAccess;

        /**
         * The highest {@code nuh_layer_id} of the VVC slices added, or -1.
         */
        int maxLayer = -1;

        /**
         * Whether a recovery point SEI message was added.
         */
        boolean recovery;

        /**
         * Whether every slice added is intra coded.
         */
        boolean intra = true;

        /**
         * The presentation time on the 90 kHz timeline, or {@link TsClock#NONE}.
         */
        long pts = TsClock.NONE;

        /**
         * The decoding time on the 90 kHz timeline, or {@link TsClock#NONE}.
         */
        long dts = TsClock.NONE;

        /**
         * The offset of the transport packet that started the PES packet holding the first byte.
         */
        long fileOffset;

        /**
         * Whether a damaged PES packet contributed bytes.
         */
        boolean corrupt;

        /**
         * Whether the access unit is a random access point.
         */
        boolean key;

        /**
         * The configuration record the access unit establishes, or {@code null}.
         */
        byte[] newConfig;
    }

    @Override
    ElementaryParser fresh() {
        var parser = new NalParser(entry, limits, codec);
        parser.track = track;
        parser.establish(track == null ? null : track.codecConfig().orElse(null));
        return parser;
    }

    /**
     * Makes a configuration record the one reported downstream and its parameter sets the active
     * ones.
     *
     * @param config the record, or {@code null} when the track is not configured
     */
    private void establish(byte[] config) {
        if (config == null) {
            return;
        }
        var parsed = vvc ? NalConfigs.parseVvc(config) : hevc ? NalConfigs.parseHevc(config)
                : NalConfigs.parseAvc(config);
        if (parsed == null) {
            return;
        }
        established = new ArrayList<>(parsed.parameterSets());
        for (var unit : parsed.parameterSets()) {
            activate(unit, 0, unit.length);
        }
    }

    /**
     * Records a parameter set as the active one of its type and identifier.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     */
    private void activate(byte[] data, int offset, int length) {
        if (length < 2 || length > 0xFFFF) {
            return;
        }
        for (var existing : active.values()) {
            if (Arrays.equals(existing, 0, existing.length, data, offset, offset + length)) {
                // a repeated parameter set, which streams commonly send before every key frame
                return;
            }
        }
        var type = unitType(data, offset);
        var id = vvc ? VvcSyntax.parameterSetId(data, offset, length) : parameterSetId(type, data, offset, length);
        if (id < 0 || active.size() >= 3 * 256) {
            return;
        }
        active.put(type * 256 + id, Arrays.copyOfRange(data, offset, offset + length));
        activated = true;
    }

    /**
     * Returns the identifier of a parameter set: {@code seq_parameter_set_id} or
     * {@code pic_parameter_set_id} for H.264 (ITU-T H.264 clauses 7.3.2.1 and 7.3.2.2), and
     * {@code vps_video_parameter_set_id}, {@code sps_seq_parameter_set_id} or
     * {@code pps_pic_parameter_set_id} for HEVC (ITU-T H.265 clauses 7.3.2.1 to 7.3.2.3).
     *
     * @param type the NAL unit type
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return the identifier, or {@code -1} for a unit that is no parameter set or is truncated
     */
    private int parameterSetId(int type, byte[] data, int offset, int length) {
        var header = hevc ? 2 : 1;
        var bits = new MsbBitReader(NalUnits.unescape(data, offset + header, Math.min(length - header, 192)));
        int id;
        if (hevc) {
            switch (type) {
                case NalConfigs.HEVC_VPS -> id = bits.read(4);
                case NalConfigs.HEVC_SPS -> {
                    bits.skip(4);
                    var subLayers = bits.read(3);
                    bits.skip(1 + 96);
                    var profiles = new boolean[subLayers];
                    var levels = new boolean[subLayers];
                    for (var layer = 0; layer < subLayers; layer++) {
                        profiles[layer] = bits.readFlag();
                        levels[layer] = bits.readFlag();
                    }
                    if (subLayers > 0) {
                        bits.skip(2L * (8 - subLayers));
                    }
                    for (var layer = 0; layer < subLayers; layer++) {
                        bits.skip((profiles[layer] ? 88 : 0) + (levels[layer] ? 8 : 0));
                    }
                    id = bits.readUe();
                }
                case NalConfigs.HEVC_PPS -> id = bits.readUe();
                default -> {
                    return -1;
                }
            }
        } else {
            switch (type) {
                case NalConfigs.AVC_SPS -> {
                    bits.skip(24);
                    id = bits.readUe();
                }
                case NalConfigs.AVC_PPS -> id = bits.readUe();
                default -> {
                    return -1;
                }
            }
        }
        return bits.overrun() || id < 0 || id > 255 ? -1 : id;
    }

    /**
     * Returns the configuration record of the active parameter sets when their sequence and video
     * parameter sets differ from the ones last reported, and makes them the reported ones.
     *
     * @return the record, or {@code null} when the active sequence and video parameter sets are the
     * reported ones or the active sets do not make a record
     */
    private byte[] changedConfig() {
        if (established == null || !activated) {
            return null;
        }
        var units = new ArrayList<>(active.values());
        if (sameSets(sequenceLevel(units), sequenceLevel(established))) {
            activated = false;
            return null;
        }
        var videoUnits = new ArrayList<byte[]>();
        var sequenceUnits = new ArrayList<byte[]>();
        var pictureUnits = new ArrayList<byte[]>();
        for (var entry : active.entrySet()) {
            var type = entry.getKey() / 256;
            if (type == videoSetType()) {
                videoUnits.add(entry.getValue());
            } else if (type == sequenceSetType()) {
                sequenceUnits.add(entry.getValue());
            } else {
                pictureUnits.add(entry.getValue());
            }
        }
        if (sequenceUnits.isEmpty() || pictureUnits.isEmpty()) {
            return null;
        }
        var config = buildConfig(videoUnits, sequenceUnits, pictureUnits);
        if (config != null) {
            established = units;
            activated = false;
        }
        return config;
    }

    /**
     * Returns the sequence and video parameter sets of a list of parameter sets.
     *
     * @param units whole NAL units
     * @return the units that are sequence or video parameter sets, in order
     */
    private List<byte[]> sequenceLevel(List<byte[]> units) {
        var result = new ArrayList<byte[]>();
        for (var unit : units) {
            var type = unitType(unit, 0);
            if (type == videoSetType() || type == sequenceSetType()) {
                result.add(unit);
            }
        }
        return result;
    }

    /**
     * {@return the NAL unit type of a NAL unit of the stream's codec}
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     */
    private int unitType(byte[] data, int offset) {
        if (vvc) {
            return VvcSyntax.type(data, offset);
        }
        return hevc ? (data[offset] >> 1) & 0x3F : data[offset] & 0x1F;
    }

    /**
     * {@return the NAL unit type of a video parameter set, or -1 for H.264, which has none}
     */
    private int videoSetType() {
        return vvc ? VvcSyntax.VPS : hevc ? NalConfigs.HEVC_VPS : -1;
    }

    /**
     * {@return the NAL unit type of a sequence parameter set}
     */
    private int sequenceSetType() {
        return vvc ? VvcSyntax.SPS : hevc ? NalConfigs.HEVC_SPS : NalConfigs.AVC_SPS;
    }

    /**
     * {@return the NAL unit type of a picture parameter set}
     */
    private int pictureSetType() {
        return vvc ? VvcSyntax.PPS : hevc ? NalConfigs.HEVC_PPS : NalConfigs.AVC_PPS;
    }

    /**
     * Builds the configuration record of the stream's codec.
     *
     * @param videoUnits the video parameter sets
     * @param sequenceUnits the sequence parameter sets
     * @param pictureUnits the picture parameter sets
     * @return the record, or {@code null} when the sets do not make one
     */
    private byte[] buildConfig(List<byte[]> videoUnits, List<byte[]> sequenceUnits, List<byte[]> pictureUnits) {
        if (vvc) {
            return NalConfigs.vvcConfig(videoUnits, sequenceUnits, pictureUnits);
        }
        return hevc ? NalConfigs.hevcConfig(videoUnits, sequenceUnits, pictureUnits)
                : NalConfigs.avcConfig(sequenceUnits, pictureUnits);
    }

    /**
     * Returns whether two lists hold the same parameter sets, in any order.
     *
     * @param first a list of whole NAL units
     * @param second a list of whole NAL units
     * @return {@code true} when every unit of each list is in the other and the sizes agree
     */
    private static boolean sameSets(List<byte[]> first, List<byte[]> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (var unit : first) {
            var found = false;
            for (var other : second) {
                if (Arrays.equals(unit, other)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Override
    void keepLeadingUnits() {
        keepLeading = true;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var dts = pes.dts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.pts(), dts == TsClock.NONE);
        if (dts == TsClock.NONE) {
            dts = pts;
        }
        if (buffer.append(pes, pts, dts)) {
            unitCode = -1;
            scanFrom = buffer.position();
            if (current != null) {
                current.corrupt = true;
            }
        }
        scan(sink);
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        scan(sink);
        if (unitCode >= 0) {
            var end = buffer.position() + buffer.available();
            completeUnit(end, sink);
            unitCode = -1;
        }
        finishAccessUnit(sink);
        if (held != null) {
            emit(held, lastDuration, sink);
            held = null;
        }
    }

    /**
     * Finds the start codes in the buffered bytes and completes the NAL units they end.
     *
     * @param sink receives the completed packets
     */
    private void scan(UnitSink sink) {
        var data = buffer.array();
        var base = buffer.position();
        var start = buffer.start();
        var available = buffer.available();
        var index = (int) Math.max(0, scanFrom - base);
        while (index + 3 <= available) {
            // a start code 00 00 01 ending at index + 2 needs that byte to be 0 or 1; any other
            // value rules out start codes beginning at index, index + 1 and index + 2
            var value = data[start + index + 2] & 0xFF;
            if (value > 1) {
                index += 3;
                continue;
            }
            if (value == 1 && data[start + index + 1] == 0 && data[start + index] == 0) {
                var code = base + index;
                if (unitCode >= 0) {
                    completeUnit(code, sink);
                    data = buffer.array();
                    start = buffer.start();
                    base = buffer.position();
                    available = buffer.available();
                    index = (int) (code - base);
                }
                unitCode = code;
                unitStart = code + 3;
                index += 3;
                continue;
            }
            index++;
        }
        scanFrom = base + Math.max(0, available - 2);
        var keep = unitCode >= 0 ? unitCode : scanFrom;
        if (keep > base) {
            buffer.skip((int) (keep - base));
        }
    }

    /**
     * Completes the NAL unit being collected, which ends where a start code begins or the input
     * ends.
     *
     * @param end the stream position after the NAL unit
     * @param sink receives the completed packets
     */
    private void completeUnit(long end, UnitSink sink) {
        var base = buffer.position();
        var from = (int) (unitStart - base);
        var to = (int) (end - base);
        var data = buffer.array();
        var start = buffer.start();
        while (to > from && data[start + to - 1] == 0) {
            to--;
        }
        if (to > from) {
            handleUnit(data, start + from, to - from, unitCode, sink);
        }
    }

    /**
     * Adds a NAL unit to the access unit it belongs to, completing the previous access unit first
     * when this one starts a new one.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @param code the stream position of the unit's start code
     * @param sink receives the completed packets
     */
    private void handleUnit(byte[] data, int offset, int length, long code, UnitSink sink) {
        if ((data[offset] & 0x80) != 0 || ((hevc || vvc) && length < 2)) {
            return;
        }
        var type = unitType(data, offset);
        if (current != null && current.coded && startsAccessUnit(type, data, offset, length)) {
            finishAccessUnit(sink);
        }
        if (current == null) {
            current = new AccessUnit();
            var marker = buffer.markerAt(code);
            if (marker != null) {
                current.fileOffset = marker.fileOffset;
                if (marker.hasTimestamps()) {
                    marker.taken = true;
                    current.pts = marker.pts;
                    current.dts = marker.dts;
                }
            }
        }
        current.corrupt |= buffer.corrupt(code, code + 3 + length);
        if (isDelimiter(type)) {
            return;
        }
        if (track == null) {
            remember(type, data, offset, length);
        }
        if (type == videoSetType() || type == sequenceSetType() || type == pictureSetType()) {
            activate(data, offset, length);
        }
        if (isSlice(type)) {
            current.coded = true;
            if (vvc) {
                var layer = VvcSyntax.layer(data, offset);
                current.maxLayer = Math.max(current.maxLayer, layer);
                if (layer == 0) {
                    if (VvcSyntax.isIrap(type) || type == VvcSyntax.GDR) {
                        current.idr = true;
                    } else {
                        current.nonRandomAccess = true;
                    }
                }
            } else if (hevc) {
                current.idr |= type >= 16 && type <= 23;
            } else {
                current.idr |= type == 5;
                if (current.intra && startsWithSliceHeader(type) && !isIntraSlice(data, offset, length)) {
                    current.intra = false;
                }
            }
        } else if (!hevc && type == 6 && hasRecoveryPoint(data, offset, length)) {
            current.recovery = true;
        }
        append(current, data, offset, length);
    }

    /**
     * Returns whether a NAL unit type is an access unit delimiter.
     *
     * @param type the NAL unit type
     * @return {@code true} for an access unit delimiter
     */
    private boolean isDelimiter(int type) {
        return vvc ? type == VvcSyntax.AUD : hevc ? type == 35 : type == 9;
    }

    /**
     * Returns whether a NAL unit type holds a coded slice.
     *
     * @param type the NAL unit type
     * @return {@code true} for a VCL NAL unit
     */
    private boolean isSlice(int type) {
        return vvc ? VvcSyntax.isSlice(type) : hevc ? type <= 31 : type >= 1 && type <= 5;
    }

    /**
     * Returns whether an H.264 VCL NAL unit type starts with a slice header: a coded slice or a
     * slice data partition A. The slice data partitions B and C start with their
     * {@code slice_id} and never begin an access unit (ITU-T H.264 7.3.2.9 and 7.4.1.2.4).
     *
     * @param type the NAL unit type
     * @return {@code true} for NAL unit types 1, 2 and 5
     */
    private static boolean startsWithSliceHeader(int type) {
        return type == 1 || type == 2 || type == 5;
    }

    /**
     * Returns whether a NAL unit starts a new access unit after a coded slice.
     *
     * @param type the NAL unit type
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return {@code true} when the NAL unit belongs to the next access unit
     */
    private boolean startsAccessUnit(int type, byte[] data, int offset, int length) {
        if (vvc) {
            if (type == VvcSyntax.AUD || type == VvcSyntax.OPI || type == VvcSyntax.DCI) {
                return true;
            }
            var startsPicture = VvcSyntax.isSlice(type) ? length > 2 && (data[offset + 2] & 0x80) != 0
                    : (type >= VvcSyntax.VPS && type <= VvcSyntax.PREFIX_APS) || type == VvcSyntax.PH
                    || type == VvcSyntax.PREFIX_SEI || type == 26 || type == 28 || type == 29;
            return startsPicture && VvcSyntax.layer(data, offset) <= current.maxLayer;
        }
        if (hevc) {
            if (type <= 31) {
                return length > 2 && (data[offset + 2] & 0x80) != 0;
            }
            return (type >= 32 && type <= 35) || type == 39 || (type >= 41 && type <= 44) || (type >= 48 && type <= 55);
        }
        if (type >= 1 && type <= 5) {
            return startsWithSliceHeader(type) && length > 1 && (data[offset + 1] & 0x80) != 0;
        }
        return (type >= 6 && type <= 9) || (type >= 14 && type <= 18);
    }

    /**
     * Returns whether an H.264 slice is intra coded ({@code slice_type} I or SI).
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return {@code true} for an intra slice
     */
    private static boolean isIntraSlice(byte[] data, int offset, int length) {
        var header = NalUnits.unescape(data, offset + 1, Math.min(length - 1, 16));
        var bits = new MsbBitReader(header);
        bits.readUe();
        var sliceType = bits.readUe() % 5;
        return !bits.overrun() && (sliceType == 2 || sliceType == 4);
    }

    /**
     * Returns whether an H.264 SEI NAL unit holds a recovery point message ({@code payloadType}
     * 6, ITU-T H.264 clause D.1.8).
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     * @return {@code true} when a recovery point message is present
     */
    private static boolean hasRecoveryPoint(byte[] data, int offset, int length) {
        var rbsp = NalUnits.unescape(data, offset + 1, length - 1);
        var position = 0;
        while (position < rbsp.length && (rbsp[position] & 0xFF) != 0x80) {
            var payloadType = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                payloadType += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return false;
            }
            payloadType += rbsp[position++] & 0xFF;
            var payloadSize = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                payloadSize += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return false;
            }
            payloadSize += rbsp[position++] & 0xFF;
            if (payloadType == 6) {
                return true;
            }
            position += payloadSize;
        }
        return false;
    }

    /**
     * Remembers a parameter set for the configuration record.
     *
     * @param type the NAL unit type
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     */
    private void remember(int type, byte[] data, int offset, int length) {
        List<byte[]> target;
        if (vvc) {
            target = switch (type) {
                case VvcSyntax.VPS -> videoSets;
                case VvcSyntax.SPS -> sequenceSets;
                case VvcSyntax.PPS -> pictureSets;
                default -> null;
            };
        } else if (hevc) {
            target = switch (type) {
                case NalConfigs.HEVC_VPS -> videoSets;
                case NalConfigs.HEVC_SPS -> sequenceSets;
                case NalConfigs.HEVC_PPS -> pictureSets;
                default -> null;
            };
        } else {
            target = switch (type) {
                case NalConfigs.AVC_SPS -> sequenceSets;
                case NalConfigs.AVC_PPS -> pictureSets;
                default -> null;
            };
        }
        if (target == null || target.size() >= MAX_PARAMETER_SETS || length > 0xFFFF) {
            return;
        }
        var unit = Arrays.copyOfRange(data, offset, offset + length);
        for (var known : target) {
            if (Arrays.equals(known, unit)) {
                return;
            }
        }
        target.add(unit);
    }

    /**
     * Appends a NAL unit with its four-byte length to an access unit.
     *
     * @param unit the access unit
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length
     */
    private void append(AccessUnit unit, byte[] data, int offset, int length) {
        var required = (long) unit.size + 4 + length;
        if (required > unit.data.length) {
            if (required > CAPACITY) {
                unit.corrupt = true;
                return;
            }
            LimitChecks.checkAllocation(limits, required);
            unit.data = Arrays.copyOf(unit.data, (int) Math.min(CAPACITY, Math.max(required, unit.data.length * 2L)));
        }
        var position = unit.size;
        unit.data[position] = (byte) (length >>> 24);
        unit.data[position + 1] = (byte) (length >>> 16);
        unit.data[position + 2] = (byte) (length >>> 8);
        unit.data[position + 3] = (byte) length;
        System.arraycopy(data, offset, unit.data, position + 4, length);
        unit.size = position + 4 + length;
    }

    /**
     * Completes the access unit being assembled: decides whether it is a key frame, configures the
     * track on the first key frame, stamps it and passes the previous one on.
     *
     * @param sink receives the completed packets
     */
    private void finishAccessUnit(UnitSink sink) {
        var unit = current;
        current = null;
        if (unit == null || !unit.coded) {
            return;
        }
        unit.key = vvc ? unit.idr && !unit.nonRandomAccess : unit.idr || (!hevc && unit.recovery && unit.intra);
        if (track == null && unit.key) {
            configure();
            if (track != null) {
                established = new ArrayList<>(active.values());
            }
        } else if (track != null && unit.idr) {
            unit.newConfig = changedConfig();
        }
        if (unit.dts == TsClock.NONE) {
            if (lastDts == TsClock.NONE) {
                return;
            }
            unit.dts = lastDts + Math.max(1, lastDuration);
            unit.pts = unit.dts;
        }
        if (!seenKey && !unit.key && !keepLeading) {
            return;
        }
        if (track == null) {
            return;
        }
        seenKey = true;
        if (held != null) {
            var duration = unit.dts - held.dts;
            if (duration <= 0) {
                duration = lastDuration;
            }
            emit(held, duration, sink);
        }
        held = unit;
        lastDts = unit.dts;
    }

    /**
     * Passes a completed access unit on as a packet.
     *
     * @param unit the access unit
     * @param duration its duration in 90 kHz ticks
     * @param sink receives the packet
     */
    private void emit(AccessUnit unit, long duration, UnitSink sink) {
        lastDuration = Math.max(0, duration);
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (unit.key) {
            flags.add(PacketFlag.KEY);
        }
        if (unit.corrupt) {
            flags.add(PacketFlag.CORRUPT);
        }
        sink.accept(new Packet(trackId(), Arrays.copyOf(unit.data, unit.size), unit.pts, unit.dts,
                lastDuration > 0 ? lastDuration : Packet.UNKNOWN_DURATION, flags,
                unit.newConfig == null ? PacketSideData.NONE : PacketSideData.builder().newCodecConfig(unit.newConfig).build()),
                unit.fileOffset);
    }

    /**
     * Builds the track from the parameter sets met so far, leaving it unset when they do not
     * suffice.
     */
    private void configure() {
        if (sequenceSets.isEmpty() || pictureSets.isEmpty()) {
            return;
        }
        var config = buildConfig(videoSets, sequenceSets, pictureSets);
        if (config == null) {
            return;
        }
        VideoConfigs.Info info;
        try {
            var first = sequenceSets.getFirst();
            info = vvc ? VideoConfigs.vvcSps(first) : hevc ? VideoConfigs.hevcSps(first) : VideoConfigs.avcSps(first);
        } catch (RuntimeException exception) {
            info = null;
        }
        var builder = VideoTrack.builder()
                .id(trackId())
                .codec(codec)
                .timeBase(TIME_BASE)
                .codecConfig(config)
                .language(entry.language());
        if (info != null) {
            LimitChecks.checkPicture(limits, Math.max(1, info.width()), Math.max(1, info.height()));
            builder.width(info.width()).height(info.height()).frameRate(info.frameRate()).color(info.color());
            if (info.sampleAspect() != null) {
                builder.sampleAspectRatio(info.sampleAspect());
            }
        }
        if (entry.dolbyVision() != null) {
            builder.dolbyVision(entry.dolbyVision());
        }
        track = builder.build();
    }
}
