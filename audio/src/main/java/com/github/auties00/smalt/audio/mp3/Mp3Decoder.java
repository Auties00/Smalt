/* Ports minimp3 (minimp3.h). By lieff; CC0 1.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-1, MPEG-2 and MPEG-2.5 audio, Layers I, II and III, into planar float samples.
 *
 * <p>The decoder accepts tracks of codec {@link Codec#MP3} and {@link Codec#MP2}; the layer of
 * each frame is taken from its own header, so a track may mix layers. A packet holds one or more
 * complete frames, each starting with its four-byte header; bytes after the last whole frame are
 * ignored. Every frame yields one {@link AudioBuffer} of 384 (Layer I), 576 (MPEG-2 and MPEG-2.5
 * Layer III) or 1152 samples per channel, in the frame's own sample rate and channel count, except that three
 * Layer I frames that decode with their multichannel extension yield one buffer of 1152: a
 * stream that switches between mono and stereo, or between sample rates, produces buffers of
 * different formats. Dual-channel frames decode to a {@link ChannelLayout#STEREO} buffer.
 * Samples are scaled so that full-scale 16-bit output corresponds to {@code [-1, 1)}.
 *
 * <p>With {@link Mp3DecoderOptions#multichannel()} on (the default), MPEG-1 frames of any layer that carry
 * the ISO/IEC 13818-3 multichannel extension decode to their full channel configuration, from
 * {@code 3/0} to {@code 3/2} with the low-frequency enhancement channel, in WAVE order (for example
 * {@link ChannelLayout#SURROUND_5_1} for {@code 3/2} with LFE); see {@link Mp3DecoderOptions#multichannel()}
 * for how the extension is recognized and where an extension frame goes. {@link Mp3DecoderOptions#program()}
 * selects the second stereo program or the multilingual channels the extension may carry instead.
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
 * outputs every frame immediately, with two exceptions that {@link #flush(Consumer)} drains: a Layer I frame that may
 * start a multichannel extension waits for the two frames after it, and a Layer III frame with a multichannel extension
 * waits for the next frame, whose {@code main_data_begin} tells where the extension's data ends in the frame. Encoder
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
     * The layouts of every count of multilingual channels, by count, each channel of unknown position.
     */
    private static final ChannelLayout[] MULTILINGUAL_LAYOUTS = new ChannelLayout[8];

    static {
        for (var count = 1; count < MULTILINGUAL_LAYOUTS.length; count++) {
            MULTILINGUAL_LAYOUTS[count] = new ChannelLayout(Collections.nCopies(count, Speaker.UNKNOWN));
        }
    }

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
     * The program of a multichannel stream that is output.
     */
    private final Mp2Program program;

    /**
     * The multilingual channel output alone, from 1 to 7, or {@code 0} for all of them.
     */
    private final int multilingualChannel;

    /**
     * Whether the last multichannel frame that was not concealed decoded to the selected program, after which a
     * frame whose extension fails keeps the program's format.
     */
    private boolean programEstablished;

    /**
     * The layout of the selected program's last frame.
     */
    private ChannelLayout programLayout;

    /**
     * The sampling rate of the selected program's last frame.
     */
    private int programRate;

    /**
     * The number of samples per channel of the selected program's frames, 1152 or 576.
     */
    private int programFrames;

    /**
     * The filterbank history of the selected program's channel pairs.
     */
    private final float[][] programStates;

    /**
     * Whether the frame decoded last was output as the selected program, leaving the filterbank history of the main
     * program behind.
     */
    private boolean mainStale;

    /**
     * Whether the frame decoded last was output as the selected program, in {@link #buffer}.
     */
    private boolean programFrame;

    /**
     * The Layer III multichannel extension decoder.
     */
    private final Layer3Multichannel layer3Extension;

    /**
     * The spectra of the base channels of a Layer III frame with an extension: granule, then channel 1 at index 576.
     */
    private final float[][] baseSpectrum = new float[2][2 * 576];

    /**
     * The number of Layer I frames held until their extension can be decoded, at most 2 between frames.
     */
    private int heldFrames;

    /**
     * The held Layer I frames, each from its first byte.
     */
    private final byte[][] heldBytes = new byte[3][];

    /**
     * The bit offset in each held frame where its audio data ends.
     */
    private final int[] heldStarts = new int[3];

    /**
     * The bit offset in each held frame where it ends.
     */
    private final int[] heldEnds = new int[3];

    /**
     * The extension frame each held frame's packet carried after it, as many bytes as {@link #heldExtensionLengths}
     * says.
     */
    private final byte[][] heldExtensions = new byte[3][];

    /**
     * The length of the extension frame of each held frame, 0 for none.
     */
    private final int[] heldExtensionLengths = new int[3];

    /**
     * The header of each held frame.
     */
    private final int[] heldHeaders = new int[3];

    /**
     * The number of Layer I frames counted since the last concealment by {@link #skipProgramFrame}.
     */
    private int skippedProgramFrames;

    /**
     * The subband samples of each held frame: frame, then channel 1 at index 576, in the layout of {@link #grbuf}.
     */
    private final float[][] heldSamples = new float[3][2 * 576];

    /**
     * The scalefactor index of each held frame: frame, channel, subband.
     */
    private final int[][][] heldScales = new int[3][2][32];

    /**
     * The allocation of each held frame: frame, channel, subband.
     */
    private final int[][][] heldAllocations = new int[3][2][32];

    /**
     * Gives the extension decoder the scalefactor indices of three held Layer I frames.
     */
    private final Mp2Multichannel.BaseScale heldScale = new Mp2Multichannel.BaseScale() {
        @Override
        public int index(int channel, int band, int part) {
            return heldScales[part][channel][band];
        }

        @Override
        public int allocation(int channel, int band) {
            for (var part = 0; part < 3; part++) {
                if (heldAllocations[part][channel][band] != 0) {
                    return heldAllocations[part][channel][band];
                }
            }
            return 0;
        }
    };

    /**
     * Whether the frames of an established Layer I extension are known to be in step with the stream, so that a
     * frame group may decode with its CRC unchecked.
     */
    private boolean heldAligned;

    /**
     * The Layer III frame held until the next frame's {@code main_data_begin} is known, with its extension frame
     * after it, or {@code null}.
     */
    private byte[] pendingFrame;

    /**
     * The number of frame bytes in {@link #pendingFrame}.
     */
    private int pendingLength;

    /**
     * The length of the extension frame after the frame in {@link #pendingFrame}.
     */
    private int pendingExtensionLength;

    /**
     * Whether the frame in {@link #pendingFrame} is concealed whatever its CRC.
     */
    private boolean pendingCorrupt;

    /**
     * Whether the last Layer III frame had a multichannel extension, so the next one probably has too and is held at
     * once, without first being parsed.
     */
    private boolean layer3NeedsLookahead;

    /**
     * Whether the frame being decoded turned out to have a multichannel extension before the next frame's
     * {@code main_data_begin} is known, so it was left undecoded.
     */
    private boolean layer3Held;

    /**
     * The {@code main_data_begin} of the frame after the one being decoded, or {@code -1} when it is not known.
     */
    private int nextMainDataBegin = -1;

    /**
     * Whether the frame decoded last was output through its Layer III extension, in {@link #buffer}.
     */
    private boolean layer3ExtensionFrame;

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
     * @param options the limits, CRC check, multichannel and program settings
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
     * @param options the limits, CRC check, multichannel and program settings
     * @param kernel the synthesis kernel
     */
    private Mp3Decoder(AudioTrack track, Mp3DecoderOptions options, SynthesisKernel kernel) {
        this.limits = options.limits();
        this.crcCheck = options.crcCheck();
        this.multichannel = options.multichannel();
        this.extension = new Mp2Multichannel();
        this.extensionStates = new float[2][15 * 64];
        this.extensionBuffer = new float[2 * 576];
        this.program = options.program();
        this.multilingualChannel = options.multilingualChannel().orElse(0);
        this.programStates = new float[program == Mp2Program.MAIN ? 0 : 4][15 * 64];
        extension.decodeMultilingual(program == Mp2Program.MULTILINGUAL);
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.MP3 && track.codec() != Codec.MP2) {
            throw new IllegalArgumentException("not an MPEG audio track: " + track.codec());
        }
        this.layer3 = new Layer3(kernel);
        this.layer12 = new Layer12();
        this.layer3Extension = new Layer3Multichannel(layer3, extension, kernel);
        layer3Extension.decodeMultilingual(program == Mp2Program.MULTILINGUAL);
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
     * II and III of MPEG-1, 576 for Layer III of MPEG-2 and MPEG-2.5 and for multilingual channels at half the
     * sampling rate, 384 for Layer I), or 1152 before the first frame. Frames held for a multichannel extension (see
     * the class description) are output first and counted in the result. Every
     * lost frame is concealed as a frame
     * failing its CRC is (see the class description), so the run of concealed frames, which fades to silence,
     * continues across the lost frames and the damaged ones around them.
     * A Layer III frame after the loss whose main data begins in a lost frame is concealed too, instead of decoding
     * to silence.
     *
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        var header = previousHeader;
        var unit = header == 0 ? MAX_FRAME_SAMPLES
                : programEstablished && isMultichannel(header) ? programFrames : MpegAudioHeader.samplesPerFrame(header);
        var frames = Math.min(Math.max(1, (samples + unit / 2) / unit), Integer.MAX_VALUE / unit);
        var released = new int[1];
        ConsumerFailure.<AudioBuffer>run("MPEG audio concealment", output, emit -> {
            Consumer<AudioBuffer> counting = buffer -> {
                released[0] += buffer.frames();
                emit.accept(buffer);
            };
            releasePending(0, counting);
            releaseHeld(counting);
            layer3.dropReservoir();
            layer3Extension.dropReservoir();
            afterLoss = true;
            heldAligned = false;
            for (var frame = 0; frame < frames; frame++) {
                concealFrame(header, emit);
            }
        });
        return released[0] + frames * unit;
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
        var multichannelFrame = isMultichannel(header);
        if (multichannelFrame && programEstablished) {
            output.accept(concealProgram());
            return;
        }
        var out = multichannelFrame && extensionEstablished ? outputBuffer(extensionLayout, rate)
                : outputBuffer(channels, rate);
        conceal(header, channels, out.plane(0), out.plane(channels - 1));
        if (out.channels() > channels) {
            silenceExtension(out, header);
        }
        out.setFrames(MpegAudioHeader.samplesPerFrame(header));
        output.accept(out);
    }

    /**
     * Returns whether a frame may carry a multichannel extension.
     *
     * @param header the frame header
     * @return {@code true} for an MPEG-1 frame with the extension on
     */
    private boolean isMultichannel(int header) {
        return multichannel && MpegAudioHeader.isMpeg1(header);
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
            var next = following + extensionSize;
            nextMainDataBegin = next + MpegAudioHeader.SIZE + 2 < length ? mainDataBegin(data, next) : -1;
            decodeFrame(header, data, offset, Math.min(size, length - offset), corrupt, output);
            offset += size + extensionSize;
        }
    }

    /**
     * Returns the {@code main_data_begin} of the Layer III frame at an offset.
     *
     * @param data the bytes
     * @param offset the index of the candidate frame, with at least seven bytes from it
     * @return the value, or {@code -1} when no MPEG-1 Layer III frame starts there
     */
    private static int mainDataBegin(byte[] data, int offset) {
        var header = MpegAudioHeader.read(data, offset);
        if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.layer(header) != 3 || !MpegAudioHeader.isMpeg1(header)) {
            return -1;
        }
        var at = offset + MpegAudioHeader.SIZE + (MpegAudioHeader.hasCrc(header) ? 2 : 0);
        return at + 1 < data.length ? (data[at] & 0xFF) << 1 | (data[at + 1] & 0xFF) >> 7 : -1;
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
        var layer = MpegAudioHeader.layer(header);
        var compatible = previousHeader == 0 || MpegAudioHeader.isCompatible(previousHeader, header);
        if (pendingFrame != null && pendingLength != 0) {
            var begin = compatible && layer == 3 ? mainDataBegin(data, offset) : 0;
            var next = nextMainDataBegin;
            var extensionBytes = extensionData;
            var extensionAt = extensionOffset;
            var extensionSize = extensionLength;
            releasePending(Math.max(0, begin), output);
            nextMainDataBegin = next;
            extensionData = extensionBytes;
            extensionOffset = extensionAt;
            extensionLength = extensionSize;
        }
        if (layer != 1 || !compatible || !isMultichannel(header)) {
            releaseHeld(output);
        }
        if (layer == 3 && compatible && isMultichannel(header) && layer3NeedsLookahead && nextMainDataBegin < 0) {
            holdLayer3Frame(data, offset, length, corrupt);
            return;
        }
        decodeNow(header, data, offset, length, corrupt, output);
    }

    /**
     * Decodes one frame whose lookahead is settled and hands its samples to {@code output}, unless it is a Layer I
     * frame that waits for the rest of its multichannel frame group.
     *
     * @param header the frame header
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param output receives the frame's buffer
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private void decodeNow(int header, byte[] data, int offset, int length, boolean corrupt,
                           Consumer<AudioBuffer> output) {
        if (previousHeader != 0 && !MpegAudioHeader.isCompatible(previousHeader, header)) {
            reset();
        }
        if (MpegAudioHeader.layer(header) == 1 && isMultichannel(header)) {
            previousHeader = header;
            LimitChecks.checkChannels(limits, MpegAudioHeader.channels(header));
            decodeLayer1Group(header, data, offset, length, corrupt, output);
            return;
        }
        previousHeader = header;
        var channels = MpegAudioHeader.channels(header);
        LimitChecks.checkChannels(limits, channels);
        var rate = MpegAudioHeader.sampleRate(header);
        var samples = MpegAudioHeader.samplesPerFrame(header);
        var multichannelFrame = isMultichannel(header);
        var out = multichannelFrame && programEstablished ? outputBuffer(programLayout, programRate)
                : multichannelFrame && extensionEstablished ? outputBuffer(extensionLayout, rate)
                : outputBuffer(channels, rate);
        var left = out.plane(0);
        var right = out.plane(Math.min(channels, out.channels()) - 1);
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
            layer3ExtensionFrame = false;
            programFrame = false;
            layer3Held = false;
            status = decodeLayer3(header, channels, left, right, data, offset, corrupt, crcWord);
            if (layer3Held) {
                holdLayer3Frame(data, offset, length, corrupt);
                return;
            }
            if (layer3ExtensionFrame) {
                out = buffer;
                if (programFrame) {
                    samples = programFrames;
                }
            } else if (status == DECODED && out.channels() > channels) {
                silenceExtension(out, header);
            }
        } else if (multichannelFrame) {
            programFrame = false;
            status = decodeMultichannel(header, channels, data, offset, length, corrupt, crcWord);
            out = buffer;
            if (programFrame) {
                samples = programFrames;
            } else {
                left = out.plane(0);
                right = out.plane(channels - 1);
            }
        } else {
            status = decodeLayer12(header, channels, left, right, data, offset, corrupt, crcWord);
        }
        if (status == CONCEAL) {
            conceal(header, channels, left, right);
            if (out.channels() > channels) {
                silenceExtension(out, header);
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
            layer3Extension.dropReservoir();
            return CONCEAL;
        }
        var mainDataBegin = layer3.readSideInfo(frameBits, header);
        if (mainDataBegin < 0 || frameBits.overrun()) {
            reset();
            return SILENT;
        }
        var complete = layer3.restoreReservoir(frameBits, mainDataBegin);
        if (!complete) {
            layer3Extension.dropReservoir();
        }
        if (complete && decodeLayer3Extension(header, channels, left, right)) {
            if (!layer3Held) {
                layer3.saveReservoir();
            }
            return DECODED;
        }
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
     * Decodes a Layer III frame with its multichannel extension, when it has one. The base channels are decoded
     * whenever the frame has an extension header; a frame whose extension cannot be read is output as its base
     * channels here, in the layout already chosen. When the frame has an extension and the next frame's
     * {@code main_data_begin} is not known, nothing changes but {@link #layer3Held}: the frame's last bytes that hold
     * the next frame's main data are not the extension's, whose own reservoir and extension frame continue after its
     * end.
     *
     * @param header the frame header
     * @param channels the number of base channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @return {@code false} when the frame has no extension and is left for plain decoding
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private boolean decodeLayer3Extension(int header, int channels, float[] left, float[] right) {
        if (!isMultichannel(header)) {
            return false;
        }
        var main = layer3.mainBits();
        var position = main.position;
        for (var granule = 0; granule < 2 * channels; granule++) {
            position += layer3.granules[granule].part23Length;
        }
        var parsed = position < main.limit
                && layer3Extension.parse(main, position, Math.max(0, nextMainDataBegin), header, channels, extensionData,
                extensionOffset, extensionLength, crcCheck || !(extensionEstablished || programEstablished));
        if (!parsed) {
            layer3Extension.dropReservoir();
            layer3NeedsLookahead = false;
            if (programEstablished) {
                concealLayer3Program(header, channels);
                return true;
            }
            if (extensionEstablished) {
                concealedFrames++;
                layer3Extension.clearExtensionHistory();
            }
            return false;
        }
        layer3NeedsLookahead = true;
        if (nextMainDataBegin < 0) {
            layer3Held = true;
            return true;
        }
        var complete = layer3Extension.readMainData();
        for (var granule = 0; granule < 2; granule++) {
            Arrays.fill(baseSpectrum[granule], 0);
            layer3.decodeSpectrum(header, granule * channels, channels, baseSpectrum[granule]);
        }
        if (!complete && programEstablished) {
            for (var granule = 0; granule < 2; granule++) {
                layer3.finishGranule(header, granule * channels, channels, baseSpectrum[granule]);
            }
            concealProgramFrame();
            layer3ExtensionFrame = true;
            return true;
        }
        if (!complete) {
            if (extensionEstablished) {
                concealedFrames++;
            }
            layer3Extension.clearExtensionHistory();
            for (var granule = 0; granule < 2; granule++) {
                layer3.finishGranule(header, granule * channels, channels, baseSpectrum[granule]);
                System.arraycopy(baseSpectrum[granule], 0, grbuf, 0, 2 * 576);
                keepGood(18, channels);
                synthesize(18, channels, left, right, granule * 576);
            }
            return true;
        }
        for (var granule = 0; granule < 2; granule++) {
            layer3Extension.synthesize(granule, baseSpectrum[granule]);
        }
        layer3Extension.configureTarget();
        layer3ExtensionFrame = true;
        if (programEstablished && extension.multilingualCutShort()) {
            concealProgramFrame();
            return true;
        }
        outputExtension(MpegAudioHeader.sampleRate(header));
        return true;
    }

    /**
     * Keeps the base channels of a Layer III frame whose extension cannot be read in step, and conceals the frame in
     * the format of the selected program, into {@link #buffer}.
     *
     * @param header the frame header
     * @param channels the number of base channels
     */
    private void concealLayer3Program(int header, int channels) {
        for (var granule = 0; granule < 2; granule++) {
            Arrays.fill(grbuf, 0);
            layer3.decodeGranule(header, granule * channels, channels, grbuf);
        }
        concealProgramFrame();
        layer3ExtensionFrame = true;
    }

    /**
     * Copies a Layer III frame and its extension frame, to decode them when the next frame's {@code main_data_begin}
     * is known.
     *
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param corrupt whether the frame is concealed whatever its CRC
     */
    private void holdLayer3Frame(byte[] data, int offset, int length, boolean corrupt) {
        var extensionSize = extensionData != null ? extensionLength : 0;
        var size = length + extensionSize;
        if (pendingFrame == null || pendingFrame.length < size) {
            pendingFrame = new byte[size];
        }
        System.arraycopy(data, offset, pendingFrame, 0, length);
        if (extensionSize > 0) {
            System.arraycopy(extensionData, extensionOffset, pendingFrame, length, extensionSize);
        }
        pendingLength = length;
        pendingExtensionLength = extensionSize;
        pendingCorrupt = corrupt;
    }

    /**
     * Decodes the Layer III frame held for its lookahead, if there is one.
     *
     * @param nextBegin the {@code main_data_begin} of the frame after it
     * @param output receives the frame's buffer
     */
    private void releasePending(int nextBegin, Consumer<AudioBuffer> output) {
        if (pendingFrame == null || pendingLength == 0) {
            return;
        }
        var length = pendingLength;
        pendingLength = 0;
        extensionData = pendingExtensionLength > 0 ? pendingFrame : null;
        extensionOffset = length;
        extensionLength = pendingExtensionLength;
        nextMainDataBegin = nextBegin;
        decodeNow(MpegAudioHeader.read(pendingFrame, 0), pendingFrame, 0, length, pendingCorrupt, output);
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
     * Decodes one MPEG-1 Layer I frame of a stream that may carry the multichannel extension, whose data and output
     * span three frames. A frame with room for an extension header is held until its group is complete. When a group
     * decodes, it is output at once as one buffer of 1152 samples; when it does not, only its first frame is output,
     * as plain Layer I, and the next frame is tried as the start of a group, which finds the groups of a stream joined
     * at any frame and the groups again after a loss. A frame with no such room needs no group: it is output with
     * those before it. Once a group of the stream decoded to a selected program, such frames instead keep the format
     * of the program, concealed.
     *
     * @param header the frame header
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param corrupt whether the frame is concealed whatever its CRC
     * @param output receives the frames' buffers
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private void decodeLayer1Group(int header, byte[] data, int offset, int length, boolean corrupt,
                                   Consumer<AudioBuffer> output) {
        var channels = MpegAudioHeader.channels(header);
        frameBits.init(data, offset + MpegAudioHeader.SIZE, length - MpegAudioHeader.SIZE);
        var crcWord = -1;
        if (MpegAudioHeader.hasCrc(header)) {
            crcWord = frameBits.read(16);
            if (frameBits.overrun() || !crcCheck) {
                crcWord = -1;
            }
        }
        var lost = corrupt;
        if (!lost) {
            layer12.readScaleInfo(header, frameBits);
            lost = crcWord >= 0 && !crcMatches(data, offset, crcWord, layer12.protectedEnd() - 16);
        }
        if (lost) {
            heldAligned = false;
            releaseHeld(output);
            if (programEstablished) {
                skipProgramFrame(output);
            } else {
                concealFrame(header, output);
            }
            return;
        }
        Arrays.fill(grbuf, 0);
        for (var part = 0; part < 3; part++) {
            layer12.dequantizeGranule(grbuf, part * 4, frameBits, 1);
        }
        if (frameBits.overrun()) {
            releaseHeld(output);
            reset();
            var out = outputBuffer(channels, MpegAudioHeader.sampleRate(header));
            for (var plane = 0; plane < out.channels(); plane++) {
                Arrays.fill(out.plane(plane), 0, 384, 0);
            }
            out.setFrames(384);
            output.accept(out);
            return;
        }
        layer12.applyScalefactors(grbuf, 0);
        if (heldFrames == 0 && !startsExtension()) {
            heldAligned = false;
            outputPlainLayer1(header, channels, grbuf, output);
            return;
        }
        holdLayer1Frame(data, offset, length, frameBits.position + MpegAudioHeader.SIZE * 8, channels);
        if (heldFrames < 3) {
            return;
        }
        var extensionFrame = (byte[]) null;
        var extensionFrameLength = 0;
        for (var part = 0; part < 3 && extensionFrame == null; part++) {
            if (heldExtensionLengths[part] > 0) {
                extensionFrame = heldExtensions[part];
                extensionFrameLength = heldExtensionLengths[part];
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var samples = extension.baseSamples(ch);
            for (var part = 0; part < 3; part++) {
                for (var sb = 0; sb < 32; sb++) {
                    System.arraycopy(heldSamples[part], 576 * ch + sb * 18, samples, sb * 36 + part * 12, 12);
                }
            }
        }
        var rate = MpegAudioHeader.sampleRate(header);
        var decoded = extension.gather(heldBytes, heldStarts, heldEnds, extensionFrame, 0, extensionFrameLength);
        if (decoded && extension.wantsExtensionFrame()) {
            decoded = extensionFrame != null && (!crcCheck
                    || Mp2Multichannel.extensionCrcMatches(extensionFrame, 0, extensionFrameLength));
        }
        decoded = decoded && extension.decode(channels, rate, heldScale,
                crcCheck || !heldAligned || !(extensionEstablished || programEstablished));
        if (decoded && programEstablished && extension.multilingualCutShort()) {
            decoded = false;
        }
        if (!decoded) {
            heldAligned = false;
            releaseOneHeld(output);
            return;
        }
        heldAligned = true;
        heldFrames = 0;
        programFrame = false;
        outputExtension(rate);
        var samples = programFrame ? programFrames : MAX_FRAME_SAMPLES;
        var out = buffer;
        concealedRun = 0;
        afterLoss = false;
        if (!started) {
            started = true;
            sampleRate = out.sampleRate();
            layout = out.layout();
        }
        out.setFrames(samples);
        output.accept(out);
    }

    /**
     * {@return whether the bits after the audio data of the Layer I frame in {@link #frameBits} can start an extension:
     * they hold a header that adds channels to the base ones, which the padding of a plain stream does not}
     */
    private boolean startsExtension() {
        if (frameBits.limit - frameBits.position < 32) {
            return false;
        }
        var position = frameBits.position;
        var present = frameBits.read(1);
        if (present == 1) {
            frameBits.read(8);
        }
        var center = frameBits.read(2);
        var surround = frameBits.read(2);
        var lfe = frameBits.read(1);
        frameBits.read(3);
        var languages = frameBits.read(3);
        frameBits.position = position;
        return center != 2 && (center != 0 || surround != 0 || lfe != 0 || languages != 0);
    }

    /**
     * Outputs one Layer I frame as its base channels: in the multichannel layout, the other channels decaying to
     * silence, once a frame group of the stream decoded with its extension.
     *
     * @param header the frame header
     * @param channels the number of base channels
     * @param samples the frame's subband samples, channel 1 at index 576; overwritten
     * @param output receives the frame's buffer
     */
    private void outputPlainLayer1(int header, int channels, float[] samples, Consumer<AudioBuffer> output) {
        if (programEstablished) {
            skipProgramFrame(output);
            return;
        }
        var rate = MpegAudioHeader.sampleRate(header);
        var out = extensionEstablished ? outputBuffer(extensionLayout, rate) : outputBuffer(channels, rate);
        if (samples != grbuf) {
            System.arraycopy(samples, 0, grbuf, 0, 2 * 576);
        }
        keepGood(12, channels);
        synthesize(12, channels, out.plane(0), out.plane(channels - 1), 0);
        if (out.channels() > channels) {
            concealedFrames++;
            silenceExtension(out, header);
        }
        concealedRun = 0;
        afterLoss = false;
        if (!started) {
            started = true;
            sampleRate = out.sampleRate();
            layout = out.layout();
        }
        out.setFrames(384);
        output.accept(out);
    }

    /**
     * Counts one Layer I frame that does not carry the selected program, and conceals the program, in its format, for
     * every three of them, the program's frame spanning three frames.
     *
     * @param output receives the concealed buffer
     */
    private void skipProgramFrame(Consumer<AudioBuffer> output) {
        if (++skippedProgramFrames == 3) {
            skippedProgramFrames = 0;
            output.accept(concealProgram());
        }
    }

    /**
     * Holds the Layer I frame just dequantized in {@link #grbuf}.
     *
     * @param data the packet payload
     * @param offset the index of the frame header
     * @param length the number of frame bytes available
     * @param start the bit offset from the frame header where the frame's audio data ends
     * @param channels the number of base channels
     */
    private void holdLayer1Frame(byte[] data, int offset, int length, int start, int channels) {
        var slot = heldFrames++;
        if (heldBytes[slot] == null || heldBytes[slot].length < length) {
            heldBytes[slot] = new byte[Math.max(length, 2048)];
        }
        System.arraycopy(data, offset, heldBytes[slot], 0, length);
        heldStarts[slot] = start;
        // the reference decoder leaves out the last bit of a Layer I frame, and test 22 is written that way
        heldEnds[slot] = length * 8 - 1;
        var extensionSize = extensionData != null ? extensionLength : 0;
        heldExtensionLengths[slot] = extensionSize;
        if (extensionSize > 0) {
            if (heldExtensions[slot] == null || heldExtensions[slot].length < extensionSize) {
                heldExtensions[slot] = new byte[Math.max(extensionSize, 2048)];
            }
            System.arraycopy(extensionData, extensionOffset, heldExtensions[slot], 0, extensionSize);
        }
        System.arraycopy(grbuf, 0, heldSamples[slot], 0, 2 * 576);
        heldHeaders[slot] = MpegAudioHeader.read(data, offset);
        for (var ch = 0; ch < channels; ch++) {
            for (var band = 0; band < 32; band++) {
                heldScales[slot][ch][band] = layer12.scaleIndex(band, ch, 0);
                heldAllocations[slot][ch][band] = layer12.allocationIndex(band, ch);
            }
        }
    }

    /**
     * Outputs the first held Layer I frame as its base channels and moves the others forward.
     *
     * @param output receives the frame's buffer
     */
    private void releaseOneHeld(Consumer<AudioBuffer> output) {
        var header = heldHeaders[0];
        outputPlainLayer1(header, MpegAudioHeader.channels(header), heldSamples[0], output);
        heldFrames--;
        var bytes = heldBytes[0];
        var samples = heldSamples[0];
        var scales = heldScales[0];
        var allocations = heldAllocations[0];
        var extensionBytes = heldExtensions[0];
        for (var slot = 0; slot < 2; slot++) {
            heldBytes[slot] = heldBytes[slot + 1];
            heldStarts[slot] = heldStarts[slot + 1];
            heldEnds[slot] = heldEnds[slot + 1];
            heldExtensions[slot] = heldExtensions[slot + 1];
            heldExtensionLengths[slot] = heldExtensionLengths[slot + 1];
            heldSamples[slot] = heldSamples[slot + 1];
            heldScales[slot] = heldScales[slot + 1];
            heldAllocations[slot] = heldAllocations[slot + 1];
            heldHeaders[slot] = heldHeaders[slot + 1];
        }
        heldBytes[2] = bytes;
        heldSamples[2] = samples;
        heldScales[2] = scales;
        heldAllocations[2] = allocations;
        heldExtensions[2] = extensionBytes;
        heldExtensionLengths[2] = 0;
    }

    /**
     * Outputs every held Layer I frame as its base channels.
     *
     * @param output receives the frames' buffers
     */
    private void releaseHeld(Consumer<AudioBuffer> output) {
        while (heldFrames > 0) {
            releaseOneHeld(output);
        }
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
            return programEstablished ? concealProgramFrame() : CONCEAL;
        }
        layer12.readScaleInfo(header, frameBits);
        if (crcWord >= 0 && !crcMatches(data, offset, crcWord, layer12.protectedEnd() - 16)) {
            return programEstablished ? concealProgramFrame() : CONCEAL;
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
        decoded = decoded && extension.decode(channels, rate, baseScale,
                crcCheck || !(extensionEstablished || programEstablished));
        if (decoded && programEstablished && extension.multilingualCutShort()) {
            return concealProgramFrame();
        }
        if (!decoded && programEstablished) {
            return concealProgramFrame();
        }
        if (decoded) {
            outputExtension(rate);
            return DECODED;
        }
        programEstablished = false;
        clearStaleMain();
        if (!extensionEstablished) {
            var out = outputBuffer(channels, rate);
            synthesizeBase(channels, out.plane(0), out.plane(channels - 1));
            return DECODED;
        }
        concealedFrames++;
        var out = outputBuffer(extensionLayout, rate);
        synthesizeBase(channels, out.plane(0), out.plane(channels - 1));
        silenceExtension(out, header);
        return DECODED;
    }

    /**
     * Outputs a frame whose extension decoded, into {@link #buffer}: the selected program when it carries it, the main
     * program otherwise.
     *
     * @param rate the sampling rate of the frame
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private void outputExtension(int rate) {
        if (program != Mp2Program.MAIN && decodeProgram(rate)) {
            programFrame = true;
            return;
        }
        programEstablished = false;
        clearStaleMain();
        var frameLayout = extension.outputLayout();
        LimitChecks.checkChannels(limits, frameLayout.channels());
        if (!extensionEstablished || !frameLayout.equals(extensionLayout)) {
            for (var state : extensionStates) {
                Arrays.fill(state, 0);
            }
            lastLfe = 0;
        }
        extensionEstablished = true;
        extensionLayout = frameLayout;
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
        synthesizeExtension(outputBuffer(frameLayout, rate), true);
    }

    /**
     * Forgets the filterbank history of the main program when the frame before was output as the selected program.
     */
    private void clearStaleMain() {
        if (mainStale) {
            Arrays.fill(qmfState, 0);
            for (var state : extensionStates) {
                Arrays.fill(state, 0);
            }
            lastGoodSlots = 0;
            lastLfe = 0;
            extensionEstablished = false;
            mainStale = false;
        }
    }

    /**
     * Outputs the selected program of a frame whose extension decoded, into {@link #buffer}.
     *
     * @param rate the sampling rate of the frame
     * @return {@code false} when the frame does not carry the program
     * @throws MediaException.LimitExceeded if the program has more channels than the limits allow
     */
    private boolean decodeProgram(int rate) {
        ChannelLayout frameLayout;
        var frameRate = rate;
        var frames = MAX_FRAME_SAMPLES;
        if (program == Mp2Program.SECOND_STEREO) {
            if (extension.secondStereoChannel() < 0) {
                return false;
            }
            frameLayout = ChannelLayout.STEREO;
        } else {
            var count = extension.multilingualChannels();
            if (count == 0 || multilingualChannel > count) {
                return false;
            }
            frameLayout = multilingualChannel == 0 ? MULTILINGUAL_LAYOUTS[count] : ChannelLayout.MONO;
            if (extension.multilingualHalfRate()) {
                frameRate = rate / 2;
                frames = MAX_FRAME_SAMPLES / 2;
            }
        }
        LimitChecks.checkChannels(limits, frameLayout.channels());
        if (!programEstablished || !frameLayout.equals(programLayout) || frameRate != programRate) {
            for (var state : programStates) {
                Arrays.fill(state, 0);
            }
        }
        programEstablished = true;
        programLayout = frameLayout;
        programRate = frameRate;
        programFrames = frames;
        mainStale = true;
        synthesizeProgram(outputBuffer(frameLayout, frameRate), false);
        return true;
    }

    /**
     * Conceals a multichannel frame in the format of the selected program, into {@link #buffer}.
     *
     * @return {@link #DECODED}
     */
    private int concealProgramFrame() {
        concealProgram();
        programFrame = true;
        return DECODED;
    }

    /**
     * Feeds silence through the filterbanks of the selected program, which lets the output of its last frame
     * decay.
     *
     * @return the output buffer, its frame count set
     */
    private AudioBuffer concealProgram() {
        concealedFrames++;
        var out = outputBuffer(programLayout, programRate);
        synthesizeProgram(out, true);
        out.setFrames(programFrames);
        return out;
    }

    /**
     * Synthesizes every channel of the selected program from the extension decoder, in pairs.
     *
     * @param out the output buffer, in the layout of the program
     * @param silent whether to feed silence instead of the frame's samples
     */
    private void synthesizeProgram(AudioBuffer out, boolean silent) {
        var channels = out.channels();
        var slots = programFrames == MAX_FRAME_SAMPLES ? 12 : 18;
        var parts = slots == 12 ? 3 : 1;
        for (var pair = 0; 2 * pair < channels; pair++) {
            var first = 2 * pair;
            var pairChannels = first + 1 < channels ? 2 : 1;
            var outLeft = out.plane(first);
            var outRight = out.plane(first + pairChannels - 1);
            for (var part = 0; part < parts; part++) {
                if (silent) {
                    Arrays.fill(extensionBuffer, 0);
                } else {
                    fillSlots(extensionBuffer, programSamples(first),
                            pairChannels == 2 ? programSamples(first + 1) : null, part * slots, slots);
                }
                synthesize(extensionBuffer, programStates[pair], slots, pairChannels, outLeft, outRight,
                        part * 32 * slots);
            }
        }
    }

    /**
     * Returns the subband samples of a channel of the selected program.
     *
     * @param channel the channel of the program's output
     * @return the samples, subband {@code sb}, time slot {@code t} at {@code sb * 36 + t}
     */
    private float[] programSamples(int channel) {
        if (program == Mp2Program.SECOND_STEREO) {
            return extension.channelSamples(extension.secondStereoChannel() + channel);
        }
        return extension.multilingualSamples(multilingualChannel == 0 ? channel : multilingualChannel - 1);
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
    private void silenceExtension(AudioBuffer out, int header) {
        var samples = MpegAudioHeader.samplesPerFrame(header);
        var parts = samples / 384;
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
            for (var part = 0; part < parts; part++) {
                Arrays.fill(extensionBuffer, 0);
                synthesize(extensionBuffer, extensionStates[pair], 12, pairChannels, outLeft, outRight, part * 384);
            }
        }
        if (lfePlane >= 0) {
            var plane = out.plane(lfePlane);
            for (var k = 0; k < samples / LFE_PERIOD; k++) {
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
        fillSlots(target, first, second, part * 12, 12);
    }

    /**
     * Copies a run of time slots of one or two channels from the extension's layout into a granule buffer.
     *
     * @param target the granule buffer, subband {@code sb}, slot {@code t} at {@code sb * 18 + t}, channel 1 at
     * index 576
     * @param first the first channel's samples, subband {@code sb}, slot {@code t} at {@code sb * 36 + t}
     * @param second the second channel's samples, or {@code null}
     * @param start the first time slot
     * @param slots the number of time slots, at most 18
     */
    private static void fillSlots(float[] target, float[] first, float[] second, int start, int slots) {
        Arrays.fill(target, 0);
        for (var sb = 0; sb < 32; sb++) {
            System.arraycopy(first, sb * 36 + start, target, sb * 18, slots);
            if (second != null) {
                System.arraycopy(second, sb * 36 + start, target, 576 + sb * 18, slots);
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
        // a Layer III frame output through its extension keeps its samples as twelve-slot groups
        var slots = layer == 3 && lastGoodSlots != 12 ? 18 : 12;
        var parts = MpegAudioHeader.samplesPerFrame(header) / (32 * slots);
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
        layer3Extension.reset();
        layer3NeedsLookahead = false;
        heldAligned = false;
        skippedProgramFrames = 0;
        for (var state : extensionStates) {
            Arrays.fill(state, 0);
        }
        extensionEstablished = false;
        lastLfe = 0;
        programEstablished = false;
        mainStale = false;
        for (var state : programStates) {
            Arrays.fill(state, 0);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Outputs the frames held for a multichannel extension: Layer I frames that do not make up a group, as their
     * base channels, and the last Layer III frame.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        ConsumerFailure.<AudioBuffer>run("MPEG audio flush", output, emit -> {
            releasePending(0, emit);
            releaseHeld(emit);
        });
    }
}
