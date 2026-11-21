package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Writes Apple Core Audio Format files from the packets of one audio track.
 *
 * <p>The track must be an {@link AudioTrack} that declares its sample rate and channel layout, with one of the codecs
 * {@link #supports(Codec)} accepts. The file holds a {@code desc} chunk, a {@code chan} chunk with the channel layout
 * (a layout tag when one describes it, else a channel bitmap or a description of every channel), a {@code kuki}
 * chunk with the configuration of a codec that needs one, an {@code info} chunk with the tags, the {@code data}
 * chunk, and, for packets of variable size, the {@code pakt} chunk after the data:
 * <table class="striped">
 * <caption>Formats</caption>
 * <thead><tr><th scope="col">Codec</th><th scope="col">Format</th><th scope="col">Packets</th></tr></thead>
 * <tbody>
 * <tr><td>the PCM codecs</td><td>{@code lpcm}, with the integer or float and the byte order of the codec;
 * {@link Codec#PCM_U8} is written as signed 8-bit samples (the sign bit flipped), the only 8-bit samples of
 * CAF</td><td>frames of constant size</td></tr>
 * <tr><td>{@link Codec#PCM_ALAW}, {@link Codec#PCM_MULAW}</td><td>{@code alaw}, {@code ulaw}</td><td>frames of
 * constant size</td></tr>
 * <tr><td>{@link Codec#ADPCM_IMA_QT}</td><td>{@code ima4}</td><td>blocks of 34 bytes per channel and 64
 * frames</td></tr>
 * <tr><td>{@link Codec#AAC}</td><td>{@code aac } at the rate, frame length and channels of the core coder, as
 * Core Audio writes it: no format flags, the {@code AudioSpecificConfig} in an elementary stream descriptor as the
 * magic cookie (with the largest packet as its buffer size and the peak and average bit rates of the packets), and,
 * for HE-AAC, an {@code ldsc} chunk listing the {@code aacp} (when parametric stereo adds channels), {@code aach}
 * and {@code aac } layers</td><td>variable</td></tr>
 * <tr><td>{@link Codec#ALAC}</td><td>{@code alac}, the source bit depth as the format flags, the
 * {@code ALACSpecificConfig} as the magic cookie</td><td>variable</td></tr>
 * <tr><td>{@link Codec#OPUS}</td><td>{@code opus}, the {@code OpusHead} as the magic cookie</td><td>variable</td></tr>
 * <tr><td>{@link Codec#MP3}, {@link Codec#MP2}</td><td>{@code .mp3}, {@code .mp2} (Layer I {@code .mp1})</td>
 * <td>variable</td></tr>
 * <tr><td>{@link Codec#AC3}, {@link Codec#EAC3}</td><td>{@code ac-3}, {@code ec-3}</td><td>variable</td></tr>
 * </tbody>
 * </table>
 * Each packet becomes one CAF packet; packets of constant size must hold whole frames or blocks and are stored back
 * to back. The frames of a packet of variable size are those its codec defines (the frame length of an AAC
 * configuration, the samples of an Opus packet or of an MPEG audio or AC-3 frame; for ALAC the frame length of its
 * configuration, or the packet's duration when shorter), or, for a packet whose codec defines none, its duration
 * rescaled to the sampling rate. When every packet but the last has the frames of the first and the last no more,
 * the {@code desc} chunk declares those frames per packet; otherwise the packet table holds the frames of every
 * packet. The packet table states the track's {@link AudioTrack#skipSamples()} (for Opus, the pre-skip of its
 * {@code OpusHead}) as priming frames and, as the valid frames, what the packets code after them, cut to the track's
 * duration and, when the track counts time in frames, to the packets' durations; the frames after them are the
 * remainder; the priming frames of an AAC track are rescaled from its sample rate to the rate of the core coder.
 * Timestamps are ignored.
 *
 * <p>The tags become the {@code info} chunk, under the CAF keys {@code title}, {@code artist}, {@code album},
 * {@code track number}, {@code year}, {@code composer}, {@code lyricist}, {@code genre}, {@code comments},
 * {@code copyright}, {@code encoding application} and {@code tempo} for those standard tags and under their own keys
 * for the others; a key that repeats keeps its first value.
 *
 * <p>Packets of variable size need a seekable sink, since their packet table is written after the data; on a
 * streaming sink only packets of constant size can be written, with a {@code data} chunk of size {@code -1} that
 * readers read to the end of the file. On a seekable sink {@link #finish()} writes the data size. The sink is
 * written from its position at construction and is never closed by the muxer.
 *
 * <p>Instances are not thread-safe.
 */
public final class CafMuxer implements Muxer {
    /**
     * The file offset of the frames per packet of the {@code desc} chunk.
     */
    private static final int DESC_FRAMES_OFFSET = 40;

    /**
     * The output sink.
     */
    private final MediaSink output;

    /**
     * The sink position at construction, where the file starts.
     */
    private final long base;

    /**
     * The declared track, or {@code null}.
     */
    private AudioTrack track;

    /**
     * The sampling rate the file declares.
     */
    private int rate;

    /**
     * The channel layout the file declares: the track's, or for AAC whose parametric stereo adds channels to the
     * core coder, the core's.
     */
    private ChannelLayout layout;

    /**
     * The file offset of the magic cookie, or -1 when there is none.
     */
    private long cookieOffset = -1;

    /**
     * The time base of one frame at {@link #rate}.
     */
    private Rational frameTimeBase;

    /**
     * The frame length of an AAC or ALAC configuration, or 0 for the other codecs.
     */
    private long configFrames;

    /**
     * The size of a packet of constant size, or 0 for packets of variable size.
     */
    private int packetBytes;

    /**
     * The frames of a packet of constant size.
     */
    private int packetFrames;

    /**
     * The tags of {@link #setTags(MediaTags)}, or {@code null}.
     */
    private MediaTags tags;

    /**
     * The byte size of every packet of variable size written.
     */
    private int[] sizes = new int[256];

    /**
     * The frames of every packet of variable size written.
     */
    private long[] durations = new long[256];

    /**
     * The number of packets of variable size written.
     */
    private int packets;

    /**
     * The frames the packets code.
     */
    private long totalFrames;

    /**
     * The frames the durations of the packets of variable size present, or -1 when a packet has none or the track
     * counts time in other units than frames.
     */
    private long presentedFrames;

    /**
     * The number of bytes of audio data written.
     */
    private long dataSize;

    /**
     * The file offset of the size field of the {@code data} chunk.
     */
    private long dataSizeOffset;

    /**
     * The converted samples of the current packet.
     */
    private byte[] converted = new byte[0];

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     * @throws MediaException.Io if the sink position cannot be read
     */
    public CafMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.base = output.position();
    }

    /**
     * Returns whether CAF files can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for the PCM codecs, A-law, mu-law, IMA4, AAC, ALAC, Opus, MP3, MP2, AC-3 and E-AC-3
     */
    public static boolean supports(Codec codec) {
        Objects.requireNonNull(codec, "codec");
        return codec.isPcm() || switch (codec) {
            case ADPCM_IMA_QT, AAC, ALAC, OPUS, MP3, MP2, AC3, EAC3 -> true;
            default -> false;
        };
    }

    /**
     * Returns whether a codec is stored in packets of variable size.
     *
     * @param codec a codec {@link #supports(Codec)} accepts
     * @return {@code true} for the compressed codecs other than IMA4
     */
    private static boolean variable(Codec codec) {
        return !codec.isPcm() && codec != Codec.ADPCM_IMA_QT;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not an audio track of a codec {@link #supports(Codec)} accepts,
     * declares no sample rate or layout, or lacks the configuration of AAC, ALAC or Opus
     * @throws IllegalStateException if a track was already declared or the muxer is finished
     * @throws MediaException.Unsupported if the codec is stored in packets of variable size and the sink is not
     * seekable
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || started) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalStateException("a CAF file carries one track");
        }
        if (!(track instanceof AudioTrack audio) || !supports(track.codec())) {
            throw new IllegalArgumentException("CAF cannot carry " + track.codec().displayName());
        }
        if (audio.sampleRate() <= 0 || audio.layout().isEmpty()) {
            throw new IllegalArgumentException("a CAF track needs its sample rate and channel layout");
        }
        var codec = audio.codec();
        var channels = audio.layout().get().channels();
        switch (codec) {
            case AAC, ALAC, OPUS -> {
                var config = audio.codecConfig().orElseThrow(() -> new IllegalArgumentException("a CAF "
                        + codec.displayName() + " track needs its codec configuration"));
                try {
                    switch (codec) {
                        case AAC -> AudioConfigs.parseAac(config);
                        case ALAC -> AudioConfigs.parseAlac(config);
                        default -> AudioConfigs.parseOpusHead(config);
                    }
                } catch (MediaException exception) {
                    throw new IllegalArgumentException("invalid " + codec.displayName() + " configuration", exception);
                }
            }
            default -> {
            }
        }
        if (variable(codec) && !output.isSeekable()) {
            throw new MediaException.Unsupported("non-seekable output", "a CAF file of " + codec.displayName()
                    + " needs a seekable output: its packet table follows the audio data");
        }
        this.rate = codec == Codec.OPUS ? 48_000 : audio.sampleRate();
        var declared = audio.layout().get();
        if (codec == Codec.AAC) {
            var aac = AudioConfigs.parseAac(audio.codecConfig().orElseThrow());
            this.rate = aac.sampleRate();
            this.configFrames = aac.frameLength();
            var core = aac.layout();
            if (core != null && core.channels() != channels) {
                declared = core;
            }
        } else if (codec == Codec.ALAC) {
            this.configFrames = AudioConfigs.parseAlac(audio.codecConfig().orElseThrow()).frameLength();
        }
        this.layout = declared;
        this.frameTimeBase = Rational.timeBaseOf(rate);
        if (codec == Codec.ADPCM_IMA_QT) {
            this.packetBytes = 34 * channels;
            this.packetFrames = 64;
        } else if (codec.isPcm()) {
            this.packetBytes = codec.pcmBitsPerSample().orElseThrow() / 8 * channels;
            this.packetFrames = 1;
        }
        this.track = audio;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags become the {@code info} chunk, as the class description states; pictures, chapters and the XMP
     * packet are not stored.
     *
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started || finished) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.tags = tags;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to another track or does not hold whole frames or IMA4
     * blocks
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "CAF");
        var data = packet.data();
        long frames;
        if (packetBytes > 0) {
            if (data.length % packetBytes != 0) {
                throw new IllegalArgumentException("a packet of " + data.length + " bytes does not hold whole "
                        + (packetFrames == 1 ? "frames" : "IMA4 blocks") + " of " + packetBytes + " bytes");
            }
            frames = (long) data.length / packetBytes * packetFrames;
        } else {
            frames = frames(packet);
            if (presentedFrames >= 0) {
                presentedFrames = packet.duration() > 0 && track.timeBase().equals(frameTimeBase)
                        ? presentedFrames + packet.duration() : -1;
            }
            if (packets == sizes.length) {
                sizes = Arrays.copyOf(sizes, 2 * packets);
                durations = Arrays.copyOf(durations, 2 * packets);
            }
            sizes[packets] = data.length;
            durations[packets] = frames;
            packets++;
        }
        if (!started) {
            writeHeader(data);
        }
        if (track.codec() == Codec.PCM_U8) {
            if (converted.length < data.length) {
                converted = new byte[Math.max(data.length, 2 * converted.length)];
            }
            for (var index = 0; index < data.length; index++) {
                converted[index] = (byte) (data[index] ^ 0x80);
            }
            output.write(ByteBuffer.wrap(converted, 0, data.length));
        } else {
            output.write(ByteBuffer.wrap(data));
        }
        dataSize += data.length;
        totalFrames += frames;
    }

    /**
     * Returns the frames a packet of variable size codes, at the sampling rate of the file: the frames its codec
     * defines (the frame length of the AAC core coder; for ALAC, at most its duration), else its duration.
     *
     * @param packet the packet of variable size
     * @return the frames, at least 0
     */
    private long frames(Packet packet) {
        var duration = packet.duration() > 0 ? track.timeBase().rescale(packet.duration(), frameTimeBase) : -1;
        var data = packet.data();
        var coded = switch (track.codec()) {
            case AAC -> configFrames;
            // the last ALAC packet may code fewer frames than the configuration's frame length
            case ALAC -> duration > 0 ? Math.min(duration, configFrames) : configFrames;
            case OPUS -> Math.max(0, AudioConfigs.opusPacketSamples(data));
            case MP3, MP2 -> {
                var header = AudioConfigs.parseMpegHeader(data, 0, data.length);
                yield header == null ? 0 : header.samplesPerFrame();
            }
            default -> {
                var frame = data.length >= 6 ? Ac3Configs.parse(data, 0, data.length) : null;
                yield frame == null ? 0 : 256L * frame.blocks();
            }
        };
        return coded > 0 ? coded : Math.max(0, duration);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A muxer without packets writes a header with an empty {@code data} chunk.
     *
     * @throws IllegalStateException if no track was declared or the muxer is finished
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (track == null) {
            throw new IllegalStateException("no track was declared");
        }
        if (!started) {
            writeHeader(null);
        }
        finished = true;
        if (!output.isSeekable()) {
            return;
        }
        var priming = priming();
        var valid = Math.max(0, totalFrames - priming);
        if (packetBytes == 0 && packets > 0 && presentedFrames >= 0) {
            valid = Math.min(valid, Math.max(0, presentedFrames - priming));
        }
        if (track.duration() >= 0) {
            var declared = track.timeBase().rescale(track.duration(), Rational.timeBaseOf(rate));
            valid = Math.min(valid, declared);
        }
        var constant = packetBytes > 0 ? packetFrames : constantFrames();
        if (packetBytes == 0 || priming > 0 || valid < totalFrames - priming) {
            writePacketTable(constant, priming, valid);
        }
        var end = output.position();
        var field = new ByteWriter(8);
        field.u64be(4 + dataSize);
        output.position(base + dataSizeOffset);
        field.writeTo(output);
        if (packetBytes == 0 && constant > 0) {
            var frames = new ByteWriter(4);
            frames.u32be(constant);
            output.position(base + DESC_FRAMES_OFFSET);
            frames.writeTo(output);
        }
        if (track.codec() == Codec.AAC && cookieOffset >= 0 && packets > 0 && totalFrames > 0) {
            writeBitrates();
        }
        output.position(end);
    }

    /**
     * Writes the buffer size and the peak and average bit rates of the packets into the AAC magic cookie: the largest
     * packet, the most bits of the packets within any second (at least the average), and the bits of all packets
     * over their frames.
     */
    private void writeBitrates() {
        var largest = 0;
        var peak = 0L;
        var windowBytes = 0L;
        var windowFrames = 0L;
        var first = 0;
        for (var index = 0; index < packets; index++) {
            largest = Math.max(largest, sizes[index]);
            windowBytes += sizes[index];
            windowFrames += durations[index];
            while (windowFrames > rate && first < index) {
                windowBytes -= sizes[first];
                windowFrames -= durations[first];
                first++;
            }
            peak = Math.max(peak, windowBytes);
        }
        var average = dataSize * 8 * rate / totalFrames;
        var fields = new ByteWriter(11);
        fields.u24be(Math.min(largest, 0xFFFFFF));
        fields.u32be(Math.min(Math.max(8 * peak, average), 0xFFFFFFFFL));
        fields.u32be(Math.min(average, 0xFFFFFFFFL));
        output.position(base + cookieOffset + CafCookies.AAC_BUFFER_SIZE_OFFSET);
        fields.writeTo(output);
    }

    /**
     * Returns the priming frames of the track: its skip, rescaled from its sample rate to the rate of the file, or
     * the pre-skip of an Opus header.
     *
     * @return the frames, at the sampling rate of the file
     */
    private long priming() {
        if (track.codec() == Codec.OPUS) {
            return Math.max(0, AudioConfigs.opusPreSkip(track.codecConfig().orElse(null)));
        }
        var skip = track.skipSamples();
        return track.sampleRate() == rate ? skip : skip * rate / track.sampleRate();
    }

    /**
     * Writes the {@code pakt} chunk after the audio data.
     *
     * @param constant the frames of every packet, or 0 when the table holds them
     * @param priming the priming frames
     * @param valid the valid frames
     */
    private void writePacketTable(long constant, long priming, long valid) {
        var coded = constant > 0 && packetBytes == 0 ? (long) packets * constant : totalFrames;
        var remainder = Math.max(0, coded - priming - valid);
        var table = new ByteWriter(24 + 10 * packets);
        if (packetBytes == 0) {
            for (var index = 0; index < packets; index++) {
                variableInteger(table, sizes[index]);
                if (constant == 0) {
                    variableInteger(table, durations[index]);
                }
            }
        }
        var chunk = new ByteWriter(12 + 24);
        chunk.fourCc("pakt");
        chunk.u64be(24 + table.size());
        // the table of packets of constant size describes none, as Core Audio writes it
        chunk.u64be(packetBytes == 0 ? packets : 0);
        chunk.u64be(valid);
        chunk.u32be(Math.min(priming, Integer.MAX_VALUE));
        chunk.u32be(Math.min(remainder, Integer.MAX_VALUE));
        chunk.writeTo(output);
        table.writeTo(output);
    }

    /**
     * Returns the frames every packet but the last has when the last has no more.
     *
     * @return the frames of the first packet, or 0 when the packets differ
     */
    private long constantFrames() {
        if (packets == 0) {
            return 0;
        }
        var first = durations[0];
        for (var index = 1; index < packets; index++) {
            if (durations[index] != first && (index != packets - 1 || durations[index] > first)) {
                return 0;
            }
        }
        return first > 0xFFFFFFFFL ? 0 : first;
    }

    /**
     * Writes a packet table integer: seven bits per byte, most significant first, the high bit set on every byte but
     * the last.
     *
     * @param writer the table being built
     * @param value the value, non-negative
     */
    private static void variableInteger(ByteWriter writer, long value) {
        for (var shift = (63 - Long.numberOfLeadingZeros(Math.max(1, value))) / 7 * 7; shift > 0; shift -= 7) {
            writer.u8((int) (0x80 | value >>> shift & 0x7F));
        }
        writer.u8((int) (value & 0x7F));
    }

    /**
     * Marks the muxer finished; an unfinished file keeps the sizes of its header as written.
     */
    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the header up to the first byte of audio data.
     *
     * @param first the first packet, or {@code null} when there is none
     */
    private void writeHeader(byte[] first) {
        started = true;
        var codec = track.codec();
        var channels = layout.channels();
        var header = new ByteWriter(256);
        header.fourCc("caff");
        header.u16be(1);
        header.u16be(0);
        header.fourCc("desc");
        header.u64be(32);
        header.u64be(Double.doubleToLongBits(rate));
        header.fourCc(formatId(codec, first));
        header.u32be(formatFlags(codec));
        header.u32be(packetBytes);
        header.u32be(packetFrames);
        header.u32be(channels);
        header.u32be(bitsPerChannel(codec));
        var chan = CafChannelLayouts.write(layout);
        header.fourCc("chan");
        header.u64be(chan.length);
        header.bytes(chan);
        var cookie = cookie(codec);
        if (cookie != null) {
            header.fourCc("kuki");
            header.u64be(cookie.length);
            cookieOffset = header.size();
            header.bytes(cookie);
        }
        var formats = formatList();
        if (formats != null) {
            header.fourCc("ldsc");
            header.u64be(formats.length);
            header.bytes(formats);
        }
        var info = info();
        if (info != null) {
            header.fourCc("info");
            header.u64be(info.length);
            header.bytes(info);
        }
        header.fourCc("data");
        dataSizeOffset = header.size();
        header.u64be(output.isSeekable() ? 4 : -1);
        header.u32be(0);
        output.position(base);
        header.writeTo(output);
    }

    /**
     * Returns the format identifier of a codec.
     *
     * @param codec the codec of the track
     * @param first the first packet, or {@code null} when there is none
     * @return the four-character code
     */
    private static String formatId(Codec codec, byte[] first) {
        if (codec.isPcm() && codec != Codec.PCM_ALAW && codec != Codec.PCM_MULAW) {
            return "lpcm";
        }
        return switch (codec) {
            case PCM_ALAW -> "alaw";
            case PCM_MULAW -> "ulaw";
            case ADPCM_IMA_QT -> "ima4";
            case AAC -> "aac ";
            case ALAC -> "alac";
            case OPUS -> "opus";
            case MP3 -> ".mp3";
            case MP2 -> {
                var header = first == null ? null : AudioConfigs.parseMpegHeader(first, 0, first.length);
                yield header != null && header.layer() == 1 ? ".mp1" : ".mp2";
            }
            case AC3 -> "ac-3";
            default -> "ec-3";
        };
    }

    /**
     * Returns the format flags of a codec.
     *
     * @param codec the codec of the track
     * @return the flags
     */
    private long formatFlags(Codec codec) {
        return switch (codec) {
            case PCM_S16LE, PCM_S24LE, PCM_S32LE, PCM_S64LE -> 2;
            case PCM_F32BE, PCM_F64BE -> 1;
            case PCM_F32LE, PCM_F64LE -> 3;
            case ALAC -> switch (AudioConfigs.parseAlac(track.codecConfig().orElseThrow()).bitDepth()) {
                case 16 -> 1;
                case 20 -> 2;
                case 24 -> 3;
                case 32 -> 4;
                default -> 0;
            };
            default -> 0;
        };
    }

    /**
     * Returns the bits per channel the {@code desc} chunk declares for a codec.
     *
     * @param codec the codec of the track
     * @return the bits, 0 for the compressed codecs
     */
    private static int bitsPerChannel(Codec codec) {
        return codec.isPcm() ? codec.pcmBitsPerSample().orElseThrow() : 0;
    }

    /**
     * Returns the magic cookie of a codec.
     *
     * @param codec the codec of the track
     * @return the cookie, or {@code null} for a codec that has none
     */
    private byte[] cookie(Codec codec) {
        var config = track.codecConfig().orElse(null);
        return switch (codec) {
            case AAC -> CafCookies.aacCookie(config, 0, 0, 0);
            case ALAC -> AudioConfigs.alacConfig(config);
            case OPUS -> config;
            default -> null;
        };
    }

    /**
     * Returns the body of the {@code ldsc} chunk of an HE-AAC track: the layers it decodes to, richest first, each a
     * {@code desc} body followed by a channel layout tag.
     *
     * @return the body, or {@code null} for a track that is not HE-AAC
     */
    private byte[] formatList() {
        if (track.codec() != Codec.AAC) {
            return null;
        }
        var aac = AudioConfigs.parseAac(track.codecConfig().orElseThrow());
        var outputRate = Math.max(track.sampleRate(), aac.outputRate());
        if (outputRate <= rate) {
            return null;
        }
        var full = track.layout().orElseThrow();
        var writer = new ByteWriter(3 * 36);
        if (full.channels() > layout.channels()) {
            formatListItem(writer, outputRate, "aacp", 2L * aac.frameLength(), full);
        }
        formatListItem(writer, outputRate, "aach", 2L * aac.frameLength(), layout);
        formatListItem(writer, rate, "aac ", aac.frameLength(), layout);
        return writer.toByteArray();
    }

    /**
     * Writes one entry of an {@code ldsc} chunk.
     *
     * @param writer the chunk body being built
     * @param rate the sampling rate of the layer
     * @param format the format identifier of the layer
     * @param frames the frames of a packet of the layer
     * @param layout the channels of the layer
     */
    private static void formatListItem(ByteWriter writer, int rate, String format, long frames, ChannelLayout layout) {
        writer.u64be(Double.doubleToLongBits(rate));
        writer.fourCc(format);
        writer.u32be(0);
        writer.u32be(0);
        writer.u32be(frames);
        writer.u32be(layout.channels());
        writer.u32be(0);
        var chan = CafChannelLayouts.write(layout);
        var tag = (chan[0] & 0xFFL) << 24 | (chan[1] & 0xFF) << 16 | (chan[2] & 0xFF) << 8 | chan[3] & 0xFF;
        // a layout without a tag of its own is named by its channel count, as kAudioChannelLayoutTag_Unknown does
        writer.u32be(tag == 0 || tag == 0x10000 ? 0xFFFF0000L | layout.channels() : tag);
    }

    /**
     * Encodes the tags as the body of an {@code info} chunk.
     *
     * @return the body, or {@code null} without tags
     */
    private byte[] info() {
        if (tags == null || tags.entries().isEmpty()) {
            return null;
        }
        var entries = new LinkedHashMap<String, String>();
        for (var entry : tags.entries()) {
            var key = StandardTag.fromName(entry.key()).map(CafMuxer::infoKey).orElse(entry.key());
            entries.putIfAbsent(key, entry.value());
        }
        var writer = new ByteWriter(256);
        writer.u32be(entries.size());
        for (var entry : entries.entrySet()) {
            writer.bytes(entry.getKey().getBytes(StandardCharsets.UTF_8));
            writer.u8(0);
            writer.bytes(entry.getValue().getBytes(StandardCharsets.UTF_8));
            writer.u8(0);
        }
        return writer.toByteArray();
    }

    /**
     * Returns the CAF key of a standard tag.
     *
     * @param tag the standard tag to name
     * @return the key
     */
    private static String infoKey(StandardTag tag) {
        return switch (tag) {
            case TITLE -> "title";
            case ARTIST -> "artist";
            case ALBUM -> "album";
            case TRACK -> "track number";
            case DATE -> "year";
            case COMPOSER -> "composer";
            case LYRICIST -> "lyricist";
            case GENRE -> "genre";
            case COMMENT -> "comments";
            case COPYRIGHT -> "copyright";
            case ENCODER -> "encoding application";
            case BPM -> "tempo";
            default -> tag.key();
        };
    }
}
