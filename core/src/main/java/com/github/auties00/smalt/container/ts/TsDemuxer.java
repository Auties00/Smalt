package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.MeasuredSource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.tags.TsDescriptors;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads MPEG-2 transport streams (ISO/IEC 13818-1): broadcast captures, {@code .ts} files, HLS
 * segments, and the M2TS ({@code .m2ts}, {@code .mts}) files of Blu-ray discs and AVCHD cameras.
 *
 * <p>Packets of 188 bytes, of 192 bytes (M2TS: a four-byte arrival timestamp before each
 * packet) and of 204 bytes (sixteen bytes of Reed-Solomon parity after each packet) are
 * recognized from the spacing of their sync bytes, after up to 64 KiB of leading garbage. The
 * program association table names the programs; the demuxer exposes the elementary streams of
 * one of them ({@link TsDemuxerOptions#program()}, the first program with an elementary stream by
 * default), and {@link #programs()} lists them all.
 *
 * <p>Supported streams, by {@code stream_type} and descriptors:
 * <ul>
 *   <li>H.264 ({@code 0x1B}) and HEVC ({@code 0x24}, or {@code 0x06} with the {@code "HEVC"}
 *       registration): one packet per access unit, split on the access unit boundaries of the
 *       byte stream whatever the PES packetization, as four-byte length-prefixed NAL units
 *       without the access unit delimiters, with an {@code avcC} or {@code hvcC} configuration
 *       built from the parameter sets met before the first key frame. IDR and IRAP pictures, and
 *       H.264 intra pictures with a recovery point SEI message, are key frames; the access units
 *       before the first key frame cannot be decoded and are dropped. A packet lasts until the
 *       next access unit's decoding time. The Dolby Vision reference processing units (HEVC NAL
 *       unit type 62) stay in the packets;</li>
 *   <li>AV1 ({@code 0x06} with the {@code "AV01"} registration, AOM "Carriage of AV1 in MPEG-2
 *       TS"): one packet per temporal unit, its OBUs freed of their start codes and emulation
 *       prevention and stored with size fields, without temporal delimiters, as ISOBMFF stores
 *       them, with an {@code av1C} configuration built from the sequence header in force at the
 *       first key frame; temporal units before it are dropped;</li>
 *   <li>the DOVI video stream descriptor (tag {@code 0xB0}, Dolby's "Dolby Vision Streams Within the MPEG-2
 *       Transport Stream Format") of an H.264, HEVC or AV1 stream gives its track's
 *       {@link com.github.auties00.smalt.track.VideoTrack#dolbyVision() Dolby Vision configuration};</li>
 *   <li>MPEG-4 Part 2 ({@code 0x10}): one packet per PES packet, with the visual object sequence,
 *       visual object and video object layer headers of the first key frame as the
 *       configuration, and the picture size, frame rate and sample aspect ratio of the video
 *       object layer;</li>
 *   <li>AAC in ADTS ({@code 0x0F}) and in LOAS/LATM ({@code 0x11}): raw data blocks with an
 *       {@code AudioSpecificConfig}; a LATM stream may declare several programs and layers (the
 *       first is exposed), chunked payloads, fixed frame lengths and a program config element;</li>
 *   <li>MPEG audio ({@code 0x03}, {@code 0x04}): MP3 and MP2 frames;</li>
 *   <li>AC-3 and E-AC-3 ({@code 0x81}, {@code 0x87}, the Blu-ray {@code 0x84}, or {@code 0x06}
 *       with a DVB AC-3 or enhanced AC-3 descriptor or the {@code "AC-3"} or {@code "EAC3"}
 *       registration): one packet per access unit (a sync frame with its dependent substreams),
 *       as MP4 and Matroska store them;</li>
 *   <li>Opus ({@code 0x06} with the {@code "Opus"} registration): Opus packets with an
 *       {@code OpusHead} built from the DVB extension descriptor;</li>
 *   <li>uncompressed audio: Blu-ray linear PCM ({@code 0x80} in a program with the
 *       {@code "HDMV"} registration) and SMPTE ST 302 AES3 data ({@code 0x06} with the
 *       {@code "BSSD"} registration), as packets of interleaved PCM samples (16-bit or 24-bit,
 *       20-bit words in 24-bit samples), one per PES packet;</li>
 *   <li>ID3 timed metadata ({@code 0x15}, or {@code 0x06} with the {@code "ID3 "} registration,
 *       as HLS uses it): a {@link com.github.auties00.smalt.track.DataTrack} with the handler
 *       {@code "id3"} whose packets are the ID3v2 tags, unparsed;</li>
 *   <li>SCTE 35 splice information ({@code 0x86} outside Blu-ray programs, carried in sections): a
 *       data track with the handler {@code "scte35"} whose packets are the whole
 *       {@code splice_info_section}s, stamped with the program's latest decoding time when they
 *       arrive, and unparsed but for the {@code pts_adjustment}, rebased so that a splice time plus
 *       it is a time of the timeline the tracks share, in 90 kHz ticks;</li>
 *   <li>subtitles: Blu-ray presentation graphic streams ({@code 0x90}) as {@link Codec#PGS}, one PES packet
 *       (one segment, usually) per packet; DVB subtitles ({@code 0x06} with a subtitling descriptor) as
 *       {@link Codec#DVB_SUBTITLE}, the segments of each PES packet with the descriptor body as the
 *       configuration; DVB teletext ({@code 0x06} with a teletext descriptor) as {@link Codec#DVB_TELETEXT}, each
 *       PES data field with the descriptor body as the configuration;</li>
 *   <li>MPEG-1 and MPEG-2 video ({@code 0x01}, {@code 0x02}) as {@link Codec#MPEG1_VIDEO} and
 *       {@link Codec#MPEG2_VIDEO}, as the sequence header says (either stream type may carry either): one packet
 *       per PES packet, with the sequence header and extensions of the first key frame as the configuration, and
 *       the picture size, frame rate, sample aspect ratio and color description of that header; packets before it
 *       are dropped;</li>
 *   <li>other PES streams: private data ({@code 0x06}, user private types from {@code 0x80})
 *       become data tracks with the handler {@code "private"}, except that a {@code 0x06} stream without a
 *       descriptor whose first payload starts with an ADTS, LOAS, AC-3 or MPEG audio header or an MPEG-4 Part 2
 *       start code is that audio or video (M2TS writers declare AAC and MPEG-4 Part 2 that way); the other
 *       Blu-ray audio types are passed through one PES packet per packet.</li>
 * </ul>
 * Other section-carried streams (DSM-CC) are not exposed. The tracks are described from the first
 * {@link TsDemuxerOptions#probeBytes()} bytes, which are read again by the first {@link #read()}.
 *
 * <p>Program map changes. A new version of the exposed program's map table (or a map that lists
 * other streams under the same version, as concatenated recordings have) takes effect at the
 * packet that carries it: a stream on the same PID with the same coding keeps its track, a stream
 * whose coding matches one the new map dropped continues that stream's track on its new PID, and
 * any other stream is a track of its own, identified by its PID, or by the first free identifier
 * from {@code 0x2000} when an earlier track already had that one. A move of the map table itself
 * to another PID is followed through the program association table. The maps in force after the
 * probe window are found when the demuxer opens a source with random access (one of unknown size
 * is measured first) or a source read once whose whole input fits its memory budget: it reads the
 * map table near the end of the file and, while that map differs from the one in force, bisects
 * for the packet where the map changes; a track a later map adds is described from the packets
 * that follow that map. From any other source read once, only the tracks of the probe window are
 * exposed: later maps move them to their new PIDs, and a later map that adds a stream fails the
 * read with {@link MediaException.Unsupported}. Every track's identifier is the PID its stream had
 * when first found.
 *
 * <p>A stream whose configuration changes after the start (a splice, concatenated recordings)
 * keeps its track, and the packet where the change takes effect carries the new configuration as
 * its {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig()}: an H.264 IDR or
 * HEVC IRAP access unit whose active sequence (and HEVC video) parameter sets differ from those of
 * the configuration in force (a picture parameter set change alone stays in band), the first ADTS
 * frame of a new sampling frequency, profile or channel configuration that the next frame
 * confirms, and the first LATM payload after a new {@code AudioSpecificConfig}. Audio durations
 * stay in samples of the track's rate. After a seek the configuration in force is the track's, so
 * the first packet read in another one carries it again.
 *
 * <p>Timing. PES timestamps are unwrapped across the 33-bit wrap and continued across timeline
 * discontinuities (a splice or a restarted encoder, recognized by a stream's decoding times
 * jumping more than half a second back or ten seconds forwards), then shifted so that the
 * earliest presentation time of the probed packets is zero; a video track's decoding times may
 * then start below zero by its reordering delay. Video and data tracks keep the 90 kHz time
 * base; audio tracks count samples of their rate, each frame stamped with the PES time of the
 * packet it starts in or following the previous frame. The program clock reference is not used:
 * the PES timestamps carry the presentation. {@link #durationMicros()} is measured from the last
 * presentation times found near the end of the file; tracks report no duration of their own.
 *
 * <p>Robustness. A lost sync byte makes the demuxer search for the next three aligned sync bytes;
 * continuity counter gaps, the transport error indicator and scrambled packets flag the packets
 * they damage with {@link com.github.auties00.smalt.track.PacketFlag#CORRUPT} (scrambled payloads
 * are dropped: there is no descrambling); elementary parsers resynchronize on their own sync
 * words, and an H.264 or HEVC stream restarts at its next key frame.
 *
 * <p>Seeking needs a source of known size. It bisects the file on PES presentation times to a
 * point shortly before the target, then reads forwards to find each track's last key frame at or
 * before the target, backing off further while a track has none, and restarts the read at the
 * earliest of those key frames, as the {@link Demuxer#seek(long)} contract describes.
 *
 * <p>Instances are not thread-safe.
 *
 * <p>A source that reads its input once, front to back (a stream), is told before every packet
 * which bytes are no longer needed, and the duration is measured only when the whole input fits
 * the source's memory budget.
 *
 * @implNote This implementation reads the probe window twice (once to describe the tracks, once
 * when reading), rather than keeping its packets, because the parsers that describe the tracks
 * change their state as they learn the stream; the window is at most
 * {@value TsDemuxerOptions#DEFAULT_PROBE_BYTES} bytes by default. Timeline continuation across
 * discontinuities applies to a read from the start; after a seek the timestamps of a file with
 * discontinuities follow the raw clock unwrapped near the target.
 */
public final class TsDemuxer implements Demuxer {
    /**
     * The largest number of leading bytes searched for the first packet.
     */
    private static final int MAX_LEADING_GARBAGE = 64 * 1024;

    /**
     * The packet sizes tried, in order: plain, M2TS (a four-byte arrival timestamp first) and
     * 204-byte packets carrying 16 bytes of Reed-Solomon parity.
     */
    private static final int[] PACKET_SIZES = {TsScanner.PACKET, 192, 204};

    /**
     * The number of consecutive aligned sync bytes that identify the packet layout.
     */
    private static final int SYNC_RUN = 5;

    /**
     * The length of the end of the file read for its last program map, to find the changes of the map; the
     * length doubles, up to {@link #MAX_TAIL_WINDOW}, until the part read holds a map table.
     */
    private static final long MAP_TAIL_BYTES = 512 * 1024;

    /**
     * The length by which each doubling of the tail read for the last program map overlaps the part read before,
     * so a section that straddles the boundary is still found.
     */
    private static final long MAP_OVERLAP_BYTES = 64 * 1024;

    /**
     * The farthest a program map read reads forwards for a section.
     */
    private static final int MAP_SAMPLE_BYTES = 4 << 20;

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
    private static final int CLOCK_PROBE_BYTES = 1 << 20;

    /**
     * The distance past the seek target, in 90 kHz ticks, after which the key frame search stops.
     */
    private static final long SEARCH_PAST_TARGET = 10 * 90_000L;

    /**
     * The first distance before the seek target, in 90 kHz ticks, at which the key frame search
     * starts.
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
     * The packet layout.
     */
    private final TsScanner.Geometry geometry;

    /**
     * The programs of the program association table, in table order.
     */
    private final List<TsProgram> programs;

    /**
     * The exposed program.
     */
    private final TsProgram program;

    /**
     * The configured parsers of the exposed tracks, by track identifier, in track order.
     */
    private final Map<Integer, ElementaryParser> parsers;

    /**
     * The program map versions of the exposed program, in file order; set once the probe ends.
     */
    private List<MapVersion> versions;

    /**
     * The program map versions found so far during the probe.
     */
    private final List<MapVersion> probeVersions = new ArrayList<>();

    /**
     * Whether the program map versions of the whole file were looked for when opening, so that a map met later
     * that adds a stream is one the sampling missed rather than one that could not be known.
     */
    private boolean mapsDiscovered;

    /**
     * The PIDs of every exposed stream in any program map version.
     */
    private final Set<Integer> exposedPids = new HashSet<>();

    /**
     * The exposed tracks, in program map order.
     */
    private final List<Track> tracks;

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
    private TsScanner scanner;

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
     * @throws MediaException.Malformed if no transport packet, program or elementary stream is found
     * @throws MediaException.Io if reading fails
     */
    public TsDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no transport packet, program or elementary stream is found
     * @throws MediaException.Io if reading fails
     */
    public TsDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, TsDemuxerOptions.builder().limits(options.limits()).build());
    }

    /**
     * Opens a transport stream.
     *
     * @param source the input, read by this demuxer; not closed by it
     * @param options the program selection, probe window and resource bounds
     * @throws MediaException.Unsupported if the requested program does not exist
     * @throws MediaException.Malformed if no transport packet, program or elementary stream is
     * found
     * @throws MediaException.Io if reading fails
     */
    public TsDemuxer(MediaSource source, TsDemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        try {
            this.geometry = locate(source);
            if (geometry == null) {
                throw new MediaException.Malformed("no MPEG transport stream packets found");
            }
            var probe = new Probe(options);
            probe.run();
            this.programs = List.copyOf(probe.programs());
            this.program = probe.selected;
            this.versions = List.copyOf(probeVersions);
            var described = new LinkedHashMap<Integer, ElementaryParser>();
            var trackList = new ArrayList<Track>();
            for (var parser : probe.parsers.values()) {
                if (parser.configured()) {
                    described.put(parser.trackId(), parser);
                    trackList.add(parser.track());
                }
            }
            if (trackList.isEmpty()) {
                throw new MediaException.Malformed("the transport stream program has no recognized elementary stream");
            }
            this.parsers = Collections.unmodifiableMap(described);
            this.tracks = List.copyOf(trackList);
            for (var version : versions) {
                for (var entry : version.entries()) {
                    if (described.containsKey(entry.trackId())) {
                        exposedPids.add(entry.pid());
                    }
                }
            }
            this.origin = probe.origin(tracks);
            this.durationMicros = measureDuration();
            restart(geometry.first(), TsClock.NONE);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid MPEG transport stream: " + exception, exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are transport packets.
     *
     * <p>Packets of 188, 192 or 204 bytes must follow each other with the sync byte where that
     * layout puts it: from the start of the bytes, at least three packets and up to eight when the
     * bytes hold them; after leading garbage, at least five.
     *
     * @param header the leading bytes of the file
     * @return {@code true} when the bytes are transport packets
     */
    public static boolean matches(byte[] header) {
        Objects.requireNonNull(header, "header");
        for (var start = 0; start < header.length; start++) {
            if ((header[start] & 0xFF) != TsScanner.SYNC) {
                continue;
            }
            for (var size : PACKET_SIZES) {
                var sync = size == 192 ? 4 : 0;
                if (start < sync) {
                    continue;
                }
                var count = (header.length - start - 1) / size + 1;
                var needed = start == sync ? Math.min(count, 8) : 5;
                if (needed >= 3 && count >= needed && run(header, start, size, needed) >= needed) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts the consecutive sync bytes at a stride.
     *
     * @param data the bytes
     * @param offset the index of the first sync byte
     * @param stride the packet size
     * @param limit the largest count needed
     * @return the number of consecutive sync bytes, at most {@code limit}
     */
    private static int run(byte[] data, int offset, int stride, int limit) {
        var count = 0;
        for (var index = offset; index < data.length && count < limit; index += stride) {
            if ((data[index] & 0xFF) != TsScanner.SYNC) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * Finds the packet layout and the first packet of a file.
     *
     * @param source the input
     * @return the layout, or {@code null} when no run of aligned sync bytes is found
     */
    private static TsScanner.Geometry locate(MediaSource source) {
        var size = source.size();
        var length = (int) (size < 0 ? MAX_LEADING_GARBAGE + SYNC_RUN * 204 : Math.min(size, MAX_LEADING_GARBAGE + SYNC_RUN * 204));
        var header = new byte[length];
        var read = new ReadAheadCache(source, 128 * 1024, 0).readUpTo(0, header, 0, length);
        for (var start = 0; start < Math.min(read, MAX_LEADING_GARBAGE); start++) {
            if ((header[start] & 0xFF) != TsScanner.SYNC) {
                continue;
            }
            for (var stride : PACKET_SIZES) {
                var sync = stride == 192 ? 4 : 0;
                if (start < sync) {
                    continue;
                }
                var available = (read - 1 - start) / stride + 1;
                var needed = Math.min(SYNC_RUN, available);
                var single = needed == 1 && start == sync && read - start <= stride;
                if ((needed >= 2 || single) && run(header, start, stride, needed) >= needed) {
                    return new TsScanner.Geometry(stride, sync, start - sync);
                }
            }
        }
        return null;
    }

    /**
     * Describes the streams of the exposed program that a program map table version maps, from the file offset
     * at which it comes in force.
     *
     * @param offset the offset of the first packet read with this mapping
     * @param pmtPid the PID of the program map table
     * @param pmt the program map section
     * @param entries the streams of the section, each with the identifier of the track it feeds
     */
    private record MapVersion(long offset, int pmtPid, Psi.PmtSection pmt, List<Psi.StreamEntry> entries) {
    }

    /**
     * Gives the streams of a program map section the identifiers of the tracks they feed.
     *
     * <p>A stream keeps the track of the stream of the previous mapping with the same PID and coding; a stream
     * whose coding matches a stream of the previous mapping that no stream continued that way continues that
     * stream's track (the stream moved to another PID); any other stream starts a track, identified by its PID
     * unless an earlier track took that identifier, in which case it gets the first free identifier from
     * {@code 0x2000}.
     *
     * @param previous the streams of the mapping in force before, with their track identifiers
     * @param pmt the new section
     * @param used the track identifiers given out so far, updated with the new ones
     * @return the streams of the new section with their track identifiers
     */
    static List<Psi.StreamEntry> assign(List<Psi.StreamEntry> previous, Psi.PmtSection pmt, Set<Integer> used) {
        var result = new ArrayList<Psi.StreamEntry>();
        var continued = new HashSet<Integer>();
        var pending = new ArrayList<Psi.StreamEntry>();
        for (var stream : pmt.streams()) {
            Psi.StreamEntry match = null;
            for (var old : previous) {
                if (old.pid() == stream.pid() && old.sameCoding(stream) && !continued.contains(old.trackId())) {
                    match = old;
                    break;
                }
            }
            if (match != null) {
                continued.add(match.trackId());
                result.add(stream.withTrackId(match.trackId()));
            } else {
                result.add(null);
                pending.add(stream);
            }
        }
        var index = 0;
        for (var position = 0; position < result.size(); position++) {
            if (result.get(position) != null) {
                continue;
            }
            var stream = pending.get(index++);
            Psi.StreamEntry moved = null;
            for (var old : previous) {
                if (old.sameCoding(stream) && !continued.contains(old.trackId())) {
                    moved = old;
                    break;
                }
            }
            int id;
            if (moved != null) {
                id = moved.trackId();
            } else if (!used.contains(stream.pid())) {
                id = stream.pid();
            } else {
                id = 0x2000;
                while (used.contains(id)) {
                    id++;
                }
            }
            continued.add(id);
            used.add(id);
            result.set(position, stream.withTrackId(id));
        }
        return result;
    }

    /**
     * Keeps the parsers of a scan in step with the program map: starts it with the mapping in force at its
     * first packet and, when the program map table (or the PID that carries it) changes during the scan,
     * moves, adds and removes parsers so that every track keeps its parser.
     */
    private final class Mapper {
        /**
         * The scan.
         */
        private final TsScanner scanner;

        /**
         * Creates the parser of a track that joins the scan.
         */
        private final Function<Psi.StreamEntry, ElementaryParser> provider;

        /**
         * The program whose map is followed.
         */
        private final int programNumber;

        /**
         * The streams of the mapping in force, with their track identifiers.
         */
        private List<Psi.StreamEntry> current;

        /**
         * The section of the mapping in force.
         */
        private Psi.PmtSection section;

        /**
         * The parser of each track of the mapping in force, by track identifier.
         */
        private final Map<Integer, ElementaryParser> live = new HashMap<>();

        /**
         * The PID of the program map table.
         */
        private int pmtPid;

        /**
         * Receives the sections of a version met during the scan, or {@code null}.
         */
        private final VersionListener versions;

        /**
         * Starts following the program map in a scan.
         *
         * @param scanner the scan
         * @param programNumber the program
         * @param version the mapping in force at the scan's first packet
         * @param provider creates the parser of a track, or returns {@code null} for a stream that is not exposed
         * @param versions receives every new mapping met, or {@code null}
         */
        Mapper(TsScanner scanner, int programNumber, MapVersion version,
               Function<Psi.StreamEntry, ElementaryParser> provider, VersionListener versions) {
            this.scanner = scanner;
            this.provider = provider;
            this.programNumber = programNumber;
            this.versions = versions;
            this.current = List.of();
            this.pmtPid = version.pmtPid();
            install(version.pmt(), version.entries(), null);
            scanner.watch(0, this::table);
            scanner.watch(pmtPid, this::table);
        }

        /**
         * Receives a section of the program association table or of the program map table.
         *
         * @param pid the PID
         * @param bytes the section
         * @param offset the offset of the packet in which it started
         * @param sink the sink of the scan
         */
        private void table(int pid, byte[] bytes, long offset, ElementaryParser.UnitSink sink) {
            var table = bytes[0] & 0xFF;
            if (pid == 0 && table == Psi.PAT_TABLE) {
                if (!Psi.isCurrent(bytes)) {
                    return;
                }
                for (var entry : Psi.parsePat(bytes)) {
                    if (entry.number() == programNumber && entry.pid() != pmtPid && entry.pid() != 0) {
                        scanner.unwatch(pmtPid);
                        pmtPid = entry.pid();
                        scanner.watch(pmtPid, this::table);
                    }
                }
            } else if (pid == pmtPid && table == Psi.PMT_TABLE && Psi.isCurrent(bytes)) {
                var pmt = Psi.parsePmt(bytes);
                if (pmt == null || pmt.number() != programNumber || pmt.sameStreams(section)) {
                    return;
                }
                var known = knownVersion(pmt);
                var entries = known != null ? known.entries() : assign(current, pmt, new HashSet<>(usedIds()));
                if (known == null && versions == null && !mapsDiscovered) {
                    for (var entry : entries) {
                        if (!parsers.containsKey(entry.trackId()) && parserFor(entry, limits) != null) {
                            throw new MediaException.Unsupported("mpeg-ts program map change", "the program map adds"
                                    + " a stream on PID " + entry.pid() + ", which a source read once without a known"
                                    + " size cannot describe in advance");
                        }
                    }
                }
                if (versions != null) {
                    versions.version(new MapVersion(offset, pmtPid, pmt, entries));
                }
                install(pmt, entries, sink);
            }
        }

        /**
         * Returns the identifiers of the tracks of the mapping in force and of every known mapping.
         *
         * @return the identifiers
         */
        private Set<Integer> usedIds() {
            var ids = new HashSet<Integer>();
            for (var entry : current) {
                ids.add(entry.trackId());
            }
            for (var version : mapVersions()) {
                for (var entry : version.entries()) {
                    ids.add(entry.trackId());
                }
            }
            return ids;
        }

        /**
         * Makes a mapping the one in force: keeps the parsers of the streams that stay on their PID, moves the
         * parser of every track whose stream changed PID (after passing it the PES packet in progress on the old
         * PID), gives the new tracks theirs, and flushes and drops those of the tracks the mapping leaves out.
         *
         * @param pmt the section
         * @param entries its streams with their track identifiers
         * @param sink receives what the dropped parsers still held, or {@code null} at the start of the scan
         */
        private void install(Psi.PmtSection pmt, List<Psi.StreamEntry> entries, ElementaryParser.UnitSink sink) {
            var kept = new HashSet<Integer>();
            var unchanged = new HashSet<Long>();
            for (var entry : entries) {
                kept.add(entry.trackId());
            }
            for (var old : current) {
                for (var entry : entries) {
                    if (entry.pid() == old.pid() && entry.trackId() == old.trackId()) {
                        unchanged.add((long) old.pid() << 32 | old.trackId());
                    }
                }
            }
            for (var old : current) {
                var parser = live.get(old.trackId());
                if (parser == null || unchanged.contains((long) old.pid() << 32 | old.trackId())) {
                    continue;
                }
                scanner.unparse(old.pid(), sink);
                if (!kept.contains(old.trackId())) {
                    live.remove(old.trackId());
                    if (sink != null) {
                        parser.flush(scanner.clock(), sink);
                    }
                }
            }
            for (var entry : entries) {
                if (unchanged.contains((long) entry.pid() << 32 | entry.trackId())) {
                    continue;
                }
                var parser = live.get(entry.trackId());
                if (parser == null) {
                    parser = provider.apply(entry);
                    if (parser == null) {
                        continue;
                    }
                    live.put(entry.trackId(), parser);
                }
                scanner.parse(entry.pid(), parser);
            }
            current = entries;
            section = pmt;
        }
    }

    /**
     * Receives the program map versions met by a scan.
     */
    @FunctionalInterface
    private interface VersionListener {
        /**
         * Receives one version.
         *
         * @param version the version, in force from the packet in which its section started
         */
        void version(MapVersion version);
    }

    /**
     * Returns the known program map versions, in file order.
     *
     * @return the versions; during the probe, those found so far
     */
    private List<MapVersion> mapVersions() {
        return versions != null ? versions : probeVersions;
    }

    /**
     * Returns the known version that maps the same streams as a section.
     *
     * @param pmt the section
     * @return the version, or {@code null}
     */
    private MapVersion knownVersion(Psi.PmtSection pmt) {
        for (var version : mapVersions()) {
            if (version.pmt().sameStreams(pmt)) {
                return version;
            }
        }
        return null;
    }

    /**
     * Returns the program map version in force at a file offset.
     *
     * @param offset the offset of a packet
     * @return the last version starting at or before it, the first one for an earlier offset
     */
    private MapVersion versionAt(long offset) {
        var result = versions.getFirst();
        for (var version : versions) {
            if (version.offset() <= offset) {
                result = version;
            }
        }
        return result;
    }

    /**
     * Describes the programs and configures the parsers from the probe window.
     */
    private final class Probe {
        /**
         * The options.
         */
        private final TsDemuxerOptions options;

        /**
         * The programs of the program association table, by number, in table order.
         */
        private final Map<Integer, Integer> pmtPids = new LinkedHashMap<>();

        /**
         * The first program map section of each program, by program number.
         */
        private final Map<Integer, Psi.PmtSection> pmts = new HashMap<>();

        /**
         * The offset of the packet in which the first program map section of each program started, by program
         * number.
         */
        private final Map<Integer, Long> pmtOffsets = new HashMap<>();

        /**
         * The parser of every track of the selected program, by track identifier, in the order the tracks were
         * met.
         */
        private final Map<Integer, ElementaryParser> parsers = new LinkedHashMap<>();

        /**
         * The track identifiers given out.
         */
        private final Set<Integer> used = new HashSet<>();

        /**
         * The earliest presentation time seen of each track, in 90 kHz ticks.
         */
        private final Map<Integer, Long> firstTimes = new HashMap<>();

        /**
         * The scanner of the probe window.
         */
        private TsScanner probeScanner;

        /**
         * The selected program, or {@code null}.
         */
        private TsProgram selected;

        /**
         * Whether the program association table was read.
         */
        private boolean patSeen;

        /**
         * The offset at which the scan of the selected program's probe window stopped.
         */
        private long scannedTo;

        /**
         * Constructs the probe.
         *
         * @param options the program selection and probe window
         */
        Probe(TsDemuxerOptions options) {
            this.options = options;
        }

        /**
         * Reads the probe window, then looks for program map changes in the rest of the file.
         *
         * @throws MediaException.Unsupported if the requested program does not exist
         * @throws MediaException.Malformed if no program is found
         */
        void run() {
            var end = geometry.first() + options.probeBytes();
            probeScanner = new TsScanner(source, geometry, geometry.first(), end, limits, new TsClock(0, TsClock.NONE));
            probeScanner.watch(0, this::section);
            ElementaryParser.UnitSink recorder = this::record;
            while (probeScanner.step(recorder)) {
                if (selected != null && complete()) {
                    break;
                }
            }
            probeScanner.finish(recorder);
            scannedTo = probeScanner.position();
            if (selected == null) {
                selectFallback();
            }
            if (selected == null) {
                if (options.program().isPresent() && patSeen) {
                    throw new MediaException.Unsupported("mpeg-ts program",
                            "the transport stream has no program " + options.program().getAsInt());
                }
                throw new MediaException.Malformed(patSeen ? "no program map table found in the transport stream"
                        : "no program association table found in the transport stream");
            }
            discover(scannedTo);
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
         * Returns whether every parser is configured and has produced a packet.
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
         * Receives a section of the program association table or of a program map table before a program is
         * selected.
         *
         * @param pid the PID
         * @param section the reassembled PSI section
         * @param offset the offset of the packet in which it started
         * @param sink the sink of the scan
         */
        private void section(int pid, byte[] section, long offset, ElementaryParser.UnitSink sink) {
            if (!Psi.isCurrent(section)) {
                return;
            }
            var table = section[0] & 0xFF;
            if (pid == 0 && table == Psi.PAT_TABLE) {
                if (patSeen) {
                    return;
                }
                patSeen = true;
                for (var entry : Psi.parsePat(section)) {
                    if (entry.number() != 0 && !pmtPids.containsKey(entry.number())) {
                        pmtPids.put(entry.number(), entry.pid());
                        probeScanner.watch(entry.pid(), this::section);
                    }
                }
            } else if (table == Psi.PMT_TABLE && pmtPids.containsValue(pid)) {
                var pmt = Psi.parsePmt(section);
                if (pmt != null && Objects.equals(pmtPids.get(pmt.number()), pid) && !pmts.containsKey(pmt.number())) {
                    pmts.put(pmt.number(), pmt);
                    pmtOffsets.put(pmt.number(), offset);
                }
            }
            if (selected == null) {
                select();
            }
        }

        /**
         * Selects the program once its map table is known.
         */
        private void select() {
            if (!patSeen) {
                return;
            }
            var requested = options.program();
            if (requested.isPresent()) {
                if (pmts.containsKey(requested.getAsInt())) {
                    choose(requested.getAsInt(), probeScanner);
                }
                return;
            }
            for (var number : pmtPids.keySet()) {
                var pmt = pmts.get(number);
                if (pmt == null) {
                    return;
                }
                if (hasParsers(pmt)) {
                    choose(number, probeScanner);
                    return;
                }
            }
        }

        /**
         * Selects, at the end of the probe window, the first program whose map table was read,
         * when the program the options ask for was not complete.
         */
        private void selectFallback() {
            if (options.program().isPresent()) {
                return;
            }
            for (var number : pmtPids.keySet()) {
                var pmt = pmts.get(number);
                if (pmt != null && hasParsers(pmt)) {
                    var replay = new TsScanner(source, geometry, geometry.first(), geometry.first() + options.probeBytes(),
                            limits, new TsClock(0, TsClock.NONE));
                    choose(number, replay);
                    ElementaryParser.UnitSink recorder = this::record;
                    while (replay.step(recorder)) {
                        if (complete()) {
                            break;
                        }
                    }
                    replay.finish(recorder);
                    scannedTo = replay.position();
                    return;
                }
            }
        }

        /**
         * Returns whether a program has a stream this demuxer parses.
         *
         * @param pmt the program map section
         * @return {@code true} when a parser exists for one of its streams
         */
        private boolean hasParsers(Psi.PmtSection pmt) {
            for (var stream : pmt.streams()) {
                if (parserFor(stream, limits) != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Selects a program and follows its map in a scan from the start of the file.
         *
         * @param number the program number
         * @param scanner the scan
         */
        private void choose(int number, TsScanner scanner) {
            var pmt = pmts.get(number);
            var pmtPid = pmtPids.get(number);
            var first = new MapVersion(geometry.first(), pmtPid, pmt, assign(List.of(), pmt, used));
            probeVersions.add(first);
            selected = program(number, pmtPid, pmt);
            new Mapper(scanner, number, first, this::parserOf, this::versionMet);
        }

        /**
         * Returns the parser of a track, creating it the first time.
         *
         * @param entry the stream feeding the track
         * @return the parser, or {@code null} for a stream that is not exposed
         */
        private ElementaryParser parserOf(Psi.StreamEntry entry) {
            var parser = parsers.get(entry.trackId());
            if (parser == null) {
                parser = parserFor(entry, limits);
                if (parser != null) {
                    parsers.put(entry.trackId(), parser);
                }
            }
            return parser;
        }

        /**
         * Records a program map version met while scanning.
         *
         * @param version the version, which a version at a later offset than every known one adds
         */
        private void versionMet(MapVersion version) {
            if (version.offset() <= probeVersions.getLast().offset()) {
                return;
            }
            for (var entry : version.entries()) {
                used.add(entry.trackId());
            }
            probeVersions.add(version);
        }

        /**
         * Looks for program map versions after the probe window, when the source is sized and cheap to reach:
         * reads the last map of the file, finds where the map changes from the one in force by bisection, and
         * repeats from there while the last map differs, configuring the parsers of the tracks each new version
         * adds from the packets that follow it.
         *
         * @param from the offset where the probe window ended
         */
        private void discover(long from) {
            var size = MeasuredSource.measure(source).size();
            if (size < 0 || !ForwardOnlySource.isCheapToReach(source, size)) {
                return;
            }
            mapsDiscovered = true;
            if (size <= from) {
                return;
            }
            var number = selected.number();
            var last = probeVersions.getLast();
            var lastOffset = alignedBefore(from);
            var offset = alignedBefore(Math.max(from, size - MAP_TAIL_BYTES));
            var found = map(offset, size, number, last.pmtPid(), true);
            while (found == null && offset > lastOffset && size - offset < MAX_TAIL_WINDOW) {
                var earlier = alignedBefore(Math.max(lastOffset, size - 2 * (size - offset)));
                found = map(earlier, Math.min(size, offset + MAP_OVERLAP_BYTES), number, last.pmtPid(), true);
                offset = earlier;
            }
            if (found != null) {
                offset = found.offset();
                while (!found.pmt().sameStreams(last.pmt())) {
                    var change = locate(lastOffset, offset, number, last);
                    var known = knownVersion(change.pmt());
                    var entries = known != null ? known.entries() : assign(last.entries(), change.pmt(), used);
                    var version = new MapVersion(change.offset(), change.pmtPid(), change.pmt(), entries);
                    probeVersions.add(version);
                    configureNew(version);
                    last = version;
                    if (change.offset() <= lastOffset) {
                        break;
                    }
                    lastOffset = change.offset();
                }
            }
        }

        /**
         * Finds by bisection the first program map section that differs from a version, between an offset where
         * the next section read is that version's and one where it is not.
         *
         * @param low an offset whose next section is the version's
         * @param high a later offset whose next section is not
         * @param number the program
         * @param last the version in force at {@code low}
         * @return the first section that differs, with the offset of the packet in which it started
         */
        private FoundMap locate(long low, long high, int number, MapVersion last) {
            while (high - low > geometry.packetSize()) {
                var middle = alignedBefore(low + (high - low) / 2);
                if (middle <= low) {
                    break;
                }
                var probe = mapAt(middle, number, last.pmtPid());
                if (probe == null || probe.pmt().sameStreams(last.pmt())) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            var located = mapAt(low, number, last.pmtPid());
            if (located == null || located.pmt().sameStreams(last.pmt())) {
                located = mapAt(high, number, last.pmtPid());
            }
            return located;
        }

        /**
         * Configures the parsers of the tracks a program map version adds, from the packets that follow it.
         *
         * @param version the version, in force from its offset
         */
        private void configureNew(MapVersion version) {
            var scan = new TsScanner(source, geometry, version.offset(), version.offset() + options.probeBytes(), limits,
                    new TsClock(0, TsClock.NONE));
            var added = new ArrayList<ElementaryParser>();
            for (var entry : version.entries()) {
                if (parsers.containsKey(entry.trackId())) {
                    continue;
                }
                var parser = parserFor(entry, limits);
                if (parser != null) {
                    parsers.put(entry.trackId(), parser);
                    added.add(parser);
                    scan.parse(entry.pid(), parser);
                }
            }
            if (added.isEmpty()) {
                return;
            }
            ElementaryParser.UnitSink ignore = (packet, offset) -> {
            };
            while (scan.step(ignore)) {
                if (added.stream().allMatch(ElementaryParser::configured)) {
                    return;
                }
            }
            scan.finish(ignore);
        }

        /**
         * Returns every program of the program association table.
         *
         * @return the programs, in table order; those whose map table was not read have no PIDs
         */
        List<TsProgram> programs() {
            var result = new ArrayList<TsProgram>();
            for (var entry : pmtPids.entrySet()) {
                var pmt = pmts.get(entry.getKey());
                result.add(pmt != null ? program(entry.getKey(), entry.getValue(), pmt)
                        : new TsProgram(entry.getKey(), entry.getValue(), 0x1FFF, List.of()));
            }
            return result;
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
     * Describes a program from its map section.
     *
     * @param number the program number
     * @param pmtPid the PID of its map table
     * @param pmt the map section
     * @return the description
     */
    private static TsProgram program(int number, int pmtPid, Psi.PmtSection pmt) {
        var pids = new ArrayList<Integer>();
        for (var stream : pmt.streams()) {
            pids.add(stream.pid());
        }
        return new TsProgram(number, pmtPid, pmt.pcrPid(), pids);
    }

    /**
     * Describes the program map section found reading forwards from an offset.
     *
     * @param offset the offset of the packet in which the section started
     * @param pmtPid the PID that carried it
     * @param pmt the section
     */
    private record FoundMap(long offset, int pmtPid, Psi.PmtSection pmt) {
    }

    /**
     * Reads forwards from an offset to the first current program map section of a program.
     *
     * @param offset a packet boundary
     * @param number the program
     * @param pmtPid the PID of its map table at the last known point; a program association table met first
     * can move it
     * @return the section, or {@code null} when none is found within {@value #MAP_SAMPLE_BYTES} bytes
     */
    private FoundMap mapAt(long offset, int number, int pmtPid) {
        return map(offset, offset + MAP_SAMPLE_BYTES, number, pmtPid, false);
    }

    /**
     * Reads a range of the file for the current program map sections of a program.
     *
     * @param offset a packet boundary where the read starts
     * @param end the offset at which the read ends
     * @param number the program
     * @param pmtPid the PID of its map table at the last known point; a program association table met first
     * can move it
     * @param last whether to return the last section of the range rather than the first
     * @return the section, or {@code null} when the range holds none
     */
    private FoundMap map(long offset, long end, int number, int pmtPid, boolean last) {
        var scan = new TsScanner(source, geometry, offset, end, limits, new TsClock(0, TsClock.NONE));
        var result = new FoundMap[1];
        var pid = new int[]{pmtPid};
        TsScanner.SectionListener listener = new TsScanner.SectionListener() {
            @Override
            public void section(int from, byte[] section, long at, ElementaryParser.UnitSink sink) {
                if ((result[0] != null && !last) || !Psi.isCurrent(section)) {
                    return;
                }
                var table = section[0] & 0xFF;
                if (from == 0 && table == Psi.PAT_TABLE) {
                    for (var entry : Psi.parsePat(section)) {
                        if (entry.number() == number && entry.pid() != pid[0] && entry.pid() != 0) {
                            scan.unwatch(pid[0]);
                            pid[0] = entry.pid();
                            scan.watch(pid[0], this);
                        }
                    }
                } else if (from == pid[0] && table == Psi.PMT_TABLE) {
                    var pmt = Psi.parsePmt(section);
                    if (pmt != null && pmt.number() == number) {
                        result[0] = new FoundMap(at, from, pmt);
                    }
                }
            }
        };
        scan.watch(0, listener);
        scan.watch(pmtPid, listener);
        ElementaryParser.UnitSink ignore = (packet, at) -> {
        };
        while ((result[0] == null || last) && scan.step(ignore)) {
            // the sections are delivered to the listener
        }
        return result[0];
    }

    /**
     * Creates the parser of an elementary stream.
     *
     * @param stream the program map entry
     * @param limits the resource bounds
     * @return the parser, or {@code null} for a stream this demuxer does not expose
     */
    static ElementaryParser parserFor(Psi.StreamEntry stream, MediaLimits limits) {
        var hdmv = stream.programRegistered(Psi.HDMV);
        return switch (stream.streamType()) {
            case 0x01 -> new MpegVideoParser(stream, limits, Codec.MPEG1_VIDEO);
            case 0x02 -> new MpegVideoParser(stream, limits, Codec.MPEG2_VIDEO);
            case 0x03, 0x04 -> new MpegAudioParser(stream, limits);
            case 0x0F -> new AdtsParser(stream, limits);
            case 0x10 -> new Mpeg4VideoParser(stream, limits);
            case 0x11 -> new LatmParser(stream, limits);
            case 0x15 -> new PesParser(stream, limits, Codec.DATA,
                    stream.id3Metadata() || stream.registered(Psi.ID3) ? PesParser.ID3_HANDLER : "metadata");
            case 0x1B -> new NalParser(stream, limits, false);
            case 0x24 -> new NalParser(stream, limits, true);
            case 0x80 -> hdmv ? new PcmParser(stream, limits, false) : new PesParser(stream, limits, Codec.DATA, "private");
            case 0x81, 0x87 -> new Ac3Parser(stream, limits);
            case 0x84 -> hdmv ? new Ac3Parser(stream, limits) : new PesParser(stream, limits, Codec.DATA, "private");
            case 0x82, 0x83, 0x85, 0xA1, 0xA2 -> hdmv ? new PesParser(stream, limits, Codec.UNKNOWN_AUDIO, null)
                    : new PesParser(stream, limits, Codec.DATA, "private");
            case 0x86 -> hdmv ? new PesParser(stream, limits, Codec.UNKNOWN_AUDIO, null) : new Scte35Parser(stream, limits);
            case 0x90 -> new PesParser(stream, limits, Codec.PGS, null);
            case 0xEA -> new PesParser(stream, limits, Codec.UNKNOWN_VIDEO, null);
            case 0x06 -> privateParser(stream, limits);
            default -> stream.streamType() >= 0x80 ? new PesParser(stream, limits, Codec.DATA, "private") : null;
        };
    }

    /**
     * Creates the parser of a private PES stream ({@code stream_type 0x06}) from its descriptors.
     *
     * @param stream the program map entry
     * @param limits the resource bounds
     * @return the parser
     */
    private static ElementaryParser privateParser(Psi.StreamEntry stream, MediaLimits limits) {
        if (stream.registered(Psi.OPUS)) {
            return new OpusParser(stream, limits);
        }
        if (stream.registered(Psi.AV1)) {
            return new Av1Parser(stream, limits);
        }
        if (stream.registered(Psi.AES3)) {
            return new PcmParser(stream, limits, true);
        }
        if (stream.ac3Descriptor() || stream.eac3Descriptor() || stream.registered(Psi.AC3)
                || stream.registered(Psi.EAC3)) {
            return new Ac3Parser(stream, limits);
        }
        if (stream.registered(Psi.HEVC)) {
            return new NalParser(stream, limits, true);
        }
        if (stream.registered(Psi.ID3) || stream.id3Metadata()) {
            return new PesParser(stream, limits, Codec.DATA, PesParser.ID3_HANDLER);
        }
        if (stream.dvbSubtitles()) {
            return new PesParser(stream, limits, Codec.DVB_SUBTITLE, null, stream.subtitling());
        }
        if (stream.teletext()) {
            return new PesParser(stream, limits, Codec.DVB_TELETEXT, null, stream.teletextPages());
        }
        return new SniffingParser(stream, limits);
    }

    /**
     * Starts a read pass with fresh parsers.
     *
     * @param position the offset of the first packet read
     * @param reference the absolute time the first timestamps are unwrapped against, or
     * {@link TsClock#NONE}
     */
    private void restart(long position, long reference) {
        scanner = newScanner(position, Long.MAX_VALUE, reference);
        queue.clear();
        ended = false;
    }

    /**
     * Creates a scanner over the exposed streams with fresh parsers.
     *
     * @param position the offset of the first packet read
     * @param end the offset at which the scan ends
     * @param reference the absolute time the first timestamps are unwrapped against, or
     * {@link TsClock#NONE}
     * @return the scanner
     */
    private TsScanner newScanner(long position, long end, long reference) {
        return newScanner(position, end, reference, false);
    }

    /**
     * Creates a scanner over the exposed streams with fresh parsers.
     *
     * @param position the offset of the first packet read
     * @param end the offset at which the scan ends
     * @param reference the absolute time the first timestamps are unwrapped against, or
     * {@link TsClock#NONE}
     * @param search whether the parsers also emit the units before a stream's first key frame
     * @return the scanner
     */
    private TsScanner newScanner(long position, long end, long reference, boolean search) {
        var created = new TsScanner(source, geometry, position, end, limits, new TsClock(origin, reference));
        new Mapper(created, program.number(), versionAt(position), entry -> {
            var parser = parsers.get(entry.trackId());
            if (parser == null) {
                return null;
            }
            var fresh = parser.fresh();
            if (search) {
                fresh.keepLeadingUnits();
            }
            return fresh;
        }, null);
        return created;
    }

    /**
     * Measures the duration from the presentation times near the end of the file.
     *
     * @return the duration in microseconds, or {@code -1} when the size is unknown or no timestamp
     * is found
     */
    private long measureDuration() {
        var size = source.size();
        if (size < 0 || !ForwardOnlySource.isCheapToReach(source, size)) {
            return -1;
        }
        for (var window = TAIL_WINDOW; ; window *= 2) {
            var start = Math.max(geometry.first(), alignedBefore(size - window));
            var ends = new long[]{Long.MIN_VALUE};
            var tail = newScanner(start, size, origin);
            ElementaryParser.UnitSink recorder = (packet, offset) -> {
                if (packet.pts() == Packet.NO_TIMESTAMP) {
                    return;
                }
                var track = trackOf(packet.trackId());
                if (track == null || track.kind() == TrackKind.DATA) {
                    return;
                }
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
            if (start == geometry.first() || window >= MAX_TAIL_WINDOW) {
                return -1;
            }
        }
    }

    /**
     * Returns the packet boundary at or before an offset, assuming packets evenly spaced from the
     * first one.
     *
     * @param offset the absolute byte offset
     * @return the boundary, not before the first packet
     */
    private long alignedBefore(long offset) {
        if (offset <= geometry.first()) {
            return geometry.first();
        }
        var size = geometry.packetSize();
        return geometry.first() + (offset - geometry.first()) / size * size;
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

    /**
     * Returns every program of the transport stream.
     *
     * @return the programs of the program association table, in table order, with the PIDs of the
     * elementary streams their map tables list (empty for a program whose map table was not found
     * in the probe window); unmodifiable
     */
    public List<TsProgram> programs() {
        return programs;
    }

    /**
     * {@return the program whose elementary streams are exposed}
     */
    public TsProgram program() {
        return program;
    }

    /**
     * Returns the size of the transport packets.
     *
     * @return 188, 192 (M2TS) or 204 bytes
     */
    public int packetSize() {
        return geometry.packetSize();
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MPEG_TS;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    /**
     * {@inheritDoc}
     *
     * @return an empty map: transport streams carry no container tags this demuxer reads
     */
    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags of a track are the first string of the ATSC component name descriptor of its program map entry,
     * under {@code "title"}.
     */
    @Override
    public Map<String, String> trackMetadata(int trackId) {
        var parser = parserOf(trackId);
        return parser == null || parser.entry.name() == null ? Map.of() : Map.of("title", parser.entry.name());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The dispositions of a track are those the {@code audio_type} of the ISO 639 language descriptor of its
     * program map entry names: 1 {@link TrackDisposition#CLEAN_EFFECTS}, 2
     * {@link TrackDisposition#HEARING_IMPAIRED} and 3 {@link TrackDisposition#VISUAL_IMPAIRED}.
     */
    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        var parser = parserOf(trackId);
        if (parser == null) {
            return Set.of();
        }
        return TsDescriptors.dispositions(parser.entry.audioType());
    }

    /**
     * Returns the parser of an exposed track.
     *
     * @param trackId the identifier of the track
     * @return the parser, or {@code null} for an unknown track
     */
    private ElementaryParser parserOf(int trackId) {
        return parsers.get(trackId);
    }

    /**
     * {@return the PES presentation time, in 90 kHz ticks, that the demuxer's timeline
     * starts from: the earliest presentation time of the probed packets, which the packets report as zero}
     *
     * @apiNote Streams of one program cut apart, such as the variants and renditions of an HLS presentation, share
     * their PES timestamps; subtracting the differences of their origins puts their packets on one timeline.
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
            throw new MediaException.Malformed("corrupt MPEG transport stream: " + exception, exception);
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
     * <p>The demuxer bisects the file on presentation times to a point one second before
     * {@code micros}, reads forwards until every track passed the target, and backs off three
     * times further each time a video or audio track met no key frame at or before the target,
     * down to the start of the file; it then restarts at the earliest of the tracks' key frames.
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "mpeg-ts demuxing cannot seek a source of unknown size");
        }
        try {
            var target = Math.max(0, micros);
            if (target == 0) {
                restart(geometry.first(), TsClock.NONE);
                filter = null;
                return;
            }
            var targetTicks = Math.floorDiv(target * 9, 100);
            var start = geometry.first();
            var backoff = FIRST_BACKOFF;
            Search search;
            while (true) {
                start = bisect(targetTicks - backoff);
                search = search(start, targetTicks);
                if (!search.needsEarlier || start == geometry.first()) {
                    break;
                }
                backoff = backoff * 3;
            }
            restart(search.restart >= 0 ? search.restart : start, origin + targetTicks);
            filter = new SeekFilter(tracks, target);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt MPEG transport stream: " + exception, exception);
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
     * @param start the offset of the first packet read
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
            if (farthest[0] > targetTicks + SEARCH_PAST_TARGET || allPassed(passed)) {
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
     * Returns whether every video and audio track produced a packet past the target.
     *
     * @param passed the tracks that produced a packet past the target
     * @return {@code true} when the search can stop
     */
    private boolean allPassed(Set<Integer> passed) {
        var any = false;
        for (var track : tracks) {
            if (track.kind() == TrackKind.DATA) {
                continue;
            }
            if (!passed.contains(track.id())) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Finds the last packet boundary whose first presentation time is at or before a time.
     *
     * @param ticks the time in 90 kHz ticks from the origin
     * @return the offset of the packet, the first packet when every time is later
     */
    private long bisect(long ticks) {
        var size = geometry.packetSize();
        var first = geometry.first();
        if (ticks <= 0) {
            return first;
        }
        var count = (source.size() - first) / size;
        var low = 0L;
        var high = count;
        var cache = new ReadAheadCache(source, 64 * 1024, 0);
        var packet = new byte[size];
        while (high - low > 1) {
            var middle = (low + high) >>> 1;
            var time = clockAt(cache, packet, first + middle * size);
            if (time != TsClock.NONE && time <= ticks) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return first + low * size;
    }

    /**
     * Returns the first presentation time of an exposed stream found reading forwards from an
     * offset.
     *
     * @param cache the read window
     * @param packet a buffer of one packet
     * @param offset the offset of a packet boundary
     * @return the time in 90 kHz ticks from the origin, or {@link TsClock#NONE} when none is found
     * within {@value #CLOCK_PROBE_BYTES} bytes
     */
    private long clockAt(ReadAheadCache cache, byte[] packet, long offset) {
        var size = geometry.packetSize();
        var sync = geometry.syncOffset();
        for (var position = offset; position < offset + CLOCK_PROBE_BYTES; position += size) {
            if (cache.readUpTo(position, packet, 0, size) < size) {
                return TsClock.NONE;
            }
            if ((packet[sync] & 0xFF) != TsScanner.SYNC || (packet[sync + 1] & 0x40) == 0) {
                continue;
            }
            var pid = (packet[sync + 1] & 0x1F) << 8 | (packet[sync + 2] & 0xFF);
            if (!exposedPids.contains(pid)) {
                continue;
            }
            var payload = sync + 4;
            var control = (packet[sync + 3] >> 4) & 3;
            if ((control & 2) != 0) {
                payload += 1 + (packet[payload] & 0xFF);
            }
            if ((control & 1) == 0 || payload + 14 > sync + TsScanner.PACKET) {
                continue;
            }
            if (packet[payload] != 0 || packet[payload + 1] != 0 || packet[payload + 2] != 1
                    || (packet[payload + 6] & 0xC0) != 0x80 || (packet[payload + 7] & 0x80) == 0) {
                continue;
            }
            var raw = TsScanner.timestamp(packet, payload + 9);
            return TsClock.unwrap(raw, origin) - origin;
        }
        return TsClock.NONE;
    }
}
