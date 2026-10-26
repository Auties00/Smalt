package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Prints one SHA-256 per workload of the codecs and picture operations that parallelize on the shared helper threads
 * (image encoders and decoders, the WebP encoder's lossy and near-lossless modes, video decoders left to choose their
 * threads, resampling, gain map computation and rendering, the Opus multistream encoder): the output of a JVM whose
 * {@code smalt.threads} differs must be the same, line for line.
 */
public final class ThreadDigest {
    private ThreadDigest() {
        throw new AssertionError();
    }

    /**
     * Prints {@code name sha256} lines, one per workload.
     *
     * @param arguments unused
     */
    public static void main(String[] arguments) {
        digests().forEach((name, digest) -> System.out.println(name + " " + digest));
    }

    /**
     * Returns the digest of every workload, in a fixed order.
     *
     * @return the digests by workload name
     */
    public static Map<String, String> digests() {
        var digests = new LinkedHashMap<String, String>();
        var picture = picture(1024, 768);
        for (var format : new ImageFormat[]{ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.JP2,
                ImageFormat.AVIF, ImageFormat.TIFF, ImageFormat.GIF}) {
            for (var lossless : new boolean[]{false, true}) {
                var options = lossless ? ImageEncoderOptions.lossless() : ImageEncoderOptions.ofQuality(80);
                byte[] encoded;
                try {
                    encoded = Images.encode(picture, format, options);
                } catch (com.github.auties00.smalt.MediaException.Unsupported unsupported) {
                    continue;
                }
                var name = format.name().toLowerCase() + (lossless ? "-lossless" : "");
                digests.put(name + "-encode", sha256(encoded));
                var decoded = Images.decode(MediaSource.of(encoded)).picture();
                digests.put(name + "-decode", sha256(ImageOps.toRgba(decoded).pixels()));
                var reduced = Images.decode(MediaSource.of(encoded), ImageDecoderOptions.builder().minSize(200, 150)
                        .build()).picture();
                digests.put(name + "-reduced", sha256(ImageOps.toRgba(reduced).pixels()));
            }
        }
        for (var lossless : new boolean[]{false, true}) {
            var options = (lossless ? Jp2EncoderOptions.lossless() : Jp2EncoderOptions.lossy(12)).toBuilder()
                    .highThroughput(true).build();
            var encoded = Jp2Encoder.encode(picture, options);
            var name = "jp2-ht" + (lossless ? "-lossless" : "");
            digests.put(name + "-encode", sha256(encoded));
            digests.put(name + "-decode", sha256(ImageOps.toRgba(Images.decode(MediaSource.of(encoded)).picture())
                    .pixels()));
        }
        var jpegModes = new LinkedHashMap<String, JpegEncoderOptions>();
        jpegModes.put("jpeg-progressive", JpegEncoderOptions.ofProgressive(80));
        jpegModes.put("jpeg-arithmetic-progressive", JpegEncoderOptions.builder().arithmetic(true).progressive(true)
                .restartInterval(5).build());
        jpegModes.put("jpeg-12bit-440", JpegEncoderOptions.builder().precision(12)
                .samplingFactors(JpegEncoderOptions.Sampling.of(1, 2)).build());
        jpegModes.put("jpeg-lossless-arithmetic", JpegEncoderOptions.ofLossless().toBuilder().arithmetic(true)
                .predictor(7).build());
        for (var mode : jpegModes.entrySet()) {
            digests.put(mode.getKey() + "-encode", sha256(JpegEncoder.encode(picture, mode.getValue())));
        }
        var jpegLs = com.github.auties00.smalt.image.jpeg.JpegLsEncoder.encode(picture,
                com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.builder()
                        .interleave(com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions.Interleave.NONE).build());
        digests.put("jpeg-ls-encode", sha256(jpegLs));
        digests.put("jpeg-ls-decode", sha256(ImageOps.toRgba(Images.decode(MediaSource.of(jpegLs)).picture())
                .pixels()));
        var webpModes = new LinkedHashMap<String, WebpEncoderOptions>();
        webpModes.put("webp-sharp", WebpEncoderOptions.builder().sharpYuv(true).build());
        webpModes.put("webp-photo", WebpEncoderOptions.forPreset(WebpEncoderOptions.Preset.PHOTO));
        webpModes.put("webp-autofilter", WebpEncoderOptions.builder().autoFilter(true).build());
        webpModes.put("webp-autofilter-m2", WebpEncoderOptions.builder().autoFilter(true).method(2).build());
        webpModes.put("webp-psnr", WebpEncoderOptions.builder().targetPsnr(36).build());
        webpModes.put("webp-passes-partitions", WebpEncoderOptions.builder().passes(3).tokenPartitions(4).build());
        webpModes.put("webp-near-lossless", WebpEncoderOptions.lossless().toBuilder().nearLossless(40).build());
        webpModes.forEach((name, options) -> digests.put(name + "-encode",
                sha256(WebpEncoder.encode(picture, options))));
        for (var filter : ResizeFilter.values()) {
            digests.put("resize-" + filter.name().toLowerCase(),
                    sha256(ImageOps.toRgba(ImageOps.resize(picture, 517, 333, filter)).pixels()));
        }
        gainMaps(picture, digests);
        for (var codec : new Codec[]{Codec.H264, Codec.VP8, Codec.VP9, Codec.AV1, Codec.PRORES, Codec.DNXHD,
                Codec.MJPEG, Codec.JPEG2000}) {
            var stream = Streams.video(codec, 640, 360, 8, builder -> builder.realtime(codec != Codec.H264));
            digests.put(codec.name().toLowerCase() + "-encode", sha256Packets(stream));
            digests.put(codec.name().toLowerCase() + "-decode", decodeDigest(stream));
        }
        var opus = Streams.audio(Codec.OPUS, ChannelLayout.SURROUND_7_1, 48_000, 48_000,
                AudioEncoderOptions.Builder::automaticBitrate);
        digests.put("opus-7.1-encode", sha256Packets(opus));
        return digests;
    }

    /**
     * Adds the digests of gain map computation and rendering: the gain map image and metadata of a single-channel
     * and a multichannel gain map of a PQ rendition, and the PQ and HLG renderings with their light levels.
     *
     * @param picture the standard-dynamic-range rendition
     * @param digests the digests by workload name
     */
    static void gainMaps(Picture picture, Map<String, String> digests) {
        var width = picture.width();
        var height = picture.height();
        var rgba = ImageOps.toRgba(picture);
        var bright = Rgba16Picture.allocate(width, height, false);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var boost = 1 + 7.0 * x / width * y / height;
                for (var c = 0; c < 3; c++) {
                    var signal = (rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF) / 255.0;
                    var light = Math.pow(signal, 2.2) * boost * 203 / 10000;
                    var p = Math.pow(light, 2610.0 / 16384);
                    var pq = Math.pow((3424.0 / 4096 + 2413.0 / 128 * p) / (1 + 2392.0 / 128 * p), 2523.0 / 32);
                    bright.pixels()[y * bright.stride() + x * 4 + c] = (short) Math.round(pq * 65535);
                }
                bright.pixels()[y * bright.stride() + x * 4 + 3] = (short) 0xFFFF;
            }
        }
        var base = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, width, height, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        var alternate = new DecodedImage(bright, ImageFormat.PNG, Orientation.NORMAL, width, height, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(ColorProfile.of(new ColorInfo(
                        MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                        TransferCharacteristics.PQ))));
        for (var multichannel : new boolean[]{false, true}) {
            var name = "gainmap-" + (multichannel ? "multichannel" : "single");
            var gainMap = GainMaps.compute(base, alternate, GainMapComputeOptions.builder().multichannel(multichannel)
                    .scale(2).build());
            var computed = digest();
            computed.update(ImageOps.toRgba(gainMap.image()).pixels());
            computed.update(gainMap.metadata().toByteArray());
            digests.put(name + "-compute", HexFormat.of().formatHex(computed.digest()));
            for (var transfer : new TransferCharacteristics[]{TransferCharacteristics.PQ, TransferCharacteristics.HLG}) {
                var rendered = GainMaps.apply(base, gainMap, GainMapOptions.builder().headroom(2).transfer(transfer)
                        .build());
                var samples = ((Rgba16Picture) rendered.picture()).pixels();
                var bytes = java.nio.ByteBuffer.allocate(samples.length * 2);
                bytes.asShortBuffer().put(samples);
                var digest = digest();
                digest.update(bytes.array());
                digest.update(rendered.colorProfile().orElseThrow().colorInfo().orElseThrow().contentLightLevel()
                        .orElseThrow().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                digests.put(name + "-" + transfer.name().toLowerCase(), HexFormat.of().formatHex(digest.digest()));
            }
        }
    }

    public static String decodeDigest(Streams.Encoded stream) {
        var digest = digest();
        try (var decoder = VideoDecoders.create((VideoTrack) stream.track(), VideoDecoderOptions.defaults())) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink =
                    frame -> digest.update(ImageOps.toRgba(frame.picture()).pixels());
            for (Packet packet : stream.packets()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Picture picture(int width, int height) {
        var random = new Random(42);
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (byte) (x * 255 / width + random.nextInt(16));
                pixels[at + 1] = (byte) (y * 255 / height);
                pixels[at + 2] = (byte) ((x ^ y) & 0xFF);
                pixels[at + 3] = (byte) (200 + random.nextInt(56));
            }
        }
        return picture;
    }

    public static String sha256Packets(Streams.Encoded stream) {
        var digest = digest();
        for (var packet : stream.packets()) {
            digest.update(packet.data());
            digest.update(Long.toString(packet.pts()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
