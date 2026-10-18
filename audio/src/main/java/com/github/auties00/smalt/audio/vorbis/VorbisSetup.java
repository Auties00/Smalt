package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Holds the decoder configuration of one Vorbis stream, parsed from its three header packets
 * (Vorbis I section 4.2).
 *
 * <p>Parsing rejects every condition the specification calls undecodable: a wrong packet type or
 * signature, end-of-packet in the identification or setup header, a zero channel count or rate,
 * block sizes outside 64..8192 or out of order, a cleared framing bit, over- or underspecified
 * Huffman trees, references to missing codebooks, floors, residues or mappings, invalid coupling
 * channels, nonzero reserved fields, and VQ reads from codebooks without values. Reserved type
 * values (Vorbis version, time, floor, residue, mapping, window, transform and lookup types) are
 * reported as {@link MediaException.Unsupported}; everything else as
 * {@link MediaException.Malformed}.
 */
final class VorbisSetup {
    /**
     * The smallest block size Vorbis I allows.
     */
    static final int MIN_BLOCKSIZE = 64;

    /**
     * The largest block size Vorbis I allows.
     */
    static final int MAX_BLOCKSIZE = 8192;

    /**
     * The number of audio channels.
     */
    final int channels;

    /**
     * The sampling rate in hertz.
     */
    final int sampleRate;

    /**
     * The short ({@code [0]}) and long ({@code [1]}) block sizes.
     */
    final int[] blocksizes;

    /**
     * The codebooks.
     */
    final Codebook[] codebooks;

    /**
     * The floor configurations.
     */
    final Floor[] floors;

    /**
     * The residue configurations.
     */
    final Residue[] residues;

    /**
     * The mapping configurations.
     */
    final Mapping[] mappings;

    /**
     * The mode configurations.
     */
    final Mode[] modes;

    /**
     * The width of the mode number at the start of every audio packet.
     */
    final int modeBits;

    /**
     * Constructs a configuration from its parsed parts.
     *
     * @param channels the channel count
     * @param sampleRate the sampling rate
     * @param blocksizes the block sizes
     * @param codebooks the codebooks of the setup header, by index
     * @param floors the floor configurations, by index
     * @param residues the residue configurations, by index
     * @param mappings the channel mappings, by index
     * @param modes the modes, by mode number
     */
    private VorbisSetup(int channels, int sampleRate, int[] blocksizes, Codebook[] codebooks, Floor[] floors,
                        Residue[] residues, Mapping[] mappings, Mode[] modes) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.blocksizes = blocksizes;
        this.codebooks = codebooks;
        this.floors = floors;
        this.residues = residues;
        this.mappings = mappings;
        this.modes = modes;
        this.modeBits = Ilog.ilog(modes.length - 1);
    }

    /**
     * Parses the three header packets stored Xiph-laced, as in Matroska {@code CodecPrivate}:
     * one byte holding the packet count minus one, the Xiph lacing sizes of all packets but the
     * last, then the packets.
     *
     * {@snippet :
     * // 0x02 | lace(len(id)) | lace(len(comment)) | id | comment | setup
     * // lace(300) = 0xFF 0x2D
     * }
     *
     * @param config the laced header packets
     * @param budget the allocation budget of the stream
     * @return the configuration
     * @throws MediaException.Malformed     if the lacing or a header is invalid
     * @throws MediaException.Unsupported   if a header uses a reserved type
     * @throws MediaException.LimitExceeded if the stream exceeds the limits
     */
    static VorbisSetup parse(byte[] config, AllocationBudget budget) {
        var packets = splitXiphLacing(config);
        return parse(packets[0], packets[1], packets[2], budget);
    }

    /**
     * Splits Xiph-laced Vorbis header packets.
     *
     * @param config the laced header packets
     * @return the identification, comment and setup packets
     * @throws MediaException.Malformed if the lacing is invalid or does not hold three packets
     */
    static byte[][] splitXiphLacing(byte[] config) {
        if (config.length == 0) {
            throw new MediaException.Malformed("empty Vorbis codec configuration");
        }
        var count = (config[0] & 0xFF) + 1;
        if (count != 3) {
            throw new MediaException.Malformed("Vorbis codec configuration holds " + count + " packets instead of 3");
        }
        var packets = XiphLacing.unlace(config);
        if (packets == null) {
            throw new MediaException.Malformed("invalid Vorbis codec configuration lacing");
        }
        return packets;
    }

    /**
     * Parses the three header packets.
     *
     * @param identification the identification header packet
     * @param comment the comment header packet
     * @param setup the setup header packet
     * @param budget the allocation budget of the stream
     * @return the configuration
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if a header uses a reserved type
     * @throws MediaException.LimitExceeded if the stream exceeds the limits
     */
    static VorbisSetup parse(byte[] identification, byte[] comment, byte[] setup, AllocationBudget budget) {
        var reader = header(identification, 1, "identification");
        var version = reader.read(32);
        var channels = reader.read(8);
        var sampleRate = reader.read(32) & 0xFFFFFFFFL;
        reader.skip(96);
        var exponent0 = reader.read(4);
        var exponent1 = reader.read(4);
        var framing = reader.readBit();
        checkNotTruncated(reader);
        if (version != 0) {
            throw new MediaException.Unsupported("vorbis version " + Integer.toUnsignedString(version),
                    "Vorbis version " + Integer.toUnsignedString(version) + " is not Vorbis I");
        }
        LimitChecks.checkChannels(budget.limits(), channels);
        if (sampleRate == 0) {
            throw new MediaException.Malformed("Vorbis stream with a zero sample rate");
        }
        if (sampleRate > Integer.MAX_VALUE) {
            throw new MediaException.Unsupported("vorbis sample rate " + sampleRate,
                    "Vorbis sample rate " + sampleRate + " Hz is not representable");
        }
        var blocksize0 = 1 << exponent0;
        var blocksize1 = 1 << exponent1;
        if (blocksize0 < MIN_BLOCKSIZE || blocksize1 > MAX_BLOCKSIZE || blocksize0 > blocksize1) {
            throw new MediaException.Malformed("invalid Vorbis block sizes " + blocksize0 + "/" + blocksize1);
        }
        if (framing == 0) {
            throw new MediaException.Malformed("Vorbis identification header framing bit is not set");
        }
        header(comment, 3, "comment");
        var blocksizes = new int[]{blocksize0, blocksize1};
        reader = header(setup, 5, "setup");
        var codebooks = new Codebook[reader.read(8) + 1];
        for (var i = 0; i < codebooks.length; i++) {
            codebooks[i] = Codebook.read(reader, budget);
        }
        var times = reader.read(6) + 1;
        for (var i = 0; i < times; i++) {
            var type = reader.read(16);
            if (type != 0) {
                throw new MediaException.Unsupported("vorbis time type " + type, "Vorbis time type " + type + " is reserved");
            }
        }
        var floors = new Floor[reader.read(6) + 1];
        for (var i = 0; i < floors.length; i++) {
            var type = reader.read(16);
            floors[i] = switch (type) {
                case 0 -> Floor0.read(reader, codebooks, blocksizes, budget);
                case 1 -> Floor1.read(reader, codebooks);
                default -> throw new MediaException.Unsupported("vorbis floor type " + type,
                        "Vorbis floor type " + type + " is reserved");
            };
        }
        var residues = new Residue[reader.read(6) + 1];
        for (var i = 0; i < residues.length; i++) {
            var type = reader.read(16);
            if (type > 2) {
                throw new MediaException.Unsupported("vorbis residue type " + type,
                        "Vorbis residue type " + type + " is reserved");
            }
            residues[i] = Residue.read(reader, type, codebooks);
        }
        var mappings = new Mapping[reader.read(6) + 1];
        for (var i = 0; i < mappings.length; i++) {
            mappings[i] = readMapping(reader, channels, floors.length, residues.length);
        }
        var modes = new Mode[reader.read(6) + 1];
        for (var i = 0; i < modes.length; i++) {
            var longBlock = reader.readBit() == 1;
            var windowType = reader.read(16);
            var transformType = reader.read(16);
            var mapping = reader.read(8);
            checkNotTruncated(reader);
            if (windowType != 0 || transformType != 0) {
                throw new MediaException.Unsupported("vorbis window/transform type " + windowType + "/" + transformType,
                        "Vorbis window type " + windowType + " or transform type " + transformType + " is reserved");
            }
            if (mapping >= mappings.length) {
                throw new MediaException.Malformed("Vorbis mode references missing mapping " + mapping);
            }
            modes[i] = new Mode(longBlock, mapping);
        }
        if (reader.readBit() != 1) {
            throw new MediaException.Malformed("Vorbis setup header framing bit is not set");
        }
        checkNotTruncated(reader);
        return new VorbisSetup(channels, (int) sampleRate, blocksizes, codebooks, floors, residues, mappings, modes);
    }

    /**
     * Reads one mapping configuration (Vorbis I section 4.2.4, "Mappings").
     *
     * @param reader the setup header reader, positioned at the mapping type
     * @param channels the channel count
     * @param floors the number of floors
     * @param residues the number of residues
     * @return the mapping
     * @throws MediaException.Malformed if the mapping is invalid
     * @throws MediaException.Unsupported if the mapping type is reserved
     */
    private static Mapping readMapping(BitReader reader, int channels, int floors, int residues) {
        var type = reader.read(16);
        if (type != 0) {
            throw new MediaException.Unsupported("vorbis mapping type " + type, "Vorbis mapping type " + type + " is reserved");
        }
        var submaps = reader.readBit() == 1 ? reader.read(4) + 1 : 1;
        var steps = reader.readBit() == 1 ? reader.read(8) + 1 : 0;
        var magnitude = new int[steps];
        var angle = new int[steps];
        var bits = Ilog.ilog(channels - 1);
        for (var i = 0; i < steps; i++) {
            magnitude[i] = reader.read(bits);
            angle[i] = reader.read(bits);
            if (magnitude[i] == angle[i] || magnitude[i] >= channels || angle[i] >= channels) {
                throw new MediaException.Malformed("invalid Vorbis coupling step " + magnitude[i] + "/" + angle[i]);
            }
        }
        if (reader.read(2) != 0) {
            throw new MediaException.Malformed("nonzero Vorbis mapping reserved field");
        }
        var mux = new int[channels];
        if (submaps > 1) {
            for (var i = 0; i < channels; i++) {
                mux[i] = reader.read(4);
                if (mux[i] >= submaps) {
                    throw new MediaException.Malformed("Vorbis channel references missing submap " + mux[i]);
                }
            }
        }
        var submapFloor = new int[submaps];
        var submapResidue = new int[submaps];
        for (var i = 0; i < submaps; i++) {
            reader.read(8);
            submapFloor[i] = reader.read(8);
            submapResidue[i] = reader.read(8);
            if (submapFloor[i] >= floors || submapResidue[i] >= residues) {
                throw new MediaException.Malformed("Vorbis submap references missing floor or residue");
            }
        }
        checkNotTruncated(reader);
        return new Mapping(submaps, mux, submapFloor, submapResidue, magnitude, angle);
    }

    /**
     * Validates the common header fields and returns a reader positioned after them.
     *
     * @param packet the header packet
     * @param type the expected packet type
     * @param name the header name used in diagnostics
     * @return a reader positioned after the {@code "vorbis"} signature
     * @throws MediaException.Malformed if the type or signature does not match
     */
    private static BitReader header(byte[] packet, int type, String name) {
        if (packet.length < 7 || packet[0] != type || packet[1] != 'v' || packet[2] != 'o' || packet[3] != 'r'
                || packet[4] != 'b' || packet[5] != 'i' || packet[6] != 's') {
            throw new MediaException.Malformed("invalid Vorbis " + name + " header");
        }
        return new BitReader(packet, 7, packet.length - 7);
    }

    /**
     * Rejects a header whose reads ran past its end.
     *
     * @param reader the header reader
     * @throws MediaException.Malformed if end-of-packet was reached
     */
    static void checkNotTruncated(BitReader reader) {
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated Vorbis header");
        }
    }
}
