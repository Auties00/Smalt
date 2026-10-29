package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.tags.TsDescriptors;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads MPEG program streams (ISO/IEC 13818-1 clause 2.5) and MPEG-1 system streams (ISO/IEC 11172-1): {@code .mpg}
 * and {@code .mpeg} files, the {@code .vob} files of DVD-Video, and the streams of VCD and SVCD.
 *
 * <p>The input is a sequence of packs, each a pack header in either syntax followed by PES packets; system headers,
 * padding, private stream 2 (the navigation packets of DVD-Video) and the program end code are read past, and up
 * to 64 KiB of leading garbage are skipped. A track is an elementary stream, identified by its {@code stream_id}
 * ({@code 0xC0} to {@code 0xDF} audio, {@code 0xE0} to {@code 0xEF} video), by {@code 0x100} plus the
 * {@code sub_stream_id} of a DVD-Video substream of private stream 1, or by {@code 0x200} plus the
 * {@code stream_id_extension} of an extended stream ({@code 0xFD}). A program stream map gives the coding of the
 * streams it lists by their {@code stream_type}, read as {@link TsDemuxer} reads the stream types of a program map
 * table, and their language; the other streams are recognized from their content:
 * <ul>
 *   <li>video streams: MPEG-1 and MPEG-2 video ({@link Codec#MPEG1_VIDEO}, {@link Codec#MPEG2_VIDEO}, as the
 *       sequence header says), MPEG-4 Part 2, H.264 and HEVC;</li>
 *   <li>audio streams: MP3 and MP2, and AAC in ADTS or LOAS/LATM framing;</li>
 *   <li>DVD-Video substreams of private stream 1: subpictures ({@code 0x20} to {@code 0x3F}) as
 *       {@link Codec#DVD_SUBTITLE}, one packet per subpicture unit lasting until its first stop display command;
 *       AC-3 ({@code 0x80} to {@code 0x87}) and AC-3 or E-AC-3 ({@code 0xC0} to {@code 0xCF}, as HD DVD uses them),
 *       one packet per access unit; DTS ({@code 0x88} to {@code 0x8F}) as {@link Codec#DTS}, one packet per frame
 *       with its DTS-HD extension substreams; linear PCM ({@code 0xA0} to {@code 0xA7}) as packets of 16-bit or
 *       24-bit big-endian samples ({@link Codec#PCM_S16BE}, {@link Codec#PCM_S24BE}, 20-bit words in 24-bit samples
 *       with 20 {@linkplain com.github.auties00.smalt.track.AudioTrack#bitsPerSample() bits per sample}); other
 *       substreams become data tracks with the handler {@code "private"}, one PES payload per packet;</li>
 *   <li>private stream 1 without substream identifiers (its first payload starts with an AC-3 sync word, or a
 *       program stream map describes it): its coding is recognized as {@link TsDemuxer} recognizes a private stream
 *       without descriptors.</li>
 * </ul>
 * Video packets are whole access units, one picture (or a pair of field pictures) with the headers before it, cut
 * from the elementary stream whatever its PES packetization, as {@link TsDemuxer} describes them otherwise. The tracks
 * are the streams met in the first 8 MiB, in the order they are met; when the source has random access (one of
 * unknown size is measured first) or its whole input fits its memory budget, windows spread over the rest of the file
 * find the streams that start later (a subtitle stream whose first subpicture comes minutes in), each described from
 * the packets that follow where it was found. The program stream map, when there is one, is taken from the first
 * that the probe window holds.
 *
 * <p>Timing. PES timestamps are unwrapped across the 33-bit wrap, continued across timeline discontinuities and
 * shifted so that the earliest presentation time of the probed packets is zero, as {@link TsDemuxer} does. Video and
 * data tracks keep the 90 kHz time base; audio tracks count samples of their rate. A picture whose PES packet
 * carries no timestamp is stamped from the frame rate. The system clock reference of the packs is not used.
 * {@link #durationMicros()} is measured from the presentation times near the end of the file.
 *
 * <p>Robustness. Bytes that do not form a pack, a system header or a PES packet make the demuxer search for the next
 * pack header whose marker bits hold, flagging the next packet of every stream
 * {@link com.github.auties00.smalt.track.PacketFlag#CORRUPT}; a PES packet cut by the end of the input is flagged too.
 * Scrambled PES packets (DVD CSS) are dropped: there is no descrambling.
 *
 * <p>Seeking needs a source of known size: the demuxer bisects the file on presentation times, then reads forwards to
 * the key frames at or before the target, as {@link TsDemuxer#seek(long)} describes. A source that reads its input
 * once, front to back (a stream), is told before every packet which bytes are no longer needed.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation reads the probe window twice (once to describe the tracks, once when reading), as
 * {@link TsDemuxer} does.
 */
public final class PsDemuxer implements Demuxer {
    /**
     * The length of the start of the file whose streams are all described.
     */
    private static final long PROBE_BYTES = 8L << 20;

    /**
     * The length after which the probe stops once every stream met is described.
     */
    private static final long MIN_PROBE_BYTES = 2L << 20;

    /**
     * The largest number of leading bytes searched for the first pack.
     */
    private static final int MAX_LEADING_GARBAGE = 64 * 1024;

    /**
     * The number of windows read after the probe window for the streams that start later.
     */
    private static final int SAMPLE_WINDOWS = 32;

    /**
     * The length of each such window.
     */
    private static final long SAMPLE_BYTES = 256 * 1024;

    /**
     * The farthest a stream found by sampling is read to describe it.
     */
    private static final long DESCRIBE_BYTES = 4L << 20;

    /**
     * The initial size of the tail window read to measure the duration.
     */
    private static final long TAIL_WINDOW = 2L << 20;

    /**
     * The largest tail window read to measure the duration.
     */
    private static final long MAX_TAIL_WINDOW = 32L << 20;

    /**
     * The farthest a bisection probe reads forwards for a presentation time.
     */
    private static final long CLOCK_PROBE_BYTES = 1L << 20;

    /**
     * The distance past the seek target, in 90 kHz ticks, after which the key frame search stops.
     */
    private static final long SEARCH_PAST_TARGET = 10 * 90_000L;

    /**
     * The first distance before the seek target, in 90 kHz ticks, at which the key frame search starts.
     */
    private static final long FIRST_BACKOFF = 90_000L;

    /**
     * The input.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The offset of the first pack.
     */
    private final long first;

    /**
     * Whether private stream 1 carries DVD-Video substream identifiers, or {@code null} when the file has none.
     */
    private final Boolean substreams;

    /**
     * The entries of the program stream map, by {@code stream_id}; empty without one.
     */
    private final Map<Integer, Psi.StreamEntry> map = new HashMap<>();

    /**
     * The configured parsers of the exposed tracks, by track identifier, in track order.
     */
    private final Map<Integer, ElementaryParser> parsers;

    /**
     * The exposed tracks.
     */
    private final List<Track> tracks;

    /**
     * The identifiers of the video and audio tracks.
     */
    private final Set<Integer> timedIds = new HashSet<>();

    /**
     * The absolute 90 kHz time presented first.
     */
    private final long origin;

    /**
     * The duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The packets completed and not yet returned.
     */
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();

    /**
     * The sink that queues completed packets.
     */
    private final ElementaryParser.UnitSink sink = (packet, offset) -> queue.add(packet);

    /**
     * The scanner of the current read pass.
     */
    private PsScanner scanner;

    /**
     * Whether the current read pass reached the end of the input.
     */
    private boolean ended;

    /**
     * The filter trimming the packets read after a seek, or {@code null}.
     */
    private SeekFilter filter;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no pack or recognized elementary stream is found
     * @throws MediaException.Io if reading fails
     */
    public PsDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no pack or recognized elementary stream is found
     * @throws MediaException.LimitExceeded if a stream exceeds the limits of {@code options}
     * @throws MediaException.Io if reading fails
     */
    public PsDemuxer(MediaSource source, DemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        try {
            this.first = locate(source);
            if (first < 0) {
                throw new MediaException.Malformed("no MPEG program stream pack header found");
            }
            var probe = new Probe();
            probe.run();
            this.substreams = probe.substreams;
            var described = new LinkedHashMap<Integer, ElementaryParser>();
            var trackList = new ArrayList<Track>();
            for (var parser : probe.parsers.values()) {
                if (parser.configured()) {
                    described.put(parser.trackId(), parser);
                    trackList.add(parser.track());
                    if (parser.track().kind() != TrackKind.DATA) {
                        timedIds.add(parser.trackId());
                    }
                }
            }
            if (trackList.isEmpty()) {
                throw new MediaException.Malformed("the program stream has no recognized elementary stream");
            }
            this.parsers = Collections.unmodifiableMap(described);
            this.tracks = List.copyOf(trackList);
            this.origin = probe.origin(tracks);
            this.durationMicros = measureDuration();
            restart(first, TsClock.NONE);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid MPEG program stream: " + exception, exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are a program stream or an MPEG-1 system stream: a pack header of
     * either syntax whose marker bits hold, at the start of the bytes, followed by a pack, system header or PES
     * start code (or the end of the bytes).
     *
     * @param header the leading bytes of the file
     * @return {@code true} when the bytes start with a pack
     */
    public static boolean matches(byte[] header) {
        if (header.length < 12 || header[0] != 0 || header[1] != 0 || header[2] != 1 || (header[3] & 0xFF) != 0xBA) {
            return false;
        }
        var length = PsScanner.packLength(header, 0, header.length);
        if (length < 0) {
            return false;
        }
        if (length + 4 > header.length) {
            return true;
        }
        return header[length] == 0 && header[length + 1] == 0 && header[length + 2] == 1
                && (header[length + 3] & 0xFF) >= 0xB9;
    }

    /**
     * Finds the first pack header of the input.
     *
     * @param source the input
     * @return its offset, or {@code -1} when none starts within the leading bytes searched
     */
    private static long locate(MediaSource source) {
        var header = new byte[MAX_LEADING_GARBAGE + 16];
        var read = 0;
        while (read < header.length) {
            var count = source.read(read, header, read, header.length - read);
            if (count <= 0) {
                break;
            }
            read += count;
        }
        for (var start = 0; start + 12 <= read && start <= MAX_LEADING_GARBAGE; start++) {
            if (header[start] == 0 && header[start + 1] == 0 && header[start + 2] == 1
                    && (header[start + 3] & 0xFF) == 0xBA && PsScanner.packLength(header, start, read - start) > 0) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Creates the parser of a stream from its key, the program stream map and its first PES packet.
     *
     * @param key the key of the stream
     * @param pes the first PES packet met, its payload after any substream header
     * @param map the entries of the program stream map, by {@code stream_id}
     * @param limits the resource bounds
     * @return the parser, or {@code null} for a stream this demuxer does not expose
     */
    static ElementaryParser parserFor(int key, ElementaryParser.Pes pes, Map<Integer, Psi.StreamEntry> map,
                                      MediaLimits limits) {
        if (key >= PsScanner.SUBSTREAM && key < PsScanner.EXTENDED) {
            var sub = key - PsScanner.SUBSTREAM;
            var entry = Psi.plainEntry(key, 0x06, null);
            if (sub >= 0x20 && sub <= 0x3F) {
                return new SubpictureParser(entry, limits);
            }
            if ((sub >= 0x80 && sub <= 0x87) || (sub >= 0xC0 && sub <= 0xCF)) {
                return new Ac3Parser(entry, limits);
            }
            if (sub >= 0x88 && sub <= 0x8F) {
                return new DtsParser(entry, limits);
            }
            if (sub >= 0xA0 && sub <= 0xA7) {
                return new DvdLpcmParser(entry, limits);
            }
            return new PesParser(entry, limits, Codec.DATA, "private");
        }
        var streamId = key >= PsScanner.EXTENDED ? 0xFD : key;
        var declared = map.get(streamId);
        if (declared != null) {
            var entry = declared.withTrackId(key);
            var parser = TsDemuxer.parserFor(entry, limits);
            if (parser instanceof MpegVideoParser) {
                return new AccessUnitFramer(parser, false);
            }
            if (parser instanceof Mpeg4VideoParser) {
                return new AccessUnitFramer(parser, true);
            }
            return parser;
        }
        var entry = Psi.plainEntry(key, 0x06, null);
        if (streamId >= 0xE0 && streamId <= 0xEF) {
            return new PsSniffer(entry, limits, true);
        }
        if (streamId >= 0xC0 && streamId <= 0xDF) {
            return new PsSniffer(entry, limits, false);
        }
        if (streamId == PsScanner.PRIVATE_1) {
            return new SniffingParser(entry, limits);
        }
        if (streamId == 0xFD) {
            return new PesParser(entry, limits, Codec.DATA, "private");
        }
        return null;
    }

    /**
     * Describes the tracks: reads the probe window, then the windows spread over the rest of the file.
     */
    private final class Probe {
        /**
         * The parser of every stream met, by key, in the order the streams were met.
         */
        private final Map<Integer, ElementaryParser> parsers = new LinkedHashMap<>();

        /**
         * The earliest presentation time seen of each track, in 90 kHz ticks.
         */
        private final Map<Integer, Long> firstTimes = new HashMap<>();

        /**
         * Whether private stream 1 carries DVD-Video substream identifiers, or {@code null} when none was met.
         */
        private Boolean substreams;

        /**
         * Whether a program stream map was read.
         */
        private boolean mapped;

        /**
         * Reads the probe window and samples the rest of the file.
         */
        void run() {
            var end = first + PROBE_BYTES;
            var scan = new PsScanner(source, first, end, new TsClock(0, TsClock.NONE));
            scan.listen((PsScanner.MapListener) (bytes, offset) -> {
                if (!mapped) {
                    var entries = Psi.parseProgramStreamMap(bytes);
                    if (entries != null) {
                        mapped = true;
                        for (var entry : entries) {
                            map.putIfAbsent(entry.pid(), entry);
                        }
                        if (map.containsKey(PsScanner.PRIVATE_1)) {
                            scan.substreams(false);
                        }
                    }
                }
            });
            scan.listen((PsScanner.StreamListener) (key, pes) -> {
                if (parsers.containsKey(key)) {
                    return null;
                }
                var parser = parserFor(key, pes, map, limits);
                if (parser != null) {
                    parsers.put(key, parser);
                }
                return parser;
            });
            ElementaryParser.UnitSink recorder = this::record;
            while (scan.step(recorder)) {
                if (scan.position() - first >= MIN_PROBE_BYTES && complete()) {
                    break;
                }
            }
            scan.finish(recorder);
            substreams = scan.substreams();
            sample(scan.position());
        }

        /**
         * Records the presentation time of a probed packet.
         *
         * @param packet a packet of a parser of the probe
         * @param offset the file offset where a read reproduces it
         */
        private void record(Packet packet, long offset) {
            var parser = parsers.get(packet.trackId());
            if (parser == null || !parser.configured() || packet.pts() == Packet.NO_TIMESTAMP) {
                return;
            }
            var ticks = parser.track().timeBase().rescale(packet.pts(), NalParser.TIME_BASE);
            firstTimes.merge(packet.trackId(), ticks, Math::min);
        }

        /**
         * Returns whether every stream met is described and has produced a timed packet.
         *
         * @return {@code true} when the probe can stop
         */
        private boolean complete() {
            for (var parser : parsers.values()) {
                if (!parser.configured() || (parser.track().kind() != TrackKind.DATA
                        && !firstTimes.containsKey(parser.trackId()))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Looks for the streams first met after the probe window in windows spread over the rest of the file, and
         * describes each from where it was found.
         *
         * @param from the offset at which the probe stopped
         */
        private void sample(long from) {
            var size = source.size();
            if (size < 0 || size - from <= SAMPLE_BYTES || !ForwardOnlySource.isCheapToReach(source, size)) {
                return;
            }
            var found = new LinkedHashMap<Integer, Long>();
            var step = (size - from) / SAMPLE_WINDOWS;
            for (var window = 0; window < SAMPLE_WINDOWS; window++) {
                var start = from + window * step;
                var scan = new PsScanner(source, start, Math.min(size, start + SAMPLE_BYTES),
                        new TsClock(0, TsClock.NONE));
                if (substreams != null) {
                    scan.substreams(substreams);
                }
                scan.listen((PsScanner.StreamListener) (key, pes) -> {
                    if (!parsers.containsKey(key) && !found.containsKey(key)
                            && parserFor(key, pes, map, limits) != null) {
                        found.put(key, pes.fileOffset());
                    }
                    return null;
                });
                ElementaryParser.UnitSink ignore = (packet, offset) -> {
                };
                while (scan.step(ignore)) {
                    // the whole window is read
                }
                if (substreams == null) {
                    substreams = scan.substreams();
                }
            }
            for (var entry : found.entrySet()) {
                describe(entry.getKey(), entry.getValue(), size);
            }
        }

        /**
         * Describes a stream from the packets that follow an offset.
         *
         * @param key the key of the stream
         * @param from the offset of a pack that holds one of its PES packets
         * @param size the size of the source
         */
        private void describe(int key, long from, long size) {
            var scan = new PsScanner(source, from, Math.min(size, from + DESCRIBE_BYTES), new TsClock(0, TsClock.NONE));
            if (substreams != null) {
                scan.substreams(substreams);
            }
            var created = new ElementaryParser[1];
            scan.listen((PsScanner.StreamListener) (candidate, pes) -> {
                if (candidate != key || created[0] != null) {
                    return null;
                }
                created[0] = parserFor(key, pes, map, limits);
                return created[0];
            });
            ElementaryParser.UnitSink ignore = (packet, offset) -> {
            };
            while (scan.step(ignore)) {
                if (created[0] != null && created[0].configured()) {
                    break;
                }
            }
            if (created[0] != null && !created[0].configured()) {
                scan.finish(ignore);
            }
            if (created[0] != null && created[0].configured()) {
                parsers.put(key, created[0]);
            }
        }

        /**
         * Returns the absolute time presented first.
         *
         * @param tracks the exposed tracks
         * @return the earliest presentation time of their probed packets, in 90 kHz ticks
         */
        long origin(List<Track> tracks) {
            var earliest = Long.MAX_VALUE;
            for (var track : tracks) {
                var time = firstTimes.get(track.id());
                if (time != null) {
                    earliest = Math.min(earliest, time);
                }
            }
            return earliest == Long.MAX_VALUE ? 0 : earliest;
        }
    }

    /**
     * Starts a read pass with fresh parsers.
     *
     * @param position the offset of the first unit read
     * @param reference the absolute time the first timestamps are unwrapped against, or {@link TsClock#NONE}
     */
    private void restart(long position, long reference) {
        scanner = newScanner(position, Long.MAX_VALUE, reference, false);
        queue.clear();
        ended = false;
    }

    /**
     * Creates a scanner over the exposed streams with fresh parsers.
     *
     * @param position the offset of the first unit read
     * @param end the offset at which the scan ends
     * @param reference the absolute time the first timestamps are unwrapped against, or {@link TsClock#NONE}
     * @param search whether the parsers also emit the units before a stream's first key frame
     * @return the scanner
     */
    private PsScanner newScanner(long position, long end, long reference, boolean search) {
        var created = new PsScanner(source, position, end, new TsClock(origin, reference));
        if (substreams != null) {
            created.substreams(substreams);
        }
        for (var parser : parsers.values()) {
            var fresh = parser.fresh();
            if (search) {
                fresh.keepLeadingUnits();
            }
            created.parse(parser.trackId(), fresh);
        }
        return created;
    }

    /**
     * Measures the duration from the presentation times near the end of the file.
     *
     * @return the duration in microseconds, or {@code -1} when the size is unknown or no timestamp is found
     */
    private long measureDuration() {
        var size = source.size();
        if (size < 0 || !ForwardOnlySource.isCheapToReach(source, size)) {
            return -1;
        }
        for (var window = TAIL_WINDOW; ; window *= 2) {
            var start = Math.max(first, size - window);
            var ends = new long[]{Long.MIN_VALUE};
            var tail = newScanner(start, size, origin, false);
            ElementaryParser.UnitSink recorder = (packet, offset) -> {
                if (packet.pts() == Packet.NO_TIMESTAMP || !timedIds.contains(packet.trackId())) {
                    return;
                }
                var track = trackOf(packet.trackId());
                try {
                    var end = track.timeBase().toMicros(packet.pts() + Math.max(0, packet.duration()));
                    ends[0] = Math.max(ends[0], end);
                } catch (ArithmeticException exception) {
                    // a timestamp out of range does not count
                }
            };
            while (tail.step(recorder)) {
                // the whole window is read
            }
            tail.finish(recorder);
            if (ends[0] != Long.MIN_VALUE) {
                return Math.max(0, ends[0]);
            }
            if (start == first || window >= MAX_TAIL_WINDOW) {
                return -1;
            }
        }
    }

    /**
     * Returns an exposed track by identifier.
     *
     * @param trackId the identifier
     * @return the track, or {@code null}
     */
    private Track trackOf(int trackId) {
        for (var track : tracks) {
            if (track.id() == trackId) {
                return track;
            }
        }
        return null;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MPEG_PS;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    /**
     * {@inheritDoc}
     *
     * @return an empty map: program streams carry no container tags this demuxer reads
     */
    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The dispositions of a track are those the {@code audio_type} of the ISO 639 language descriptor of its
     * program stream map entry names: 1 {@link TrackDisposition#CLEAN_EFFECTS}, 2
     * {@link TrackDisposition#HEARING_IMPAIRED} and 3 {@link TrackDisposition#VISUAL_IMPAIRED}.
     */
    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        var parser = parsers.get(trackId);
        if (parser == null) {
            return Set.of();
        }
        return TsDescriptors.dispositions(parser.entry.audioType());
    }

    /**
     * {@return the PES presentation time, in 90 kHz ticks, that the demuxer's timeline starts from: the earliest
     * presentation time of the probed packets, which the packets report as zero}
     */
    public long originTicks() {
        return origin;
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public Packet read() {
        try {
            if (filter != null) {
                while (true) {
                    var packet = filter.poll();
                    if (packet != null) {
                        return packet;
                    }
                    if (filter.isTransparent()) {
                        filter = null;
                        return nextPacket();
                    }
                    var next = nextPacket();
                    if (next == null) {
                        filter.finish();
                    } else {
                        filter.offer(next);
                    }
                }
            }
            return nextPacket();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG program stream: " + exception, exception);
        }
    }

    /**
     * Returns the next packet in file order, without the seek filter.
     *
     * @return the packet, or {@code null} at the end of the input
     */
    private Packet nextPacket() {
        while (true) {
            var packet = queue.poll();
            if (packet != null) {
                return packet;
            }
            if (ended) {
                return null;
            }
            ForwardOnlySource.release(source, scanner.position());
            if (!scanner.step(sink)) {
                scanner.finish(sink);
                ended = true;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the size of the source is known
     */
    @Override
    public boolean isSeekable() {
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The demuxer bisects the file on presentation times to a point one second before {@code micros}, reads
     * forwards until every video and audio track passed the target, and backs off three times further each time
     * such a track met no key frame at or before the target, down to the start of the file; it then restarts at the
     * earliest of the tracks' key frames.
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "mpeg-ps demuxing cannot seek a source of unknown size");
        }
        try {
            var target = Math.max(0, micros);
            if (target == 0) {
                restart(first, TsClock.NONE);
                filter = null;
                return;
            }
            var targetTicks = Math.floorDiv(target * 9, 100);
            var start = first;
            var backoff = FIRST_BACKOFF;
            Search search;
            while (true) {
                start = bisect(targetTicks - backoff);
                search = search(start, targetTicks);
                if (!search.needsEarlier || start == first) {
                    break;
                }
                backoff = backoff * 3;
            }
            restart(search.restart >= 0 ? search.restart : start, origin + targetTicks);
            filter = new SeekFilter(tracks, target);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG program stream: " + exception, exception);
        }
    }

    /**
     * Holds the outcome of a key frame search.
     *
     * @param restart the offset of the earliest key frame found, or {@code -1}
     * @param needsEarlier whether a video or audio track met no key frame at or before the target
     */
    private record Search(long restart, boolean needsEarlier) {
    }

    /**
     * Reads forwards from an offset to find each track's last key frame at or before a target.
     *
     * @param start the offset of the first unit read
     * @param targetTicks the target in 90 kHz ticks from the origin
     * @return the search outcome
     */
    private Search search(long start, long targetTicks) {
        var candidates = new HashMap<Integer, Long>();
        var later = new HashMap<Integer, Long>();
        var seen = new HashSet<Integer>();
        var passed = new HashSet<Integer>();
        var farthest = new long[]{Long.MIN_VALUE};
        var scan = newScanner(start, Long.MAX_VALUE, origin + targetTicks, true);
        ElementaryParser.UnitSink recorder = (packet, offset) -> {
            var track = trackOf(packet.trackId());
            if (track == null) {
                return;
            }
            var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
            if (time == Packet.NO_TIMESTAMP) {
                return;
            }
            var ticks = track.timeBase().rescale(time, NalParser.TIME_BASE);
            farthest[0] = Math.max(farthest[0], ticks);
            seen.add(track.id());
            if (ticks <= targetTicks) {
                if (packet.keyframe()) {
                    candidates.put(track.id(), offset);
                }
            } else {
                passed.add(track.id());
                if (packet.keyframe()) {
                    later.putIfAbsent(track.id(), offset);
                }
            }
        };
        var exhausted = true;
        while (scan.step(recorder)) {
            if (farthest[0] > targetTicks + SEARCH_PAST_TARGET || passed.containsAll(timedIds)) {
                exhausted = false;
                break;
            }
        }
        if (exhausted) {
            scan.finish(recorder);
        }
        var needsEarlier = false;
        var restart = -1L;
        for (var track : tracks) {
            if (track.kind() == TrackKind.DATA) {
                continue;
            }
            var candidate = candidates.get(track.id());
            if (candidate == null) {
                if (seen.contains(track.id())) {
                    needsEarlier = true;
                }
                candidate = later.get(track.id());
                if (candidate == null) {
                    continue;
                }
            }
            restart = restart < 0 ? candidate : Math.min(restart, candidate);
        }
        for (var track : tracks) {
            var candidate = candidates.get(track.id());
            if (track.kind() == TrackKind.DATA && candidate != null && restart >= 0) {
                restart = Math.min(restart, candidate);
            }
        }
        return new Search(restart, needsEarlier);
    }

    /**
     * Finds an offset shortly before the first unit whose presentation time is after a time.
     *
     * @param ticks the time in 90 kHz ticks from the origin
     * @return the offset, the first pack when every time is later
     */
    private long bisect(long ticks) {
        if (ticks <= 0) {
            return first;
        }
        var low = first;
        var high = source.size();
        while (high - low > 2048) {
            var middle = low + (high - low) / 2;
            var time = clockAt(middle);
            if (time != TsClock.NONE && time <= ticks) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Returns the first presentation time of a video or audio track found reading forwards from an offset.
     *
     * @param offset the offset the search starts from, anywhere in the file
     * @return the time in 90 kHz ticks from the origin, or {@link TsClock#NONE} when none is found within
     * {@value #CLOCK_PROBE_BYTES} bytes
     */
    private long clockAt(long offset) {
        var scan = new PsScanner(source, offset, Math.min(source.size(), offset + CLOCK_PROBE_BYTES),
                new TsClock(0, TsClock.NONE));
        if (substreams != null) {
            scan.substreams(substreams);
        }
        var found = new long[]{TsClock.NONE};
        scan.listen((PsScanner.StreamListener) (key, pes) -> {
            if (found[0] == TsClock.NONE && pes.pts() != TsClock.NONE && timedIds.contains(key)) {
                found[0] = TsClock.unwrap(pes.pts(), origin) - origin;
            }
            return null;
        });
        ElementaryParser.UnitSink ignore = (packet, at) -> {
        };
        while (found[0] == TsClock.NONE && scan.step(ignore)) {
            // read until a timestamp is found
        }
        return found[0];
    }
}
