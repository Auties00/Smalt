package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.internal.tags.TsDescriptors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reassembles and parses the program specific information of a transport stream: the program
 * association table and the program map tables (ISO/IEC 13818-1 clause 2.4.4).
 */
final class Psi {
    /**
     * The {@code table_id} of a program association section.
     */
    static final int PAT_TABLE = 0x00;

    /**
     * The {@code table_id} of a program map section.
     */
    static final int PMT_TABLE = 0x02;

    /**
     * The {@code table_id} of an SCTE 35 {@code splice_info_section}.
     */
    static final int SPLICE_INFO_TABLE = 0xFC;

    /**
     * The largest section, header included: a private section's 12-bit length plus its header.
     */
    private static final int MAX_SECTION = 4096;

    /**
     * The largest number of registration descriptors kept per descriptor loop.
     */
    private static final int MAX_REGISTRATIONS = 8;

    /**
     * The registration {@code format_identifier} of ID3 timed metadata ({@code "ID3 "}).
     */
    static final int ID3 = fourCc("ID3 ");

    /**
     * The registration {@code format_identifier} of AC-3 ({@code "AC-3"}).
     */
    static final int AC3 = fourCc("AC-3");

    /**
     * The registration {@code format_identifier} of E-AC-3 ({@code "EAC3"}).
     */
    static final int EAC3 = fourCc("EAC3");

    /**
     * The registration {@code format_identifier} of Opus ({@code "Opus"}).
     */
    static final int OPUS = fourCc("Opus");

    /**
     * The registration {@code format_identifier} of HEVC ({@code "HEVC"}).
     */
    static final int HEVC = fourCc("HEVC");

    /**
     * The registration {@code format_identifier} of Blu-ray and AVCHD programs ({@code "HDMV"}).
     */
    static final int HDMV = fourCc("HDMV");

    /**
     * The registration {@code format_identifier} of AV1 ({@code "AV01"}).
     */
    static final int AV1 = fourCc("AV01");

    /**
     * The registration {@code format_identifier} of SMPTE ST 302 AES3 data ({@code "BSSD"}).
     */
    static final int AES3 = fourCc("BSSD");

    /**
     * The registration {@code format_identifier} of SCTE 35 programs ({@code "CUEI"}).
     */
    static final int CUEI = fourCc("CUEI");

    private Psi() {
        throw new AssertionError();
    }

    /**
     * Returns the big-endian value of a four-character code.
     *
     * @param code the four ASCII characters
     * @return the code as an integer
     */
    static int fourCc(String code) {
        var bytes = code.getBytes(StandardCharsets.US_ASCII);
        return (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    /**
     * Describes one entry of a program association table.
     *
     * @param number the {@code program_number}; zero names the network information PID
     * @param pid the PID of the program's map table
     */
    record PatEntry(int number, int pid) {
    }

    /**
     * Describes an elementary stream as its program map entry declares it.
     *
     * @param pid the PID of the stream
     * @param trackId the identifier of the track the stream feeds: its PID, unless the stream continues a track
     * first carried on another PID or that PID is taken by another track
     * @param streamType the {@code stream_type}
     * @param registrations the {@code format_identifier}s of the stream's registration descriptors, in order
     * @param programRegistrations the {@code format_identifier}s of the program's registration descriptors, in
     * order
     * @param language the ISO 639-2 language code, or {@code null}
     * @param ac3Descriptor whether a DVB AC-3 descriptor (tag {@code 0x6A}) is present
     * @param eac3Descriptor whether a DVB enhanced AC-3 descriptor (tag {@code 0x7A}) is present
     * @param opusChannels the {@code channel_config_code} of a DVB Opus extension descriptor, or
     * {@code -1}
     * @param id3Metadata whether a metadata descriptor (tag {@code 0x26}) announces ID3
     * @param dvbSubtitles whether a DVB subtitling descriptor (tag {@code 0x59}) is present
     * @param teletext whether a DVB teletext descriptor (tag {@code 0x56}) is present
     * @param audioType the {@code audio_type} of the first language of the ISO 639 language descriptor, or
     * {@code -1}
     * @param name the first string of an ATSC component name descriptor (tag {@code 0xA3}), or {@code null}
     */
    record StreamEntry(int pid, int trackId, int streamType, List<Integer> registrations,
                       List<Integer> programRegistrations, String language, boolean ac3Descriptor,
                       boolean eac3Descriptor, int opusChannels, boolean id3Metadata, boolean dvbSubtitles,
                       boolean teletext, int audioType, String name) {
        /**
         * Returns whether a registration descriptor of the stream carries an identifier.
         *
         * @param identifier the {@code format_identifier}
         * @return {@code true} when one does
         */
        boolean registered(int identifier) {
            return registrations.contains(identifier);
        }

        /**
         * Returns whether a registration descriptor of the program carries an identifier.
         *
         * @param identifier the {@code format_identifier}
         * @return {@code true} when one does
         */
        boolean programRegistered(int identifier) {
            return programRegistrations.contains(identifier);
        }

        /**
         * Returns this entry feeding another track.
         *
         * @param id the track identifier
         * @return the entry
         */
        StreamEntry withTrackId(int id) {
            return new StreamEntry(pid, id, streamType, registrations, programRegistrations, language, ac3Descriptor,
                    eac3Descriptor, opusChannels, id3Metadata, dvbSubtitles, teletext, audioType, name);
        }

        /**
         * Returns whether another entry declares the same coding: stream type, registrations, coding descriptors
         * and language, whatever its PID.
         *
         * @param other the entry
         * @return {@code true} for the same coding
         */
        boolean sameCoding(StreamEntry other) {
            return streamType == other.streamType && registrations.equals(other.registrations)
                    && programRegistrations.equals(other.programRegistrations)
                    && Objects.equals(language, other.language) && ac3Descriptor == other.ac3Descriptor
                    && eac3Descriptor == other.eac3Descriptor && opusChannels == other.opusChannels
                    && id3Metadata == other.id3Metadata && dvbSubtitles == other.dvbSubtitles
                    && teletext == other.teletext;
        }
    }

    /**
     * Describes a program map section.
     *
     * @param number the {@code program_number}
     * @param version the {@code version_number}
     * @param pcrPid the {@code PCR_PID}
     * @param streams the elementary streams, in section order
     */
    record PmtSection(int number, int version, int pcrPid, List<StreamEntry> streams) {
        /**
         * Returns whether another section maps the same streams: the same PIDs with the same codings, in order.
         *
         * @param other the section
         * @return {@code true} when nothing but the version or the clock PID differs
         */
        boolean sameStreams(PmtSection other) {
            if (streams.size() != other.streams.size()) {
                return false;
            }
            for (var index = 0; index < streams.size(); index++) {
                var mine = streams.get(index);
                var theirs = other.streams.get(index);
                if (mine.pid() != theirs.pid() || !mine.sameCoding(theirs)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Returns whether a long-form section applies now rather than announcing the next version.
     *
     * @param section the whole section
     * @return {@code true} when its {@code current_next_indicator} is set
     */
    static boolean isCurrent(byte[] section) {
        return section.length > 5 && (section[5] & 0x01) != 0;
    }

    /**
     * Parses a program association section.
     *
     * @param section the whole section, CRC included and verified
     * @return the entries, in section order
     */
    static List<PatEntry> parsePat(byte[] section) {
        var entries = new ArrayList<PatEntry>();
        var end = section.length - 4;
        for (var position = 8; position + 4 <= end; position += 4) {
            var number = (section[position] & 0xFF) << 8 | (section[position + 1] & 0xFF);
            var pid = (section[position + 2] & 0x1F) << 8 | (section[position + 3] & 0xFF);
            entries.add(new PatEntry(number, pid));
        }
        return entries;
    }

    /**
     * Parses a program map section.
     *
     * @param section the whole section, CRC included and verified
     * @return the description, or {@code null} when the section is truncated
     */
    static PmtSection parsePmt(byte[] section) {
        if (section.length < 16) {
            return null;
        }
        var number = (section[3] & 0xFF) << 8 | (section[4] & 0xFF);
        var version = (section[5] >> 1) & 0x1F;
        var pcrPid = (section[8] & 0x1F) << 8 | (section[9] & 0xFF);
        var infoLength = (section[10] & 0x0F) << 8 | (section[11] & 0xFF);
        var end = section.length - 4;
        var position = 12;
        if (position + infoLength > end) {
            return null;
        }
        var programDescriptors = new Descriptors(section, position, infoLength);
        position += infoLength;
        var streams = new ArrayList<StreamEntry>();
        while (position + 5 <= end) {
            var type = section[position] & 0xFF;
            var pid = (section[position + 1] & 0x1F) << 8 | (section[position + 2] & 0xFF);
            var length = (section[position + 3] & 0x0F) << 8 | (section[position + 4] & 0xFF);
            position += 5;
            if (position + length > end) {
                return null;
            }
            var descriptors = new Descriptors(section, position, length);
            position += length;
            streams.add(new StreamEntry(pid, pid, type, List.copyOf(descriptors.registrations),
                    List.copyOf(programDescriptors.registrations), descriptors.language, descriptors.ac3,
                    descriptors.eac3, descriptors.opusChannels, descriptors.id3, descriptors.subtitles,
                    descriptors.teletext, descriptors.audioType, descriptors.name));
        }
        return new PmtSection(number, version, pcrPid, streams);
    }

    /**
     * Holds what this demuxer reads from a descriptor loop (ISO/IEC 13818-1 clause 2.6 and ETSI
     * EN 300 468 clause 6).
     */
    private static final class Descriptors {
        /**
         * The registration {@code format_identifier}s, in order.
         */
        final List<Integer> registrations = new ArrayList<>(1);

        /**
         * The ISO 639-2 language code, or {@code null}.
         */
        String language;

        /**
         * Whether an AC-3 descriptor is present.
         */
        boolean ac3;

        /**
         * Whether an enhanced AC-3 descriptor is present.
         */
        boolean eac3;

        /**
         * The Opus {@code channel_config_code}, or {@code -1}.
         */
        int opusChannels = -1;

        /**
         * Whether a metadata descriptor announces ID3.
         */
        boolean id3;

        /**
         * Whether a subtitling descriptor is present.
         */
        boolean subtitles;

        /**
         * Whether a teletext descriptor is present.
         */
        boolean teletext;

        /**
         * The {@code audio_type} of the language descriptor, or {@code -1}.
         */
        int audioType = -1;

        /**
         * The component name, or {@code null}.
         */
        String name;

        /**
         * Reads a descriptor loop.
         *
         * @param data the section
         * @param offset the index of the first descriptor
         * @param length the length of the loop
         */
        Descriptors(byte[] data, int offset, int length) {
            var end = offset + length;
            var position = offset;
            while (position + 2 <= end) {
                var tag = data[position] & 0xFF;
                var size = data[position + 1] & 0xFF;
                var body = position + 2;
                if (body + size > end) {
                    break;
                }
                switch (tag) {
                    case 0x05 -> {
                        if (size >= 4 && registrations.size() < MAX_REGISTRATIONS) {
                            registrations.add((data[body] & 0xFF) << 24 | (data[body + 1] & 0xFF) << 16
                                    | (data[body + 2] & 0xFF) << 8 | (data[body + 3] & 0xFF));
                        }
                    }
                    case 0x0A -> {
                        if (size >= 3 && isLetters(data, body)) {
                            language = new String(data, body, 3, StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT);
                        }
                        if (size >= 4) {
                            audioType = data[body + 3] & 0xFF;
                        }
                    }
                    case TsDescriptors.COMPONENT_NAME -> name = TsDescriptors.multipleString(data, body, size);
                    case 0x26 -> id3 |= metadataIsId3(data, body, size);
                    case 0x56 -> teletext = true;
                    case 0x59 -> subtitles = true;
                    case 0x6A -> ac3 = true;
                    case 0x7A -> eac3 = true;
                    case 0x7F -> {
                        if (size >= 2 && (data[body] & 0xFF) == 0x80) {
                            opusChannels = data[body + 1] & 0xFF;
                        }
                    }
                    default -> {
                    }
                }
                position = body + size;
            }
        }

        /**
         * Returns whether three bytes are ASCII letters.
         *
         * @param data the bytes
         * @param offset the index of the first byte
         * @return {@code true} for three letters
         */
        private static boolean isLetters(byte[] data, int offset) {
            for (var index = offset; index < offset + 3; index++) {
                var value = data[index] | 0x20;
                if (value < 'a' || value > 'z') {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns whether a metadata descriptor (ISO/IEC 13818-1 clause 2.6.60) announces ID3
         * metadata.
         *
         * @param data the section
         * @param offset the index of the descriptor body
         * @param size the body length
         * @return {@code true} when the metadata format identifier is {@code "ID3 "}
         */
        private static boolean metadataIsId3(byte[] data, int offset, int size) {
            if (size < 3) {
                return false;
            }
            var position = offset + 2;
            if ((data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0xFF) {
                position += 4;
            }
            if (position >= offset + size) {
                return false;
            }
            var format = data[position] & 0xFF;
            if (format != 0xFF) {
                return false;
            }
            if (position + 5 > offset + size) {
                return false;
            }
            var identifier = (data[position + 1] & 0xFF) << 24 | (data[position + 2] & 0xFF) << 16
                    | (data[position + 3] & 0xFF) << 8 | (data[position + 4] & 0xFF);
            return identifier == ID3;
        }
    }

    /**
     * Reassembles the sections carried on one PID (ISO/IEC 13818-1 clause 2.4.4.1).
     *
     * <p>A section starts at the {@code pointer_field} of a packet with the payload unit start
     * indicator and may span packets; several sections may follow each other in a packet, until
     * {@code 0xFF} stuffing. A section whose CRC fails, or that a continuity error interrupts, is
     * dropped. Instances are not thread-safe.
     */
    static final class SectionAssembler {
        /**
         * The bytes of the section being collected.
         */
        private final byte[] buffer = new byte[MAX_SECTION];

        /**
         * Whether short-form sections that end with a {@code CRC_32} (SCTE 35 splice information) are accepted
         * besides long-form ones.
         */
        private final boolean shortForm;

        /**
         * Constructs an assembler of long-form sections.
         */
        SectionAssembler() {
            this(false);
        }

        /**
         * Constructs an assembler.
         *
         * @param shortForm whether short-form sections ending with a {@code CRC_32} are accepted too
         */
        SectionAssembler(boolean shortForm) {
            this.shortForm = shortForm;
        }

        /**
         * The number of bytes collected.
         */
        private int length;

        /**
         * Whether a section is being collected.
         */
        private boolean collecting;

        /**
         * Receives the payload of one transport packet of the PID.
         *
         * @param data the packet bytes
         * @param offset the index of the payload
         * @param end the index after the payload
         * @param unitStart whether the payload unit start indicator is set
         * @param continuous whether the packet follows the previous one without a continuity error
         * @param sections receives every complete section whose CRC holds
         */
        void accept(byte[] data, int offset, int end, boolean unitStart, boolean continuous, Consumer<byte[]> sections) {
            if (!continuous) {
                collecting = false;
            }
            var position = offset;
            if (unitStart) {
                if (position >= end) {
                    return;
                }
                var pointer = data[position++] & 0xFF;
                if (collecting) {
                    append(data, position, Math.min(end, position + pointer), sections);
                }
                collecting = false;
                position += pointer;
                if (position >= end) {
                    return;
                }
                collecting = true;
                length = 0;
            } else if (!collecting) {
                return;
            }
            while (collecting && position < end) {
                if (length == 0 && (data[position] & 0xFF) == 0xFF) {
                    collecting = false;
                    return;
                }
                position = append(data, position, end, sections);
                if (!collecting && position < end) {
                    collecting = true;
                    length = 0;
                }
            }
        }

        /**
         * Appends bytes to the section being collected and completes it when its length is
         * reached.
         *
         * @param data the bytes
         * @param from the index of the first byte
         * @param end the index after the last available byte
         * @param sections receives the section when complete
         * @return the index after the bytes consumed
         */
        private int append(byte[] data, int from, int end, Consumer<byte[]> sections) {
            var position = from;
            while (position < end) {
                var target = length < 3 ? 3 : 3 + ((buffer[1] & 0x0F) << 8 | (buffer[2] & 0xFF));
                if (target > MAX_SECTION || (length >= 3 && target <= length)) {
                    collecting = false;
                    return end;
                }
                var take = Math.min(target - length, end - position);
                System.arraycopy(data, position, buffer, length, take);
                length += take;
                position += take;
                if (length == target && length > 3) {
                    collecting = false;
                    var section = Arrays.copyOf(buffer, length);
                    length = 0;
                    if (isValid(section)) {
                        sections.accept(section);
                    }
                    return position;
                }
            }
            return position;
        }

        /**
         * Returns whether a section is long enough and its CRC holds.
         *
         * @param section a whole section, its {@code CRC_32} included
         * @return {@code true} for a usable section
         */
        private boolean isValid(byte[] section) {
            if ((section[1] & 0x80) == 0 && !shortForm) {
                return false;
            }
            return section.length >= 12 && Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length) == 0;
        }
    }
}
