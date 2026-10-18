package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioDecoders;
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
 * {@link SmaltFormatConversionProvider} decodes to PCM. Java Sound compares encodings by name, so
 * {@code new AudioFormat.Encoding("OPUS")} equals {@link #encodingOf(Codec)} of
 * {@link Codec#OPUS}.
 *
 * <p>This class is thread-safe.
 */
public final class SmaltAudioFormats {
    /**
     * An Ogg file holding Vorbis, FLAC or another codec except Opus.
     */
    public static final AudioFileFormat.Type OGG = new AudioFileFormat.Type("Ogg", "ogg");

    /**
     * An Ogg file holding Opus; the type {@link SmaltAudioFileWriter} writes.
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
     * An MPEG-4 file (M4A, MP4, MOV, 3GP).
     */
    public static final AudioFileFormat.Type MPEG4 = new AudioFileFormat.Type("MPEG-4", "m4a");

    /**
     * A Matroska or WebM file.
     */
    public static final AudioFileFormat.Type MATROSKA = new AudioFileFormat.Type("Matroska", "mka");

    /**
     * An AMR file.
     */
    public static final AudioFileFormat.Type AMR = new AudioFileFormat.Type("AMR", "amr");

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
     * Returns the file type of a container holding a codec.
     *
     * @param container the container format
     * @param codec the codec of its audio track
     * @return the file type, or empty for the containers left to the JDK's readers (WAV, AIFF) and
     * the video-only ones
     */
    public static Optional<AudioFileFormat.Type> fileTypeOf(ContainerFormat container, Codec codec) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(codec, "codec");
        return Optional.ofNullable(switch (container) {
            case OGG -> codec == Codec.OPUS ? OGG_OPUS : OGG;
            case FLAC -> FLAC;
            case MPEG_AUDIO -> MP3;
            case ADTS -> AAC;
            case MP4 -> MPEG4;
            case MATROSKA -> MATROSKA;
            case AMR -> AMR;
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
