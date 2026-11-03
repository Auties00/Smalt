package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.internal.TimecodeTags;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.codec.JxsConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes Material Exchange Format files (SMPTE ST 377-1) of the OP1a (SMPTE ST 378) and OP-Atom (SMPTE ST 390)
 * operational patterns, in the generic container (SMPTE ST 379-2).
 *
 * <p>The tracks carried are:
 * <table class="striped">
 * <caption>Tracks</caption>
 * <thead><tr><th scope="col">Codec</th><th scope="col">Mapping</th><th scope="col">Requirements</th></tr></thead>
 * <tbody>
 * <tr><td>{@link Codec#DNXHD}</td><td>VC-3, SMPTE ST 2019-4</td><td>none</td></tr>
 * <tr><td>{@link Codec#PRORES}</td><td>SMPTE RDD 44, frame wrapped</td><td>none</td></tr>
 * <tr><td>{@link Codec#JPEG_XS}</td><td>SMPTE ST 2124, frame wrapped (two field codestreams per element for an
 * interlaced track) or clip wrapped, with the JPEG XS picture sub-descriptor of the first codestream</td>
 * <td>none</td></tr>
 * <tr><td>{@link Codec#H264}</td><td>AVC byte stream, SMPTE ST 381-3, or AVC-Intra (SMPTE RP 2027) with
 * {@link MxfMuxerOptions#avcIntraClass()}</td><td>an {@code avcC} configuration</td></tr>
 * <tr><td>{@link Codec#MPEG1_VIDEO}, {@link Codec#MPEG2_VIDEO}, {@link Codec#MPEG4_PART2}</td><td>MPEG video
 * elementary stream, SMPTE ST 381-1</td><td>none</td></tr>
 * <tr><td>PCM: {@link Codec#PCM_S16LE}, {@link Codec#PCM_S24LE}, {@link Codec#PCM_S32LE}, {@link Codec#PCM_U8},
 * their big-endian and signed 8-bit counterparts, which are converted</td><td>Broadcast Wave, SMPTE ST 382</td>
 * <td>none</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Picture tracks set the edit rate: their {@linkplain VideoTrack#frameRate() frame rate} or, without one, the
 * inverse of their time base; every picture track of a file has the same edit rate, and each packet is one frame.
 * Frame-wrapped sound is cut into one element per edit unit, of the samples falling in that edit unit (the five-frame
 * sequence 1602, 1601, 1602, 1601, 1602 of 48 kHz at 29.97 Hz). Clip wrapping holds one track. Picture packets
 * arrive in decoding order with their presentation timestamps, from which the index table records the temporal
 * offsets of reordered streams; an H.264 track also gets an AVC sub-descriptor whose decoding delay keeps the distance
 * between its first decoding and presentation timestamps. An MPEG-2 track is labeled with the picture coding label of
 * its profile and level, read from the sequence extension of its first frame with its chroma format, and as I-frame
 * only coding, where the SMPTE Labels register has such a label, when every picture written was an I picture; until
 * the stream is complete, and so in the header partition of a sink that cannot seek, it is labeled long GOP.
 *
 * <p>The header partition holds the header metadata, the body partitions the essence, and the footer partition a
 * complete index table, the header metadata (not for OP-Atom) and a random index pack follows it. On a seekable sink
 * {@link #finish()} rewrites the header partition closed and complete, with the final durations; on any other sink the
 * header partition stays open and incomplete and the footer holds the final metadata, so OP-Atom and clip wrapping
 * need a seekable sink. The material package holds a timecode track, starting at the {@code timecode} tag or at
 * zero, the {@code title} tag becomes the material package name, the track titles of the
 * {@linkplain MediaTags#tracks() track tags} the track names, and every other tag a user comment of the material
 * package; pictures, chapters and attachments are not stored.
 *
 * <p>Instances are not thread-safe.
 */
public final class MxfMuxer implements Muxer {
    /**
     * The body stream id of the essence container.
     */
    private static final int BODY_SID = 1;

    /**
     * The index stream id of the index table.
     */
    private static final int INDEX_SID = 2;

    /**
     * The largest number of entries of one index table segment, which keeps its entry array within a local item.
     */
    private static final int SEGMENT_ENTRIES = 4096;

    /**
     * The product UID of the identification set.
     */
    private static final byte[] PRODUCT_UID = MxfLabels.bytes("5b0f3e1a6c2d4f8e9a1b7c3d2e4f5a6b");

    /**
     * The access unit delimiter of an AVC-Intra access unit, I pictures.
     */
    private static final byte[] INTRA_DELIMITER = {0, 0, 0, 1, 9, 0x10};

    /**
     * The VC-3 compression ids by the last-but-two byte of their picture coding label (SMPTE ST 2019-4 Table 4).
     */
    private static final int[][] VC3_IDS = {{1235, 0x01}, {1237, 0x03}, {1238, 0x04}, {1241, 0x07}, {1242, 0x08},
            {1243, 0x09}, {1244, 0x0A}, {1250, 0x10}, {1251, 0x11}, {1252, 0x12}, {1253, 0x13}, {1256, 0x16},
            {1258, 0x18}, {1259, 0x19}, {1260, 0x1A}, {1270, 0x24}, {1271, 0x25}, {1272, 0x26}, {1273, 0x27},
            {1274, 0x28}};

    /**
     * The value of {@link #displayOf} for a content package without a picture.
     */
    private static final long NO_PICTURE = Long.MIN_VALUE + 1;

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The settings.
     */
    private final MxfMuxerOptions options;

    /**
     * The sink offset of the header partition pack, which every partition offset is relative to.
     */
    private final long origin;

    /**
     * The tracks declared, in order.
     */
    private final List<Out> outs = new ArrayList<>();

    /**
     * The tracks by track id.
     */
    private final Map<Integer, Out> byId = new HashMap<>();

    /**
     * The tags to write.
     */
    private MediaTags tags = MediaTags.empty();

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} completed.
     */
    private boolean finished;

    /**
     * Whether the essence is clip wrapped.
     */
    private boolean clip;

    /**
     * The edit rate of the picture tracks, or of the sound of a file without pictures.
     */
    private Rational editRate;

    /**
     * Whether a picture track is declared.
     */
    private boolean hasPictures;

    /**
     * The material package UMID.
     */
    private byte[] materialUmid;

    /**
     * The file package UMID.
     */
    private byte[] fileUmid;

    /**
     * The number of instance UIDs handed out.
     */
    private int uids;

    /**
     * The instance UIDs of the header metadata, fixed at the first serialization.
     */
    private final List<byte[]> instanceUids = new ArrayList<>();

    /**
     * The bytes of the header metadata written in the header partition, primer to the end of its fill.
     */
    private int headerMetadataSize;

    /**
     * The sink offset of the header metadata in the header partition.
     */
    private long headerMetadataAt;

    /**
     * The essence container labels of the tracks, without duplicates.
     */
    private List<byte[]> containers;

    /**
     * The timecode the timecode tracks start at, as a frame count, and its rounded base and drop-frame flag.
     */
    private long timecodeStart;

    private int timecodeBase;

    private boolean timecodeDrop;

    /**
     * The creation time of the packages, as an MXF timestamp.
     */
    private byte[] timestamp = new byte[8];

    /**
     * The scratch buffer for packets.
     */
    private final ByteWriter scratch = new ByteWriter(1 << 16);

    /**
     * The number of edit units written (content packages, or edit units of the clip).
     */
    private long units;

    /**
     * The bytes of the essence container written: elements, keys, lengths and fill, without partitions.
     */
    private long streamOffset;

    /**
     * The edit unit the current body partition started at.
     */
    private long partitionUnit;

    /**
     * The offsets (relative to the origin) and body stream ids of the partitions written, in order.
     */
    private final List<long[]> partitions = new ArrayList<>();

    /**
     * The stream offset of each indexed edit unit.
     */
    private long[] indexOffsets = new long[256];

    /**
     * The flags of each indexed edit unit.
     */
    private byte[] indexFlags = new byte[256];

    /**
     * The display position of each stored edit unit of the indexed picture track.
     */
    private long[] displayOf = new long[256];

    /**
     * Whether any indexed picture is displayed out of its stored order.
     */
    private boolean reordered;

    /**
     * The sink offset of the 8-byte length of the clip element.
     */
    private long clipLengthAt;

    /**
     * The bytes of the clip element written.
     */
    private long clipLength;

    /**
     * Constructs a muxer writing OP1a with {@link MxfMuxerOptions#defaults()}.
     *
     * @param output the output, written from its current position
     */
    public MxfMuxer(MediaSink output) {
        this(output, MxfMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer with the given settings.
     *
     * @param output the output, written from its current position
     * @param options the settings
     * @throws IllegalArgumentException if the options ask for OP-Atom or clip wrapping and the output is not
     * seekable
     */
    public MxfMuxer(MediaSink output, MxfMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        if (!output.isSeekable() && (options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM
                || options.wrapping().orElse(null) == MxfMuxerOptions.Wrapping.CLIP)) {
            throw new IllegalArgumentException("OP-Atom and clip-wrapped MXF files need a seekable output");
        }
        this.origin = output.position();
    }

    /**
     * Returns whether a codec can be written.
     *
     * @param codec the coding format asked about
     * @return {@code true} for the codecs of the class documentation
     */
    public static boolean supports(Codec codec) {
        return switch (codec) {
            case DNXHD, PRORES, H264, MPEG1_VIDEO, MPEG2_VIDEO, MPEG4_PART2, JPEG_XS, PCM_S16LE, PCM_S24LE,
                 PCM_S32LE, PCM_S16BE, PCM_S24BE, PCM_S32BE, PCM_U8, PCM_S8 -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the id is already used, the codec cannot be written, a picture track has
     * an edit rate other than that of the picture tracks declared before it or no frame rate and a time base that is
     * not a frame period, an H.264 track has no {@code avcC} configuration or does not match the AVC-Intra class of
     * the options, a sound track has no sampling rate or channel count, or the pattern or wrapping holds one track and
     * this is a second
     * @throws IllegalStateException if a packet was already written
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (byId.containsKey(track.id())) {
            throw new IllegalArgumentException("track id " + track.id() + " is already used");
        }
        if (!supports(track.codec())) {
            throw new IllegalArgumentException("MXF cannot carry " + track.codec());
        }
        var single = options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM
                || options.wrapping().orElse(null) == MxfMuxerOptions.Wrapping.CLIP;
        if (single && !outs.isEmpty()) {
            throw new IllegalArgumentException("an OP-Atom or clip-wrapped MXF file holds one track");
        }
        var out = new Out(track);
        if (track instanceof VideoTrack video) {
            if (track.codec() == Codec.PRORES && options.wrapping().orElse(null) == MxfMuxerOptions.Wrapping.CLIP) {
                throw new IllegalArgumentException("ProRes is frame wrapped in MXF");
            }
            var rate = video.frameRate().orElse(null);
            if (rate == null) {
                var base = track.timeBase();
                if (base.numerator() > 1001 || base.denominator() < 10L * base.numerator()
                        || base.denominator() > 300L * base.numerator()) {
                    throw new IllegalArgumentException("an MXF picture track needs a frame rate");
                }
                rate = new Rational(base.denominator(), base.numerator());
            }
            rate = rate.reduce();
            for (var other : outs) {
                if (other.kind == 'p' && !other.rate.equals(rate)) {
                    throw new IllegalArgumentException("MXF picture tracks of " + other.rate + " and " + rate
                            + " frames per second");
                }
            }
            out.rate = rate;
            describePicture(out, video);
        } else if (track instanceof AudioTrack audio) {
            var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
            if (audio.sampleRate() <= 0 || channels <= 0) {
                throw new IllegalArgumentException("an MXF sound track needs a sampling rate and a channel count");
            }
            out.sampleRate = audio.sampleRate();
            out.channels = channels;
            var bytes = audio.codec().pcmBitsPerSample().orElse(8) / 8;
            out.bytes = bytes;
            out.blockAlign = bytes * out.channels;
            out.bits = audio.bitsPerSample() > 0 && audio.bitsPerSample() <= 8 * bytes ? audio.bitsPerSample()
                    : 8 * bytes;
            if (out.blockAlign > 0xFFFF) {
                throw new IllegalArgumentException("MXF sound of " + out.channels + " channels");
            }
        } else {
            throw new IllegalArgumentException("MXF cannot carry " + track.codec());
        }
        outs.add(out);
        byId.put(track.id(), out);
    }

    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code title} tag names the material package, the {@code timecode} tag starts the timecode tracks, the
     * titles of the track tags name the tracks, and every other tag becomes a user comment; pictures, chapters,
     * attachments and the XMP packet are not stored.
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws MediaException.Unsupported if the {@code timecode} tag is not a timecode at the frame rate
     * @throws MediaException.LimitExceeded if a tag is longer than a local item holds
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        var timecode = tags.get("timecode").orElse(null);
        if (timecode != null && TimecodeTags.parse(timecode.strip(), firstVideo()) == null) {
            throw new MediaException.Unsupported("mxf timecode", "\"" + timecode + "\" is not a timecode");
        }
        for (var entry : tags.entries()) {
            if (entry.value().length() > 0x7FF0 / 2 || entry.key().length() > 0x7FF0 / 2) {
                throw new MediaException.LimitExceeded("MXF tag " + entry.key() + " is longer than a local item");
            }
        }
        this.tags = tags;
    }

    /**
     * {@return the first declared picture track, or {@code null}}
     */
    private VideoTrack firstVideo() {
        for (var out : outs) {
            if (out.track instanceof VideoTrack video) {
                return video;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet's track was not declared, or an AVC-Intra access unit does not
     * fit the coded frame size of its class
     * @throws IllegalStateException {@inheritDoc}
     * @throws MediaException.Unsupported if the packet changes its track's codec configuration in a way the access
     * units cannot carry
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        var out = byId.get(packet.trackId());
        if (out == null) {
            throw new IllegalArgumentException("undeclared track " + packet.trackId());
        }
        if (!started) {
            start();
        }
        if (out.kind == 'p') {
            if (out.written == 0 && out.frames.isEmpty() && out.firstDts == Packet.NO_TIMESTAMP && out.timed) {
                out.firstDts = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
            }
            if (packet.pts() == Packet.NO_TIMESTAMP) {
                out.timed = false;
                out.firstDts = Packet.NO_TIMESTAMP;
            } else if (out.timed) {
                out.minimumPts = Math.min(out.minimumPts, packet.pts());
            }
            var element = pictureElement(out, packet);
            if (clip) {
                writeClipPicture(out, element, packet);
            } else {
                out.frames.add(new Frame(element, packet.keyframe(), packet.pts(), headerFlag(out, packet)));
            }
        } else {
            var samples = pcm(out, packet.data());
            if (clip) {
                writeRaw(samples, 0, samples.length);
                clipLength += samples.length;
                out.samples += samples.length / out.blockAlign;
                units = out.samples;
            } else {
                out.pcm.bytes(samples);
            }
        }
        if (!clip) {
            drain(false);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (!started) {
            start();
        }
        if (clip) {
            patchClipLength();
        } else {
            drain(true);
        }
        finished = true;
        writeFooter();
    }

    @Override
    public void close() {
    }

    /**
     * One declared track.
     */
    private static final class Out {
        private final Track track;
        private final char kind;
        private Rational rate;
        private int trackNumber;
        private byte[] key;
        private int fileTrackId;
        private byte[] container;
        private byte[] coding;
        private int width;
        private int height;
        private boolean interlaced;
        private boolean topFirst = true;
        private Rational sampleAspect;
        private ColorInfo color;
        private int depth = 8;
        private int horizontalSubsampling = 2;
        private int verticalSubsampling = 1;
        private int mpegProfileLevel = -1;
        private boolean mpegConstrained;
        private boolean longGop;
        private int lengthSize = 4;
        private byte[] parameterSets;
        private int avcClass;
        private int avcRaster = -1;
        private long written;
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private int sampleRate;
        private int channels;
        private int bytes;
        private int bits;
        private int blockAlign;
        private long samples;
        private long firstDts = Packet.NO_TIMESTAMP;
        private long minimumPts = Long.MAX_VALUE;
        private long maximumPts = Long.MIN_VALUE;
        private boolean timed = true;
        private ByteWriter pcm = new ByteWriter(1 << 12);
        private int pcmStart;
        private boolean described;

        /**
         * The main header of the first codestream of a JPEG XS track, from {@code SOC} to its first slice header,
         * or {@code null}.
         */
        private byte[] jxsHeader;

        private Out(Track track) {
            this.track = track;
            this.kind = track instanceof VideoTrack ? 'p' : 's';
        }
    }

    /**
     * One stored picture waiting for its content package.
     *
     * @param data the element value
     * @param key whether decoding can start at it
     * @param pts its presentation timestamp in the track's time base, or {@link Packet#NO_TIMESTAMP}
     * @param flags the index entry flags it contributes beyond random access
     */
    private record Frame(byte[] data, boolean key, long pts, int flags) {
    }

    /**
     * Reads the picture parameters of a track into its descriptor fields.
     *
     * @param out the track
     * @param video the declared track
     */
    private void describePicture(Out out, VideoTrack video) {
        out.width = video.width();
        out.height = video.height();
        out.interlaced = video.interlacing().map(scan -> scan.isInterlaced()).orElse(false);
        out.topFirst = video.interlacing().map(scan -> scan.isTopFieldFirst() || !scan.isInterlaced())
                .orElse(true);
        out.sampleAspect = video.sampleAspectRatio().orElse(null);
        out.color = video.color().orElse(null);
        var config = video.codecConfig().orElse(null);
        switch (video.codec()) {
            case H264 -> {
                if (config == null) {
                    throw new IllegalArgumentException("an MXF H.264 track needs an avcC configuration");
                }
                var parsed = NalConfigs.parseAvc(config);
                var sps = parsed == null ? null : firstSps(parsed.parameterSets());
                if (sps == null) {
                    throw new IllegalArgumentException("invalid avcC configuration");
                }
                out.lengthSize = parsed.lengthSize();
                out.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                var info = VideoConfigs.avcSps(sps);
                if (info != null) {
                    out.width = info.width() > 0 ? info.width() : out.width;
                    out.height = info.height() > 0 ? info.height() : out.height;
                    out.depth = info.bitDepth() > 0 ? info.bitDepth() : 8;
                    subsampling(out, info.chroma());
                    if (out.color == null) {
                        out.color = info.color();
                    }
                }
                var profile = sps.length > 1 ? sps[1] & 0xFF : 0;
                var constraints = sps.length > 2 ? sps[2] & 0xFF : 0;
                out.coding = avcCoding(profile, constraints);
                var cls = options.avcIntraClass();
                if (cls != 0) {
                    var expected = cls == 50 ? 110 : 122;
                    if (profile != expected) {
                        throw new IllegalArgumentException("AVC-Intra class " + cls + " is coded with profile "
                                + expected + ", not " + profile);
                    }
                    var raster = AvcIntra.rasterOf(new byte[16], out.height, out.interlaced, out.rate);
                    var size = raster < 0 ? null : AvcIntra.pictureSize(cls, raster);
                    if (size == null || size[0] != out.width || size[1] != out.height) {
                        throw new IllegalArgumentException("a " + out.width + "x" + out.height + " track at "
                                + out.rate + " Hz is not an AVC-Intra class " + cls + " raster");
                    }
                    out.avcClass = cls;
                    out.avcRaster = raster;
                    out.coding = AvcIntra.codingLabel(cls, raster);
                }
            }
            case PRORES -> {
                var profile = config != null && config.length == 4 ? switch (new String(config,
                        StandardCharsets.US_ASCII)) {
                    case "apco" -> 1;
                    case "apcs" -> 2;
                    case "apcn" -> 3;
                    case "apch" -> 4;
                    case "ap4h" -> 5;
                    case "ap4x" -> 6;
                    default -> 0;
                } : 0;
                out.depth = profile >= 5 ? 12 : 10;
                out.horizontalSubsampling = profile >= 5 ? 1 : 2;
                if (profile > 0) {
                    out.coding = MxfLabels.PRORES_CODING.clone();
                    out.coding[14] = (byte) profile;
                    out.described = true;
                }
            }
            case MPEG4_PART2 -> out.coding = MxfLabels.bytes("060e2b34040101030401020201200000");
            case JPEG_XS -> {
                out.coding = MxfLabels.JXS_CODING.clone();
                var parsed = JxsConfigs.parseConfig(config);
                if (parsed != null) {
                    out.coding[14] = (byte) jxsProfileIndex(parsed.profile());
                    if ((parsed.schar() & 0x8000) != 0) {
                        out.depth = ((parsed.schar() >>> 4) & 0x0F) + 1;
                        var sampling = parsed.schar() & 0x0F;
                        out.horizontalSubsampling = sampling == 0 || sampling == 3 || sampling == 4 ? 2 : 1;
                        out.verticalSubsampling = sampling == 3 ? 2 : 1;
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * {@return the profile byte of the JPEG XS picture coding label of a profile (SMPTE ST 2124 table 3), 1
     * (unrestricted) for a profile the table does not name}
     *
     * @param profile the profile code {@code Ppih}
     */
    private static int jxsProfileIndex(int profile) {
        return switch (profile) {
            case 0x3540 -> 2;
            case 0x3A40 -> 3;
            case 0x3E40 -> 4;
            case 0x1500 -> 5;
            case 0x1A00 -> 6;
            case 0x2500 -> 7;
            case 0x4A40 -> 8;
            case 0x4E40 -> 9;
            default -> 1;
        };
    }

    /**
     * {@return the first sequence parameter set of a list of H.264 parameter sets, or {@code null}}
     *
     * @param sets the parameter sets, whole NAL units
     */
    private static byte[] firstSps(List<byte[]> sets) {
        for (var set : sets) {
            if (set.length > 3 && (set[0] & 0x1F) == NalConfigs.AVC_SPS) {
                return set;
            }
        }
        return null;
    }

    /**
     * Sets the subsampling of a track from its chroma format.
     */
    private static void subsampling(Out out, com.github.auties00.smalt.image.ChromaFormat chroma) {
        if (chroma == null) {
            return;
        }
        switch (chroma) {
            case YUV420 -> {
                out.horizontalSubsampling = 2;
                out.verticalSubsampling = 2;
            }
            case YUV444 -> {
                out.horizontalSubsampling = 1;
                out.verticalSubsampling = 1;
            }
            default -> {
                out.horizontalSubsampling = 2;
                out.verticalSubsampling = 1;
            }
        }
    }

    /**
     * {@return the picture coding label of an H.264 profile}
     *
     * @param profile {@code profile_idc}
     * @param constraints the constraint flags byte
     */
    private static byte[] avcCoding(int profile, int constraints) {
        var intra = (constraints & 0x10) != 0;
        var code = switch (profile) {
            case 66 -> 0x311001;
            case 77 -> 0x312001;
            case 88 -> 0x313001;
            case 100 -> 0x314001;
            case 110 -> intra ? 0x322001 : 0x315001;
            case 122 -> intra ? 0x323001 : 0x316001;
            case 244 -> intra ? 0x324001 : 0x317001;
            case 44 -> 0x325001;
            default -> 0x300000;
        };
        var label = MxfLabels.bytes("060e2b34040101000401020201000000");
        label[13] = (byte) (code >>> 16);
        label[14] = (byte) (code >>> 8);
        label[15] = (byte) code;
        label[7] = (byte) (code >= 0x320000 && code < 0x324000 || code == 0x300000 ? 0x0A : 0x0D);
        return label;
    }

    /**
     * Writes the header partition, and the first body partition and clip header, at the first packet.
     */
    private void start() {
        started = true;
        var hasPicture = false;
        for (var out : outs) {
            hasPicture |= out.kind == 'p';
        }
        var wrapping = options.wrapping().orElse(null);
        if (wrapping == null) {
            wrapping = options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM
                    || outs.size() == 1 && !hasPicture && output.isSeekable()
                    ? MxfMuxerOptions.Wrapping.CLIP : MxfMuxerOptions.Wrapping.FRAME;
        }
        clip = wrapping == MxfMuxerOptions.Wrapping.CLIP && outs.size() == 1
                && outs.getFirst().track.codec() != Codec.PRORES;
        if (outs.isEmpty()) {
            clip = false;
        }
        editRate = null;
        for (var out : outs) {
            if (out.kind == 'p') {
                editRate = out.rate;
                break;
            }
        }
        if (editRate == null && outs.isEmpty()) {
            editRate = options.audioEditRate();
        } else if (editRate == null) {
            editRate = clip ? new Rational(outs.getFirst().sampleRate, 1) : options.audioEditRate();
        }
        hasPictures = hasPicture;
        assignNumbers();
        identifiers();
        var timecode = TimecodeTags.parse(tags.get("timecode").map(String::strip).orElse(null), firstVideo());
        var rate = timecodeRate();
        timecodeBase = (int) Math.max(1, (rate.numerator() + rate.denominator() - 1) / rate.denominator());
        if (timecode != null) {
            timecodeStart = timecode.frameNumber();
            timecodeBase = timecode.framesPerSecond();
            timecodeDrop = timecode.dropFrame();
        }
        timestamp = timestamp(tags.get(StandardTag.CREATION_TIME).orElse(null));
        writeHeaderPartition();
        if (!outs.isEmpty()) {
            writeBodyPartition();
        }
        if (clip) {
            var out = outs.getFirst();
            scratch.reset();
            scratch.bytes(out.key);
            scratch.u8(0x87);
            clipLengthAt = output.position() + 17;
            scratch.zeros(7);
            writeScratch();
            streamOffset = 0;
        }
    }

    /**
     * Assigns the track numbers, element keys and essence container labels.
     */
    private void assignNumbers() {
        var pictures = 0;
        var sounds = 0;
        for (var out : outs) {
            if (out.kind == 'p') {
                pictures++;
            } else {
                sounds++;
            }
        }
        var picture = 0;
        var sound = 0;
        var trackId = 2;
        var labels = new LinkedHashSet<String>();
        containers = new ArrayList<>();
        for (var out : outs) {
            int type;
            int item;
            int count;
            int number;
            if (out.kind == 'p') {
                item = MxfLabels.PICTURE_ITEM;
                count = pictures;
                number = ++picture;
                switch (out.track.codec()) {
                    case DNXHD -> {
                        type = clip ? 0x0D : 0x0C;
                        out.container = clip ? MxfLabels.VC3_CLIP : MxfLabels.VC3_FRAME;
                    }
                    case PRORES -> {
                        type = 0x17;
                        out.container = MxfLabels.PRORES_FRAME;
                    }
                    case H264 -> {
                        type = clip ? 0x06 : 0x05;
                        out.container = clip ? MxfLabels.AVC_CLIP : MxfLabels.AVC_FRAME;
                    }
                    case JPEG_XS -> {
                        type = clip ? 0x1B : 0x1A;
                        out.container = clip ? MxfLabels.JXS_CLIP : out.interlaced ? MxfLabels.JXS_FRAME_INTERLACED
                                : MxfLabels.JXS_FRAME;
                    }
                    default -> {
                        type = clip ? 0x06 : 0x05;
                        out.container = clip ? MxfLabels.MPEG_CLIP : MxfLabels.MPEG_FRAME;
                    }
                }
            } else {
                item = MxfLabels.SOUND_ITEM;
                count = sounds;
                number = ++sound;
                type = clip ? 0x02 : 0x01;
                out.container = clip ? MxfLabels.BWF_CLIP : MxfLabels.BWF_FRAME;
            }
            out.trackNumber = item << 24 | count << 16 | type << 8 | number;
            out.key = Arrays.copyOf(MxfLabels.ELEMENT_PREFIX, 16);
            out.key[12] = (byte) item;
            out.key[13] = (byte) count;
            out.key[14] = (byte) type;
            out.key[15] = (byte) number;
            out.fileTrackId = trackId++;
            if (labels.add(Arrays.toString(out.container))) {
                containers.add(out.container);
            }
        }
    }

    /**
     * Chooses the package UMIDs.
     */
    private void identifiers() {
        var material = options.materialPackageUmid().orElse(null);
        if (material == null) {
            material = Arrays.copyOf(MxfLabels.UMID_PREFIX, 32);
            var random = UUID.randomUUID();
            ByteBuffer.wrap(material, 16, 16).putLong(random.getMostSignificantBits())
                    .putLong(random.getLeastSignificantBits());
        }
        materialUmid = material;
        fileUmid = Arrays.copyOf(MxfLabels.UMID_PREFIX, 32);
        System.arraycopy(digest(material, -1), 0, fileUmid, 16, 16);
    }

    /**
     * {@return 16 bytes derived from the material package UMID and a counter, unique for each counter}
     *
     * @param umid the UMID
     * @param counter the index of the identifier derived
     */
    private static byte[] digest(byte[] umid, int counter) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update(umid);
            sha.update(new byte[]{(byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8),
                    (byte) counter});
            return Arrays.copyOf(sha.digest(), 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * {@return the next instance UID}
     */
    private byte[] uid() {
        if (uids == instanceUids.size()) {
            instanceUids.add(digest(materialUmid, uids));
        }
        return instanceUids.get(uids++);
    }

    /**
     * Converts a creation time tag into an MXF timestamp.
     *
     * @param text the ISO 8601 date and time, or {@code null}
     * @return the 8-byte timestamp, zero when the tag is absent or unreadable
     */
    private static byte[] timestamp(String text) {
        var out = new byte[8];
        if (text == null) {
            return out;
        }
        try {
            var time = Instant.parse(text.strip()).atOffset(ZoneOffset.UTC);
            out[0] = (byte) (time.getYear() >>> 8);
            out[1] = (byte) time.getYear();
            out[2] = (byte) time.getMonthValue();
            out[3] = (byte) time.getDayOfMonth();
            out[4] = (byte) time.getHour();
            out[5] = (byte) time.getMinute();
            out[6] = (byte) time.getSecond();
            out[7] = (byte) (time.getNano() / 4_000_000);
        } catch (DateTimeParseException | ArithmeticException exception) {
            return new byte[8];
        }
        return out;
    }

    /**
     * Builds the element value of a picture packet.
     *
     * @param out the track
     * @param packet the packet written
     * @return the element value
     */
    private byte[] pictureElement(Out out, Packet packet) {
        var data = packet.data();
        var newConfig = packet.sideData().newCodecConfig().orElse(null);
        if (out.track.codec() == Codec.H264) {
            if (newConfig != null) {
                var parsed = NalConfigs.parseAvc(newConfig);
                if (parsed == null || firstSps(parsed.parameterSets()) == null) {
                    throw new MediaException.Unsupported("mxf config change", "invalid avcC configuration change");
                }
                out.parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                out.lengthSize = parsed.lengthSize();
            }
            if (out.avcClass != 0) {
                return avcIntraFrame(out, data);
            }
            return NalUnits.toAnnexB(data, out.lengthSize, false, packet.keyframe() ? out.parameterSets : null, null);
        }
        if (!out.described) {
            describeFromFrame(out, data);
        }
        if (!out.longGop && (out.track.codec() == Codec.MPEG2_VIDEO || out.track.codec() == Codec.MPEG1_VIDEO)) {
            out.longGop = !isMpegIntra(data);
        }
        return data;
    }

    /**
     * {@return whether every picture header of an MPEG-1 or MPEG-2 video frame, of which there are two for a frame
     * coded as two field pictures, codes an I picture; {@code false} for a frame without a picture header}
     *
     * @param data the frame
     */
    private static boolean isMpegIntra(byte[] data) {
        var pictures = 0;
        for (var at = startCode(data, 0, 0x00); at >= 0 && at + 5 < data.length; at = startCode(data, at + 4, 0x00)) {
            if (((data[at + 5] >>> 3) & 7) != 1) {
                return false;
            }
            pictures++;
        }
        return pictures > 0;
    }

    /**
     * {@return the offset of the first MPEG video start code of a value at or after an offset, or {@code -1}}
     *
     * @param data the frame
     * @param from the first offset searched
     * @param code the start code value, the byte after {@code 00 00 01}
     */
    private static int startCode(byte[] data, int from, int code) {
        for (var at = from; at + 3 < data.length; at++) {
            if (data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1 && (data[at + 3] & 0xFF) == code) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Reads the profile and level, the constrained parameters flag and the chroma format of an MPEG-1 or MPEG-2 video
     * track from the sequence header and sequence extension of its first frame.
     *
     * @param out the track
     * @param data the frame
     */
    private static void describeMpegVideo(Out out, byte[] data) {
        out.horizontalSubsampling = 2;
        out.verticalSubsampling = 2;
        var header = startCode(data, 0, 0xB3);
        if (header < 0 || header + 12 > data.length) {
            return;
        }
        out.mpegConstrained = (data[header + 11] & 0x04) != 0;
        for (var at = startCode(data, header + 4, 0xB5); at >= 0; at = startCode(data, at + 4, 0xB5)) {
            if (at + 6 > data.length) {
                return;
            }
            if ((data[at + 4] & 0xF0) == 0x10) {
                out.mpegProfileLevel = (data[at + 4] & 0x0F) << 4 | (data[at + 5] & 0xFF) >>> 4;
                var chroma = (data[at + 5] >>> 1) & 3;
                out.horizontalSubsampling = chroma == 3 ? 1 : 2;
                out.verticalSubsampling = chroma == 1 ? 2 : 1;
                return;
            }
        }
    }

    /**
     * Returns the picture coding label of an MPEG-1 or MPEG-2 video track from the SMPTE Labels register: the label of
     * its profile and level, and of its picture structure where the register distinguishes I-frame only from long GOP
     * coding.
     *
     * @param out the track
     * @param known whether the whole stream was written, so that an I-frame only stream can be labeled as such; the
     *              label is a long GOP one until then
     * @return the 16-byte label
     */
    private static byte[] mpegVideoCoding(Out out, boolean known) {
        var intra = known && !out.longGop;
        if (out.track.codec() == Codec.MPEG1_VIDEO) {
            return MxfLabels.bytes(out.mpegConstrained ? "060e2b34040101030401020201100100"
                    : "060e2b34040101030401020201100200");
        }
        var level = out.mpegProfileLevel;
        // labels whose last byte but one distinguishes I-frame only (02, or 10 for MP@ML) from long GOP (03, or 11)
        var structured = switch (level) {
            case 0x48 -> "0101";
            case 0x85 -> "0102";
            case 0x44 -> "0103";
            case 0x82 -> "0104";
            case 0x46 -> "0105";
            case 0x18 -> "0106";
            case 0x14 -> "0107";
            case 0x16 -> "0108";
            default -> null;
        };
        if (structured != null) {
            var version = switch (structured) {
                case "0105" -> "08";
                case "0106", "0107", "0108" -> "09";
                default -> "03";
            };
            var structure = level == 0x48 ? (intra ? "10" : "11") : intra ? "02" : "03";
            return MxfLabels.bytes("060e2b34040101" + version + "04010202" + structured + structure + "00");
        }
        var other = switch (level) {
            case 0x5A -> "0100";
            case 0x58 -> "0200";
            case 0x54 -> "0300";
            case 0x56 -> "0400";
            case 0x4A -> "1000";
            case 0x1A -> "2000";
            case 0x2A -> "3000";
            case 0x28 -> "3100";
            case 0x26 -> "3200";
            case 0x3A -> "4000";
            case 0x38 -> "4100";
            case 0x34 -> "4200";
            case 0x36 -> "4300";
            case 0x8E -> "5000";
            case 0x8D -> "5100";
            case 0x8A -> "5200";
            case 0x8B -> "5300";
            default -> "0000";
        };
        return MxfLabels.bytes("060e2b340401010a040102020109" + other);
    }

    /**
     * Completes the descriptor fields of a track from its first frame.
     *
     * @param out the track
     * @param data the frame
     */
    private void describeFromFrame(Out out, byte[] data) {
        out.described = true;
        switch (out.track.codec()) {
            case JPEG_XS -> {
                var picture = JxsConfigs.picture(data, 0, data.length);
                if (picture == null) {
                    return;
                }
                out.jxsHeader = jxsMainHeader(data);
                out.coding = MxfLabels.JXS_CODING.clone();
                out.coding[14] = (byte) jxsProfileIndex(picture.profile());
                if (picture.bitDepth() > 0) {
                    out.depth = picture.bitDepth();
                }
                if (picture.sampling() >= 0) {
                    var sampling = picture.sampling();
                    out.horizontalSubsampling = sampling == 0 || sampling == 3 || sampling == 4 ? 2 : 1;
                    out.verticalSubsampling = sampling == 3 ? 2 : 1;
                }
            }
            case DNXHD -> {
                if (data.length < 0x30) {
                    return;
                }
                var cid = (data[0x28] & 0xFF) << 24 | (data[0x29] & 0xFF) << 16 | (data[0x2A] & 0xFF) << 8
                        | data[0x2B] & 0xFF;
                out.coding = MxfLabels.VC3_CODING.clone();
                for (var entry : VC3_IDS) {
                    if (entry[0] == cid) {
                        out.coding[13] = (byte) entry[1];
                        // the register version of the labels added with 1244 and from 1256 on
                        out.coding[7] = (byte) (cid == 1244 || cid >= 1256 ? 0x0D : 0x0A);
                    }
                }
                out.depth = switch ((data[0x21] & 0xFF) >>> 5) {
                    case 2 -> 10;
                    case 3 -> 12;
                    default -> 8;
                };
                var chroma = (data[0x2C] >>> 5) & 3;
                out.horizontalSubsampling = chroma == 2 ? 1 : 2;
                out.verticalSubsampling = chroma == 1 ? 2 : 1;
                out.interlaced = (data[5] & 3) == 2;
            }
            case PRORES -> {
                var header = data.length >= 8 && data[4] == 'i' && data[5] == 'c' && data[6] == 'p' && data[7] == 'f'
                        ? 8 : 0;
                var type = CodecConfigs.quickTimeVisualType(true, null, data);
                out.coding = MxfLabels.PRORES_CODING.clone();
                var chroma444 = type == 0x61703468;
                out.coding[14] = (byte) (chroma444 ? 5 : 3);
                out.depth = chroma444 ? 12 : 10;
                out.horizontalSubsampling = chroma444 ? 1 : 2;
                if (data.length > header + 12) {
                    var mode = (data[header + 12] >>> 2) & 3;
                    out.interlaced = mode != 0;
                    out.topFirst = mode != 2;
                }
            }
            case MPEG1_VIDEO, MPEG2_VIDEO -> describeMpegVideo(out, data);
            case MPEG4_PART2 -> {
                out.horizontalSubsampling = 2;
                out.verticalSubsampling = 2;
            }
            default -> {
            }
        }
    }

    /**
     * Lays out an AVC-Intra access unit as SMPTE RP 2027 requires.
     *
     * @param out the track
     * @param data the length-prefixed access unit
     * @return the Annex B access unit, of the class's size
     */
    private byte[] avcIntraFrame(Out out, byte[] data) {
        var sei = new ByteWriter(1024);
        var slices = new ByteWriter(data.length + 64);
        var offset = 0;
        while (offset < data.length) {
            if (offset + out.lengthSize > data.length) {
                throw new IllegalArgumentException("truncated NAL unit length");
            }
            var length = (int) NalUnits.readLength(data, offset, out.lengthSize);
            var start = offset + out.lengthSize;
            if (length < 0 || length > data.length - start) {
                throw new IllegalArgumentException("NAL unit of " + length + " bytes overruns its packet");
            }
            offset = start + length;
            if (length == 0) {
                continue;
            }
            var type = data[start] & 0x1F;
            if (type == 9 || type == 7 || type == 8 || type == 12) {
                continue;
            }
            var target = type == 6 ? sei : slices;
            target.bytes(new byte[]{0, 0, 0, 1});
            target.bytes(data, start, length);
        }
        var headers = options.avcIntraParameterSets();
        var blocks = AvcIntra.seiBlocks(out.avcRaster);
        var total = AvcIntra.codedFrameSize(out.avcClass, out.avcRaster) + 512 * (blocks + (headers ? 1 : 0));
        var frame = new byte[total];
        System.arraycopy(INTRA_DELIMITER, 0, frame, 0, INTRA_DELIMITER.length);
        var at = INTRA_DELIMITER.length;
        if (headers) {
            if (at + out.parameterSets.length > 512) {
                throw new IllegalArgumentException("AVC-Intra parameter sets longer than 506 bytes");
            }
            System.arraycopy(out.parameterSets, 0, frame, at, out.parameterSets.length);
            at = 512;
        }
        var seiEnd = 512 * (blocks + (headers ? 1 : 0));
        if (at + sei.size() > seiEnd) {
            throw new IllegalArgumentException("AVC-Intra SEI messages longer than " + (seiEnd - at) + " bytes");
        }
        System.arraycopy(sei.buffer(), 0, frame, at, sei.size());
        if (seiEnd + slices.size() > total) {
            throw new IllegalArgumentException("AVC-Intra picture of " + slices.size() + " bytes exceeds the "
                    + AvcIntra.codedFrameSize(out.avcClass, out.avcRaster) + " byte frame of class " + out.avcClass);
        }
        System.arraycopy(slices.buffer(), 0, frame, seiEnd, slices.size());
        return frame;
    }

    /**
     * {@return the index flags a picture contributes beyond random access: the sequence header flag and the
     * prediction bits; an H.264 picture presented before a picture written earlier is flagged bidirectional}
     *
     * @param out the track
     * @param packet the packet written
     */
    private static int headerFlag(Out out, Packet packet) {
        var data = packet.data();
        var flags = 0;
        switch (out.track.codec()) {
            case H264 -> {
                // a picture presented before one decoded earlier is coded from a later picture: bidirectional
                var pts = packet.pts();
                flags |= packet.keyframe() ? 0x40 : pts == Packet.NO_TIMESTAMP ? 0x33 : pts < out.maximumPts ? 0x30
                        : 0x20;
                if (pts != Packet.NO_TIMESTAMP) {
                    out.maximumPts = Math.max(out.maximumPts, pts);
                }
            }
            case MPEG1_VIDEO, MPEG2_VIDEO -> {
                for (var at = 0; at + 6 < data.length; at++) {
                    if (data[at] != 0 || data[at + 1] != 0 || data[at + 2] != 1) {
                        continue;
                    }
                    var code = data[at + 3] & 0xFF;
                    if (code == 0xB3) {
                        flags |= 0x40;
                    } else if (code == 0) {
                        var type = (data[at + 5] >>> 3) & 7;
                        flags |= type == 2 ? 0x20 : type == 3 ? 0x30 : 0;
                        break;
                    }
                }
            }
            default -> {
            }
        }
        return flags;
    }

    /**
     * Converts the samples of a packet into little-endian Broadcast Wave samples.
     *
     * @param out the track
     * @param data the samples in the track's codec
     * @return the converted samples, {@code data} itself when no conversion is needed
     */
    private static byte[] pcm(Out out, byte[] data) {
        var codec = out.track.codec();
        var length = data.length - data.length % out.blockAlign;
        switch (codec) {
            case PCM_S16BE, PCM_S24BE, PCM_S32BE -> {
                var converted = new byte[length];
                var size = out.bytes;
                for (var at = 0; at < length; at += size) {
                    for (var index = 0; index < size; index++) {
                        converted[at + index] = data[at + size - 1 - index];
                    }
                }
                return converted;
            }
            case PCM_S8 -> {
                var converted = new byte[length];
                for (var at = 0; at < length; at++) {
                    converted[at] = (byte) (data[at] ^ 0x80);
                }
                return converted;
            }
            default -> {
                return length == data.length ? data : Arrays.copyOf(data, length);
            }
        }
    }

    /**
     * {@return the samples of the sound element of an edit unit}
     *
     * @param out the sound track
     * @param unit the edit unit
     */
    private long samplesOf(Out out, long unit) {
        return cumulative(out, unit + 1) - cumulative(out, unit);
    }

    /**
     * {@return the samples before an edit unit, rounded to nearest}
     */
    private long cumulative(Out out, long unit) {
        var exact = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(out.sampleRate))
                .multiply(BigInteger.valueOf(editRate.denominator()));
        var divisor = BigInteger.valueOf(editRate.numerator());
        return exact.shiftLeft(1).add(divisor).divide(divisor.shiftLeft(1)).longValueExact();
    }

    /**
     * Writes the content packages whose elements are all available.
     *
     * @param last whether the input ended, so that incomplete content packages are written too
     */
    private void drain(boolean last) {
        while (true) {
            var ready = !outs.isEmpty();
            var any = false;
            for (var out : outs) {
                if (out.kind == 'p') {
                    ready &= !out.frames.isEmpty();
                    any |= !out.frames.isEmpty();
                } else {
                    var available = (out.pcm.size() - out.pcmStart) / out.blockAlign;
                    ready &= available >= samplesOf(out, units);
                    any |= available > 0;
                }
            }
            if (!ready && !(last && any)) {
                return;
            }
            writeContentPackage();
        }
    }

    /**
     * Writes the content package of the next edit unit.
     */
    private void writeContentPackage() {
        Frame lead = null;
        for (var out : outs) {
            if (out.kind == 'p' && !out.frames.isEmpty()) {
                lead = out.frames.peek();
                break;
            }
        }
        var allKey = true;
        for (var out : outs) {
            if (out.kind == 'p') {
                allKey &= out.frames.isEmpty() || out.frames.peek().key;
            }
        }
        var partitionUnits = Math.max(1L, Rational.MICROSECONDS.rescale(options.partitionMicros(),
                new Rational(editRate.denominator(), editRate.numerator())));
        if (options.partitionMicros() > 0 && units - partitionUnit >= partitionUnits && allKey) {
            writeBodyPartition();
        }
        var flags = lead == null ? 0x80 : (lead.key ? 0x80 : 0) | lead.flags;
        index(units, streamOffset, flags, lead == null ? NO_PICTURE : lead.pts);
        scratch.reset();
        var start = output.position();
        for (var out : outs) {
            if (out.kind == 'p') {
                var frame = out.frames.poll();
                if (frame == null) {
                    continue;
                }
                element(out, frame.data, 0, frame.data.length, start);
                out.written++;
            } else {
                var wanted = samplesOf(out, units);
                var available = (out.pcm.size() - out.pcmStart) / out.blockAlign;
                var samples = Math.min(wanted, available);
                if (samples <= 0) {
                    continue;
                }
                var bytes = (int) (samples * out.blockAlign);
                element(out, out.pcm.buffer(), out.pcmStart, bytes, start);
                out.pcmStart += bytes;
                out.samples += samples;
                if (out.pcmStart > 1 << 16 && out.pcmStart * 2 > out.pcm.size()) {
                    var rest = new ByteWriter(Math.max(1 << 12, out.pcm.size() - out.pcmStart));
                    rest.bytes(out.pcm.buffer(), out.pcmStart, out.pcm.size() - out.pcmStart);
                    out.pcm = rest;
                    out.pcmStart = 0;
                }
            }
        }
        streamOffset += scratch.size();
        writeScratch();
        units++;
    }

    /**
     * Appends one essence element and the fill after it to {@link #scratch}.
     *
     * @param out the track
     * @param data the array holding the value
     * @param offset the index of the value
     * @param length the value length
     * @param base the sink offset of the start of {@link #scratch}
     */
    private void element(Out out, byte[] data, int offset, int length, long base) {
        scratch.bytes(out.key);
        if (out.track.codec() == Codec.PRORES) {
            scratch.u8(0x84);
            scratch.u32be(length);
        } else {
            if (length >= 1 << 24) {
                scratch.u8(0x84);
                scratch.u32be(length);
            } else {
                scratch.u8(0x83);
                scratch.u24be(length);
            }
        }
        scratch.bytes(data, offset, length);
        MxfSetWriter.fill(scratch, base - origin + scratch.size(), options.kagSize());
    }

    /**
     * Records the index entry of an edit unit.
     *
     * @param unit the stored edit unit
     * @param offset its stream offset
     * @param flags its flags
     * @param pts the presentation timestamp of its lead picture, or {@link Packet#NO_TIMESTAMP}
     */
    private void index(long unit, long offset, int flags, long pts) {
        if (unit >= Integer.MAX_VALUE - 16) {
            throw new IllegalStateException("MXF file of more than " + (Integer.MAX_VALUE - 16) + " edit units");
        }
        var at = (int) unit;
        if (at >= indexOffsets.length) {
            var capacity = indexOffsets.length * 2;
            indexOffsets = Arrays.copyOf(indexOffsets, capacity);
            indexFlags = Arrays.copyOf(indexFlags, capacity);
            displayOf = Arrays.copyOf(displayOf, capacity);
        }
        indexOffsets[at] = offset;
        indexFlags[at] = (byte) flags;
        displayOf[at] = pts;
    }

    /**
     * Writes a picture packet into the clip.
     *
     * @param out the track
     * @param element the element value
     * @param packet the packet written
     */
    private void writeClipPicture(Out out, byte[] element, Packet packet) {
        index(units, clipLength, (packet.keyframe() ? 0x80 : 0) | headerFlag(out, packet), packet.pts());
        writeRaw(element, 0, element.length);
        clipLength += element.length;
        out.written++;
        units++;
    }

    /**
     * Fills in the length of the clip element and pads after it.
     */
    private void patchClipLength() {
        var end = output.position();
        output.position(clipLengthAt);
        var length = new byte[8];
        ByteBuffer.wrap(length).putLong(clipLength);
        output.write(length, 1, 7);
        output.position(end);
        scratch.reset();
        MxfSetWriter.fill(scratch, end - origin, options.kagSize());
        writeScratch();
    }

    /**
     * Writes the header partition: pack, header metadata and fill.
     */
    private void writeHeaderPartition() {
        var metadata = headerMetadata(false, headerMetadataStart());
        scratch.reset();
        var packStart = output.position();
        partitionPack(scratch, 2, 1, 0, 0, 0, metadata.length, 0, 0, 0, 0);
        MxfSetWriter.fill(scratch, packStart - origin + scratch.size(), options.kagSize());
        headerMetadataAt = packStart + scratch.size();
        scratch.bytes(metadata);
        headerMetadataSize = metadata.length;
        writeScratch();
        partitions.add(new long[]{packStart - origin, 0});
    }

    /**
     * Starts a body partition.
     */
    private void writeBodyPartition() {
        scratch.reset();
        var packStart = output.position();
        var previous = partitions.getLast()[0];
        partitionPack(scratch, 3, 4, packStart - origin, previous, 0, 0, 0, 0, streamOffset, BODY_SID);
        MxfSetWriter.fill(scratch, packStart - origin + scratch.size(), options.kagSize());
        writeScratch();
        partitions.add(new long[]{packStart - origin, BODY_SID});
        partitionUnit = units;
    }

    /**
     * Writes the footer partition, the random index pack and, on a seekable sink, the final header partition.
     */
    private void writeFooter() {
        var footerAt = output.position() - origin;
        var atom = options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM;
        var probe = new ByteWriter(64);
        MxfSetWriter.fill(probe, footerAt + partitionPackSize(), options.kagSize());
        var metadataAt = footerAt + partitionPackSize() + probe.size();
        var metadata = atom ? new byte[0] : headerMetadata(true, metadataAt);
        var index = indexTable(metadataAt + metadata.length);
        scratch.reset();
        var previous = partitions.getLast()[0];
        partitionPack(scratch, 4, 4, footerAt, previous, footerAt, metadata.length, index.length, INDEX_SID, 0, 0);
        MxfSetWriter.fill(scratch, footerAt + scratch.size(), options.kagSize());
        scratch.bytes(metadata);
        scratch.bytes(index);
        partitions.add(new long[]{footerAt, 0});
        var ripEntries = partitions.size();
        var ripLength = 16 + 4 + 12 * ripEntries + 4;
        scratch.bytes(MxfLabels.RANDOM_INDEX);
        scratch.u8(0x83);
        scratch.u24be(12 * ripEntries + 4);
        for (var partition : partitions) {
            scratch.u32be(partition[1]);
            scratch.u64be(partition[0]);
        }
        scratch.u32be(ripLength);
        writeScratch();
        if (output.isSeekable()) {
            var end = output.position();
            var rewritten = headerMetadata(true, headerMetadataStart());
            if (rewritten.length != headerMetadataSize) {
                throw new IllegalStateException("MXF header metadata changed size");
            }
            scratch.reset();
            partitionPack(scratch, 2, 4, 0, 0, footerAt, rewritten.length, 0, 0, 0, 0);
            output.position(origin);
            writeScratch();
            output.position(headerMetadataAt);
            output.write(rewritten);
            output.position(end);
        }
    }

    /**
     * Writes a partition pack.
     *
     * @param out the writer
     * @param kind 2 header, 3 body, 4 footer
     * @param status 1 open incomplete, 4 closed complete
     * @param self the offset of this partition
     * @param previous the offset of the previous partition
     * @param footer the offset of the footer partition, or 0
     * @param headerBytes the header metadata byte count
     * @param indexBytes the index byte count
     * @param indexSid the index stream id, or 0
     * @param bodyOffset the stream offset of the partition's essence
     * @param bodySid the body stream id, or 0
     */
    private void partitionPack(ByteWriter out, int kind, int status, long self, long previous, long footer,
                               long headerBytes, long indexBytes, int indexSid, long bodyOffset, int bodySid) {
        out.bytes(MxfLabels.PARTITION_PREFIX);
        out.u8(0x01);
        out.u8(kind);
        out.u8(status);
        out.u8(0x00);
        out.u8(0x83);
        out.u24be(88 + 16 * containers.size());
        out.u16be(1);
        out.u16be(3);
        out.u32be(options.kagSize());
        out.u64be(self);
        out.u64be(previous);
        out.u64be(footer);
        out.u64be(headerBytes);
        out.u64be(indexBytes);
        out.u32be(indexSid);
        out.u64be(bodyOffset);
        out.u32be(bodySid);
        out.bytes(options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM ? MxfLabels.OP_ATOM : MxfLabels.OP1A);
        out.u32be(containers.size());
        out.u32be(16);
        for (var container : containers) {
            out.bytes(container);
        }
    }

    /**
     * Appends {@link #scratch} to the output.
     */
    private void writeScratch() {
        writeRaw(scratch.buffer(), 0, scratch.size());
    }

    private void writeRaw(byte[] data, int offset, int length) {
        output.write(data, offset, length);
    }

    /**
     * {@return the index table segments of the essence, with the fill after them}
     *
     * @param base the offset, relative to the origin, the segments are written at, which their fill aligns from
     */
    private byte[] indexTable(long base) {
        var writer = new ByteWriter(1 << 12);
        var sets = new MxfSetWriter(writer);
        var single = clip ? outs.getFirst() : null;
        if (single != null && single.kind == 's') {
            sets.beginKey(MxfLabels.INDEX_SEGMENT);
            sets.bytes(0x3C0A, uid());
            sets.rational(0x3F0B, single.sampleRate, 1);
            sets.s64(0x3F0C, 0);
            sets.s64(0x3F0D, single.samples);
            sets.u32(0x3F05, single.blockAlign);
            sets.u32(0x3F06, INDEX_SID);
            sets.u32(0x3F07, BODY_SID);
            sets.u8(0x3F08, 0);
            sets.u8(0x3F0E, 0);
            sets.end();
            MxfSetWriter.fill(writer, base + writer.size(), options.kagSize());
            return writer.toByteArray();
        }
        var count = (int) units;
        var display = displayPositions(count);
        var stored = new long[count];
        Arrays.fill(stored, -1);
        for (var unit = 0; unit < count; unit++) {
            var position = display[unit];
            if (position >= 0 && position < count && stored[(int) position] < 0) {
                stored[(int) position] = unit;
            }
        }
        var lastKey = 0;
        var keyOf = new int[count];
        for (var unit = 0; unit < count; unit++) {
            if ((indexFlags[unit] & 0x80) != 0) {
                lastKey = unit;
            }
            keyOf[unit] = lastKey;
        }
        for (var first = 0; first < count || first == 0; first += SEGMENT_ENTRIES) {
            var entries = Math.min(SEGMENT_ENTRIES, count - first);
            sets.beginKey(MxfLabels.INDEX_SEGMENT);
            sets.bytes(0x3C0A, uid());
            sets.rational(0x3F0B, editRate.numerator(), editRate.denominator());
            sets.s64(0x3F0C, first);
            sets.s64(0x3F0D, entries);
            sets.u32(0x3F05, 0);
            sets.u32(0x3F06, INDEX_SID);
            sets.u32(0x3F07, BODY_SID);
            sets.u8(0x3F08, 0);
            sets.u8(0x3F0E, 0);
            var delta = new byte[8 + 6];
            delta[3] = 1;
            delta[7] = 6;
            delta[8] = (byte) (reordered ? -1 : 0);
            sets.bytes(0x3F09, delta);
            var array = new byte[8 + 11 * entries];
            ByteBuffer.wrap(array).putInt(entries).putInt(11);
            for (var entry = 0; entry < entries; entry++) {
                var unit = first + entry;
                var at = 8 + 11 * entry;
                var temporal = stored[unit] < 0 ? 0 : stored[unit] - unit;
                var keyOffset = Math.max(-128, keyOf[unit] - unit);
                array[at] = (byte) Math.clamp(temporal, -128, 127);
                array[at + 1] = (byte) keyOffset;
                array[at + 2] = indexFlags[unit];
                ByteBuffer.wrap(array, at + 3, 8).putLong(indexOffsets[unit]);
            }
            sets.bytes(0x3F0A, array);
            sets.end();
            if (count == 0) {
                break;
            }
        }
        MxfSetWriter.fill(writer, base + writer.size(), options.kagSize());
        return writer.toByteArray();
    }

    /**
     * {@return the size of a partition pack KLV}
     */
    private int partitionPackSize() {
        return 20 + 88 + 16 * containers.size();
    }

    /**
     * Returns the display position of every stored picture, from the presentation timestamps.
     *
     * @param count the number of stored edit units
     * @return the display positions, the stored position itself where the timestamps do not tell
     */
    private long[] displayPositions(int count) {
        var out = new long[count];
        Out lead = null;
        for (var candidate : outs) {
            if (candidate.kind == 'p') {
                lead = candidate;
                break;
            }
        }
        var minimum = Long.MAX_VALUE;
        var known = lead != null;
        for (var unit = 0; unit < count && known; unit++) {
            if (displayOf[unit] == Packet.NO_TIMESTAMP) {
                known = false;
            } else if (displayOf[unit] != NO_PICTURE) {
                minimum = Math.min(minimum, displayOf[unit]);
            }
        }
        if (!known) {
            for (var unit = 0; unit < count; unit++) {
                out[unit] = unit;
            }
            reordered = false;
            return out;
        }
        var frameTime = new Rational(editRate.denominator(), editRate.numerator());
        var base = lead.track.timeBase();
        for (var unit = 0; unit < count; unit++) {
            out[unit] = displayOf[unit] == NO_PICTURE ? unit : base.rescale(displayOf[unit] - minimum, frameTime);
            reordered |= out[unit] != unit;
        }
        return out;
    }

    /**
     * Serializes the header metadata: primer pack, sets and the fill after them.
     *
     * @param last whether the essence is complete, so that durations are known
     * @param base the offset, relative to the origin, the header metadata is written at, which its fill aligns from
     * @return the bytes
     */
    private byte[] headerMetadata(boolean last, long base) {
        uids = 0;
        var known = last;
        var writer = new ByteWriter(1 << 13);
        var sets = new MxfSetWriter(writer);
        primer(writer);
        var preface = uid();
        var identification = uid();
        var storage = uid();
        var essenceData = uid();
        var material = uid();
        var file = uid();
        var timecodeTrack = uid();
        var timecodeSequence = uid();
        var timecodeComponent = uid();
        var fileTimecodeTrack = uid();
        var fileTimecodeSequence = uid();
        var fileTimecodeComponent = uid();
        var materialTracks = new ArrayList<byte[]>();
        var fileTracks = new ArrayList<byte[]>();
        var trackSets = new ArrayList<byte[][]>();
        for (var ignored : outs) {
            var ids = new byte[][]{uid(), uid(), uid(), uid(), uid(), uid(), uid()};
            trackSets.add(ids);
            materialTracks.add(ids[0]);
            fileTracks.add(ids[3]);
        }
        var multiple = outs.size() > 1 ? uid() : null;
        var avcSubs = new ArrayList<byte[]>();
        for (var out : outs) {
            avcSubs.add(out.track.codec() == Codec.H264
                    || out.track.codec() == Codec.JPEG_XS && out.jxsHeader != null ? uid() : null);
        }
        var comments = new ArrayList<byte[]>();
        var commentEntries = new ArrayList<MediaTags.Entry>();
        for (var entry : tags.entries()) {
            var key = entry.key();
            if (key.equalsIgnoreCase("title") || key.equalsIgnoreCase("timecode")) {
                continue;
            }
            comments.add(uid());
            commentEntries.add(entry);
        }
        var duration = known ? units : -1;

        sets.begin(MxfLabels.PREFACE, preface);
        sets.timestamp(0x3B02, timestamp);
        sets.u16(0x3B05, 0x0103);
        sets.u32(0x3B07, 1);
        sets.bytes(0x3B03, storage);
        sets.batch(0x3B06, List.of(identification));
        sets.bytes(0x3B08, file);
        sets.bytes(0x3B09, options.pattern() == MxfMuxerOptions.Pattern.OP_ATOM ? MxfLabels.OP_ATOM : MxfLabels.OP1A);
        sets.batch(0x3B0A, containers);
        sets.batch(0x3B0B, List.of());
        sets.end();

        sets.begin(MxfLabels.IDENTIFICATION, identification);
        sets.string(0x3C01, "Smalt");
        sets.string(0x3C02, "Smalt");
        sets.string(0x3C04, version());
        sets.bytes(0x3C05, PRODUCT_UID);
        sets.timestamp(0x3C06, timestamp);
        sets.bytes(0x3C09, digest(materialUmid, Integer.MAX_VALUE));
        sets.end();

        sets.begin(MxfLabels.CONTENT_STORAGE, storage);
        sets.batch(0x1901, List.of(material, file));
        sets.batch(0x1902, List.of(essenceData));
        sets.end();

        sets.begin(MxfLabels.ESSENCE_CONTAINER_DATA, essenceData);
        sets.bytes(0x2701, fileUmid);
        sets.u32(0x3F06, INDEX_SID);
        sets.u32(0x3F07, BODY_SID);
        sets.end();

        var title = tags.get(StandardTag.TITLE).orElse(null);
        var all = new ArrayList<byte[]>();
        all.add(timecodeTrack);
        all.addAll(materialTracks);
        sets.begin(MxfLabels.MATERIAL_PACKAGE, material);
        sets.bytes(0x4401, materialUmid);
        if (title != null) {
            sets.string(0x4402, title);
        }
        sets.timestamp(0x4405, timestamp);
        sets.timestamp(0x4404, timestamp);
        sets.batch(0x4403, all);
        if (!comments.isEmpty()) {
            sets.batch(0x4406, comments);
        }
        sets.end();
        timecodeTrack(sets, timecodeTrack, timecodeSequence, timecodeComponent, known);
        var fileAll = new ArrayList<byte[]>();
        fileAll.add(fileTimecodeTrack);
        fileAll.addAll(fileTracks);
        for (var index = 0; index < outs.size(); index++) {
            var out = outs.get(index);
            var ids = trackSets.get(index);
            var trackDuration = !known ? -1 : clip && out.kind == 's' ? out.samples : units;
            var trackRate = clip && out.kind == 's' ? new Rational(out.sampleRate, 1) : editRate;
            var name = trackName(out);
            essenceTrack(sets, ids[0], ids[1], ids[2], index + 2, 0, name, trackRate, trackDuration, out.kind,
                    fileUmid, out.fileTrackId);
        }
        for (var index = 0; index < comments.size(); index++) {
            var entry = commentEntries.get(index);
            sets.begin(MxfLabels.TAGGED_VALUE, comments.get(index));
            sets.string(0x5001, entry.key());
            var value = entry.value().getBytes(StandardCharsets.UTF_16BE);
            var indirect = new byte[17 + value.length];
            indirect[0] = 0x42;
            System.arraycopy(MxfLabels.bytes("060e2b34010401010110020000000000"), 0, indirect, 1, 16);
            System.arraycopy(value, 0, indirect, 17, value.length);
            sets.bytes(0x5003, indirect);
            sets.end();
        }

        var descriptor = multiple != null ? multiple : trackSets.getFirst()[6];
        sets.begin(MxfLabels.SOURCE_PACKAGE, file);
        sets.bytes(0x4401, fileUmid);
        sets.timestamp(0x4405, timestamp);
        sets.timestamp(0x4404, timestamp);
        sets.batch(0x4403, fileAll);
        sets.bytes(0x4701, descriptor);
        sets.end();
        timecodeTrack(sets, fileTimecodeTrack, fileTimecodeSequence, fileTimecodeComponent, known);
        for (var index = 0; index < outs.size(); index++) {
            var out = outs.get(index);
            var ids = trackSets.get(index);
            var trackDuration = !known ? -1 : clip && out.kind == 's' ? out.samples : units;
            var trackRate = clip && out.kind == 's' ? new Rational(out.sampleRate, 1) : editRate;
            essenceTrack(sets, ids[3], ids[4], ids[5], out.fileTrackId, out.trackNumber, trackName(out), trackRate,
                    trackDuration, out.kind, null, 0);
        }
        if (multiple != null) {
            var subs = new ArrayList<byte[]>();
            for (var ids : trackSets) {
                subs.add(ids[6]);
            }
            sets.begin(MxfLabels.MULTIPLE_DESCRIPTOR, multiple);
            sets.rational(0x3001, editRate.numerator(), editRate.denominator());
            sets.s64(0x3002, known ? units : -1);
            sets.bytes(0x3004, MxfLabels.MULTIPLE_WRAPPINGS);
            sets.batch(0x3F01, subs);
            sets.end();
        }
        for (var index = 0; index < outs.size(); index++) {
            descriptor(sets, outs.get(index), trackSets.get(index)[6], known, avcSubs.get(index));
            if (avcSubs.get(index) != null) {
                var out = outs.get(index);
                if (out.track.codec() == Codec.JPEG_XS) {
                    jxsSubDescriptor(sets, out, avcSubs.get(index));
                } else {
                    sets.begin(MxfLabels.AVC_SUB_DESCRIPTOR, avcSubs.get(index));
                    sets.u8(MxfLabels.AVC_DECODING_DELAY_WRITE_TAG, known ? decodingDelay(out) : 0xFF);
                    sets.end();
                }
            }
        }
        MxfSetWriter.fill(writer, base + writer.size(), options.kagSize());
        return writer.toByteArray();
    }

    /**
     * {@return the edit rate of the timecode tracks: that of the pictures, or {@link MxfMuxerOptions#audioEditRate()}
     * for clip-wrapped sound}
     */
    private Rational timecodeRate() {
        return clip && outs.getFirst().kind == 's' ? options.audioEditRate() : editRate;
    }

    /**
     * {@return the offset, relative to the origin, of the header metadata of the header partition}
     */
    private long headerMetadataStart() {
        var probe = new ByteWriter(64);
        MxfSetWriter.fill(probe, partitionPackSize(), options.kagSize());
        return partitionPackSize() + probe.size();
    }

    /**
     * {@return the name of a track from its track tags, or {@code null}}
     *
     * @param out the track
     */
    private String trackName(Out out) {
        return tags.track(out.track.id()).flatMap(track -> track.tags().get(StandardTag.TITLE)).orElse(null);
    }

    /**
     * Writes the primer pack of the static local tags used.
     *
     * @param writer the output of the header metadata
     */
    private static void primer(ByteWriter writer) {
        writer.bytes(MxfLabels.PRIMER);
        var entries = PRIMER_TAGS.length / 2;
        writer.u8(0x83);
        writer.u24be(8 + 18 * entries);
        writer.u32be(entries);
        writer.u32be(18);
        for (var index = 0; index < entries; index++) {
            writer.u16be(Integer.parseInt(PRIMER_TAGS[2 * index], 16));
            writer.bytes(MxfLabels.bytes(PRIMER_TAGS[2 * index + 1]));
        }
    }

    /**
     * The local tags written and their labels (SMPTE Elements register).
     */
    private static final String[] PRIMER_TAGS = {
            "3c0a", "060e2b34010101010101150200000000",
            "3b02", "060e2b34010101020702011002040000",
            "3b05", "060e2b34010101020301020105000000",
            "3b07", "060e2b34010101020301020104000000",
            "3b03", "060e2b34010101020601010402010000",
            "3b06", "060e2b34010101020601010406040000",
            "3b08", "060e2b34010101040601010401080000",
            "3b09", "060e2b34010101050102020300000000",
            "3b0a", "060e2b34010101050102021002010000",
            "3b0b", "060e2b34010101050102021002020000",
            "3c01", "060e2b34010101020520070102010000",
            "3c02", "060e2b34010101020520070103010000",
            "3c04", "060e2b34010101020520070105010000",
            "3c05", "060e2b34010101020520070107000000",
            "3c06", "060e2b34010101020702011002030000",
            "3c09", "060e2b34010101020520070101000000",
            "1901", "060e2b34010101020601010405010000",
            "1902", "060e2b34010101020601010405020000",
            "2701", "060e2b34010101020601010601000000",
            "3f06", "060e2b34010101040103040500000000",
            "3f07", "060e2b34010101040103040400000000",
            "4401", "060e2b34010101010101151000000000",
            "4402", "060e2b34010101010103030201000000",
            "4403", "060e2b34010101020601010406050000",
            "4404", "060e2b34010101020702011002050000",
            "4405", "060e2b34010101020702011001030000",
            "4406", "060e2b3401010102030201020c000000",
            "4701", "060e2b34010101020601010402030000",
            "4801", "060e2b34010101020107010100000000",
            "4802", "060e2b34010101020107010201000000",
            "4803", "060e2b34010101020601010402040000",
            "4804", "060e2b34010101020104010300000000",
            "4b01", "060e2b34010101020530040500000000",
            "4b02", "060e2b34010101020702010301030000",
            "0201", "060e2b34010101020407010000000000",
            "0202", "060e2b34010101020702020101030000",
            "1001", "060e2b34010101020601010406090000",
            "1201", "060e2b34010101020702010301040000",
            "1101", "060e2b34010101020601010301000000",
            "1102", "060e2b34010101020601010302000000",
            "1501", "060e2b34010101020702010301050000",
            "1502", "060e2b34010101020404010102060000",
            "1503", "060e2b34010101010404010105000000",
            "5001", "060e2b34010101020302010209010000",
            "5003", "060e2b3401010102030201020a010000",
            "3f01", "060e2b340101010406010104060b0000",
            "3006", "060e2b34010101050601010305000000",
            "3001", "060e2b34010101010406010100000000",
            "3002", "060e2b34010101010406010200000000",
            "3004", "060e2b34010101020601010401020000",
            "3201", "060e2b34010101020401060100000000",
            "3202", "060e2b34010101010401050201000000",
            "3203", "060e2b34010101010401050202000000",
            "3204", "060e2b34010101010401050107000000",
            "3205", "060e2b34010101010401050108000000",
            "3208", "060e2b3401010101040105010b000000",
            "3209", "060e2b3401010101040105010c000000",
            "320c", "060e2b34010101010401030104000000",
            "320d", "060e2b34010101020401030205000000",
            "320e", "060e2b34010101010401010101000000",
            "3210", "060e2b34010101020401020101010200",
            "3212", "060e2b34010101020401030106000000",
            "3219", "060e2b34010101090401020101060100",
            "321a", "060e2b34010101020401020101030100",
            "3301", "060e2b3401010102040105030a000000",
            "3302", "060e2b34010101010401050105000000",
            "3304", "060e2b34010101010401050303000000",
            "3305", "060e2b34010101010401050304000000",
            "3306", "060e2b34010101020401050305000000",
            "3308", "060e2b34010101020401050110000000",
            "3d01", "060e2b34010101040402030304000000",
            "3d02", "060e2b34010101040402030104000000",
            "3d03", "060e2b34010101050402030101010000",
            "3d07", "060e2b34010101050402010104000000",
            "3d09", "060e2b34010101050402030305000000",
            "3d0a", "060e2b34010101050402030201000000",
            "8000", "060e2b34010101090601010406100000",
            "8001", "060e2b340101010e04010606010e0000",
            "8002", "060e2b340101010e0401060b01000000",
            "8003", "060e2b340101010e0401060b02000000",
            "8004", "060e2b340101010e0401060b03000000",
            "8005", "060e2b340101010e0401060b04000000",
            "8006", "060e2b340101010e0401060b05000000",
            "8007", "060e2b340101010e0401060b06000000",
            "8008", "060e2b340101010e0401060b07000000",
            "8009", "060e2b340101010e0401060b08000000"
    };

    /**
     * {@return the version string of the identification set}
     */
    private static String version() {
        return MxfMuxer.class.getModule().getDescriptor() == null ? "unknown"
                : MxfMuxer.class.getModule().getDescriptor().rawVersion().orElse("unknown");
    }

    /**
     * Writes a timecode track, its sequence and its timecode component.
     */
    private void timecodeTrack(MxfSetWriter sets, byte[] track, byte[] sequence, byte[] component, boolean known) {
        var rate = timecodeRate();
        var duration = !known ? -1 : rate.equals(editRate) ? units
                : new Rational(editRate.denominator(), editRate.numerator()).rescale(units,
                new Rational(rate.denominator(), rate.numerator()));
        sets.begin(MxfLabels.TIMELINE_TRACK, track);
        sets.u32(0x4801, 1);
        sets.u32(0x4804, 0);
        sets.rational(0x4B01, rate.numerator(), rate.denominator());
        sets.s64(0x4B02, 0);
        sets.bytes(0x4803, sequence);
        sets.end();
        sets.begin(MxfLabels.SEQUENCE, sequence);
        sets.bytes(0x0201, MxfLabels.TIMECODE_DEFINITION);
        sets.s64(0x0202, duration);
        sets.batch(0x1001, List.of(component));
        sets.end();
        sets.begin(MxfLabels.TIMECODE_COMPONENT, component);
        sets.bytes(0x0201, MxfLabels.TIMECODE_DEFINITION);
        sets.s64(0x0202, duration);
        sets.s64(0x1501, timecodeStart);
        sets.u16(0x1502, timecodeBase);
        sets.u8(0x1503, timecodeDrop ? 1 : 0);
        sets.end();
    }

    /**
     * Writes an essence track, its sequence and its source clip.
     */
    private static void essenceTrack(MxfSetWriter sets, byte[] track, byte[] sequence, byte[] clipUid, int trackId,
                                     int trackNumber, String name, Rational rate, long duration, char kind,
                                     byte[] sourcePackage, int sourceTrack) {
        var definition = kind == 'p' ? MxfLabels.PICTURE_DEFINITION : MxfLabels.SOUND_DEFINITION;
        sets.begin(MxfLabels.TIMELINE_TRACK, track);
        sets.u32(0x4801, trackId);
        sets.u32(0x4804, trackNumber);
        if (name != null) {
            sets.string(0x4802, name);
        }
        sets.rational(0x4B01, rate.numerator(), rate.denominator());
        sets.s64(0x4B02, 0);
        sets.bytes(0x4803, sequence);
        sets.end();
        sets.begin(MxfLabels.SEQUENCE, sequence);
        sets.bytes(0x0201, definition);
        sets.s64(0x0202, duration);
        sets.batch(0x1001, List.of(clipUid));
        sets.end();
        sets.begin(MxfLabels.SOURCE_CLIP, clipUid);
        sets.bytes(0x0201, definition);
        sets.s64(0x0202, duration);
        sets.s64(0x1201, 0);
        sets.bytes(0x1101, sourcePackage == null ? new byte[32] : sourcePackage);
        sets.u32(0x1102, sourceTrack);
        sets.end();
    }

    /**
     * Writes the file descriptor of a track.
     *
     * @param sets the writer
     * @param out the track
     * @param instance the descriptor's instance UID
     * @param known whether the durations are known
     * @param avcSub the instance UID of the track's AVC sub-descriptor, or {@code null}
     */
    private void descriptor(MxfSetWriter sets, Out out, byte[] instance, boolean known, byte[] avcSub) {
        if (out.kind == 's') {
            sets.begin(MxfLabels.WAVE_DESCRIPTOR, instance);
            sets.u32(0x3006, out.fileTrackId);
            if (clip || !hasPictures) {
                sets.rational(0x3001, out.sampleRate, 1);
                sets.s64(0x3002, known ? out.samples : -1);
            } else {
                sets.rational(0x3001, editRate.numerator(), editRate.denominator());
                sets.s64(0x3002, known ? units : -1);
            }
            sets.bytes(0x3004, out.container);
            sets.rational(0x3D03, out.sampleRate, 1);
            sets.u8(0x3D02, 1);
            sets.u32(0x3D07, out.channels);
            sets.u32(0x3D01, out.bits);
            sets.u16(0x3D0A, out.blockAlign);
            sets.u32(0x3D09, (long) out.blockAlign * out.sampleRate);
            sets.end();
            return;
        }
        var mpeg = out.track.codec() == Codec.MPEG1_VIDEO || out.track.codec() == Codec.MPEG2_VIDEO
                || out.track.codec() == Codec.MPEG4_PART2;
        sets.begin(mpeg ? MxfLabels.MPEG_DESCRIPTOR : MxfLabels.CDCI_DESCRIPTOR, instance);
        sets.u32(0x3006, out.fileTrackId);
        sets.rational(0x3001, editRate.numerator(), editRate.denominator());
        sets.s64(0x3002, known ? units : -1);
        sets.bytes(0x3004, out.container);
        var coding = out.track.codec() == Codec.MPEG1_VIDEO || out.track.codec() == Codec.MPEG2_VIDEO
                ? mpegVideoCoding(out, known) : out.coding;
        sets.bytes(0x3201, coding != null ? coding : MxfLabels.bytes("060e2b34040101010401020200000000"));
        var fieldHeight = out.interlaced ? (out.height + 1) / 2 : out.height;
        sets.u32(0x3203, out.width);
        sets.u32(0x3202, fieldHeight);
        sets.u32(0x3205, out.width);
        sets.u32(0x3204, fieldHeight);
        sets.u32(0x3209, out.width);
        sets.u32(0x3208, fieldHeight);
        sets.u8(0x320C, out.interlaced ? 1 : 0);
        sets.int32Array(0x320D, lineMap(out));
        var aspectWidth = (long) out.width * (out.sampleAspect == null ? 1 : out.sampleAspect.numerator());
        var aspectHeight = (long) Math.max(1, out.height) * (out.sampleAspect == null ? 1
                : out.sampleAspect.denominator());
        var divisor = BigInteger.valueOf(Math.max(1, aspectWidth)).gcd(BigInteger.valueOf(aspectHeight)).longValue();
        sets.rational(0x320E, Math.max(1, aspectWidth) / divisor, aspectHeight / divisor);
        if (out.interlaced) {
            sets.u8(0x3212, out.topFirst ? 1 : 2);
        }
        color(sets, out.color);
        sets.u32(0x3301, out.depth);
        sets.u32(0x3302, out.horizontalSubsampling);
        sets.u32(0x3308, out.verticalSubsampling);
        var limited = out.color == null || out.color.range() != com.github.auties00.smalt.image.ColorRange.FULL;
        var scale = 1L << (out.depth - 8);
        sets.u32(0x3304, limited ? 16 * scale : 0);
        sets.u32(0x3305, limited ? 235 * scale : (1L << out.depth) - 1);
        sets.u32(0x3306, limited ? 224 * scale + 1 : 1L << out.depth);
        if (avcSub != null) {
            sets.batch(MxfLabels.SUB_DESCRIPTORS_WRITE_TAG, List.of(avcSub));
        }
        sets.end();
    }

    /**
     * Writes the JPEG XS picture sub-descriptor of a track (SMPTE ST 2124 section 9.2) from the main header of its
     * first codestream.
     *
     * @param sets the header metadata
     * @param out the JPEG XS track, whose main header is known
     * @param instance the instance UID of the set
     */
    private static void jxsSubDescriptor(MxfSetWriter sets, Out out, byte[] instance) {
        var header = out.jxsHeader;
        var pih = 4 + u16(header, 4);
        sets.beginKey(MxfLabels.JXS_SUB_DESCRIPTOR);
        sets.bytes(0x3C0A, instance);
        var tag = MxfLabels.JXS_FIRST_WRITE_TAG;
        sets.u16(tag, u16(header, pih + 8));
        sets.u16(tag + 1, u16(header, pih + 10));
        sets.u16(tag + 2, u16(header, pih + 12));
        sets.u16(tag + 3, u16(header, pih + 14));
        sets.u8(tag + 4, header[pih + 20] & 0xFF);
        var cdt = pih + 28;
        while (cdt + 4 <= header.length && u16(header, cdt) != 0xFF13) {
            cdt += 2 + u16(header, cdt + 2);
        }
        if (cdt + 4 <= header.length) {
            sets.bytes(tag + 5, Arrays.copyOfRange(header, cdt, Math.min(header.length, cdt + 2 + u16(header, cdt + 2))));
        }
        sets.u16(tag + 6, u16(header, pih + 16));
        sets.u16(tag + 7, u16(header, pih + 18));
        sets.end();
    }

    /**
     * {@return the bytes of a JPEG XS codestream from {@code SOC} to its first slice header}
     *
     * @param data the codestream
     */
    private static byte[] jxsMainHeader(byte[] data) {
        var position = 2;
        while (position + 4 <= data.length) {
            var marker = u16(data, position);
            if (marker == 0xFF20 || marker == 0xFF21) {
                break;
            }
            position += 2 + u16(data, position + 2);
        }
        return Arrays.copyOf(data, Math.min(position, data.length));
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    /**
     * {@return the decoding delay of an AVC track in frames: the presentation time of its first picture shown less the
     * decoding time of its first picture decoded, or {@code 0xFF} when the packets carried no timestamps}
     *
     * @param out the track
     */
    private int decodingDelay(Out out) {
        if (out.firstDts == Packet.NO_TIMESTAMP || out.minimumPts == Long.MAX_VALUE) {
            return 0xFF;
        }
        var frame = new Rational(editRate.denominator(), editRate.numerator());
        var delay = out.track.timeBase().rescale(out.minimumPts - out.firstDts, frame);
        return (int) Math.clamp(delay, 0, 0xFE);
    }

    /**
     * {@return the video line map of a picture track: the first line of each field}
     *
     * @param out the track
     */
    private static long[] lineMap(Out out) {
        if (out.interlaced) {
            return switch (out.height) {
                case 1080 -> new long[]{21, 584};
                case 576, 608 -> new long[]{23, 336};
                case 480 -> new long[]{23, 286};
                case 486, 512 -> new long[]{21, 283};
                default -> new long[]{0, 0};
            };
        }
        return switch (out.height) {
            case 1080 -> new long[]{42, 0};
            case 720 -> new long[]{26, 0};
            case 576 -> new long[]{45, 0};
            case 480 -> new long[]{45, 0};
            default -> new long[]{0, 0};
        };
    }

    /**
     * Writes the transfer characteristic, color primaries and coding equations labels of a color description.
     *
     * @param sets the writer
     * @param color the color, or {@code null}
     */
    private static void color(MxfSetWriter sets, ColorInfo color) {
        if (color == null) {
            return;
        }
        var transfer = switch (color.transfer()) {
            case BT709, SMPTE170M -> 0x02;
            case GAMMA28 -> 0x01;
            case SMPTE240M -> 0x03;
            case BT1361 -> 0x05;
            case LINEAR -> 0x06;
            case IEC61966_2_4 -> 0x08;
            case BT2020_10, BT2020_12 -> 0x09;
            case PQ -> 0x0A;
            case HLG -> 0x0B;
            case SRGB -> 0x0D;
            default -> 0;
        };
        if (transfer != 0) {
            var label = MxfLabels.bytes("060e2b34040101010401010101000000");
            label[13] = (byte) transfer;
            label[7] = (byte) (transfer == 9 ? 0x0E : transfer >= 8 ? 0x0D : transfer >= 5 ? 0x06 : 0x01);
            sets.bytes(0x3210, label);
        }
        var primaries = switch (color.primaries()) {
            case SMPTE170M -> 0x01;
            case BT470BG -> 0x02;
            case BT709 -> 0x03;
            case BT2020 -> 0x04;
            case SMPTE428 -> 0x05;
            case SMPTE432 -> 0x06;
            case SMPTE431 -> 0x0A;
            default -> 0;
        };
        if (primaries != 0) {
            var label = MxfLabels.bytes("060e2b34040101060401010103000000");
            label[13] = (byte) primaries;
            label[7] = (byte) (primaries >= 4 ? 0x0D : 0x06);
            sets.bytes(0x3219, label);
        }
        var equations = switch (color.matrix()) {
            case BT601, BT470BG -> 0x01;
            case BT709 -> 0x02;
            case SMPTE240M -> 0x03;
            case YCGCO -> 0x04;
            case IDENTITY -> 0x05;
            case BT2020_NCL -> 0x06;
            default -> 0;
        };
        if (equations != 0) {
            var label = MxfLabels.bytes("060e2b34040101010401010102000000");
            label[13] = (byte) equations;
            label[7] = (byte) (equations >= 4 ? 0x0D : equations == 3 ? 0x06 : 0x01);
            sets.bytes(0x321A, label);
        }
    }
}
