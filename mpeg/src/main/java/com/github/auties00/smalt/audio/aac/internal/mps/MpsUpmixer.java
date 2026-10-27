package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;
import java.util.List;

/**
 * Upmixes the decoded downmix of an AAC stream with MPEG Surround, from the spatial audio side information of its
 * {@code sac_extension_data()} fill element payloads (ISO/IEC 14496-3 subclause 4.5.2.10) or of a separate MPEG
 * Surround elementary stream (object type 30).
 *
 * <p>Each raw data block may carry fragments of the next spatial frame ({@code ancStart} begins one, {@code ancStop}
 * ends one); a frame whose {@code ancType} is 1 starts with the {@code SpatialSpecificConfig} (after
 * {@code sacTimeAlignFlag} and {@code sacHeaderLen}), which configures or reconfigures the decoder; a configuration
 * with an unsupported tool leaves the downmix as the output, as a decoder without MPEG Surround would. The upmix
 * starts at the first frame after a configuration whose {@code bsIndependencyFlag} is set, and covers spatial frames
 * as long as a raw data block. A block without a complete frame upmixes with the parameters of the last one.
 * Samples are taken and returned in the decoder's float scale, a full-scale 16-bit signal spanning {@code [-1, 1]}.
 *
 * <p>Instances are not thread-safe.
 */
public final class MpsUpmixer {
    /**
     * The extension type of a fill element holding spatial audio coding side information ({@code EXT_SAC_DATA}).
     */
    public static final int EXT_SAC_DATA = 12;

    private static final float SCALE = 32768.0f;
    private static final ChannelLayout CENTER_7_1 = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER));

    private int sampleRate;
    private int blockLength;
    private MpsConfig config;
    private boolean reconfigured;
    private boolean framesRead;
    private boolean resynchronizing;
    private MpsDecoder decoder;
    private final AacBitReader payloadReader = new AacBitReader(new byte[0]);
    private final AacBitReader frameReader = new AacBitReader(new byte[0]);
    private byte[] header = new byte[0];
    private byte[] payload = new byte[256];
    private int payloadLength;
    private int payloadType = -1;
    private boolean collecting;
    private byte[] frame;
    private int frameLength;
    private boolean frameReady;
    private float[][] input = new float[0][];
    private float[][] output = new float[0][];
    private float[][][] qmfReal = new float[0][][];
    private float[][][] qmfImag = new float[0][][];

    /**
     * Constructs an upmixer that waits for the configuration of the stream.
     */
    public MpsUpmixer() {
    }

    /**
     * Configures the upmixer with the {@code SpatialSpecificConfig} of an MPEG Surround elementary stream.
     *
     * @param config the configuration
     */
    public void configure(MpsConfig config) {
        this.config = config;
        this.reconfigured = true;
    }

    /**
     * Signals that blocks of the stream were lost, so their spatial frames never arrived.
     *
     * <p>The blocks that replace them are upmixed with the parameters of the last frame read, and so are the
     * blocks after them until a frame whose {@code bsIndependencyFlag} is set arrives: the frames in between are
     * coded as differences to the lost ones and are dropped.
     */
    public void lose() {
        resynchronizing = framesRead;
    }

    /**
     * {@return whether a configuration is known}
     */
    public boolean configured() {
        return config != null;
    }

    /**
     * {@return the number of downmix channels the configuration takes, or 0 before any configuration}
     */
    public int inputChannels() {
        return config == null ? 0 : config.inputChannels();
    }

    /**
     * {@return the layout of the upmix, in WAVE order}
     *
     * @throws IllegalStateException if no configuration is known
     */
    public ChannelLayout layout() {
        if (config == null) {
            throw new IllegalStateException("no MPEG Surround configuration");
        }
        return switch (config.treeConfig()) {
            case MpsConfig.TREE_7271, MpsConfig.TREE_7571 -> CENTER_7_1;
            case MpsConfig.TREE_7272, MpsConfig.TREE_7572 -> ChannelLayout.SURROUND_7_1;
            default -> ChannelLayout.SURROUND_5_1;
        };
    }

    /**
     * Reads one {@code sac_extension_data()} payload of a fill element, after its extension type.
     *
     * @param reader the bit source, positioned after the extension type
     * @param bytes the number of bytes of the payload counted from its extension type ({@code cnt})
     */
    public void readExtension(AacBitReader reader, int bytes) {
        var type = reader.read(2);
        var start = reader.readFlag();
        var stop = reader.readFlag();
        if (start) {
            payloadLength = 0;
            payloadType = type;
            collecting = true;
        }
        var count = bytes - 1;
        if (!collecting || payloadLength + count > (1 << 20)) {
            collecting = false;
            reader.skip(8L * count);
            return;
        }
        if (payloadLength + count > payload.length) {
            payload = Arrays.copyOf(payload, Math.max(2 * payload.length, payloadLength + count));
        }
        for (var i = 0; i < count; i++) {
            payload[payloadLength++] = (byte) reader.read(8);
        }
        if (stop) {
            collecting = false;
            complete();
        }
    }

    /**
     * Takes the {@code SpatialFrame()} of a separate MPEG Surround elementary stream for the current block.
     *
     * @param data the access unit
     * @param offset the index of its first byte
     * @param length its number of bytes
     */
    public void addFrame(byte[] data, int offset, int length) {
        if (frame == null || frame.length < length) {
            frame = new byte[Math.max(length, 64)];
        }
        System.arraycopy(data, offset, frame, 0, length);
        frameLength = length;
        frameReady = length > 0;
    }

    private void complete() {
        var reader = payloadReader;
        reader.reset(payload, 0, payloadLength);
        if (payloadType == 1) {
            var timeAlign = reader.readFlag();
            var headerLength = reader.read(7);
            if (headerLength == 127) {
                headerLength += reader.read(16);
            }
            var start = (int) (reader.bitPosition() >>> 3);
            if (start + (long) headerLength > payloadLength) {
                throw new MediaException.Malformed("MPEG Surround header overruns its payload");
            }
            if (!Arrays.equals(payload, start, start + headerLength, header, 0, header.length)) {
                header = Arrays.copyOfRange(payload, start, start + headerLength);
                try {
                    configure(MpsConfig.read(new AacBitReader(header), 8L * headerLength));
                } catch (MediaException.Unsupported _) {
                    unsupported();
                }
            }
            if (config == null) {
                return;
            }
            reader.skip(8L * headerLength);
            if (timeAlign) {
                reader.read(16);
            }
        } else if (payloadType != 0) {
            return;
        }
        var offset = (int) (reader.bitPosition() >>> 3);
        addFrame(payload, offset, payloadLength - offset);
    }

    /**
     * Drops a configuration that uses an unsupported tool, so that the downmix stays the output, as for a decoder
     * without MPEG Surround.
     */
    private void unsupported() {
        config = null;
        decoder = null;
        framesRead = false;
        frameReady = false;
    }

    /**
     * Reads the spatial frame the current block completed, or keeps the parameters of the last one.
     *
     * <p>The decoder is (re)created when the configuration changes or the block format does; a spatial frame
     * must last as long as a block. Until a frame whose {@code bsIndependencyFlag} is set arrives after the decoder
     * is created, frames are dropped and the downmix stays the output; after {@link #lose()}, frames are dropped
     * the same way until such a frame, and the last parameters read stay in use. A configuration with a tool the
     * decoder does not support is dropped the same way.
     *
     * @param sampleRate the output sampling frequency of the core decoder
     * @param blockLength the number of samples per channel of the decoded block
     * @return {@code true} when the block is upmixed
     * @throws MediaException.Malformed if the configuration or the frame is invalid
     */
    public boolean prepare(int sampleRate, int blockLength) {
        if (config == null) {
            frameReady = false;
            return false;
        }
        if (decoder == null || reconfigured || sampleRate != this.sampleRate || blockLength != this.blockLength) {
            this.sampleRate = sampleRate;
            this.blockLength = blockLength;
            this.reconfigured = false;
            this.framesRead = false;
            this.resynchronizing = false;
            try {
                this.decoder = new MpsDecoder(config, sampleRate);
            } catch (MediaException.Unsupported _) {
                unsupported();
                return false;
            }
        }
        // TODO: a spatial frame that spans several raw data blocks (bsFrameLength longer than a block) needs the
        //  downmix of all of them before it can be upmixed; such configurations leave the downmix as the output.
        if (decoder.frameLength() != blockLength) {
            frameReady = false;
            return false;
        }
        if (frameReady) {
            frameReady = false;
            frameReader.reset(frame, 0, frameLength);
            // a decoder that joins the stream, after a seek or at a later configuration, starts at the first frame
            // that does not depend on the frames before it
            if (framesRead && !resynchronizing || decoder.independentFrame(frameReader)) {
                frameReader.reset(frame, 0, frameLength);
                decoder.readFrame(frameReader);
                framesRead = true;
                resynchronizing = false;
            } else if (framesRead) {
                decoder.repeatFrame();
            }
        } else if (framesRead) {
            decoder.repeatFrame();
        }
        return framesRead;
    }

    private void ensureBuffers() {
        var inputs = decoder.inputChannels();
        var outputs = decoder.outputChannels();
        if (input.length != inputs || (inputs > 0 && input[0].length != blockLength)) {
            input = new float[inputs][blockLength];
        }
        if (output.length != outputs || output[0].length != blockLength) {
            output = new float[outputs][blockLength];
        }
    }

    /**
     * Upmixes one block of time samples.
     *
     * @param downmix the downmix channels, in the order the tree takes them (left, right, center, LFE, left
     *                surround, right surround for the 5.1 downmix)
     * @param out receives the upmix channels in the order of {@link #layout()}
     */
    public void upmix(float[][] downmix, float[][] out) {
        ensureBuffers();
        for (var ch = 0; ch < input.length; ch++) {
            var source = downmix[ch];
            var target = input[ch];
            for (var i = 0; i < blockLength; i++) {
                target[i] = source[i] * SCALE;
            }
        }
        decoder.decode(input, 0, output, 0);
        emit(out);
    }

    /**
     * Upmixes one block of QMF slots of a 64-band spectral band replication decoder, in its scale (a full-scale
     * 16-bit signal spanning {@code [-32768, 32768]}).
     *
     * @param real the real parts of each downmix channel, 64 per slot
     * @param imag the imaginary parts
     * @param out receives the upmix channels in the order of {@link #layout()}
     */
    public void upmixQmf(float[][] real, float[][] imag, float[][] out) {
        ensureBuffers();
        var inputs = decoder.inputChannels();
        var slots = decoder.slots();
        var bands = decoder.qmfBands();
        if (qmfReal.length != inputs || qmfReal[0].length != slots) {
            qmfReal = new float[inputs][slots][bands];
            qmfImag = new float[inputs][slots][bands];
        }
        for (var ch = 0; ch < inputs; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                System.arraycopy(real[ch], ts * 64, qmfReal[ch][ts], 0, bands);
                System.arraycopy(imag[ch], ts * 64, qmfImag[ch][ts], 0, bands);
            }
        }
        decoder.decodeQmf(qmfReal, qmfImag, output, 0);
        emit(out);
    }

    /**
     * {@return whether QMF input of 64 bands fits the decoder}
     */
    public boolean acceptsQmf() {
        return decoder != null && decoder.qmfBands() == 64;
    }

    private void emit(float[][] out) {
        var tree = config.treeConfig();
        var rear = tree == MpsConfig.TREE_7272 || tree == MpsConfig.TREE_7572;
        for (var ch = 0; ch < output.length; ch++) {
            var target = rear && ch >= 4 ? out[ch < 6 ? ch + 2 : ch - 2] : out[ch];
            var source = output[ch];
            for (var i = 0; i < blockLength; i++) {
                target[i] = source[i] / SCALE;
            }
        }
    }
}
