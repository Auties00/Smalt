<div align="center">

# smalt-desktop

**ImageIO and Java Sound providers for the formats Smalt handles**

![Artifact](https://img.shields.io/badge/artifact-smalt--desktop-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.desktop-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | **desktop** | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-desktop` plugs Smalt into `javax.imageio` and `javax.sound.sampled`, so existing desktop code reads and
writes Smalt's formats without calling Smalt. It names no codec: it serves whatever the codec artifacts next to
it handle. It depends on [`smalt-core`](../core/README.md) and on `java.desktop`, and it is the only artifact
that may use `java.desktop`.

**Contents:** [Install](#install) | [Guide](#guide) | [Known limits](#known-limits)

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-desktop</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

Add the codec artifacts whose formats you want served: [image](../image/README.md), [audio](../audio/README.md),
[video](../video/README.md), [mpeg](../mpeg/README.md), [raw](../raw/README.md).

## Guide

### Use Smalt from ImageIO and Java Sound

Add `smalt-desktop` next to the codec artifacts and existing code that uses the JDK APIs picks the formats
up, with no Smalt types in sight:

```java
BufferedImage image = ImageIO.read(new File("photo.webp"));  // WebP, APNG, AVIF, HEIC, JPEG XL, JPEG XS, ICO, ANI, JP2, DNG, raw
ImageIO.write(image, "webp", new File("copy.webp"));
ImageIO.write(image, "avif", new File("copy.avif"));
ImageIO.write(image, "heic", new File("copy.heic"));
ImageIO.write(image, "smalt-gif", new File("copy.gif"));          // quantized and dithered by Smalt
ImageIO.write(image, "smalt-tiff", new File("copy.tif"));         // the JDK keeps "tiff"
ImageIO.write(image, "smalt-bmp", new File("copy.bmp"));         // the JDK keeps "bmp"

try (var encoded = AudioSystem.getAudioInputStream(new File("song.mp3")); // Opus, Vorbis, FLAC, MP3, AAC, AC-3...
     var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
    // 16-bit PCM, ready for a SourceDataLine or a Clip
    AudioSystem.write(pcm, SmaltAudioFormats.FLAC, new File("song.flac"));  // or OGG, MP3, MPEG4, AAC, AMR, AC3
}
```

Animated images read as one image per frame (`ImageReader.getNumImages(true)`) and write as animated WebP,
APNG (format name `apng`) or GIF (format name `smalt-gif`; the JDK keeps `gif` and `png`) through the writer's
sequence API (`prepareWriteSequence`, `writeToSequence`, `endWriteSequence`), keeping the
frame delays of Smalt and GIF metadata; for TIFF and ICO, which do not animate, the sequence API writes the
pages of a multi-page TIFF and the entries of an icon. `ImageWriteParam` selects lossy quality or lossless. Images
deeper than 8 bits (16-bit PNG, TIFF and JPEG XL, 10 and 12-bit AVIF and HEIC) read as 16-bit `TYPE_USHORT`
images, as the JDK reads a 16-bit PNG, with the 8-bit types offered too; source and destination band selection,
`readRaster` and thumbnails (HEIF thumbnail items and EXIF thumbnails) are supported. The writers write rasters
and 16-bit images, and take the EXIF block, ICC profile, XMP packet and orientation from the image metadata: a
`SmaltImageMetadata` read by Smalt or filled through `getDefaultImageMetadata`, or the JDK JPEG and PNG plug-ins'
metadata. `AudioSystem.write` writes Ogg Opus and Vorbis, FLAC (exactly the integer input), MP3, AAC in M4A or ADTS,
AMR, AC-3 and E-AC-3 (`SmaltAudioFormats`), and `AudioSystem.getAudioInputStream(encoding, pcm)` encodes PCM to those
codecs as a stream of the file's bytes. The reader also reads AC-3, E-AC-3, DTS, CAF, Wave64, WavPack and the other
audio containers, the audio of AVI, MPEG-TS and MPEG-PS files, and the WAVE, AIFF-C and AU variants the JDK's
readers decline (RF64, ADPCM, `sowt`, `fl32`, `alaw` and the others), and `SampledAudio` converts G.711 mu-law and
A-law, 8 to 32-bit integer and half-precision float samples. Smalt's TIFF reader and writer are
opt-in by the format name `smalt-tiff` (the JDK keeps `tiff` and `ImageIO.read`), and the reader exposes every
page of a multi-page TIFF as an image index; Smalt's BMP reader and writer are opt-in by `smalt-bmp` the same way,
the reader exposing every bitmap of an OS/2 bitmap array as an image index; icons and cursors read and write under
`ico`, JPEG 2000 under `jpeg2000` and `jp2`, and camera raw files (with `smalt-raw`) read under `dng` and `raw`, ahead of the
JDK's TIFF reader for the TIFF-structured ones.
`BufferedImages` and `SampledAudio` convert between Smalt's pictures and samples and `BufferedImage` and
PCM bytes. The providers work on the class path and on the module path.

## Known limits

> [!WARNING]
> ImageIO and Java Sound model less than Smalt's own API does. Open the entry to see what is lost on the way.

<details>
<summary><b>ImageIO and Java Sound (<code>smalt-desktop</code>)</b></summary>

A single image written through ImageIO records the EXIF block, ICC
profile, XMP packet and orientation of its metadata, but its pixel density only as a page of a TIFF sequence,
because the still encoders take no density. The JDK reads JPEG and PNG, so their MPF and EXIF thumbnails are the
JDK plug-ins' to report; thumbnails of HEIF files with several master images are not listed. A FLAC stream
encoded by the Java Sound conversion is written as it is read, so its `STREAMINFO` holds no sample count and no MD5
signature, as the writer's FLAC file written to an `OutputStream` does; an ADTS, AMR, AC-3 or E-AC-3 stream carries
the encoder's priming and padding, which those elementary streams cannot mark.

</details>
