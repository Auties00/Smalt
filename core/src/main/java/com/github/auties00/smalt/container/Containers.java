package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.aiff.AiffDemuxer;
import com.github.auties00.smalt.container.aiff.AiffMuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.ivf.IvfDemuxer;
import com.github.auties00.smalt.container.ivf.IvfMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDocType;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.subtitle.AssDemuxer;
import com.github.auties00.smalt.container.subtitle.AssMuxer;
import com.github.auties00.smalt.container.subtitle.AssScriptType;
import com.github.auties00.smalt.container.subtitle.LrcDemuxer;
import com.github.auties00.smalt.container.subtitle.LrcMuxer;
import com.github.auties00.smalt.container.subtitle.MicroDvdDemuxer;
import com.github.auties00.smalt.container.subtitle.MicroDvdMuxer;
import com.github.auties00.smalt.container.subtitle.SamiDemuxer;
import com.github.auties00.smalt.container.subtitle.SamiMuxer;
import com.github.auties00.smalt.container.subtitle.SubRipDemuxer;
import com.github.auties00.smalt.container.subtitle.SubRipMuxer;
import com.github.auties00.smalt.container.subtitle.TtmlDemuxer;
import com.github.auties00.smalt.container.subtitle.TtmlMuxer;
import com.github.auties00.smalt.container.subtitle.WebVttDemuxer;
import com.github.auties00.smalt.container.subtitle.WebVttMuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.subtitle.AssFile;
import com.github.auties00.smalt.internal.subtitle.LrcText;
import com.github.auties00.smalt.internal.subtitle.MicroDvdText;
import com.github.auties00.smalt.internal.subtitle.SamiText;
import com.github.auties00.smalt.internal.subtitle.SubRipText;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.internal.subtitle.TtmlDocument;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.transcode.OutputContainer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Recognizes the container of a media file from its content and opens the matching demuxer, and
 * creates the muxer that writes a container.
 *
 * <p>Recognition never trusts a file name or a declared mimetype: it inspects the leading bytes,
 * skipping an ID3v2 tag first because MP3, AAC, AC-3, AMR and FLAC files often start with one. Formats with a
 * magic number (MP4 family, Matroska, Ogg, RIFF WAVE, RIFF AVI, AIFF, FLAC, AMR) are recognized exactly,
 * MPEG transport streams by the sync bytes of their first packets, and raw AC-3 and E-AC-3 streams
 * by two consecutive sync frames;
 * raw MPEG audio and ADTS streams, which have only a frame sync word, are recognized by requiring
 * two consecutive consistent frame headers. Text subtitle files are recognized last, in this order: a
 * WebVTT file by its {@code WEBVTT} signature, a TTML document by its {@code tt} root element, a SAMI file
 * by its {@code SAMI} tag, an SSA or ASS script by its {@code [Script Info]} section, a SubRip file by a
 * timing line among its first lines, a MicroDVD file by a {@code {start}{end}} first line, and an LRC
 * file by a time or ID tag on its first line.
 *
 * <p>{@link #openMuxer(ContainerFormat, MediaSink)} creates muxers with each format's default options: a
 * fast-start {@link Mp4Muxer}, a Matroska (not WebM) {@link MatroskaMuxer}, an {@link OggMuxer}, and the
 * {@link WavMuxer}, {@link AiffMuxer}, {@link AdtsMuxer}, {@link IvfMuxer}, {@link TsMuxer} and {@link AviMuxer};
 * MPEG audio, FLAC, AMR, AC-3, H.264, HEVC and AV1 files are the elementary streams a {@link RawMuxer} writes.
 * {@link #openMuxer(OutputContainer, MediaSink)} also writes WebM, M4A, QuickTime and fragmented MP4
 * ({@link FragmentedMp4Muxer}). A caller that needs other options (RF64 policies, fragment durations)
 * constructs the muxer directly.
 */
public final class Containers {
    /**
     * The number of leading bytes inspected when no ID3v2 tag is present.
     */
    private static final int HEADER_LENGTH = 4096;

    /**
     * The largest ID3v2 tag skipped during recognition, in bytes.
     */
    private static final int MAX_ID3_SKIP = 64 * 1024 * 1024;

    /**
     * The four-character box types that may start an ISO base media file.
     */
    private static final String[] MP4_LEADING_BOXES = {"ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid",
            "styp", "sidx", "moof", "junk"};
    private Containers() {
        throw new AssertionError();
    }

    /**
     * Recognizes the container format of a media file, by content only.
     *
     * <p>Recognition reads at most the leading bytes of the input (past an ID3v2 tag, when one
     * starts it); the format is never taken from a file name or a declared media type.
     *
     * @param source the input
     * @return the recognized format, or empty when the content matches no known container
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<ContainerFormat> detect(MediaSource source) {
        Objects.requireNonNull(source, "source");
        var header = read(source, 0, HEADER_LENGTH);
        var magic = detectMagic(header);
        if (magic != null) {
            return Optional.of(magic);
        }
        var leading = header;
        var offset = 0L;
        var tagSize = Id3v2Tag.size(header, 0);
        if (tagSize >= 0) {
            if (tagSize > MAX_ID3_SKIP) {
                return Optional.empty();
            }
            offset = tagSize;
            header = read(source, offset, HEADER_LENGTH);
            if (header.length >= 4 && header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
                return Optional.of(ContainerFormat.FLAC);
            }
            if (Ac3Demuxer.matches(header)) {
                return Optional.of(ContainerFormat.AC3);
            }
            if (matches(header, 0, "#!AMR")) {
                return Optional.of(ContainerFormat.AMR);
            }
        }
        var frames = detectFrameStream(header);
        return Optional.ofNullable(frames != null ? frames : detectText(leading));
    }

    /**
     * Creates the demuxer for a media file, recognized by {@link #detect(MediaSource)}, with
     * {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, read by the returned demuxer and not closed by it
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if the content is not a recognized container
     * @throws MediaException.Malformed if the container is corrupt
     * @throws MediaException.LimitExceeded if a size the container declares exceeds the limits, or reading its
     * structure runs out of memory or stack
     * @throws MediaException.Io if reading the source fails
     */
    public static Demuxer openDemuxer(MediaSource source) {
        return openDemuxer(source, DemuxerOptions.defaults());
    }

    /**
     * Creates the demuxer for a media file, recognized by {@link #detect(MediaSource)}, that
     * enforces the limits of {@code options}.
     *
     * @param source the input, read by the returned demuxer and not closed by it
     * @param options the demuxing options
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if the content is not a recognized container
     * @throws MediaException.Malformed if the container is corrupt
     * @throws MediaException.LimitExceeded if a size the container declares exceeds the limits, or reading its
     * structure runs out of memory or stack
     * @throws MediaException.Io if reading the source fails
     */
    public static Demuxer openDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(options, "options");
        return Guards.bounded("container", () -> open(source, options));
    }

    /**
     * Creates the demuxer of the container format {@link #detect(MediaSource)} recognizes.
     *
     * @param source the input
     * @param options the demuxing options
     * @return the demuxer
     * @throws MediaException.Unsupported if the content is not a recognized container
     */
    private static Demuxer open(MediaSource source, DemuxerOptions options) {
        var format = detect(source).orElseThrow(() -> new MediaException.Unsupported(
                "unknown container", "the input is not a recognized media container"));
        return switch (format) {
            case MP4 -> new Mp4Demuxer(source, options);
            case MATROSKA -> new MatroskaDemuxer(source, options);
            case OGG -> new OggDemuxer(source, options);
            case MPEG_AUDIO -> new MpegAudioDemuxer(source, options);
            case ADTS -> new AdtsDemuxer(source, options);
            case WAV -> new WavDemuxer(source, options);
            case FLAC -> new FlacDemuxer(source, options);
            case AMR -> new AmrDemuxer(source, options);
            case AIFF -> new AiffDemuxer(source, options);
            case IVF -> new IvfDemuxer(source, options);
            case MPEG_TS -> new TsDemuxer(source, options);
            case AC3 -> new Ac3Demuxer(source, options);
            case SUBRIP -> new SubRipDemuxer(source, options);
            case WEBVTT -> new WebVttDemuxer(source, options);
            case ASS -> new AssDemuxer(source, options);
            case TTML -> new TtmlDemuxer(source, options);
            case SAMI -> new SamiDemuxer(source, options);
            case LRC -> new LrcDemuxer(source, options);
            case MICRODVD -> new MicroDvdDemuxer(source, options);
            case AVI -> new AviDemuxer(source, options);
            // TODO: read H.264 and HEVC Annex B and AV1 OBU elementary streams, which detect() does not recognize yet
            case H264, HEVC, AV1 -> throw new MediaException.Unsupported(format.name().toLowerCase(Locale.ROOT)
                    + " elementary stream", "elementary video streams cannot be read");
        };
    }

    /**
     * Returns whether the muxer of a container format accepts tracks of a codec.
     *
     * @param format the container format
     * @param codec the codec of the track
     * @return {@code true} when the muxer {@link #openMuxer(ContainerFormat, MediaSink)} returns for {@code format} can
     * carry {@code codec}
     */
    public static boolean isMuxable(ContainerFormat format, Codec codec) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(codec, "codec");
        return switch (format) {
            case MP4 -> Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, codec);
            case MATROSKA -> MatroskaMuxer.supports(MatroskaDocType.MATROSKA, codec);
            case OGG -> OggMuxer.supports(codec);
            case WAV -> WavMuxer.supports(codec);
            case AIFF -> AiffMuxer.supports(codec);
            case ADTS -> AdtsMuxer.supports(codec);
            case IVF -> IvfMuxer.supports(codec);
            case MPEG_TS -> TsMuxer.supports(codec);
            case MPEG_AUDIO, FLAC, AMR, AC3, H264, HEVC, AV1 -> RawMuxer.supports(format, codec);
            case SUBRIP -> SubRipMuxer.supports(codec);
            case WEBVTT -> WebVttMuxer.supports(codec);
            case ASS -> AssMuxer.supports(codec);
            case TTML -> TtmlMuxer.supports(codec);
            case SAMI -> SamiMuxer.supports(codec);
            case LRC -> LrcMuxer.supports(codec);
            case MICRODVD -> MicroDvdMuxer.supports(codec);
            case AVI -> AviMuxer.supports(codec);
        };
    }

    /**
     * Returns whether a file of an output container profile can carry a stream of a codec.
     *
     * <p>The answer is {@link OutputContainer#supports(Codec)}: the codecs of the muxer of the
     * profile, narrowed by the profile (for example {@link OutputContainer#M4A} carries audio only
     * and {@link OutputContainer#WEBM} the WebM codecs only).
     *
     * @param container the output container profile
     * @param codec the codec of the track
     * @return {@code true} when the muxer {@link #openMuxer(OutputContainer, MediaSink)} returns for {@code container}
     * accepts tracks of {@code codec}
     */
    public static boolean isMuxable(OutputContainer container, Codec codec) {
        return container.supports(codec);
    }

    /**
     * Creates the muxer that writes a container format with the default options of that format.
     *
     * <p>The muxers are the ones the class description lists; MPEG audio, FLAC, AMR, AC-3, H.264, HEVC
     * and AV1 files are elementary streams whose muxer accepts only the codecs of the format.
     *
     * @param format the container format
     * @param output the sink, written from its position and not closed by the muxer
     * @return the muxer, which the caller finishes and closes
     * @throws MediaException.Io if the sink position cannot be read
     */
    public static Muxer openMuxer(ContainerFormat format, MediaSink output) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(output, "output");
        return switch (format) {
            case MP4 -> new Mp4Muxer(output);
            case MATROSKA -> new MatroskaMuxer(output);
            case OGG -> new OggMuxer(output);
            case WAV -> new WavMuxer(output);
            case AIFF -> new AiffMuxer(output);
            case ADTS -> new AdtsMuxer(output);
            case IVF -> new IvfMuxer(output);
            case MPEG_AUDIO, FLAC, AMR, AC3, H264, HEVC, AV1 -> new FormatMuxer(format, new RawMuxer(output));
            case MPEG_TS -> new TsMuxer(output);
            case SUBRIP -> new SubRipMuxer(output);
            case WEBVTT -> new WebVttMuxer(output);
            case ASS -> new AssMuxer(output);
            case TTML -> new TtmlMuxer(output);
            case SAMI -> new SamiMuxer(output);
            case LRC -> new LrcMuxer(output);
            case MICRODVD -> new MicroDvdMuxer(output);
            case AVI -> new AviMuxer(output);
        };
    }

    /**
     * Creates the muxer that writes a file of an output container profile.
     *
     * <p>The profile chooses the muxer and its options:
     * <ul>
     *   <li>{@link OutputContainer#MP4}, {@link OutputContainer#M4A} and {@link OutputContainer#MOV}
     *       get a fast-start {@link Mp4Muxer} with the {@link Mp4MuxerOptions#mp4()},
     *       {@link Mp4MuxerOptions#m4a()} and {@link Mp4MuxerOptions#quickTime()} options;</li>
     *   <li>{@link OutputContainer#FRAGMENTED_MP4} gets a {@link FragmentedMp4Muxer} with its default
     *       options;</li>
     *   <li>{@link OutputContainer#MATROSKA} and {@link OutputContainer#WEBM} get a
     *       {@link MatroskaMuxer} with the {@link MatroskaMuxerOptions#matroska()} and
     *       {@link MatroskaMuxerOptions#webm()} options;</li>
     *   <li>{@link OutputContainer#ASS} and {@link OutputContainer#SSA} get an {@link AssMuxer} that writes
     *       that version of the format;</li>
     *   <li>every other profile gets the muxer {@link #openMuxer(ContainerFormat, MediaSink)} returns
     *       for its {@linkplain OutputContainer#format() format}; for {@link OutputContainer#OGG_FLAC},
     *       {@link OutputContainer#AC3} and {@link OutputContainer#EAC3} it rejects a track of a codec
     *       the profile does not {@linkplain OutputContainer#supports(Codec) carry}.</li>
     * </ul>
     *
     * @param container the output container profile
     * @param output the sink, written from its position and not closed by the muxer
     * @return the muxer, which the caller finishes and closes
     * @throws MediaException.Unsupported if the sink cannot hold the file
     * @throws MediaException.Io if the sink position cannot be read
     */
    public static Muxer openMuxer(OutputContainer container, MediaSink output) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(output, "output");
        return switch (container) {
            case MP4 -> new Mp4Muxer(output, Mp4MuxerOptions.mp4());
            case M4A -> new Mp4Muxer(output, Mp4MuxerOptions.m4a());
            case MOV -> new Mp4Muxer(output, Mp4MuxerOptions.quickTime());
            case FRAGMENTED_MP4 -> new FragmentedMp4Muxer(output);
            case MATROSKA -> new MatroskaMuxer(output, MatroskaMuxerOptions.matroska());
            case WEBM -> new MatroskaMuxer(output, MatroskaMuxerOptions.webm());
            case OGG_FLAC, AC3, EAC3 -> new FormatMuxer(container, openMuxer(container.format(), output));
            case ASS -> new AssMuxer(output, AssScriptType.V4_PLUS);
            case SSA -> new AssMuxer(output, AssScriptType.V4);
            default -> openMuxer(container.format(), output);
        };
    }

    /**
     * Restricts a muxer to the codecs of one file format or profile.
     *
     * @param name the name of the format, for messages
     * @param accepts whether the format carries a codec
     * @param raw the muxer writing the file
     */
    private record FormatMuxer(String name, Predicate<Codec> accepts, Muxer raw) implements Muxer {
        /**
         * Restricts a {@link RawMuxer} to the codecs of an elementary-stream format.
         *
         * @param format the format written
         * @param raw the muxer writing the stream
         */
        private FormatMuxer(ContainerFormat format, RawMuxer raw) {
            this(format.name().toLowerCase(Locale.ROOT).replace('_', '-'), codec -> isMuxable(format, codec), raw);
        }

        /**
         * Restricts a muxer to the codecs of an output container profile.
         *
         * @param container the profile written
         * @param raw the muxer writing the file
         */
        private FormatMuxer(OutputContainer container, Muxer raw) {
            this(container.name().toLowerCase(Locale.ROOT).replace('_', '-'), container::supports, raw);
        }

        /**
         * {@inheritDoc}
         *
         * @throws IllegalArgumentException if the format cannot carry the track's codec
         */
        @Override
        public void addTrack(Track track) {
            Objects.requireNonNull(track, "track");
            if (!accepts.test(track.codec())) {
                throw new IllegalArgumentException(name + " cannot carry " + track.codec().displayName());
            }
            raw.addTrack(track);
        }

        @Override
        public boolean supportsTags() {
            return raw.supportsTags();
        }

        /**
         * @throws MediaException.Unsupported {@inheritDoc}
         */
        @Override
        public void setTags(MediaTags tags) {
            raw.setTags(tags);
        }

        @Override
        public void writePacket(Packet packet) {
            raw.writePacket(packet);
        }

        @Override
        public void updateCodecConfig(int trackId, byte[] config) {
            raw.updateCodecConfig(trackId, config);
        }

        @Override
        public void finish() {
            raw.finish();
        }

        @Override
        public void close() {
            raw.close();
        }
    }

    /**
     * Recognizes the formats identified by a magic number at offset zero.
     *
     * @param header the leading bytes of the input
     * @return the recognized format, or {@code null}
     */
    private static ContainerFormat detectMagic(byte[] header) {
        if (header.length < 12) {
            return header.length >= 4 && matches(header, 0, "fLaC") ? ContainerFormat.FLAC : null;
        }
        if (matches(header, 0, "OggS")) {
            return ContainerFormat.OGG;
        }
        if ((header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45 && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3) {
            return ContainerFormat.MATROSKA;
        }
        if (matches(header, 0, "fLaC")) {
            return ContainerFormat.FLAC;
        }
        if (IvfDemuxer.matches(header)) {
            return ContainerFormat.IVF;
        }
        if (matches(header, 0, "#!AMR")) {
            return ContainerFormat.AMR;
        }
        if (matches(header, 0, "RIFF") || matches(header, 0, "RIFX") || matches(header, 0, "RF64") || matches(header, 0, "BW64")) {
            if (matches(header, 8, "WAVE")) {
                return ContainerFormat.WAV;
            }
            if (matches(header, 8, "AVI ")) {
                return ContainerFormat.AVI;
            }
            return null;
        }
        if (matches(header, 0, "FORM") && (matches(header, 8, "AIFF") || matches(header, 8, "AIFC"))) {
            return ContainerFormat.AIFF;
        }
        if (header.length >= 24 && header[0] == 0 && header[1] == 0 && header[2] == 0 && header[3] == 12
                && matches(header, 4, "jP  ") && matches(header, 16, "ftyp")
                && (matches(header, 20, "mjp2") || matches(header, 20, "mj2s"))) {
            return ContainerFormat.MP4;
        }
        for (var box : MP4_LEADING_BOXES) {
            if (matches(header, 4, box)) {
                return ContainerFormat.MP4;
            }
        }
        if (TsDemuxer.matches(header)) {
            return ContainerFormat.MPEG_TS;
        }
        if (Ac3Demuxer.matches(header)) {
            return ContainerFormat.AC3;
        }
        return null;
    }

    /**
     * Recognizes a text subtitle file from its leading bytes: a WebVTT, TTML, SAMI, SSA or ASS file by its
     * signature, a SubRip file by a timing line as the first or second of its first non-blank lines, then a
     * MicroDVD or LRC file by its first line.
     *
     * @param header the leading bytes of the input
     * @return the text subtitle format, or {@code null}
     */
    private static ContainerFormat detectText(byte[] header) {
        if (header.length == 0) {
            return null;
        }
        var text = TextFiles.decode(header);
        if (WebVttText.isSignature(text)) {
            return ContainerFormat.WEBVTT;
        }
        if (TtmlDocument.isSignature(text)) {
            return ContainerFormat.TTML;
        }
        if (SamiText.isSignature(text)) {
            return ContainerFormat.SAMI;
        }
        if (AssFile.isSignature(text)) {
            return ContainerFormat.ASS;
        }
        var times = new long[2];
        var seen = 0;
        for (var line : text.split("\n", 64)) {
            if (line.isBlank()) {
                continue;
            }
            if (SubRipText.isTimingLine(line, times)) {
                return ContainerFormat.SUBRIP;
            }
            if (++seen == 2) {
                break;
            }
        }
        if (MicroDvdText.isSignature(text)) {
            return ContainerFormat.MICRODVD;
        }
        if (LrcText.isSignature(text)) {
            return ContainerFormat.LRC;
        }
        return null;
    }

    /**
     * Recognizes a raw MPEG audio or ADTS stream by two consecutive consistent frame headers.
     *
     * @param header the bytes at the start of the frame stream
     * @return {@link ContainerFormat#ADTS}, {@link ContainerFormat#MPEG_AUDIO}, or {@code null}
     */
    private static ContainerFormat detectFrameStream(byte[] header) {
        if (header.length < 4 || (header[0] & 0xFF) != 0xFF) {
            return null;
        }
        var second = header[1] & 0xFF;
        if ((second & 0xF6) == 0xF0) {
            if (header.length < 7) {
                return null;
            }
            var length = ((header[3] & 0x03) << 11) | ((header[4] & 0xFF) << 3) | ((header[5] & 0xE0) >>> 5);
            if (length < 7) {
                return null;
            }
            if (length + 2 > header.length) {
                return ContainerFormat.ADTS;
            }
            return (header[length] & 0xFF) == 0xFF && (header[length + 1] & 0xF6) == 0xF0 ? ContainerFormat.ADTS : null;
        }
        if ((second & 0xE0) != 0xE0 || ((second >>> 1) & 0x03) == 0) {
            return null;
        }
        var frameLength = mpegFrameLength(header, 0);
        if (frameLength <= 0) {
            return null;
        }
        if (frameLength + 4 > header.length) {
            return ContainerFormat.MPEG_AUDIO;
        }
        return mpegFrameLength(header, frameLength) > 0 ? ContainerFormat.MPEG_AUDIO : null;
    }

    /**
     * Computes the length of the MPEG audio frame whose header starts at {@code offset}.
     *
     * @param data the bytes holding the header
     * @param offset the header offset
     * @return the frame length in bytes, or {@code -1} when the header is invalid or free-format
     */
    private static int mpegFrameLength(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return -1;
        }
        var header = MpegAudioHeader.read(data, offset);
        if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.isFreeFormat(header)) {
            return -1;
        }
        return MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
    }

    /**
     * Returns whether an ASCII tag appears at an offset.
     *
     * @param data the bytes
     * @param offset the offset of the tag
     * @param tag the ASCII tag
     * @return {@code true} when the bytes match
     */
    private static boolean matches(byte[] data, int offset, String tag) {
        if (offset + tag.length() > data.length) {
            return false;
        }
        for (var i = 0; i < tag.length(); i++) {
            if (data[offset + i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads up to {@code length} bytes at an offset.
     *
     * @param source the input
     * @param offset the absolute offset
     * @param length the maximum number of bytes
     * @return the bytes read, possibly fewer than requested at the end of the input
     * @throws MediaException.Io if reading fails or, against its contract, the source returns no
     * bytes before its end
     */
    private static byte[] read(MediaSource source, long offset, int length) {
        var size = source.size();
        var available = size < 0 ? length : (int) Math.max(0, Math.min(length, size - offset));
        var buffer = new byte[available];
        var done = 0;
        while (done < available) {
            var read = source.read(offset + done, buffer, done, available - done);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                var message = "the source returned no bytes at offset " + (offset + done) + " before its end";
                throw new MediaException.Io(message, new IOException(message));
            }
            done += read;
        }
        return done == buffer.length ? buffer : Arrays.copyOf(buffer, done);
    }
}
