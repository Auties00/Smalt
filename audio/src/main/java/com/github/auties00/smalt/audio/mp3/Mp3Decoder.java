/* Ports minimp3 (minimp3.h). By lieff; CC0 1.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-1, MPEG-2 and MPEG-2.5 audio, Layers I, II and III, into planar float samples.
 *
 * <p>The decoder accepts tracks of codec {@link Codec#MP3} and {@link Codec#MP2}; the layer of
 * each frame is taken from its own header, so a track may mix layers. A packet holds one or more
 * complete frames, each starting with its four-byte header; bytes after the last whole frame are
 * ignored. Every frame yields one {@link AudioBuffer} of 384 (Layer I), 576 (MPEG-2 and MPEG-2.5
 * Layer III) or 1152 samples per channel, in the frame's own sample rate and channel count: a
 * stream that switches between mono and stereo, or between sample rates, produces buffers of
 * different formats. Dual-channel frames decode to a {@link ChannelLayout#STEREO} buffer.
 * Samples are scaled so that full-scale 16-bit output corresponds to {@code [-1, 1)}.
 *
 * <p>With {@link Mp3DecoderOptions#multichannel()} on (the default), MPEG-1 Layer II frames that carry
 * the ISO/IEC 13818-3 multichannel extension decode to their full channel configuration, from
 * {@code 3/0} to {@code 3/2} with the low-frequency enhancement channel, in WAVE order (for example
 * {@link ChannelLayout#SURROUND_5_1} for {@code 3/2} with LFE); see {@link Mp3DecoderOptions#multichannel()}
 * for how the extension is recognized and where an extension frame goes.
 *
 * <p>Layer III main data that begins in earlier frames (the bit reservoir) is carried across
 * packets, so packets must be fed in stream order. A frame whose reservoir bytes are missing (the
 * first frames after starting mid-stream) and a frame whose side information or allocation is
 * inconsistent decode to silence of the full frame length, which keeps the output aligned with
 * the packet timestamps; an inconsistent frame also resets the decoder state. A frame whose
 * reservoir bytes were in frames lost through {@link #conceal(int, Consumer)} is concealed instead. A change of
 * version, layer or sample rate between frames resets the filterbank and reservoir, as does
 * starting a new stream.
 *
 * <p>With {@link Mp3DecoderOptions#crcCheck()} on (the default), a frame whose CRC-16 word does not
 * match its protected bits is concealed, as is every frame of a packet flagged
 * {@link PacketFlag#CORRUPT}: the subband samples of the last decoded granule (Layer III) or group
 * of twelve slots (Layers I and II) are repeated through the synthesis filterbank at half the
 * amplitude, halving again for each further concealed frame in a row, and from the fifth concealed
 * frame in a row on the output fades to silence. The concealed frame keeps its full length, so the
 * output stays aligned with the packet timestamps; the main data of a concealed Layer III frame still
 * joins the bit reservoir for the frames after it.
 *
 * <p>{@link #sampleRate()} and {@link #layout()} report the track's declared format until the
 * first frame is decoded, and the format of the first decoded frame afterwards. The decoder
 * outputs every frame immediately, so {@link #flush(Consumer)} has nothing to drain. Encoder
 * delay and padding are not removed here: readers apply {@link AudioTrack#skipSamples()} and
 * {@link AudioTrack#duration()} as the demuxer documents.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows minimp3's table layouts, Huffman decoding scheme, IMDCT
 * factorization and float polyphase synthesis. The synthesis stages run on the Vector API when it
 * is enabled, with output identical to the scalar path.
 */
public final class Mp3Decoder implements AudioDecoder {
    /**
     * The largest number of samples per channel of one frame.
     */
    private static final int MAX_FRAME_SAMPLES = 1152;

    /**
     * The status of a frame whose samples were decoded.
     */
    private static final int DECODED = 0;

    /**
     * The status of a frame that decodes to silence.
     */
    private static final int SILENT = 1;

    /**
     * The status of a frame to conceal.
     */
    private static final int CONCEAL = 2;

    /**
     * The number of concealed frames in a row whose repetition is still heard; later ones are silent.
     */
    private static final int AUDIBLE_CONCEALMENT = 4;

    /**
     * The number of output samples of one low-frequency enhancement sample.
     */
    private static final int LFE_PERIOD = 96;

    /**
     * The resource bounds the decoder enforces.
     */
    private final MediaLimits limits;

    /**
     * Whether the multichannel extension of Layer II frames is decoded.
     */
    private final boolean multichannel;

    /**
     * The multichannel extension decoder.
     */
    private final Mp2Multichannel extension;

    /**
     * The filterbank history of the transmission channel pairs of the extension, {@code T2/T3} and
     * {@code T4}.
     */
    private final float[][] extensionStates;

    /**
     * The subband samples of an extension channel pair being synthesized, channel 1 at index 576.
     */
    private final float[] extensionBuffer;

    /**
     * Gives the extension decoder the scalefactor indices of the base channels of the frame.
     */
    private final Mp2Multichannel.BaseScale baseScale;

    /**
     * Whether a frame of the stream decoded with its multichannel extension, after which a frame whose
     * extension fails is concealed in the multichannel layout.
     */
    private boolean extensionEstablished;

    /**
     * The layout of the last frame decoded with its multichannel extension.
     */
    private ChannelLayout extensionLayout;

    /**
     * The output plane of every extension channel of the last multichannel frame, in the reference order
     * of {@link Mp2Multichannel#channelSamples(int)}, or {@code -1} when it is not output.
     */
    private final int[] extensionPlanes = new int[7];

    /**
     * The output plane of the low-frequency enhancement channel of the last multichannel frame, or
     * {@code -1}.
     */
    private int lfePlane = -1;

    /**
     * The last low-frequency enhancement sample output, the start of the next interpolation.
     */
    private float lastLfe;

    /**
     * The bytes of the extension frame following the frame being decoded, or {@code null}.
     */
    private byte[] extensionData;

    /**
     * The index of the extension frame in {@link #extensionData}.
     */
    private int extensionOffset;

    /**
     * The length of the extension frame in bytes.
     */
    private int extensionLength;

    /**
     * Whether frames failing their CRC-16 are concealed.
     */
    private final boolean crcCheck;

    /**
     * The Layer III decoder and its reservoir and overlap state.
     */
    private final Layer3 layer3;

    /**
     * The Layer I and II decoder.
     */
    private final Layer12 layer12;

    /**
     * The synthesis kernel.
     */
    private final SynthesisKernel kernel;

    /**
     * The reader over the current frame.
     */
    private final BitReader frameBits;

    /**
     * The subband samples of the current granule, channel 1 at index 576.
     */
    private final float[] grbuf;

    /**
     * The polyphase filterbank history carried between granules.
     */
    private final float[] qmfState;

    /**
     * The working area of the filterbank: the history followed by the new slots.
     */
    private final float[] lins;

    /**
     * The subband samples of the last decoded granule or group, in the layout of {@link #grbuf}.
     */
    private final float[] lastGood;

    /**
     * The number of time slots of {@link #lastGood}, 12 or 18, or {@code 0} when it holds nothing.
     */
    private int lastGoodSlots;

    /**
     * The number of channels of {@link #lastGood}.
     */
    private int lastGoodChannels;

    /**
     * The number of frames concealed in a row.
     */
    private int concealedRun;

    /**
     * The number of frames concealed since the decoder was created.
     */
    private long concealedFrames;

    /**
     * Whether frames were lost since the last decoded frame, so a Layer III frame whose main data begins in them
     * is concealed rather than silent.
     */
    private boolean afterLoss;

    /**
     * The header of the previous frame, or {@code 0} before the first.
     */
    private int previousHeader;

    /**
     * The output buffer of the current format, or {@code null} before the first frame.
     */
    private AudioBuffer buffer;

    /**
     * The sampling rate reported by {@link #sampleRate()}.
     */
    private int sampleRate;

    /**
     * The layout reported by {@link #layout()}.
     */
    private ChannelLayout layout;

    /**
     * Whether a frame was decoded, which fixes {@link #sampleRate} and {@link #layout}.
     */
    private boolean started;

    /**
     * Creates a decoder for an MPEG audio track under {@link MediaLimits#DEFAULT}.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @throws IllegalArgumentException if the codec is neither
     */
    public Mp3Decoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an MPEG audio track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the
     * stream's own sampling rate, whatever {@link AudioDecoderOptions#preferredSampleRate()}
     * asks for. The channel count of every frame is checked against
     * {@link MediaLimits#maxChannels()} before it is decoded.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the codec is neither
     */
    public Mp3Decoder(AudioTrack track, AudioDecoderOptions options) {
        this(track, Mp3DecoderOptions.builder().limits(options.limits()).build(), SynthesisKernel.create());
    }

    /**
     * Constructs a decoder for an MPEG audio track with MPEG audio specific options.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param options the limits, CRC check and multichannel settings
     * @throws IllegalArgumentException if the codec is neither
     */
    public Mp3Decoder(AudioTrack track, Mp3DecoderOptions options) {
        this(track, options, SynthesisKernel.create());
    }

    /**
     * Creates a decoder under {@link MediaLimits#DEFAULT} that uses a specific synthesis kernel;
     * tests compare the kernels with it.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param kernel the synthesis kernel
     */
    Mp3Decoder(AudioTrack track, SynthesisKernel kernel) {
        this(track, Mp3DecoderOptions.defaults(), kernel);
    }

    /**
     * Creates a decoder that uses specific options and a specific synthesis kernel.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param options the limits, CRC check and multichannel settings
     * @param kernel the synthesis kernel
     */
    private Mp3Decoder(AudioTrack track, Mp3DecoderOptions options, SynthesisKernel kernel) {
        this.limits = options.limits();
        this.crcCheck = options.crcCheck();
        this.multichannel = options.multichannel();
        this.extension = new Mp2Multichannel();
        this.extensionStates = new float[2][15 * 64];
        this.extensionBuffer = new float[2 * 576];
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.MP3 && track.codec() != Codec.MP2) {
            throw new IllegalArgumentException("not an MPEG audio track: " + track.codec());
        }
        this.layer3 = new Layer3(kernel);
        this.layer12 = new Layer12();
        var base = layer12;
        this.baseScale = new Mp2Multichannel.BaseScale() {
            @Override
            public int index(int channel, int band, int part) {
                return base.scaleIndex(band, channel, part);
            }

            @Override
            public int allocation(int channel, int band) {
                return base.allocationIndex(band, channel);
            }
        };
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.frameBits = new BitReader();
        this.grbuf = new float[2 * 576];
        this.qmfState = new float[15 * 64];
        this.lins = new float[(18 + 15) * 64];
        this.lastGood = new float[2 * 576];
        this.sampleRate = track.sampleRate();
        this.layout = track.layout().orElse(ChannelLayout.STEREO);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An exception thrown by {@code output} reaches the caller unchanged.
     *
     * @throws MediaException.Malformed if the packet does not start with a valid frame header, or is
     * otherwise corrupt
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var corrupt = packet.flags().contains(PacketFlag.CORRUPT);
        ConsumerFailure.<AudioBuffer>run("MPEG audio packet", output, emit -> decodePacket(packet.data(), corrupt, emit));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The concealment unit is the frame length of the last decoded frame (1152 samples per channel for Layers
     * II and III of MPEG-1, 576 for Layer III of MPEG-2 and MPEG-2.5, 384 for Layer I), or 1152 before the first
     * frame. Every lost frame is concealed as a frame failing its CRC is (see the class description), so the run of
     * concealed frames, which fades to silence, continues across the lost frames and the damaged ones around them.
     * A Layer III frame after the loss whose main data begins in a lost frame is concealed too, instead of decoding
     * to silence.
     *
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        var header = previousHeader;
        var unit = header == 0 ? MAX_FRAME_SAMPLES : MpegAudioHeader.samplesPerFrame(header);
        var frames = Math.min(Math.max(1, (samples + unit / 2) / unit), Integer.MAX_VALUE / unit);
        layer3.dropReservoir();
        afterLoss = true;
        ConsumerFailure.<AudioBuffer>run("MPEG audio concealment", output, emit -> {
            for (var frame = 0; frame < frames; frame++) {
                concealFrame(header, emit);
            }
        });
        return frames * unit;
    }

    /**
     * Conceals one lost frame and hands it to {@code output}.
     *
     * @param header the header of the last decoded frame, or {@code 0} before the first, which conceals with
     * silence in the track's format (44.1 kHz when the track declares no rate)
     * @param output receives the frame's buffer
     */
    private void concealFrame(int header, Consumer<AudioBuffer> output) {
        if (header == 0) {
            var out = outputBuffer(layout, sampleRate > 0 ? sampleRate : 44100);
            for (var plane = 0; plane < out.channels(); plane++) {
                Arrays.fill(out.plane(plane), 0, MAX_FRAME_SAMPLES, 0);
            }
            out.setFrames(MAX_FRAME_SAMPLES);
            output.accept(out);
            return;
        }
        var channels = MpegAudioHeader.channels(header);
        var rate = MpegAudioHeader.sampleRate(header);
        var multichannelFrame = multichannel && MpegAudioHeader.layer(header) == 2 && MpegAudioHeader.isMpeg1(header);
        var out = multichannelFrame && extensionEstablished ? outputBuffer(extensionLayout, rate)
                : outputBuffer(channels, rate);
        conceal(header, channels, out.plane(0), out.plane(channels - 1));
        if (out.channels() > channels) {
            silenceExtension(out);
        }
        out.setFrames(MpegAudioHeader.samplesPerFrame(header));
        output.accept(out);
    }

    /**
     * {@return the number of frames concealed since the decoder was created}
     */
    long concealedFrames() {
        return concealedFrames;
    }

    /**
     * Decodes every whole frame of a packet.
     *
     * @param data the packet payload
     * @param corrupt whether the packet is flagged corrupt, so every frame is concealed
     * @param output receives one buffer per frame
     * @throws MediaException.Malformed if the payload does not start with a valid frame header
     */
    private void decodePacket(byte[] data, boolean corrupt, Consumer<AudioBuffer> output) {
        var length = data.length;
        if (length < MpegAudioHeader.SIZE) {
            throw new MediaException.Malformed("MPEG audio packet of " + length + " bytes has no frame header");
        }
        var offset = 0;
        while (length - offset >= MpegAudioHeader.SIZE) {
            var header = MpegAudioHeader.read(data, offset);
            if (!MpegAudioHeader.isValid(header)) {
                if (offset == 0) {
                    throw new MediaException.Malformed("packet does not start with an MPEG audio frame header");
                }
                return;
            }
            var size = MpegAudioHeader.frameBytes(header, 0);
            if (size == 0) {
                size = freeFormatSize(data, offset, header);
            } else {
                size += MpegAudioHeader.padding(header);
            }
            var following = offset + size;
            var extensionSize = following < length
                    ? Mp2Multichannel.extensionFrameLength(data, following, length - following) : 0;
            extensionData = extensionSize > 0 ? data : null;
            extensionOffset = following;
            extensionLength = extensionSize;
            decodeFrame(header, data, offset, Math.min(size, length - offset), corrupt, output);
            offset += size + extensionSize;
        }
    }

    /**
     * Returns the size of a free-format frame inside a packet: up to the next compatible header,
     * or to the end of the packet.
     *
     * @param data the packet payload
     * @param offset the index of the frame
     * @param header the frame header
     * @return the frame size in bytes
     */
    private static int freeFormatSize(byte[] data, int offset, int header) {
        var end = Math.min(data.length, offset + Layer3.MAX_FRAME_PAYLOAD);
        for (var k = offset + MpegAudioHeader.SIZE; k + MpegAudioHeader.SIZE <= end; k++) {
            if (data[k] == (byte) 0xFF && MpegAudioHeader.isCompatible(header, MpegAudioHeader.read(data, k))) {
                return k - offset;
            }
        }
        return data.length - offset;
    }

    /**
     * Decodes one frame and hands its samples to {@code output}.
     *
     * @param header the frame header
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param output receives the frame's buffer
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private void decodeFrame(int header, byte[] data, int offset, int length, boolean corrupt,
                             Consumer<AudioBuffer> output) {
        if (previousHeader != 0 && !MpegAudioHeader.isCompatible(previousHeader, header)) {
            reset();
        }
        previousHeader = header;
        var channels = MpegAudioHeader.channels(header);
        LimitChecks.checkChannels(limits, channels);
        var rate = MpegAudioHeader.sampleRate(header);
        var samples = MpegAudioHeader.samplesPerFrame(header);
        // TODO: decode the 13818-3 extension of Layer I (one extension per three frames, 13818-4 test22) and of
        //  Layer III (13818-4 test23); those streams play their Lo/Ro base.
        var multichannelFrame = multichannel && MpegAudioHeader.layer(header) == 2 && MpegAudioHeader.isMpeg1(header);
        var out = multichannelFrame && extensionEstablished ? outputBuffer(extensionLayout, rate)
                : outputBuffer(channels, rate);
        var left = out.plane(0);
        var right = out.plane(channels - 1);
        frameBits.init(data, offset + MpegAudioHeader.SIZE, length - MpegAudioHeader.SIZE);
        var crcWord = -1;
        if (MpegAudioHeader.hasCrc(header)) {
            crcWord = frameBits.read(16);
            if (frameBits.overrun() || !crcCheck) {
                crcWord = -1;
            }
        }
        int status;
        if (MpegAudioHeader.layer(header) == 3) {
            status = decodeLayer3(header, channels, left, right, data, offset, corrupt, crcWord);
        } else if (multichannelFrame) {
            status = decodeMultichannel(header, channels, data, offset, length, corrupt, crcWord);
            out = buffer;
            left = out.plane(0);
            right = out.plane(channels - 1);
        } else {
            status = decodeLayer12(header, channels, left, right, data, offset, corrupt, crcWord);
        }
        if (status == CONCEAL) {
            conceal(header, channels, left, right);
            if (out.channels() > channels) {
                silenceExtension(out);
            }
        } else {
            concealedRun = 0;
            afterLoss = false;
            if (status == SILENT) {
                for (var plane = 0; plane < out.channels(); plane++) {
                    Arrays.fill(out.plane(plane), 0, samples, 0);
                }
            }
        }
        if (!started) {
            started = true;
            sampleRate = out.sampleRate();
            layout = out.layout();
        }
        out.setFrames(samples);
        output.accept(out);
    }

    /**
     * Returns whether the CRC-16 word of a frame matches the bits it protects.
     *
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param crcWord the CRC-16 word the frame carries
     * @param bits the number of protected bits after the CRC word
     * @return {@code false} on a mismatch, or when the protected bits run past the end of the frame
     */
    private boolean crcMatches(byte[] data, int offset, int crcWord, int bits) {
        if (16 + bits > frameBits.limit) {
            return false;
        }
        return MpegAudioCrc.frame(data, offset, bits) == crcWord;
    }

    /**
     * Decodes the granules of a Layer III frame.
     *
     * @param header the frame header
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param crcWord the CRC-16 word to check, or {@code -1} for none
     * @return {@link #DECODED}, {@link #SILENT} or {@link #CONCEAL}
     */
    private int decodeLayer3(int header, int channels, float[] left, float[] right, byte[] data, int offset,
                             boolean corrupt, int crcWord) {
        var sideInfoBits = MpegAudioHeader.sideInfoBytes(header) * 8;
        if (corrupt || crcWord >= 0 && !crcMatches(data, offset, crcWord, sideInfoBits)) {
            frameBits.position += sideInfoBits;
            if (!frameBits.overrun()) {
                layer3.keepMainData(frameBits);
            }
            return CONCEAL;
        }
        var mainDataBegin = layer3.readSideInfo(frameBits, header);
        if (mainDataBegin < 0 || frameBits.overrun()) {
            reset();
            return SILENT;
        }
        var complete = layer3.restoreReservoir(frameBits, mainDataBegin);
        if (complete) {
            var granules = MpegAudioHeader.isMpeg1(header) ? 2 : 1;
            for (var granule = 0; granule < granules; granule++) {
                Arrays.fill(grbuf, 0);
                layer3.decodeGranule(header, granule * channels, channels, grbuf);
                keepGood(18, channels);
                synthesize(18, channels, left, right, granule * 576);
            }
        }
        layer3.saveReservoir();
        return complete ? DECODED : afterLoss ? CONCEAL : SILENT;
    }

    /**
     * Decodes the groups of a Layer I or Layer II frame.
     *
     * @param header the frame header
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param crcWord the CRC-16 word to check, or {@code -1} for none
     * @return {@link #DECODED}, {@link #SILENT} or {@link #CONCEAL}
     */
    private int decodeLayer12(int header, int channels, float[] left, float[] right, byte[] data, int offset,
                              boolean corrupt, int crcWord) {
        if (corrupt) {
            return CONCEAL;
        }
        layer12.readScaleInfo(header, frameBits);
        if (crcWord >= 0 && !crcMatches(data, offset, crcWord, layer12.protectedEnd() - 16)) {
            return CONCEAL;
        }
        var groupSize = MpegAudioHeader.layer(header) | 1;
        Arrays.fill(grbuf, 0);
        var slot = 0;
        var output = 0;
        for (var part = 0; part < 3; part++) {
            slot += layer12.dequantizeGranule(grbuf, slot, frameBits, groupSize);
            if (slot == 12) {
                slot = 0;
                layer12.applyScalefactors(grbuf, part);
                keepGood(12, channels);
                synthesize(12, channels, left, right, output);
                Arrays.fill(grbuf, 0);
                output += 384;
            }
            if (frameBits.overrun()) {
                reset();
                return SILENT;
            }
        }
        return DECODED;
    }

    /**
     * Decodes a Layer II frame that may carry the multichannel extension: the base channels go to the
     * extension decoder, which dematrixes them with the extension's channels when the extension is present
     * and valid; otherwise the frame decodes as its base channels, or, once the stream is known to be
     * multichannel, in the multichannel layout with only the base channels heard.
     *
     * <p>The output buffer is left in {@link #buffer}.
     *
     * @param header the frame header
     * @param channels the number of base channels
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param crcWord the CRC-16 word to check, or {@code -1} for none
     * @return {@link #DECODED}, {@link #SILENT} or {@link #CONCEAL}
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private int decodeMultichannel(int header, int channels, byte[] data, int offset, int length, boolean corrupt,
                                   int crcWord) {
        var rate = MpegAudioHeader.sampleRate(header);
        if (corrupt) {
            return CONCEAL;
        }
        layer12.readScaleInfo(header, frameBits);
        if (crcWord >= 0 && !crcMatches(data, offset, crcWord, layer12.protectedEnd() - 16)) {
            return CONCEAL;
        }
        Arrays.fill(grbuf, 0);
        for (var part = 0; part < 3; part++) {
            layer12.dequantizeGranule(grbuf, 0, frameBits, 3);
            layer12.applyScalefactors(grbuf, part);
            for (var ch = 0; ch < channels; ch++) {
                var base = extension.baseSamples(ch);
                for (var sb = 0; sb < 32; sb++) {
                    System.arraycopy(grbuf, 576 * ch + sb * 18, base, sb * 36 + part * 12, 12);
                }
            }
            Arrays.fill(grbuf, 0);
            if (frameBits.overrun()) {
                reset();
                outputBuffer(channels, rate);
                return SILENT;
            }
        }
        var start = frameBits.start * 8 + frameBits.position;
        var end = (offset + length) * 8;
        var decoded = extension.gather(data, start, end, extensionData, extensionOffset, extensionLength);
        if (decoded && extension.wantsExtensionFrame()) {
            decoded = extensionLength > 0 && (!crcCheck
                    || Mp2Multichannel.extensionCrcMatches(extensionData, extensionOffset, extensionLength));
        }
        decoded = decoded && extension.decode(channels, rate, baseScale, crcCheck || !extensionEstablished);
        if (decoded) {
            var layout = extension.outputLayout();
            LimitChecks.checkChannels(limits, layout.channels());
            if (!extensionEstablished || !layout.equals(extensionLayout)) {
                for (var state : extensionStates) {
                    Arrays.fill(state, 0);
                }
                lastLfe = 0;
            }
            extensionEstablished = true;
            extensionLayout = layout;
            Arrays.fill(extensionPlanes, -1);
            lfePlane = -1;
            var map = extension.outputChannels();
            for (var plane = 0; plane < map.length; plane++) {
                if (map[plane] < 0) {
                    lfePlane = plane;
                } else {
                    extensionPlanes[map[plane]] = plane;
                }
            }
            var out = outputBuffer(layout, rate);
            synthesizeExtension(out, true);
            return DECODED;
        }
        if (!extensionEstablished) {
            var out = outputBuffer(channels, rate);
            synthesizeBase(channels, out.plane(0), out.plane(channels - 1));
            return DECODED;
        }
        concealedFrames++;
        var out = outputBuffer(extensionLayout, rate);
        synthesizeBase(channels, out.plane(0), out.plane(channels - 1));
        silenceExtension(out);
        return DECODED;
    }

    /**
     * Synthesizes the base channels kept by the extension decoder, as plain Layer II output.
     *
     * @param channels the number of base channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     */
    private void synthesizeBase(int channels, float[] left, float[] right) {
        for (var part = 0; part < 3; part++) {
            fillGranule(grbuf, extension.baseSamples(0), channels == 2 ? extension.baseSamples(1) : null, part);
            keepGood(12, channels);
            synthesize(12, channels, left, right, part * 384);
        }
    }

    /**
     * Synthesizes every output channel of a multichannel frame from the extension decoder.
     *
     * @param out the output buffer, in the multichannel layout
     * @param withLfe whether the low-frequency enhancement channel carries the frame's samples, rather than
     * silence
     */
    private void synthesizeExtension(AudioBuffer out, boolean withLfe) {
        var left = out.plane(extensionPlanes[0]);
        var right = out.plane(extensionPlanes[1] < 0 ? extensionPlanes[0] : extensionPlanes[1]);
        var baseChannels = extensionPlanes[1] < 0 ? 1 : 2;
        for (var part = 0; part < 3; part++) {
            fillGranule(grbuf, extension.channelSamples(0), baseChannels == 2 ? extension.channelSamples(1) : null,
                    part);
            keepGood(12, baseChannels);
            synthesize(12, baseChannels, left, right, part * 384);
        }
        for (var pair = 0; pair < 2; pair++) {
            var first = 2 + 2 * pair;
            var second = first + 1;
            var firstPlane = first < extensionPlanes.length ? extensionPlanes[first] : -1;
            var secondPlane = second < extensionPlanes.length ? extensionPlanes[second] : -1;
            if (firstPlane < 0 && secondPlane < 0) {
                continue;
            }
            var pairChannels = firstPlane >= 0 && secondPlane >= 0 ? 2 : 1;
            var single = firstPlane >= 0 ? first : second;
            var outLeft = out.plane(firstPlane >= 0 ? firstPlane : secondPlane);
            var outRight = pairChannels == 2 ? out.plane(secondPlane) : outLeft;
            for (var part = 0; part < 3; part++) {
                fillGranule(extensionBuffer, extension.channelSamples(pairChannels == 2 ? first : single),
                        pairChannels == 2 ? extension.channelSamples(second) : null, part);
                synthesize(extensionBuffer, extensionStates[pair], 12, pairChannels, outLeft, outRight, part * 384);
            }
        }
        if (lfePlane >= 0) {
            var plane = out.plane(lfePlane);
            var lfe = extension.lfeSamples();
            for (var k = 0; k < 12; k++) {
                var target = withLfe ? lfe[k] : 0;
                var step = (target - lastLfe) / LFE_PERIOD;
                for (var n = 0; n < LFE_PERIOD; n++) {
                    plane[k * LFE_PERIOD + n] = lastLfe + step * (n + 1);
                }
                plane[k * LFE_PERIOD + LFE_PERIOD - 1] = target;
                lastLfe = target;
            }
        }
    }

    /**
     * Feeds silence through the filterbanks of the extension channels of a multichannel output buffer,
     * which lets their last output decay, and fades the low-frequency enhancement channel out.
     *
     * @param out the output buffer, in the multichannel layout
     */
    private void silenceExtension(AudioBuffer out) {
        for (var pair = 0; pair < 2; pair++) {
            var first = 2 + 2 * pair;
            var firstPlane = extensionPlanes[first];
            var secondPlane = first + 1 < extensionPlanes.length ? extensionPlanes[first + 1] : -1;
            if (firstPlane < 0 && secondPlane < 0) {
                continue;
            }
            var pairChannels = firstPlane >= 0 && secondPlane >= 0 ? 2 : 1;
            var outLeft = out.plane(firstPlane >= 0 ? firstPlane : secondPlane);
            var outRight = pairChannels == 2 ? out.plane(secondPlane) : outLeft;
            for (var part = 0; part < 3; part++) {
                Arrays.fill(extensionBuffer, 0);
                synthesize(extensionBuffer, extensionStates[pair], 12, pairChannels, outLeft, outRight, part * 384);
            }
        }
        if (lfePlane >= 0) {
            var plane = out.plane(lfePlane);
            for (var k = 0; k < 12; k++) {
                var step = -lastLfe / LFE_PERIOD;
                for (var n = 0; n < LFE_PERIOD; n++) {
                    plane[k * LFE_PERIOD + n] = lastLfe + step * (n + 1);
                }
                lastLfe = 0;
            }
        }
    }

    /**
     * Copies one block of twelve slots of one or two channels from the extension's layout into a granule
     * buffer.
     *
     * @param target the granule buffer, channel 1 at index 576
     * @param first the first channel's samples, subband {@code sb}, slot {@code t} at {@code sb * 36 + t}
     * @param second the second channel's samples, or {@code null}
     * @param part the block of twelve slots, 0 to 2
     */
    private static void fillGranule(float[] target, float[] first, float[] second, int part) {
        Arrays.fill(target, 0);
        for (var sb = 0; sb < 32; sb++) {
            System.arraycopy(first, sb * 36 + part * 12, target, sb * 18, 12);
            if (second != null) {
                System.arraycopy(second, sb * 36 + part * 12, target, 576 + sb * 18, 12);
            }
        }
    }

    /**
     * Keeps the subband samples about to be synthesized for concealing later frames.
     *
     * @param slots the number of time slots, 12 or 18
     * @param channels the number of channels
     */
    private void keepGood(int slots, int channels) {
        System.arraycopy(grbuf, 0, lastGood, 0, 576 * channels);
        lastGoodSlots = slots;
        lastGoodChannels = channels;
    }

    /**
     * Conceals a frame by repeating the last decoded subband samples at a falling gain.
     *
     * <p>The repetition needs the samples of a frame of the same layer and channel count; without
     * them, or once the run of concealed frames is long, the filterbank is fed silence, which lets the
     * output of the last good frame decay.
     *
     * @param header the frame header
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     */
    private void conceal(int header, int channels, float[] left, float[] right) {
        concealedFrames++;
        concealedRun++;
        var layer = MpegAudioHeader.layer(header);
        var slots = layer == 3 ? 18 : 12;
        var parts = layer == 3 ? MpegAudioHeader.samplesPerFrame(header) / 576 : layer == 1 ? 1 : 3;
        var gain = concealedRun <= AUDIBLE_CONCEALMENT ? 1.0f / (1 << concealedRun) : 0;
        var repeat = gain != 0 && lastGoodSlots == slots && lastGoodChannels == channels;
        for (var part = 0; part < parts; part++) {
            if (repeat) {
                for (var i = 0; i < 576 * channels; i++) {
                    grbuf[i] = lastGood[i] * gain;
                }
            } else {
                Arrays.fill(grbuf, 0);
            }
            synthesize(slots, channels, left, right, part * 32 * slots);
        }
    }

    /**
     * Runs the synthesis filterbank over the subband samples of a granule.
     *
     * @param slots the number of time slots, 12 or 18
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @param offset the index of the first output sample
     */
    private void synthesize(int slots, int channels, float[] left, float[] right, int offset) {
        synthesize(grbuf, qmfState, slots, channels, left, right, offset);
    }

    /**
     * Runs the synthesis filterbank over the subband samples of a granule, with a given buffer and history.
     *
     * @param samples the subband samples, channel 1 at index 576; rewritten
     * @param state the filterbank history of the channels, carried between granules
     * @param slots the number of time slots, 12 or 18
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @param offset the index of the first output sample
     */
    private void synthesize(float[] samples, float[] state, int slots, int channels, float[] left, float[] right,
                            int offset) {
        for (var ch = 0; ch < channels; ch++) {
            kernel.dct(samples, 576 * ch, slots);
        }
        System.arraycopy(state, 0, lins, 0, 15 * 64);
        var rightBase = 576 * (channels - 1);
        for (var i = 0; i < slots; i += 2) {
            kernel.window(samples, i, rightBase + i, left, right, offset + 32 * i, lins, i * 64);
        }
        if (channels == 1) {
            for (var i = 0; i < 15 * 64; i += 2) {
                state[i] = lins[slots * 64 + i];
            }
        } else {
            System.arraycopy(lins, slots * 64, state, 0, 15 * 64);
        }
    }

    /**
     * Returns the buffer for a frame format, allocating a new one when the format changes.
     *
     * @param channels the number of channels
     * @param rate the sampling rate
     * @return the buffer
     */
    private AudioBuffer outputBuffer(int channels, int rate) {
        return outputBuffer(channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO, rate);
    }

    /**
     * Returns the buffer for a frame layout, allocating a new one when the format changes.
     *
     * @param frameLayout the layout
     * @param rate the sampling rate
     * @return the buffer
     */
    private AudioBuffer outputBuffer(ChannelLayout frameLayout, int rate) {
        if (buffer == null || !buffer.layout().equals(frameLayout) || buffer.sampleRate() != rate) {
            buffer = new AudioBuffer(frameLayout, rate, MAX_FRAME_SAMPLES);
        }
        return buffer;
    }

    /**
     * Clears the reservoir, the IMDCT overlap and the filterbank history.
     */
    private void reset() {
        layer3.reset();
        layer12.reset();
        Arrays.fill(qmfState, 0);
        lastGoodSlots = 0;
        extension.reset();
        for (var state : extensionStates) {
            Arrays.fill(state, 0);
        }
        extensionEstablished = false;
        lastLfe = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: every frame is output as soon as it is decoded.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }
}
