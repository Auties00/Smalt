package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.image.ImageFormat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Describes a media file from its headers: its format, duration, overall bit rate, streams,
 * metadata tags, chapters and embedded pictures.
 *
 * <p>Obtained from {@link com.github.auties00.smalt.Media#probe}. A container file has a
 * {@link #container()} and a stream per track; an image file has an {@link #imageFormat()} and a
 * single {@link ImageStreamInfo}. Tag names are normalized: the common tags get the lower-case
 * names ffprobe uses ({@code title}, {@code artist}, {@code album}, {@code album_artist},
 * {@code composer}, {@code genre}, {@code date}, {@code track}, {@code disc}, {@code comment},
 * {@code copyright}, {@code encoder}, {@code encoded_by}, {@code publisher}, {@code language},
 * {@code lyrics}, {@code grouping}, {@code creation_time}) whatever the container calls them
 * (ID3v2 frame identifiers, RIFF {@code INFO} identifiers, Vorbis comments and Matroska tags in
 * any case, MP4 {@code ilst} items), and every other tag keeps its container name.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class MediaInfo {
    /**
     * The container format, or {@code null} for an image.
     */
    private final ContainerFormat container;

    /**
     * The short name of the container, or {@code null} for an image.
     */
    private final String containerName;

    /**
     * The image format, or {@code null} for a container.
     */
    private final ImageFormat image;

    /**
     * The size of the file, or {@code -1}.
     */
    private final long size;

    /**
     * The duration, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The overall bit rate, or {@code -1}.
     */
    private final long bitRate;

    /**
     * The streams.
     */
    private final List<StreamInfo> streams;

    /**
     * The normalized tags.
     */
    private final Map<String, String> tags;

    /**
     * The chapters.
     */
    private final List<Chapter> chapters;

    /**
     * The embedded pictures.
     */
    private final List<AttachedPicture> pictures;

    /**
     * Constructs a description.
     *
     * @param container      the container format, or {@code null} for an image
     * @param containerName  the short name of the container, or {@code null} for an image
     * @param image          the image format, or {@code null} for a container
     * @param size           the file size, or {@code -1}
     * @param durationMicros the duration in microseconds, or {@code -1}
     * @param bitRate        the overall bit rate, or {@code -1}
     * @param streams        the streams, copied
     * @param tags           the normalized tags, unmodifiable
     * @param chapters       the chapters, copied
     * @param pictures       the embedded pictures, copied
     */
    MediaInfo(ContainerFormat container, String containerName, ImageFormat image, long size, long durationMicros,
              long bitRate, List<StreamInfo> streams, Map<String, String> tags, List<Chapter> chapters, List<AttachedPicture> pictures) {
        this.container = container;
        this.containerName = containerName;
        this.image = image;
        this.size = size;
        this.durationMicros = durationMicros;
        this.bitRate = bitRate;
        this.streams = List.copyOf(streams);
        this.tags = tags;
        this.chapters = List.copyOf(chapters);
        this.pictures = List.copyOf(pictures);
    }

    /**
     * Returns the container format.
     *
     * @return the format, or empty for an image file
     */
    public Optional<ContainerFormat> container() {
        return Optional.ofNullable(container);
    }

    /**
     * Returns the short name of the container, which identifies a container that {@link ContainerFormat} does
     * not name.
     *
     * <p>The name is {@link ContainerFormat#shortName()} of {@link #container()}, or, for
     * {@link ContainerFormat#OTHER}, the name of the {@link com.github.auties00.smalt.spi.ContainerProvider} that
     * read the file.
     *
     * @return the name, for example {@code "mp4"}, or empty for an image file
     */
    public Optional<String> containerName() {
        return Optional.ofNullable(containerName);
    }

    /**
     * Returns the image format.
     *
     * @return the format, or empty for a container file
     */
    public Optional<ImageFormat> imageFormat() {
        return Optional.ofNullable(image);
    }

    /**
     * Returns whether the file is an image rather than a container.
     *
     * @return {@code true} for an image file
     */
    public boolean isImage() {
        return image != null;
    }

    /**
     * Returns the size of the file.
     *
     * @return the size in bytes, or empty when the source does not know it
     */
    public OptionalLong size() {
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /**
     * Returns the duration of the presentation.
     *
     * @return the duration in microseconds, or empty when unknown (always for still images)
     */
    public OptionalLong durationMicros() {
        return durationMicros < 0 ? OptionalLong.empty() : OptionalLong.of(durationMicros);
    }

    /**
     * Returns the overall bitrate: the file size over the duration.
     *
     * @return the bitrate in bits per second, or empty when the size or the duration is unknown
     */
    public OptionalLong bitrate() {
        return bitRate < 0 ? OptionalLong.empty() : OptionalLong.of(bitRate);
    }

    /**
     * Returns every stream, in container order.
     *
     * @return the streams; unmodifiable
     */
    public List<StreamInfo> streams() {
        return streams;
    }

    /**
     * Returns the video streams, in container order.
     *
     * @return the video streams; unmodifiable
     */
    public List<VideoStreamInfo> videoStreams() {
        return streams.stream().filter(VideoStreamInfo.class::isInstance).map(VideoStreamInfo.class::cast).toList();
    }

    /**
     * Returns the audio streams, in container order.
     *
     * @return the audio streams; unmodifiable
     */
    public List<AudioStreamInfo> audioStreams() {
        return streams.stream().filter(AudioStreamInfo.class::isInstance).map(AudioStreamInfo.class::cast).toList();
    }

    /**
     * Returns the first video stream.
     *
     * @return the stream, or empty when the file has none
     */
    public Optional<VideoStreamInfo> video() {
        return videoStreams().stream().findFirst();
    }

    /**
     * Returns the first audio stream.
     *
     * @return the stream, or empty when the file has none
     */
    public Optional<AudioStreamInfo> audio() {
        return audioStreams().stream().findFirst();
    }

    /**
     * Returns the picture of an image file.
     *
     * @return the stream, or empty for a container file
     */
    public Optional<ImageStreamInfo> image() {
        return streams.stream().filter(ImageStreamInfo.class::isInstance).map(ImageStreamInfo.class::cast).findFirst();
    }

    /**
     * Returns the metadata tags of the file, with common names normalized as the class
     * description says.
     *
     * @return the tags; unmodifiable
     */
    public Map<String, String> tags() {
        return tags;
    }

    /**
     * Returns the chapters of the presentation, in start order.
     *
     * @return the chapters; unmodifiable
     */
    public List<Chapter> chapters() {
        return chapters;
    }

    /**
     * Returns the pictures embedded in the file, such as cover art.
     *
     * @return the pictures; unmodifiable, empty when the probe options leave them out
     */
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    @Override
    public String toString() {
        return "MediaInfo[" + (container != null ? "container=" + containerName : "image=" + image)
                + (size >= 0 ? ", size=" + size : "") + (durationMicros >= 0 ? ", durationMicros=" + durationMicros : "")
                + (bitRate >= 0 ? ", bitRate=" + bitRate : "") + ", streams=" + streams
                + (tags.isEmpty() ? "" : ", tags=" + tags) + (chapters.isEmpty() ? "" : ", chapters=" + chapters)
                + (pictures.isEmpty() ? "" : ", pictures=" + pictures) + "]";
    }
}
