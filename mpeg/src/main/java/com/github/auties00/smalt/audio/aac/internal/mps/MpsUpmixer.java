package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.OutputChannelPositions;

import java.util.Arrays;
import java.util.List;

/**
 * Upmixes the decoded downmix of an AAC stream with MPEG Surround, from the spatial audio side information of its
 * {@code sac_extension_data()} fill element payloads (ISO/IEC 14496-3 subclause 4.5.2.10) or of a separate MPEG
 * Surround elementary stream (object type 30).
 *
 * <p>Each raw data block may carry fragments of the next spatial frame ({@code ancStart} begins one, {@code ancStop}
 * ends one); a frame whose {@code ancType} is 1 starts with the {@code SpatialSpecificConfig} (after
 * {@code sacTimeAlignFlag} and {@code sacHeaderLen}), which configures or reconfigures the decoder as soon as its
 * bytes have arrived; a configuration with an unsupported tool leaves the downmix as the output, as a decoder without
 * MPEG Surround would. The upmix starts at the first frame after a configuration whose {@code bsIndependencyFlag} is
 * set. A spatial frame ({@code bsFrameLength}) lasts one raw data block, a whole number of them, or a whole fraction
 * of one; a frame whose length is neither leaves the downmix as the output.
 *
 * <p>A frame of one block upmixes the block that completes it. A frame of a {@code m}-th of a block is one of the
 * {@code m} frames each block carries, the first upmixing the first {@code m}-th of the block and so on, in the order
 * the frames are completed (embedded) or given (separate stream); the output does not lag the downmix, frames missing
 * from a block are upmixed with the parameters of the frame before them, and frames beyond the {@code m}-th are
 * dropped. The upmix starts at the first independent frame of a block; the part of that block before it is silent.
 * A frame of {@code n} blocks upmixes the {@code n}
 * blocks that end with the one completing it, for embedded side information, or that start with the block its access
 * unit comes with, for a separate elementary stream; its upmix is output over the {@code n} blocks after it is
 * complete, so the output lags the downmix by {@code n - 1} blocks while such a configuration is in use, the downmix
 * included: until the first frame is upmixed the output is the downmix of {@code n - 1} blocks earlier, silent for
 * the first {@code n - 1} blocks. {@link #delayedBlocks()} tells how many blocks of silence to feed after the last
 * one so that its samples come out; a change of configuration discards the blocks the old one delayed. Once the upmix
 * has started, frames are upmixed every {@code n} blocks: a frame that completes earlier waits for that block, the
 * last one replacing any before it, so that a stream whose blocks were dropped keeps its parameters running at the
 * cost of their timing, and frames missing when due are upmixed with the parameters of the last one.
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
    /**
     * The most spatial frames kept between blocks: a block of 2048 samples holds 64 frames of one 32-band slot.
     */
    private static final int MAX_QUEUED = 64;
    private static final ChannelLayout CENTER_7_1 = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER));

    private int sampleRate;
    private int blockLength;
    private MpsConfig config;
    private ChannelLayout layout;
    private int[] outputOrder = new int[0];
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
    private int headerEnd = -1;
    /**
     * The spatial frames taken and not yet read, in arrival order; while frames span one or more blocks, only the
     * last one is read.
     */
    private byte[][] queued = new byte[0][];
    private int[] queuedLengths = new int[0];
    private int queuedCount;
    private boolean frameSeparate;
    private int frameAge;

    /**
     * The number of blocks of a spatial frame, 0 while the frame neither lasts a whole number of blocks nor divides
     * a block.
     */
    private int blocksPerFrame;
    /**
     * The number of spatial frames of a block: more than 1 when a frame lasts a fraction of a block, 1 otherwise.
     */
    private int framesPerBlock = 1;
    /**
     * The number of queued frames the current block upmixes, while frames last a fraction of a block.
     */
    private int blockFrames;
    /**
     * The blocks taken since the last frame was upmixed; while the upmix runs, the block of that frame's upmix the
     * current block outputs.
     */
    private int sinceFrame;
    private boolean frameDue;
    private boolean outputUpmixed;
    /**
     * The blocks of output the ring still holds, which only the blocks after them push out.
     */
    private int delayed;
    /**
     * The downmix of the last {@link #blocksPerFrame} blocks, oldest at {@code ringStart}, by block, channel and
     * sample, as the caller gave it.
     */
    private float[][][] ring = new float[0][][];
    private float[][][][] ringReal = new float[0][][][];
    private float[][][][] ringImag = new float[0][][][];
    private boolean[] ringQmf = new boolean[0];
    private int ringStart;
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
     * Configures the upmixer with the {@code SpatialSpecificConfig} of an MPEG Surround elementary stream, dropping
     * the frames taken under the configuration before it.
     *
     * @param config the configuration
     */
    public void configure(MpsConfig config) {
        this.config = config;
        this.reconfigured = true;
        this.queuedCount = 0;
        if (config.arbitraryTreeActive()) {
            var speakers = OutputChannelPositions.speakers(config.arbitraryTreePositions());
            outputOrder = OutputChannelPositions.waveOrder(speakers);
            layout = OutputChannelPositions.layout(speakers, outputOrder);
            return;
        }
        var outputs = config.outputChannels();
        outputOrder = new int[outputs];
        for (var ch = 0; ch < outputs; ch++) {
            outputOrder[ch] = ch;
        }
        switch (config.treeConfig()) {
            case MpsConfig.TREE_7271, MpsConfig.TREE_7571 -> layout = CENTER_7_1;
            case MpsConfig.TREE_7272, MpsConfig.TREE_7572 -> {
                layout = ChannelLayout.SURROUND_7_1;
                outputOrder[4] = 6;
                outputOrder[5] = 7;
                outputOrder[6] = 4;
                outputOrder[7] = 5;
            }
            default -> layout = ChannelLayout.SURROUND_5_1;
        }
    }

    /**
     * Signals that blocks of the stream were lost, so their spatial frames never arrived.
     *
     * <p>The blocks that replace them are upmixed with the parameters of the last frame read, and so are the
     * blocks after them until a frame whose {@code bsIndependencyFlag} is set arrives: the frames in between are
     * coded as differences to the lost ones and are dropped, as is the frame whose fragments the lost blocks cut.
     */
    public void lose() {
        resynchronizing = framesRead;
        collecting = false;
    }

    /**
     * {@return the number of blocks to feed after the last one for all the samples to come out, 0 unless spatial
     * frames span several blocks}
     *
     * <p>Each of those blocks is silence, handed through {@link #prepare(int, int)} and
     * {@link #upmix(float[][], float[][])} or {@link #upmixQmf(float[][], float[][], float[][], float[][])} like any
     * other block.
     */
    public int delayedBlocks() {
        return reconfigured ? 0 : delayed;
    }

    /**
     * Records that the blocks of {@link #delayedBlocks()} were fed, so that it returns 0 until another block is taken.
     */
    public void drained() {
        delayed = 0;
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
     * Returns the layout of the upmix, in WAVE order.
     *
     * <p>The 5.1 and 7.1 trees output their layouts; with the arbitrary tree extension, the speaker of each channel
     * follows its {@code bsOutputChannelPosAT} (the position table of {@code bsOutputChannelPos}), a position taken
     * twice or without a speaker being {@link Speaker#UNKNOWN}.
     *
     * @return the layout
     * @throws IllegalStateException if no configuration is known
     */
    public ChannelLayout layout() {
        if (config == null) {
            throw new IllegalStateException("no MPEG Surround configuration");
        }
        return layout;
    }

    /**
     * Reads one {@code sac_extension_data()} payload of a fill element, after its extension type.
     *
     * @param reader the bit source, positioned after the extension type
     * @param bytes the number of bytes of the payload counted from its extension type ({@code cnt})
     * @throws MediaException.Malformed if a header in the payload is invalid
     */
    public void readExtension(AacBitReader reader, int bytes) {
        var type = reader.read(2);
        var start = reader.readFlag();
        var stop = reader.readFlag();
        if (start) {
            payloadLength = 0;
            payloadType = type;
            collecting = true;
            headerEnd = -1;
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
        if (payloadType == 1 && headerEnd < 0) {
            readHeader(false);
        }
        if (stop) {
            collecting = false;
            complete();
        }
    }

    /**
     * Takes the {@code SpatialFrame()} of a separate MPEG Surround elementary stream, whose spatial frame starts with
     * the current block.
     *
     * <p>An empty access unit carries no frame: the block is upmixed with the parameters of the last frame, and a
     * frame that spans several blocks and is not due yet stays in place. While a frame lasts a fraction of a block,
     * each access unit given before the block is prepared is the next frame of the block.
     *
     * @param data the access unit
     * @param offset the index of its first byte
     * @param length its number of bytes
     */
    public void addFrame(byte[] data, int offset, int length) {
        if (length == 0) {
            if (decoder != null && blocksPerFrame <= 1 && framesPerBlock == 1) {
                queuedCount = 0;
            }
            return;
        }
        storeFrame(data, offset, length);
        frameSeparate = true;
        frameAge = 0;
    }

    /**
     * Queues a spatial frame; past {@link #MAX_QUEUED} frames, the last one is replaced.
     */
    private void storeFrame(byte[] data, int offset, int length) {
        if (length == 0) {
            return;
        }
        if (queued.length == queuedCount && queuedCount < MAX_QUEUED) {
            var capacity = Math.min(MAX_QUEUED, Math.max(2, 2 * queuedCount));
            queued = Arrays.copyOf(queued, capacity);
            queuedLengths = Arrays.copyOf(queuedLengths, capacity);
        }
        var index = queuedCount < MAX_QUEUED ? queuedCount++ : queuedCount - 1;
        var frame = queued[index];
        if (frame == null || frame.length < length) {
            frame = queued[index] = new byte[Math.max(length, 64)];
        }
        System.arraycopy(data, offset, frame, 0, length);
        queuedLengths[index] = length;
        frameSeparate = false;
    }

    /**
     * Reads the header at the start of the payload being collected, once its bytes are in, and configures the
     * upmixer with it when it differs from the last one.
     *
     * @param complete whether the payload is complete, so that a header it cannot hold is malformed
     */
    private void readHeader(boolean complete) {
        if (payloadLength < 1 || (payload[0] & 0x7F) == 127 && payloadLength < 3) {
            if (complete) {
                throw new MediaException.Malformed("MPEG Surround header overruns its payload");
            }
            return;
        }
        var reader = payloadReader;
        reader.reset(payload, 0, payloadLength);
        var timeAlign = reader.readFlag();
        var headerLength = reader.read(7);
        if (headerLength == 127) {
            headerLength += reader.read(16);
        }
        var start = (int) (reader.bitPosition() >>> 3);
        var end = start + (long) headerLength + (timeAlign ? 2 : 0);
        if (end > payloadLength) {
            if (complete) {
                throw new MediaException.Malformed("MPEG Surround header overruns its payload");
            }
            return;
        }
        if (!Arrays.equals(payload, start, start + headerLength, header, 0, header.length)) {
            var candidate = Arrays.copyOfRange(payload, start, start + headerLength);
            try {
                configure(MpsConfig.read(new AacBitReader(candidate), 8L * headerLength));
            } catch (MediaException.Unsupported _) {
                unsupported();
            }
            // kept only once read, so that an invalid header fails every time rather than leaving the frames after
            // it to the configuration before it
            header = candidate;
        }
        headerEnd = (int) end;
    }

    private void complete() {
        var offset = 0;
        if (payloadType == 1) {
            if (headerEnd < 0) {
                readHeader(true);
            }
            if (config == null) {
                return;
            }
            offset = headerEnd;
        } else if (payloadType != 0) {
            return;
        }
        storeFrame(payload, offset, payloadLength - offset);
    }

    /**
     * Drops a configuration that uses an unsupported tool, so that the downmix stays the output, as for a decoder
     * without MPEG Surround.
     */
    private void unsupported() {
        config = null;
        decoder = null;
        framesRead = false;
        queuedCount = 0;
    }

    /**
     * Reads the spatial frame due with the current block, or keeps the parameters of the last one.
     *
     * <p>The decoder is (re)created when the configuration changes or the block format does. Until a frame whose
     * {@code bsIndependencyFlag} is set arrives after the decoder is created, frames are dropped; after
     * {@link #lose()}, frames are dropped the same way until such a frame, and the last parameters read stay in use.
     * A configuration with a tool the decoder does not support is dropped the same way. While frames last a fraction
     * of a block, the frames of the block are read as {@link #upmix(float[][], float[][])} reaches them.
     *
     * @param sampleRate the output sampling frequency of the core decoder
     * @param blockLength the number of samples per channel of the decoded block
     * @return {@code true} when the upmixer outputs the block: its upmix when {@link #upmixed()}, or else, while
     * spatial frames span several blocks, the delayed downmix
     * @throws MediaException.Malformed if the configuration or the frame is invalid
     */
    public boolean prepare(int sampleRate, int blockLength) {
        if (config == null) {
            queuedCount = 0;
            return false;
        }
        if (decoder == null || reconfigured || sampleRate != this.sampleRate || blockLength != this.blockLength) {
            this.sampleRate = sampleRate;
            this.blockLength = blockLength;
            this.reconfigured = false;
            this.framesRead = false;
            this.resynchronizing = false;
            this.decoder = null;
            this.delayed = 0;
            try {
                this.decoder = new MpsDecoder(config, sampleRate);
            } catch (MediaException.Unsupported _) {
                unsupported();
                return false;
            }
            var length = decoder.frameLength();
            if (length >= blockLength && length % blockLength == 0) {
                blocksPerFrame = length / blockLength;
                framesPerBlock = 1;
            } else if (length < blockLength && blockLength % length == 0 && blockLength / length <= MAX_QUEUED) {
                blocksPerFrame = 1;
                framesPerBlock = blockLength / length;
            } else {
                // TODO: a spatial frame that neither lasts a whole number of blocks nor divides one has its frame
                //  boundaries inside blocks; which block completes such a frame (ISO/IEC 23003-1 subclause 7.2.4,
                //  not available) is not known, so the downmix stays the output
                blocksPerFrame = 0;
                framesPerBlock = 1;
            }
            sinceFrame = 0;
            if (blocksPerFrame != 0) {
                allocate();
            }
        }
        if (blocksPerFrame == 0) {
            queuedCount = 0;
            return false;
        }
        if (framesPerBlock > 1) {
            return prepareFractional();
        }
        var available = queuedCount > 0;
        if (available && frameSeparate && frameAge < blocksPerFrame - 1) {
            frameAge++;
            available = false;
        }
        var frame = available ? queuedCount - 1 : -1;
        sinceFrame = Math.min(sinceFrame + 1, blocksPerFrame);
        if (framesRead && sinceFrame < blocksPerFrame) {
            frameDue = false;
        } else {
            var started = framesRead;
            if (available) {
                queuedCount = 0;
                readFrame(frame);
            } else if (framesRead) {
                decoder.repeatFrame();
            }
            frameDue = framesRead;
            if (frameDue || started) {
                sinceFrame = 0;
            }
        }
        outputUpmixed = framesRead;
        return blocksPerFrame > 1 || framesRead;
    }

    /**
     * Prepares a block of several spatial frames: it is upmixed once the upmix has started or when one of its frames
     * is independent. Its frames leave the queue here, so that an invalid one fails only its own block.
     */
    private boolean prepareFractional() {
        blockFrames = Math.min(queuedCount, framesPerBlock);
        queuedCount = 0;
        var start = framesRead;
        for (var f = 0; f < blockFrames && !start; f++) {
            frameReader.reset(queued[f], 0, queuedLengths[f]);
            start = decoder.independentFrame(frameReader);
        }
        if (!start) {
            blockFrames = 0;
        }
        outputUpmixed = start;
        return start;
    }

    /**
     * Reads a queued frame, or keeps the last parameters when it depends on frames the decoder did not read.
     */
    private void readFrame(int index) {
        var frame = queued[index];
        var length = queuedLengths[index];
        frameReader.reset(frame, 0, length);
        // a decoder that joins the stream, after a seek or at a later configuration, starts at the first frame that
        // does not depend on the frames before it
        if (framesRead && !resynchronizing || decoder.independentFrame(frameReader)) {
            frameReader.reset(frame, 0, length);
            decoder.readFrame(frameReader);
            framesRead = true;
            resynchronizing = false;
        } else if (framesRead) {
            decoder.repeatFrame();
        }
    }

    /**
     * Reads the frame of the {@code f}-th fraction of a block of several frames, or repeats the last parameters when
     * the block lacks it.
     *
     * @return whether the fraction is upmixed
     */
    private boolean advanceFraction(int f) {
        if (f < blockFrames) {
            readFrame(f);
        } else if (framesRead) {
            decoder.repeatFrame();
        }
        return framesRead;
    }

    /**
     * {@return whether the block {@link #prepare(int, int)} took outputs an upmix, in {@link #layout()}, rather than
     * the delayed downmix}
     */
    public boolean upmixed() {
        return outputUpmixed;
    }

    private void allocate() {
        var inputs = decoder.inputChannels();
        var outputs = decoder.outputChannels();
        var length = Math.max(decoder.frameLength(), blockLength);
        if (input.length != inputs || input[0].length != length) {
            input = new float[inputs][length];
        }
        if (output.length != outputs || output[0].length != length) {
            output = new float[outputs][length];
        }
        if (qmfReal.length != inputs || qmfReal[0].length != decoder.slots()) {
            qmfReal = new float[inputs][decoder.slots()][];
            qmfImag = new float[inputs][decoder.slots()][];
        }
        var slots = decoder.slots() / blocksPerFrame;
        var bands = decoder.qmfBands();
        if (ring.length != blocksPerFrame || ring[0].length != inputs || ring[0][0].length != blockLength
                || ringReal[0][0].length != slots || ringReal[0][0][0].length != bands) {
            ring = new float[blocksPerFrame][inputs][blockLength];
            ringReal = new float[blocksPerFrame][inputs][slots][bands];
            ringImag = new float[blocksPerFrame][inputs][slots][bands];
            ringQmf = new boolean[blocksPerFrame];
        } else {
            for (var block : ring) {
                for (var channel : block) {
                    Arrays.fill(channel, 0.0f);
                }
            }
        }
        ringStart = 0;
    }

    /**
     * Upmixes one block of time samples.
     *
     * <p>When {@link #upmixed()} is {@code false}, {@code out} is left alone and {@code downmix} receives the
     * downmix of {@code n - 1} blocks earlier.
     *
     * @param downmix the downmix channels, in the order the tree takes them (left, right, center, LFE, left
     *                surround, right surround for the 5.1 downmix)
     * @param out receives the upmix channels in the order of {@link #layout()}
     */
    public void upmix(float[][] downmix, float[][] out) {
        if (framesPerBlock > 1) {
            scaleInput(downmix, 0);
            var length = decoder.frameLength();
            for (var f = 0; f < framesPerBlock; f++) {
                if (advanceFraction(f)) {
                    decoder.decode(input, f * length, output, f * length);
                } else {
                    silence(f * length, length);
                }
            }
            emit(out, 0);
            return;
        }
        if (blocksPerFrame == 1) {
            scaleInput(downmix, 0);
            decoder.decode(input, 0, output, 0);
            emit(out, 0);
            return;
        }
        var block = push(downmix);
        ringQmf[block] = false;
        if (frameDue) {
            decodeFrame(false);
        }
        finish(downmix, out);
    }

    /**
     * Upmixes one block of QMF slots of a 64-band spectral band replication decoder, in its scale (a full-scale
     * 16-bit signal spanning {@code [-32768, 32768]}).
     *
     * <p>When {@link #upmixed()} is {@code false}, {@code out} is left alone and {@code downmix} receives the
     * downmix of {@code n - 1} blocks earlier. A frame of several blocks is upmixed from QMF slots when all its blocks
     * came as QMF slots, and from time samples otherwise.
     *
     * @param real the real parts of each downmix channel, 64 per slot
     * @param imag the imaginary parts
     * @param downmix the time samples of the downmix channels
     * @param out receives the upmix channels in the order of {@link #layout()}
     */
    public void upmixQmf(float[][] real, float[][] imag, float[][] downmix, float[][] out) {
        var inputs = decoder.inputChannels();
        var bands = decoder.qmfBands();
        if (framesPerBlock > 1) {
            var slots = decoder.slots();
            var length = decoder.frameLength();
            for (var f = 0; f < framesPerBlock; f++) {
                if (!advanceFraction(f)) {
                    silence(f * length, length);
                    continue;
                }
                for (var ch = 0; ch < inputs; ch++) {
                    for (var ts = 0; ts < slots; ts++) {
                        var rowReal = ringReal[0][ch][ts];
                        var rowImag = ringImag[0][ch][ts];
                        System.arraycopy(real[ch], (f * slots + ts) * 64, rowReal, 0, bands);
                        System.arraycopy(imag[ch], (f * slots + ts) * 64, rowImag, 0, bands);
                        qmfReal[ch][ts] = rowReal;
                        qmfImag[ch][ts] = rowImag;
                    }
                }
                decoder.decodeQmf(qmfReal, qmfImag, output, f * length);
            }
            emit(out, 0);
            return;
        }
        if (blocksPerFrame == 1) {
            var slots = decoder.slots();
            for (var ch = 0; ch < inputs; ch++) {
                for (var ts = 0; ts < slots; ts++) {
                    var rowReal = ringReal[0][ch][ts];
                    var rowImag = ringImag[0][ch][ts];
                    System.arraycopy(real[ch], ts * 64, rowReal, 0, bands);
                    System.arraycopy(imag[ch], ts * 64, rowImag, 0, bands);
                    qmfReal[ch][ts] = rowReal;
                    qmfImag[ch][ts] = rowImag;
                }
            }
            decoder.decodeQmf(qmfReal, qmfImag, output, 0);
            emit(out, 0);
            return;
        }
        var block = push(downmix);
        var slots = decoder.slots() / blocksPerFrame;
        for (var ch = 0; ch < inputs; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                System.arraycopy(real[ch], ts * 64, ringReal[block][ch][ts], 0, bands);
                System.arraycopy(imag[ch], ts * 64, ringImag[block][ch][ts], 0, bands);
            }
        }
        ringQmf[block] = true;
        if (frameDue) {
            var qmf = true;
            for (var taken : ringQmf) {
                qmf &= taken;
            }
            decodeFrame(qmf);
        }
        finish(downmix, out);
    }

    /**
     * {@return whether QMF input of 64 bands fits the decoder and its blocks}
     */
    public boolean acceptsQmf() {
        return decoder != null && decoder.qmfBands() == 64 && blocksPerFrame != 0
                && decoder.slots() % blocksPerFrame == 0;
    }

    /**
     * Adds a block to the downmix of the last frame's worth of blocks.
     *
     * @return the ring slot of the block
     */
    private int push(float[][] downmix) {
        delayed = blocksPerFrame - 1;
        var block = ringStart;
        ringStart = (ringStart + 1) % blocksPerFrame;
        var slot = ring[block];
        for (var ch = 0; ch < slot.length; ch++) {
            System.arraycopy(downmix[ch], 0, slot[ch], 0, blockLength);
        }
        return block;
    }

    /**
     * Upmixes the frame of the last {@link #blocksPerFrame} blocks.
     */
    private void decodeFrame(boolean qmf) {
        if (qmf) {
            var inputs = decoder.inputChannels();
            var slots = decoder.slots() / blocksPerFrame;
            for (var j = 0; j < blocksPerFrame; j++) {
                var block = (ringStart + j) % blocksPerFrame;
                for (var ch = 0; ch < inputs; ch++) {
                    for (var ts = 0; ts < slots; ts++) {
                        qmfReal[ch][j * slots + ts] = ringReal[block][ch][ts];
                        qmfImag[ch][j * slots + ts] = ringImag[block][ch][ts];
                    }
                }
            }
            decoder.decodeQmf(qmfReal, qmfImag, output, 0);
            return;
        }
        for (var j = 0; j < blocksPerFrame; j++) {
            scaleInput(ring[(ringStart + j) % blocksPerFrame], j * blockLength);
        }
        decoder.decode(input, 0, output, 0);
    }

    private void finish(float[][] downmix, float[][] out) {
        if (outputUpmixed) {
            emit(out, sinceFrame * blockLength);
            return;
        }
        var oldest = ring[ringStart];
        for (var ch = 0; ch < oldest.length; ch++) {
            System.arraycopy(oldest[ch], 0, downmix[ch], 0, blockLength);
        }
    }

    private void scaleInput(float[][] downmix, int offset) {
        for (var ch = 0; ch < input.length; ch++) {
            var source = downmix[ch];
            var target = input[ch];
            for (var i = 0; i < blockLength; i++) {
                target[offset + i] = source[i] * SCALE;
            }
        }
    }

    private void silence(int offset, int length) {
        for (var channel : output) {
            Arrays.fill(channel, offset, offset + length, 0.0f);
        }
    }

    private void emit(float[][] out, int offset) {
        for (var ch = 0; ch < output.length; ch++) {
            var target = out[outputOrder[ch]];
            var source = output[ch];
            for (var i = 0; i < blockLength; i++) {
                target[i] = source[offset + i] / SCALE;
            }
        }
    }
}
