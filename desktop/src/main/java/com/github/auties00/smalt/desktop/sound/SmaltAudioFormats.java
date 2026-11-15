package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Names the Java Sound encodings and file types of the formats the Smalt providers read and
 * write.
 *
 * <p>Each compressed {@link Codec} has an {@link AudioFormat.Encoding} named after the constant
 * ({@code "OPUS"}, {@code "VORBIS"}, {@code "FLAC"}, {@code "MP3"}, {@code "AAC"}, ...): it is the
 * encoding of the streams {@link SmaltAudioFileReader} returns, which
 * {@link SmaltFormatConversionProvider} decodes to PCM, and of the streams that provider encodes from
 * PCM. Java Sound compares encodings by name, so {@code new AudioFormat.Encoding("OPUS")} equals
 * {@link #encodingOf(Codec)} of {@link Codec#OPUS}.
 *
 * <p>This class is thread-safe.
 */
public final class SmaltAudioFormats {
    /**
     * An Ogg file holding Vorbis, FLAC or another codec except Opus; {@link SmaltAudioFileWriter} writes it
     * as Ogg Vorbis.
     */
    public static final AudioFileFormat.Type OGG = new AudioFileFormat.Type("Ogg", "ogg");

    /**
     * An Ogg file holding Opus.
     */
    public static final AudioFileFormat.Type OGG_OPUS = new AudioFileFormat.Type("Ogg Opus", "opus");

    /**
     * A native FLAC file.
     */
    public static final AudioFileFormat.Type FLAC = new AudioFileFormat.Type("FLAC", "flac");

    /**
     * An MPEG audio file (MP3 or MP2).
     */
    public static final AudioFileFormat.Type MP3 = new AudioFileFormat.Type("MP3", "mp3");

    /**
     * A raw AAC stream in ADTS framing.
     */
    public static final AudioFileFormat.Type AAC = new AudioFileFormat.Type("AAC", "aac");

    /**
     * A raw AAC stream in LOAS framing.
     */
    public static final AudioFileFormat.Type LOAS = new AudioFileFormat.Type("LOAS", "loas");

    /**
     * A raw AAC stream in the Audio Data Interchange Format.
     */
    public static final AudioFileFormat.Type ADIF = new AudioFileFormat.Type("ADIF", "adif");

    /**
     * An MPEG-4 file (M4A, MP4, MOV, 3GP); {@link SmaltAudioFileWriter} writes it as an M4A file of AAC.
     */
    public static final AudioFileFormat.Type MPEG4 = new AudioFileFormat.Type("MPEG-4", "m4a");

    /**
     * A Matroska or WebM file.
     */
    public static final AudioFileFormat.Type MATROSKA = new AudioFileFormat.Type("Matroska", "mka");

    /**
     * An AMR file, AMR-NB or AMR-WB.
     */
    public static final AudioFileFormat.Type AMR = new AudioFileFormat.Type("AMR", "amr");

    /**
     * A Dolby Digital (AC-3) elementary stream.
     */
    public static final AudioFileFormat.Type AC3 = new AudioFileFormat.Type("AC-3", "ac3");

    /**
     * A Dolby Digital Plus (E-AC-3) elementary stream.
     */
    public static final AudioFileFormat.Type EAC3 = new AudioFileFormat.Type("E-AC-3", "ec3");

    /**
     * A DTS or DTS-HD elementary stream.
     */
    public static final AudioFileFormat.Type DTS = new AudioFileFormat.Type("DTS", "dts");

    /**
     * An Apple Core Audio Format file.
     */
    public static final AudioFileFormat.Type CAF = new AudioFileFormat.Type("CAF", "caf");

    /**
     * A Sony Wave64 file.
     */
    public static final AudioFileFormat.Type W64 = new AudioFileFormat.Type("Wave64", "w64");

    /**
     * A RIFF AVI file.
     */
    public static final AudioFileFormat.Type AVI = new AudioFileFormat.Type("AVI", "avi");

    /**
     * An MPEG-2 transport stream.
     */
    public static final AudioFileFormat.Type MPEG_TS = new AudioFileFormat.Type("MPEG-TS", "ts");

    /**
     * An MPEG program stream or MPEG-1 system stream.
     */
    public static final AudioFileFormat.Type MPEG_PS = new AudioFileFormat.Type("MPEG-PS", "mpg");

    /**
     * An Advanced Systems Format file (WMA, WMV).
     */
    public static final AudioFileFormat.Type ASF = new AudioFileFormat.Type("ASF", "wma");

    /**
     * A Flash Video file.
     */
    public static final AudioFileFormat.Type FLV = new AudioFileFormat.Type("FLV", "flv");

    /**
     * A Material Exchange Format file.
     */
    public static final AudioFileFormat.Type MXF = new AudioFileFormat.Type("MXF", "mxf");

    /**
     * A raw DV stream.
     */
    public static final AudioFileFormat.Type DV = new AudioFileFormat.Type("DV", "dv");

    /**
     * A WavPack file.
     */
    public static final AudioFileFormat.Type WAVPACK = new AudioFileFormat.Type("WavPack", "wv");

    /**
     * A Monkey's Audio file.
     */
    public static final AudioFileFormat.Type APE = new AudioFileFormat.Type("Monkey's Audio", "ape");

    /**
     * A Musepack file.
     */
    public static final AudioFileFormat.Type MUSEPACK = new AudioFileFormat.Type("Musepack", "mpc");

    /**
     * A Sony DSD Stream File.
     */
    public static final AudioFileFormat.Type DSF = new AudioFileFormat.Type("DSF", "dsf");

    /**
     * A Philips DSD Interchange File Format file.
     */
    public static final AudioFileFormat.Type DSDIFF = new AudioFileFormat.Type("DSDIFF", "dff");

    /**
     * The encoding of each compressed audio codec.
     */
    private static final Map<Codec, AudioFormat.Encoding> ENCODINGS = encodings();

    /**
     * Prevents instantiation.
     */
    private SmaltAudioFormats() {
        throw new AssertionError();
    }

    /**
     * Returns the encoding of a compressed audio codec.
     *
     * @param codec a compressed audio codec
     * @return the encoding, named after the codec constant
     * @throws IllegalArgumentException if {@code codec} is not a compressed audio codec
     */
    public static AudioFormat.Encoding encodingOf(Codec codec) {
        var encoding = ENCODINGS.get(Objects.requireNonNull(codec, "codec"));
        if (encoding == null) {
            throw new IllegalArgumentException(codec.displayName() + " is not a compressed audio codec");
        }
        return encoding;
    }

    /**
     * Returns the codec of an encoding.
     *
     * @param encoding the Java Sound encoding to look up
     * @return the compressed audio codec the encoding is named after, or empty for any other
     * encoding (PCM included)
     */
    public static Optional<Codec> codecOf(AudioFormat.Encoding encoding) {
        Objects.requireNonNull(encoding, "encoding");
        for (var entry : ENCODINGS.entrySet()) {
            if (entry.getValue().equals(encoding)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the encodings of the codecs that the codec artifacts present at run time decode.
     *
     * @return the encodings, in {@link Codec} order
     */
    public static List<AudioFormat.Encoding> decodableEncodings() {
        var result = new ArrayList<AudioFormat.Encoding>();
        for (var entry : ENCODINGS.entrySet()) {
            if (AudioDecoders.isSupported(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the encodings that {@link SmaltFormatConversionProvider} encodes PCM to with the codec
     * artifacts present at run time.
     *
     * <p>They are the encodings of the codecs that have an encoder and a file type
     * {@link SmaltAudioFileWriter} writes: Opus and Vorbis (in Ogg), FLAC, MP3, AAC (in ADTS), AMR-NB
     * and AMR-WB, AC-3 and E-AC-3.
     *
     * @return the encodings, in {@link Codec} order
     */
    public static List<AudioFormat.Encoding> encodableEncodings() {
        var result = new ArrayList<AudioFormat.Encoding>();
        for (var entry : ENCODINGS.entrySet()) {
            if (EncodedFiles.streamContainerOf(entry.getKey()).isPresent() && AudioEncoders.isSupported(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the file type of a container holding a codec.
     *
     * <p>A WAVE, AIFF or AU file has the JDK's own type ({@link AudioFileFormat.Type#WAVE},
     * {@link AudioFileFormat.Type#AIFF} or {@link AudioFileFormat.Type#AIFC}, {@link AudioFileFormat.Type#AU}):
     * {@link SmaltAudioFileReader} reads only the variants of those formats the JDK's readers decline.
     *
     * @param container the container format
     * @param codec the codec of its audio track
     * @return the file type, or empty for the containers that hold no audio
     */
    public static Optional<AudioFileFormat.Type> fileTypeOf(ContainerFormat container, Codec codec) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(codec, "codec");
        return Optional.ofNullable(switch (container) {
            case ContainerFormat.OGG -> codec == Codec.OPUS ? OGG_OPUS : OGG;
            case ContainerFormat.FLAC -> FLAC;
            case ContainerFormat.MPEG_AUDIO -> MP3;
            case ContainerFormat.ADTS -> AAC;
            case ContainerFormat.LOAS -> LOAS;
            case ContainerFormat.ADIF -> ADIF;
            case ContainerFormat.MP4 -> MPEG4;
            case ContainerFormat.MATROSKA -> MATROSKA;
            case ContainerFormat.AMR -> AMR;
            case ContainerFormat.AC3 -> codec == Codec.EAC3 ? EAC3 : AC3;
            case ContainerFormat.DTS -> DTS;
            case ContainerFormat.WAV -> AudioFileFormat.Type.WAVE;
            case ContainerFormat.W64 -> W64;
            case ContainerFormat.AIFF -> AudioFileFormat.Type.AIFF;
            case ContainerFormat.CAF -> CAF;
            case ContainerFormat.AU -> AudioFileFormat.Type.AU;
            case ContainerFormat.AVI -> AVI;
            case ContainerFormat.MPEG_TS -> MPEG_TS;
            case ContainerFormat.MPEG_PS -> MPEG_PS;
            case ContainerFormat.ASF -> ASF;
            case ContainerFormat.FLV -> FLV;
            case ContainerFormat.MXF -> MXF;
            case ContainerFormat.DV -> DV;
            case ContainerFormat.WAVPACK -> WAVPACK;
            case ContainerFormat.APE -> APE;
            case ContainerFormat.MUSEPACK -> MUSEPACK;
            case ContainerFormat.DSF -> DSF;
            case ContainerFormat.DSDIFF -> DSDIFF;
            default -> null;
        });
    }

    /**
     * Builds the encoding of every compressed audio codec.
     *
     * @return the encodings
     */
    private static Map<Codec, AudioFormat.Encoding> encodings() {
        var result = new EnumMap<Codec, AudioFormat.Encoding>(Codec.class);
        for (var codec : Codec.values()) {
            if (codec.kind() == TrackKind.AUDIO && !codec.isPcm() && codec != Codec.UNKNOWN_AUDIO) {
                result.put(codec, new AudioFormat.Encoding(codec.name()));
            }
        }
        return result;
    }
}
