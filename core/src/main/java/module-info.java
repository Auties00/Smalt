/**
 * Smalt: pure-Java image, audio and video codecs, containers, pixel and sample processing, and
 * capture devices. This is the core module: the shared model, the codec service-provider
 * interfaces and the registries that find the codecs, every container, pixel and sample
 * processing, and PCM.
 *
 * <p>Smalt never loads native code for coding. DSP kernels use the incubating Vector API when the
 * JVM is started with {@code --add-modules jdk.incubator.vector} and fall back to scalar code
 * otherwise; {@link com.github.auties00.smalt.MediaRuntime} reports the choice.
 *
 * <p>Image operations (resizing, color conversion, orientation), the JPEG decoder and encoder,
 * the PNG and GIF decoders, the WebP encoders and decoders and the VP8 decoder split large work
 * across one shared, bounded pool of daemon helper threads that exit when idle. The calling thread
 * always takes part, so an operation never waits for a helper to become free, and the output never
 * depends on the scheduling. The {@code smalt.threads} system property caps the threads that run
 * this work at once, the calling thread included (by default the number of available processors):
 * however many threads call the module, it runs at most that many minus one helpers, and {@code 1}
 * runs everything on the calling thread. The H.264 decoder and encoder start the worker threads
 * their own thread count asks for, per instance, outside that pool.
 *
 * <p>The registries ({@code Images}, {@code AudioDecoders}, {@code AudioEncoders},
 * {@code VideoDecoders}, {@code VideoEncoders} and {@code Containers}) name no codec: they find
 * the decoders and encoders through the service-provider interfaces of the {@code spi} package,
 * so a module or class-path jar adds a codec by providing an implementation, and a container the
 * same way. Inputs are read
 * through {@code MediaSource} and outputs written through {@code MediaSink}. The {@code transcode}
 * package converts files on top of them: remuxing, re-encoding, trimming, scaling and resampling;
 * the {@code container.packaging} package segments them into HLS and DASH presentations, which the
 * {@code container.hls} and {@code container.dash} demuxers read back.
 * The {@code metadata} package reads and writes the tags, cover art and chapters of audio and video
 * files without touching their streams, and models XMP packets. The {@code subtitle} package reads,
 * writes and converts text subtitles, and {@code audio.loudness} measures loudness (EBU R128),
 * detects silence and computes normalization gains.
 *
 * <p>Smalt's exceptions are serializable, but their serialized forms are not compatible across
 * releases.
 *
 * @uses com.github.auties00.smalt.spi.ImageCodecProvider the image codecs {@code Images} decodes and
 * encodes with
 * @uses com.github.auties00.smalt.spi.AudioCodecProvider the audio codecs of {@code AudioDecoders} and
 * {@code AudioEncoders}
 * @uses com.github.auties00.smalt.spi.VideoCodecProvider the video codecs of {@code VideoDecoders} and
 * {@code VideoEncoders}
 * @uses com.github.auties00.smalt.spi.ContainerProvider the containers of other artifacts and applications, which
 * {@code Containers}, the readers, {@code Media.probe}, {@code TagEditor} and the transcoder handle like core's own
 * @provides com.github.auties00.smalt.spi.AudioCodecProvider the PCM decoders and encoders
 * @provides com.github.auties00.smalt.spi.VideoCodecProvider the uncompressed video decoder and encoder
 */
module com.github.auties00.smalt {
    // Vector API: optional, selected at runtime by internal.simd.Simd
    requires static jdk.incubator.vector;

    // Shared model: failures, limits, byte sources
    exports com.github.auties00.smalt;
    exports com.github.auties00.smalt.io;

    // Codec and container service-provider interfaces, through which the registries find the codecs and containers
    exports com.github.auties00.smalt.spi;

    // Pictures, samples, tracks and the codec/container contracts
    exports com.github.auties00.smalt.image;
    exports com.github.auties00.smalt.audio;
    exports com.github.auties00.smalt.video;
    exports com.github.auties00.smalt.track;
    exports com.github.auties00.smalt.container;

    // Probing: the description of a media file from its headers (behind the Media facade)
    exports com.github.auties00.smalt.probe;

    // Metadata: tags, cover art and chapters of audio and video files, and XMP packets
    exports com.github.auties00.smalt.metadata;

    // Previews: thumbnails of videos and images, waveforms of audio
    exports com.github.auties00.smalt.preview;

    // Text subtitles: cues, reading, writing and converting
    exports com.github.auties00.smalt.subtitle;

    // Image codecs and processing
    exports com.github.auties00.smalt.image.ops;

    // Color management: ICC profiles and color transforms
    exports com.github.auties00.smalt.image.color;

    // HDR gain maps
    exports com.github.auties00.smalt.image.gainmap;

    // Containers
    exports com.github.auties00.smalt.container.aac;
    exports com.github.auties00.smalt.container.ac3;
    exports com.github.auties00.smalt.container.dts;
    exports com.github.auties00.smalt.container.adts;
    exports com.github.auties00.smalt.container.aiff;
    exports com.github.auties00.smalt.container.amr;
    exports com.github.auties00.smalt.container.au;
    exports com.github.auties00.smalt.container.avi;
    exports com.github.auties00.smalt.container.caf;
    exports com.github.auties00.smalt.container.dash;
    exports com.github.auties00.smalt.container.flac;
    exports com.github.auties00.smalt.container.hls;
    exports com.github.auties00.smalt.container.ivf;
    exports com.github.auties00.smalt.container.matroska;
    exports com.github.auties00.smalt.container.mp4;
    exports com.github.auties00.smalt.container.mpeg;
    exports com.github.auties00.smalt.container.mxf;
    exports com.github.auties00.smalt.container.ogg;
    exports com.github.auties00.smalt.container.packaging;
    exports com.github.auties00.smalt.container.raw;
    exports com.github.auties00.smalt.container.subtitle;
    exports com.github.auties00.smalt.container.ts;
    exports com.github.auties00.smalt.container.wav;
    exports com.github.auties00.smalt.container.wavpack;
    exports com.github.auties00.smalt.container.y4m;

    // Audio codecs and processing
    exports com.github.auties00.smalt.audio.loudness;
    exports com.github.auties00.smalt.audio.pcm;
    exports com.github.auties00.smalt.audio.process;

    // Remuxing and transcoding over the registries
    exports com.github.auties00.smalt.transcode;

    // Internals shared with the other Smalt modules
    exports com.github.auties00.smalt.internal.anim to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.video;
    exports com.github.auties00.smalt.internal.bits to
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.audio;
    exports com.github.auties00.smalt.internal.check to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture,
            com.github.auties00.smalt.raw,
            com.github.auties00.smalt.desktop,
            com.github.auties00.smalt.aot;
    exports com.github.auties00.smalt.internal.codec to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.desktop;
    exports com.github.auties00.smalt.internal.color to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture;
    exports com.github.auties00.smalt.internal.header to
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.video;
    exports com.github.auties00.smalt.internal.edit to com.github.auties00.smalt.image;
    exports com.github.auties00.smalt.internal.gainmap to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg;
    exports com.github.auties00.smalt.internal.heif to
            com.github.auties00.smalt.aot,
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg;
    exports com.github.auties00.smalt.internal.io to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.nal to com.github.auties00.smalt.mpeg;
    exports com.github.auties00.smalt.internal.caption to com.github.auties00.smalt.mpeg;
    exports com.github.auties00.smalt.internal.plane to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.region to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.tiff to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.limits to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.quantize to com.github.auties00.smalt.image;
    exports com.github.auties00.smalt.internal.tags to com.github.auties00.smalt.desktop;
    exports com.github.auties00.smalt.internal.simd to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.capture,
            com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.internal.task to
            com.github.auties00.smalt.image,
            com.github.auties00.smalt.audio,
            com.github.auties00.smalt.video,
            com.github.auties00.smalt.mpeg,
            com.github.auties00.smalt.raw;

    // The registries look their codecs up through these interfaces
    uses com.github.auties00.smalt.spi.ImageCodecProvider;
    uses com.github.auties00.smalt.spi.AudioCodecProvider;
    uses com.github.auties00.smalt.spi.VideoCodecProvider;
    // Containers and the tag editor look up the containers of other artifacts and applications through this one
    uses com.github.auties00.smalt.spi.ContainerProvider;
    // The HEIF item codecs of other artifacts, for the HEIF decoders of the artifacts that do not depend on them
    uses com.github.auties00.smalt.internal.heif.HeifComponentCodec;
    // The HEIF decoders of the artifacts, each handing the images of the other's codings to it
    uses com.github.auties00.smalt.internal.heif.HeifImageCodec;

    // The codecs of this module
    provides com.github.auties00.smalt.spi.AudioCodecProvider with
            com.github.auties00.smalt.internal.provider.PcmCodecProvider;
    provides com.github.auties00.smalt.spi.VideoCodecProvider with
            com.github.auties00.smalt.internal.provider.RawVideoCodecProvider;
}
