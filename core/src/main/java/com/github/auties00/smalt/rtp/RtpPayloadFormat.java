package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;

import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.StringJoiner;

/**
 * Describes an RTP payload format as SDP signals it: the encoding name and clock rate of the {@code a=rtpmap}
 * attribute, its optional channel count, and the format parameters of the {@code a=fmtp} attribute.
 *
 * <p>The formats {@link RtpPacketizer} and {@link RtpDepacketizer} implement, by encoding name (case-insensitive):
 * <table class="striped">
 * <caption>Supported payload formats</caption>
 * <thead>
 * <tr><th scope="col">Encoding name</th><th scope="col">Codec</th><th scope="col">Specification</th>
 * <th scope="col">Parameters read</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>{@code H264}</td><td>{@link Codec#H264}</td><td>RFC 6184</td><td>{@code packetization-mode},
 * {@code sprop-parameter-sets}, {@code profile-level-id}, {@code sprop-interleaving-depth}</td></tr>
 * <tr><td>{@code VP8}</td><td>{@link Codec#VP8}</td><td>RFC 7741</td><td>none</td></tr>
 * <tr><td>{@code VP9}</td><td>{@link Codec#VP9}</td><td>RFC 9628</td><td>none</td></tr>
 * <tr><td>{@code AV1}</td><td>{@link Codec#AV1}</td><td>AOMedia RTP Payload Format for AV1</td><td>none</td></tr>
 * <tr><td>{@code opus}</td><td>{@link Codec#OPUS}</td><td>RFC 7587</td><td>{@code sprop-stereo}</td></tr>
 * <tr><td>{@code AMR}, {@code AMR-WB}</td><td>{@link Codec#AMR_NB}, {@link Codec#AMR_WB}</td><td>RFC 4867</td>
 * <td>{@code octet-align}, {@code crc}, {@code robust-sorting}, {@code interleaving}</td></tr>
 * <tr><td>{@code mpeg4-generic}</td><td>{@link Codec#AAC}</td><td>RFC 3640</td><td>{@code streamType},
 * {@code mode}, {@code config}, {@code sizeLength}, {@code indexLength}, {@code indexDeltaLength},
 * {@code CTSDeltaLength}, {@code DTSDeltaLength}, {@code randomAccessIndication}, {@code streamStateIndication},
 * {@code auxiliaryDataSizeLength}, {@code constantSize}, {@code constantDuration}, {@code maxDisplacement}</td>
 * </tr>
 * </tbody>
 * </table>
 * Other encoding names can be described, but packetizers and depacketizers reject them.
 *
 * <p>Parameter names are case-insensitive, as media type parameter names are; {@link #parameters()} holds them in
 * lowercase, in the order they were set. Values are kept as written.
 *
 * <p>Instances are immutable and thread-safe; create them with {@link #builder(String, int)}, a preset such as
 * {@link #h264(int)}, {@link #forTrack(Track)} or {@link #fromSdp(String, String)}.
 */
public final class RtpPayloadFormat {
    /**
     * The clock rate of every video payload format of this package, 90 kHz.
     */
    public static final int VIDEO_CLOCK_RATE = 90_000;

    private final String encodingName;
    private final int clockRate;
    private final int channels;
    private final Map<String, String> parameters;

    private RtpPayloadFormat(Builder builder) {
        this.encodingName = builder.encodingName;
        this.clockRate = builder.clockRate;
        this.channels = builder.channels;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.parameters));
    }

    /**
     * Returns a builder of a format with no channel count and no parameters.
     *
     * @param encodingName the encoding name of the {@code a=rtpmap} attribute; the known names of the class
     * documentation are stored in their registered spelling
     * @param clockRate the RTP timestamp clock rate in hertz, positive
     * @return a new builder
     * @throws IllegalArgumentException if {@code encodingName} is empty or holds a slash or white space, or
     * {@code clockRate} is not positive
     */
    public static Builder builder(String encodingName, int clockRate) {
        return new Builder().encodingName(encodingName).clockRate(clockRate);
    }

    /**
     * Returns a builder initialized with this format.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.encodingName = encodingName;
        builder.clockRate = clockRate;
        builder.channels = channels;
        builder.parameters.putAll(parameters);
        return builder;
    }

    /**
     * Returns the H.264 format of RFC 6184 with a packetization mode and no parameter sets.
     *
     * <p>Mode 0 sends every NAL unit in its own packet, mode 1 adds aggregation (STAP-A) and fragmentation (FU-A),
     * and mode 2 is the interleaved mode (STAP-B, MTAP16, MTAP24, FU-A and FU-B, ordered by decoding order number).
     *
     * @param packetizationMode the {@code packetization-mode}, from 0 to 2
     * @return the format
     * @throws IllegalArgumentException if {@code packetizationMode} is not from 0 to 2
     */
    public static RtpPayloadFormat h264(int packetizationMode) {
        if (packetizationMode < 0 || packetizationMode > 2) {
            throw new IllegalArgumentException("packetization mode " + packetizationMode + " is not from 0 to 2");
        }
        return builder("H264", VIDEO_CLOCK_RATE).parameter("packetization-mode", Integer.toString(packetizationMode))
                .build();
    }

    /**
     * {@return the VP8 format of RFC 7741}
     */
    public static RtpPayloadFormat vp8() {
        return builder("VP8", VIDEO_CLOCK_RATE).build();
    }

    /**
     * {@return the VP9 format of RFC 9628}
     */
    public static RtpPayloadFormat vp9() {
        return builder("VP9", VIDEO_CLOCK_RATE).build();
    }

    /**
     * {@return the AV1 format of the AOMedia RTP payload specification}
     */
    public static RtpPayloadFormat av1() {
        return builder("AV1", VIDEO_CLOCK_RATE).build();
    }

    /**
     * Returns the Opus format of RFC 7587, whose clock rate is always 48 kHz and channel count always 2.
     *
     * @param stereo whether the sender sends and the receiver prefers stereo, which sets {@code stereo} and
     * {@code sprop-stereo} to 1
     * @return the format
     */
    public static RtpPayloadFormat opus(boolean stereo) {
        var builder = builder("opus", 48_000).channels(2);
        if (stereo) {
            builder.parameter("stereo", "1").parameter("sprop-stereo", "1");
        }
        return builder.build();
    }

    /**
     * Returns the single-channel AMR or AMR-WB format of RFC 4867, in the bandwidth-efficient or octet-aligned mode.
     *
     * @param wideband whether the codec is AMR-WB (16 kHz) rather than AMR (8 kHz)
     * @param octetAligned whether the session uses the octet-aligned mode, which sets {@code octet-align} to 1
     * @return the format
     */
    public static RtpPayloadFormat amr(boolean wideband, boolean octetAligned) {
        var builder = builder(wideband ? "AMR-WB" : "AMR", wideband ? 16_000 : 8_000).channels(1);
        if (octetAligned) {
            builder.parameter("octet-align", "1");
        }
        return builder.build();
    }

    /**
     * Returns the {@code AAC-hbr} mode of RFC 3640 for an AAC stream: 13-bit sizes, 3-bit indices, AUs of up to
     * 8191 bytes that may be fragmented.
     *
     * @param audioSpecificConfig the {@code AudioSpecificConfig} of the stream, which gives the clock rate and the
     * channel count
     * @return the format
     * @throws MediaException.Malformed if the configuration cannot be parsed
     */
    public static RtpPayloadFormat aacHbr(byte[] audioSpecificConfig) {
        return aac(audioSpecificConfig, "AAC-hbr", 13, 3);
    }

    /**
     * Returns the {@code AAC-lbr} mode of RFC 3640 for an AAC stream: 6-bit sizes, 2-bit indices, AUs of at most
     * 63 bytes that are never fragmented.
     *
     * @param audioSpecificConfig the {@code AudioSpecificConfig} of the stream, which gives the clock rate and the
     * channel count
     * @return the format
     * @throws MediaException.Malformed if the configuration cannot be parsed
     */
    public static RtpPayloadFormat aacLbr(byte[] audioSpecificConfig) {
        return aac(audioSpecificConfig, "AAC-lbr", 6, 2);
    }

    private static RtpPayloadFormat aac(byte[] config, String mode, int sizeLength, int indexLength) {
        var parsed = AudioConfigs.parseAac(config);
        var builder = builder("mpeg4-generic", parsed.outputRate())
                .parameter("streamtype", "5")
                .parameter("profile-level-id", "1")
                .parameter("mode", mode)
                .parameter("sizelength", Integer.toString(sizeLength))
                .parameter("indexlength", Integer.toString(indexLength))
                .parameter("indexdeltalength", Integer.toString(indexLength))
                .parameter("config", HexFormat.of().formatHex(config));
        if (parsed.channels() > 0) {
            builder.channels(parsed.channels());
        }
        return builder.build();
    }

    /**
     * Returns the payload format that carries a track, with the parameters its codec configuration gives.
     *
     * <p>H.264 gets packetization mode 1 with the {@code profile-level-id} and {@code sprop-parameter-sets} of its
     * {@code avcC} record; Opus gets {@code sprop-stereo} for two channels; AMR and AMR-WB get the octet-aligned
     * mode with the track's channel count; AAC gets {@link #aacHbr(byte[])}; VP8, VP9 and AV1 get no parameters.
     *
     * @param track the track whose codec and configuration give the format
     * @return the format
     * @throws MediaException.Unsupported if no payload format of this package carries the track's codec
     * @throws MediaException.Malformed if the track's codec configuration is missing where needed or cannot be
     * parsed
     */
    public static RtpPayloadFormat forTrack(Track track) {
        return switch (track.codec()) {
            case H264 -> {
                var builder = h264(1).toBuilder();
                var config = track.codecConfig();
                if (config.isPresent() && config.get().length > 0) {
                    var avc = config.get();
                    var parsed = NalRecords.parseAvcStrict(avc);
                    if (avc.length >= 4) {
                        builder.parameter("profile-level-id", HexFormat.of().formatHex(avc, 1, 4));
                    }
                    if (!parsed.parameterSets().isEmpty()) {
                        var sets = new StringJoiner(",");
                        for (var set : parsed.parameterSets()) {
                            sets.add(Base64.getEncoder().encodeToString(set));
                        }
                        builder.parameter("sprop-parameter-sets", sets.toString());
                    }
                }
                yield builder.build();
            }
            case VP8 -> vp8();
            case VP9 -> vp9();
            case AV1 -> av1();
            case OPUS -> opus(track instanceof AudioTrack audio && audio.layout().map(l -> l.channels() == 2)
                    .orElse(false));
            case AMR_NB, AMR_WB -> {
                var builder = amr(track.codec() == Codec.AMR_WB, true).toBuilder();
                if (track instanceof AudioTrack audio) {
                    audio.layout().ifPresent(layout -> builder.channels(layout.channels()));
                }
                yield builder.build();
            }
            case AAC -> aacHbr(track.codecConfig().filter(config -> config.length > 0)
                    .orElseThrow(() -> new MediaException.Malformed("AAC track has no AudioSpecificConfig")));
            default -> throw new MediaException.Unsupported(track.codec().toString(), "no RTP payload format for "
                    + track.codec());
        };
    }

    /**
     * Parses the values of an SDP {@code a=rtpmap} and {@code a=fmtp} attribute pair.
     *
     * <p>Each value may be given with or without its {@code a=rtpmap:} or {@code a=fmtp:} prefix and its payload
     * type number: {@code "H264/90000"}, {@code "96 H264/90000"} and {@code "a=rtpmap:96 H264/90000"} are the same.
     * The format parameters are {@code name=value} pairs separated by semicolons; white space around names and
     * values is ignored, and a parameter without {@code =} gets an empty value.
     *
     * @param rtpmap the {@code a=rtpmap} value, {@code <encoding name>/<clock rate>[/<channels>]}
     * @param fmtp the {@code a=fmtp} value, or {@code null} or an empty string for none
     * @return the format
     * @throws MediaException.Malformed if the {@code rtpmap} value is not well formed, or a format parameter name is
     * not a token
     */
    public static RtpPayloadFormat fromSdp(String rtpmap, String fmtp) {
        var map = stripAttribute(rtpmap, "a=rtpmap:");
        var parts = map.split("/", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty()) {
            throw new MediaException.Malformed("rtpmap value \"" + rtpmap + "\" is not <name>/<rate>[/<channels>]");
        }
        Builder builder;
        try {
            builder = builder(parts[0], Integer.parseInt(parts[1].strip()));
            if (parts.length == 3) {
                builder.channels(Integer.parseInt(parts[2].strip()));
            }
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("rtpmap value \"" + rtpmap + "\" is not valid", exception);
        }
        if (fmtp != null) {
            var values = stripAttribute(fmtp, "a=fmtp:");
            for (var pair : values.split(";")) {
                var trimmed = pair.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                var equals = trimmed.indexOf('=');
                try {
                    if (equals < 0) {
                        builder.parameter(trimmed, "");
                    } else if (equals > 0) {
                        builder.parameter(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
                    }
                } catch (IllegalArgumentException exception) {
                    throw new MediaException.Malformed("fmtp parameter \"" + trimmed + "\" is not valid", exception);
                }
            }
        }
        return builder.build();
    }

    private static String stripAttribute(String value, String prefix) {
        var text = value.strip();
        if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            text = text.substring(prefix.length()).strip();
        }
        var digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits < text.length() && Character.isWhitespace(text.charAt(digits))) {
            text = text.substring(digits).strip();
        }
        return text;
    }

    /**
     * {@return the encoding name of the {@code a=rtpmap} attribute, in its registered spelling for the formats this
     * package implements}
     */
    public String encodingName() {
        return encodingName;
    }

    /**
     * {@return the RTP timestamp clock rate in hertz}
     */
    public int clockRate() {
        return clockRate;
    }

    /**
     * {@return the channel count of the {@code a=rtpmap} attribute, empty when it is absent, as it is for video}
     */
    public OptionalInt channels() {
        return channels == 0 ? OptionalInt.empty() : OptionalInt.of(channels);
    }

    /**
     * {@return the format parameters, with lowercase names, in the order they were set, unmodifiable}
     */
    public Map<String, String> parameters() {
        return parameters;
    }

    /**
     * Returns one format parameter.
     *
     * @param name the parameter name, case-insensitive
     * @return the value, or an empty optional when the parameter is absent
     */
    public Optional<String> parameter(String name) {
        return Optional.ofNullable(parameters.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * {@return the codec the format carries: the codec of the class documentation's table, {@link Codec#AAC} for
     * {@code mpeg4-generic} only when {@code streamType} is 5 or absent, and otherwise {@link Codec#UNKNOWN_VIDEO}
     * at the video clock rate and {@link Codec#UNKNOWN_AUDIO} at any other}
     */
    public Codec codec() {
        return switch (encodingName) {
            case "H264" -> Codec.H264;
            case "VP8" -> Codec.VP8;
            case "VP9" -> Codec.VP9;
            case "AV1" -> Codec.AV1;
            case "opus" -> Codec.OPUS;
            case "AMR" -> Codec.AMR_NB;
            case "AMR-WB" -> Codec.AMR_WB;
            case "mpeg4-generic" -> parameter("streamtype").map(String::strip).orElse("5").equals("5")
                    ? Codec.AAC : Codec.UNKNOWN_AUDIO;
            default -> clockRate == VIDEO_CLOCK_RATE ? Codec.UNKNOWN_VIDEO : Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * {@return the value of the {@code a=rtpmap} attribute after the payload type: {@code
     * <encoding name>/<clock rate>}, followed by {@code /<channels>} when there is a channel count}
     */
    public String rtpmap() {
        return encodingName + "/" + clockRate + (channels == 0 ? "" : "/" + channels);
    }

    /**
     * {@return the value of the {@code a=fmtp} attribute after the payload type, the {@code name=value} pairs
     * separated by {@code "; "}, or an empty string when there are no parameters}
     */
    public String fmtp() {
        var joiner = new StringJoiner("; ");
        parameters.forEach((name, value) -> joiner.add(value.isEmpty() ? name : name + "=" + value));
        return joiner.toString();
    }

    int intParameter(String name, int fallback, int min, int max) {
        var value = parameter(name);
        if (value.isEmpty() || value.get().isBlank()) {
            return fallback;
        }
        try {
            var parsed = Long.parseLong(value.get().strip());
            if (parsed < min || parsed > max) {
                throw new MediaException.Malformed("parameter " + name + "=" + value.get() + " is not from " + min
                        + " to " + max);
            }
            return (int) parsed;
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("parameter " + name + "=" + value.get() + " is not an integer",
                    exception);
        }
    }

    boolean flagParameter(String name) {
        return intParameter(name, 0, 0, 1) == 1;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RtpPayloadFormat that && encodingName.equals(that.encodingName)
                && clockRate == that.clockRate && channels == that.channels && parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encodingName, clockRate, channels, parameters);
    }

    @Override
    public String toString() {
        return "RtpPayloadFormat[" + rtpmap() + (parameters.isEmpty() ? "" : "; " + fmtp()) + "]";
    }

    /**
     * Assembles an {@link RtpPayloadFormat} setting by setting.
     *
     * <p>Each setter validates its value. Instances are not thread-safe.
     */
    public static final class Builder {
        private String encodingName;
        private int clockRate;
        private int channels;
        private final Map<String, String> parameters = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the encoding name; see {@link RtpPayloadFormat#encodingName()}.
         *
         * @param encodingName the value
         * @return this builder
         * @throws IllegalArgumentException if {@code encodingName} is empty or holds a slash or white space
         */
        public Builder encodingName(String encodingName) {
            if (encodingName.isEmpty() || encodingName.chars().anyMatch(c -> c == '/' || Character.isWhitespace(c))) {
                throw new IllegalArgumentException("encoding name \"" + encodingName + "\" is not a token");
            }
            this.encodingName = canonical(encodingName);
            return this;
        }

        private static String canonical(String name) {
            for (var known : new String[]{"H264", "VP8", "VP9", "AV1", "opus", "AMR", "AMR-WB", "mpeg4-generic"}) {
                if (known.equalsIgnoreCase(name)) {
                    return known;
                }
            }
            return name;
        }

        /**
         * Sets the clock rate; see {@link RtpPayloadFormat#clockRate()}.
         *
         * @param hertz the value, positive
         * @return this builder
         * @throws IllegalArgumentException if {@code hertz} is not positive
         */
        public Builder clockRate(int hertz) {
            if (hertz <= 0) {
                throw new IllegalArgumentException("clock rate " + hertz + " is not positive");
            }
            this.clockRate = hertz;
            return this;
        }

        /**
         * Sets the channel count; see {@link RtpPayloadFormat#channels()}.
         *
         * @param channels the value, from 1 to 255
         * @return this builder
         * @throws IllegalArgumentException if {@code channels} is not from 1 to 255
         */
        public Builder channels(int channels) {
            if (channels < 1 || channels > 255) {
                throw new IllegalArgumentException("channel count " + channels + " is not from 1 to 255");
            }
            this.channels = channels;
            return this;
        }

        /**
         * Removes the channel count; see {@link RtpPayloadFormat#channels()}.
         *
         * @return this builder
         */
        public Builder noChannels() {
            this.channels = 0;
            return this;
        }

        /**
         * Sets a format parameter, replacing any previous value; see {@link RtpPayloadFormat#parameters()}.
         *
         * @param name the parameter name, case-insensitive
         * @param value the value, possibly empty
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is empty or holds {@code =}, {@code ;} or white space, or
         * {@code value} holds {@code ;}
         */
        public Builder parameter(String name, String value) {
            if (name.isEmpty() || name.chars().anyMatch(c -> c == '=' || c == ';' || Character.isWhitespace(c))) {
                throw new IllegalArgumentException("parameter name \"" + name + "\" is not a token");
            }
            if (value.indexOf(';') >= 0) {
                throw new IllegalArgumentException("parameter value \"" + value + "\" holds a semicolon");
            }
            parameters.put(name.toLowerCase(Locale.ROOT), value);
            return this;
        }

        /**
         * Removes a format parameter; see {@link RtpPayloadFormat#parameters()}.
         *
         * @param name the parameter name, case-insensitive
         * @return this builder
         */
        public Builder removeParameter(String name) {
            parameters.remove(name.toLowerCase(Locale.ROOT));
            return this;
        }

        /**
         * Builds the format.
         *
         * @return the format
         */
        public RtpPayloadFormat build() {
            return new RtpPayloadFormat(this);
        }
    }
}
