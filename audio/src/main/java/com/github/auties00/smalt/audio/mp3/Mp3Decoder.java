/* Ports minimp3 (minimp3.h). By lieff; CC0 1.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

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
 * <p>Layer III main data that begins in earlier frames (the bit reservoir) is carried across
 * packets, so packets must be fed in stream order. A frame whose reservoir bytes are missing (the
 * first frames after starting mid-stream) and a frame whose side information or allocation is
 * inconsistent decode to silence of the full frame length, which keeps the output aligned with
 * the packet timestamps; an inconsistent frame also resets the decoder state. A change of
 * version, layer or sample rate between frames resets the filterbank and reservoir, as does
 * starting a new stream. CRC-16 words are skipped, not verified.
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
     * The resource bounds the decoder enforces.
     */
    private final MediaLimits limits;

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
        this(track, options.limits(), SynthesisKernel.create());
    }

    /**
     * Creates a decoder under {@link MediaLimits#DEFAULT} that uses a specific synthesis kernel;
     * tests compare the kernels with it.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param kernel the synthesis kernel
     */
    Mp3Decoder(AudioTrack track, SynthesisKernel kernel) {
        this(track, MediaLimits.DEFAULT, kernel);
    }

    /**
     * Creates a decoder that uses specific limits and a specific synthesis kernel.
     *
     * @param track the track; its codec is {@link Codec#MP3} or {@link Codec#MP2}
     * @param limits the resource limits
     * @param kernel the synthesis kernel
     */
    private Mp3Decoder(AudioTrack track, MediaLimits limits, SynthesisKernel kernel) {
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.MP3 && track.codec() != Codec.MP2) {
            throw new IllegalArgumentException("not an MPEG audio track: " + track.codec());
        }
        this.layer3 = new Layer3(kernel);
        this.layer12 = new Layer12();
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.frameBits = new BitReader();
        this.grbuf = new float[2 * 576];
        this.qmfState = new float[15 * 64];
        this.lins = new float[(18 + 15) * 64];
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
        ConsumerFailure.<AudioBuffer>run("MPEG audio packet", output, emit -> decodePacket(packet.data(), emit));
    }

    /**
     * Decodes every whole frame of a packet.
     *
     * @param data the packet payload
     * @param output receives one buffer per frame
     * @throws MediaException.Malformed if the payload does not start with a valid frame header
     */
    private void decodePacket(byte[] data, Consumer<AudioBuffer> output) {
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
            decodeFrame(header, data, offset, Math.min(size, length - offset), output);
            offset += size;
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
     * @param output receives the frame's buffer
     * @throws MediaException.LimitExceeded if the frame has more channels than the limits allow
     */
    private void decodeFrame(int header, byte[] data, int offset, int length, Consumer<AudioBuffer> output) {
        if (previousHeader != 0 && !MpegAudioHeader.isCompatible(previousHeader, header)) {
            reset();
        }
        previousHeader = header;
        var channels = MpegAudioHeader.channels(header);
        LimitChecks.checkChannels(limits, channels);
        var rate = MpegAudioHeader.sampleRate(header);
        var samples = MpegAudioHeader.samplesPerFrame(header);
        var out = outputBuffer(channels, rate);
        var left = out.plane(0);
        var right = out.plane(channels - 1);
        frameBits.init(data, offset + MpegAudioHeader.SIZE, length - MpegAudioHeader.SIZE);
        if (MpegAudioHeader.hasCrc(header)) {
            frameBits.read(16);
        }
        var decoded = MpegAudioHeader.layer(header) == 3
                ? decodeLayer3(header, channels, left, right)
                : decodeLayer12(header, channels, left, right);
        if (!decoded) {
            Arrays.fill(left, 0, samples, 0);
            Arrays.fill(right, 0, samples, 0);
        }
        out.setFrames(samples);
        output.accept(out);
    }

    /**
     * Decodes the granules of a Layer III frame.
     *
     * @param header the frame header
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @return {@code false} when the frame decodes to silence
     */
    private boolean decodeLayer3(int header, int channels, float[] left, float[] right) {
        var mainDataBegin = layer3.readSideInfo(frameBits, header);
        if (mainDataBegin < 0 || frameBits.overrun()) {
            reset();
            return false;
        }
        var complete = layer3.restoreReservoir(frameBits, mainDataBegin);
        if (complete) {
            var granules = MpegAudioHeader.isMpeg1(header) ? 2 : 1;
            for (var granule = 0; granule < granules; granule++) {
                Arrays.fill(grbuf, 0);
                layer3.decodeGranule(header, granule * channels, channels, grbuf);
                synthesize(18, channels, left, right, granule * 576);
            }
        }
        layer3.saveReservoir();
        return complete;
    }

    /**
     * Decodes the groups of a Layer I or Layer II frame.
     *
     * @param header the frame header
     * @param channels the number of channels
     * @param left the left output plane
     * @param right the right output plane, or {@code left} for mono
     * @return {@code false} when the frame decodes to silence
     */
    private boolean decodeLayer12(int header, int channels, float[] left, float[] right) {
        layer12.readScaleInfo(header, frameBits);
        var groupSize = MpegAudioHeader.layer(header) | 1;
        Arrays.fill(grbuf, 0);
        var slot = 0;
        var output = 0;
        for (var part = 0; part < 3; part++) {
            slot += layer12.dequantizeGranule(grbuf, slot, frameBits, groupSize);
            if (slot == 12) {
                slot = 0;
                layer12.applyScalefactors(grbuf, part);
                synthesize(12, channels, left, right, output);
                Arrays.fill(grbuf, 0);
                output += 384;
            }
            if (frameBits.overrun()) {
                reset();
                return false;
            }
        }
        return true;
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
        for (var ch = 0; ch < channels; ch++) {
            kernel.dct(grbuf, 576 * ch, slots);
        }
        System.arraycopy(qmfState, 0, lins, 0, 15 * 64);
        var rightBase = 576 * (channels - 1);
        for (var i = 0; i < slots; i += 2) {
            kernel.window(grbuf, i, rightBase + i, left, right, offset + 32 * i, lins, i * 64);
        }
        if (channels == 1) {
            for (var i = 0; i < 15 * 64; i += 2) {
                qmfState[i] = lins[slots * 64 + i];
            }
        } else {
            System.arraycopy(lins, slots * 64, qmfState, 0, 15 * 64);
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
        if (buffer == null || buffer.channels() != channels || buffer.sampleRate() != rate) {
            buffer = new AudioBuffer(channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO, rate, MAX_FRAME_SAMPLES);
        }
        if (!started) {
            started = true;
            sampleRate = rate;
            layout = buffer.layout();
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
