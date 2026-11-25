# smalt-desktop

> ImageIO readers and writers and Java Sound providers for the formats the other Smalt artifacts handle.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-desktop)

## At a glance

- **Keep your JDK code.** `ImageIO.read`, `ImageIO.write`, `AudioSystem.getAudioInputStream` and `AudioSystem.write` pick up the Smalt formats.
- **No setup code.** The JDK finds the plug-ins through `ServiceLoader` when the jar is on the class path or module path.
- **Formats come from the codec artifacts.** This jar adds no codec; add the artifacts listed under [Install](#install).
- **The JDK keeps its own formats.** JPEG, still PNG, GIF, BMP and TIFF stay with the JDK plug-ins under their usual names.
- **Smalt TIFF, BMP and GIF are opt-in.** Ask for them by name with `SmaltImageIo.TIFF`, `.BMP` or `.GIF`; plain `"tiff"` still gets the JDK plug-in.
- **`BufferedImages` and `SampledAudio`** convert Smalt pictures to `BufferedImage` and Smalt samples to Java Sound bytes, and back. See [Public classes](#public-classes).
- Depends on [smalt-core](../core/README.md#at-a-glance) and `java.desktop`. Works on the class path and the module path, with no JVM flag.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-desktop</artifactId>
</dependency>
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-image</artifactId>
</dependency>
```

Add the codec artifacts for the formats you want:

| Add | To get |
|---|---|
| [smalt-image](../image/README.md#formats) | WebP, APNG, JPEG XL, JPEG 2000, TIFF, BMP, ICO, GIF writing, PSD, EXR and more |
| [smalt-video](../video/README.md#formats) | AVIF |
| [smalt-mpeg](../mpeg/README.md#formats) | HEIC, JPEG XS, AAC, AMR, AC-3, E-AC-3, DTS |
| [smalt-raw](../raw/README.md#formats) | DNG, camera raw |
| [smalt-audio](../audio/README.md#formats) | Opus, Vorbis, FLAC, MP3, WavPack, Monkey's Audio, Musepack |

smalt-core alone already plays PCM in CAF, Wave64, RF64 and AIFF-C, and DSD in DSF and DSDIFF, through Java Sound.

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.

## Examples

### Read and write images with ImageIO

With smalt-image and smalt-video on the path, the standard ImageIO calls read WebP and write AVIF, JPEG XL and GIF.

```java
BufferedImage image = ImageIO.read(new File("photo.webp"));
ImageIO.write(image, "avif", new File("photo.avif"));
ImageIO.write(image, "jxl", new File("photo.jxl"));
ImageIO.write(image, SmaltImageIo.GIF, new File("photo.gif"));
```

### Write lossless WebP

Pick lossy or lossless with the standard `ImageWriteParam` compression type.

```java
BufferedImage image = ImageIO.read(new File("photo.png"));
ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
ImageWriteParam param = writer.getDefaultWriteParam();
param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
param.setCompressionType(SmaltImageWriteParam.LOSSLESS);
try (var output = ImageIO.createImageOutputStream(new File("photo.webp"))) {
    writer.setOutput(output);
    writer.write(null, new IIOImage(image, null, null), param);
} finally {
    writer.dispose();
}
```

### Turn an animated WebP into a GIF

The sequence API carries each frame's delay from the reader's metadata to the writer.

```java
try (var input = ImageIO.createImageInputStream(new File("clip.webp"));
     var output = ImageIO.createImageOutputStream(new File("clip.gif"))) {
    ImageReader reader = ImageIO.getImageReaders(input).next();
    reader.setInput(input);
    ImageWriter writer = ImageIO.getImageWritersByFormatName(SmaltImageIo.GIF).next();
    writer.setOutput(output);
    writer.prepareWriteSequence(null);
    for (int i = 0; i < reader.getNumImages(true); i++) {
        writer.writeToSequence(reader.readAll(i, null), null);
    }
    writer.endWriteSequence();
    writer.dispose();
    reader.dispose();
}
```

### Read every page of a TIFF

Ask for the Smalt TIFF reader by its `smalt-tiff` name; each page is one image index.

```java
try (var input = ImageIO.createImageInputStream(new File("scan.tif"))) {
    ImageReader reader = ImageIO.getImageReadersByFormatName(SmaltImageIo.TIFF).next();
    reader.setInput(input);
    for (int page = 0; page < reader.getNumImages(true); page++) {
        BufferedImage image = reader.read(page);
        System.out.println(page + ": " + image.getWidth() + "x" + image.getHeight());
    }
    reader.dispose();
}
```

### Convert a Smalt picture to a BufferedImage

Hand a decoded `Picture` to Swing or the JDK plug-ins with `BufferedImages`.

```java
DecodedImage decoded = Images.decode(Path.of("photo.heic"));
BufferedImage image = BufferedImages.toBufferedImage(decoded.picture());
ImageIO.write(image, "png", new File("photo.png"));
```

### Play an Opus file

Convert the compressed stream to PCM and feed it to a `SourceDataLine`.

```java
try (var encoded = AudioSystem.getAudioInputStream(new File("song.opus"));
     var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded);
     var line = AudioSystem.getSourceDataLine(pcm.getFormat())) {
    line.open(pcm.getFormat());
    line.start();
    byte[] buffer = new byte[16 * 1024];
    for (int read; (read = pcm.read(buffer)) > 0; ) {
        line.write(buffer, 0, read);
    }
    line.drain();
}
```

### Convert MP3 to FLAC

Decode with `AudioSystem` and write with one of the `SmaltAudioFormats` file types.

```java
try (var encoded = AudioSystem.getAudioInputStream(new File("song.mp3"));
     var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
    AudioSystem.write(pcm, SmaltAudioFormats.FLAC, new File("song.flac"));
}
```

## Formats

A row works only when the artifact its Notes name is on the class path or module path.

### ImageIO

| Format (ImageIO name) | Decode | Encode | Notes |
|---|:-:|:-:|---|
| WebP (`webp`) | ✅ | ✅ | smalt-image; still and animated |
| APNG (`apng`) | ✅ | ✅ | smalt-image; reads animated PNG only; one image writes a still PNG |
| GIF (`smalt-gif`) | ❌ | ✅ | smalt-image; the JDK reads GIF |
| TIFF (`smalt-tiff`) | ✅ | ✅ | smalt-image; opt-in by name; multi-page |
| BMP (`smalt-bmp`) | ✅ | ✅ | smalt-image; opt-in by name |
| JPEG XL (`jxl`) | ✅ | ✅ | smalt-image; still and animated |
| JPEG 2000 (`jpeg2000`, `jp2`) | ✅ | ✅ | smalt-image; reads JPH and HTJ2K too |
| ICO and CUR (`ico`) | ✅ | ✅ | smalt-image; writes icons, never cursors |
| ANI (`ani`) | ✅ | ❌ | smalt-image; frames as images |
| PNM, TGA, QOI, HDR, ICNS (`pnm`, `tga`, `qoi`, `hdr`, `icns`) | ✅ | ❌ | smalt-image |
| PSD, EXR, JXR, DDS (`psd`, `exr`, `jxr`, `dds`) | ✅ | ❌ | smalt-image |
| AVIF (`avif`) | ✅ | ✅ | smalt-video; still and animated |
| HEIC (`heic`, `heif`) | ✅ | ✅ | smalt-mpeg; still and animated |
| JPEG XS (`jpeg-xs`, `jxs`) | ✅ | ✅ | smalt-mpeg; writes JXS files |
| DNG (`dng`) | ✅ | ❌ | smalt-raw |
| Camera raw (`raw`, `camera-raw`) | ✅ | ❌ | smalt-raw; CR2, CR3, NEF, ARW, RAF and 19 more |
| JPEG, still PNG | ➖ | ➖ | JDK plug-ins |

✅ supported · ❌ not supported · ➖ not applicable

Every name also matches in upper case. smalt-image encodes ANI, PNM, TGA, QOI, HDR, ICNS and JPEG through its own API, not through ImageIO.

<details>
<summary><b>What the ImageIO reader does</b></summary>

- Detects the format from the content (the first 64 KiB, following TIFF directories further to tell DNG and camera raw apart), never from the file name.
- Reads stills deeper than 8 bits as 16-bit images, and also offers 8-bit image types.
- Honors source region, subsampling, destination offset, image and type, and source and destination bands.
- Supports `readRaster`.
- Lists thumbnails from HEIF thumbnail items, then from the EXIF JPEG or RGB thumbnail.
- Reports the EXIF orientation in the metadata, but never rotates the pixels.
- Exposes EXIF, ICC, XMP, density, frame delay and loop count through `SmaltImageMetadata`.
- Wraps decode failures in an `IIOException`.
- Reads DNG and camera raw ahead of the JDK TIFF reader.
- TIFF reads old-style JPEG, ThunderScan, BigTIFF, CMYK, YCbCr and float data.
- BMP reads 64-bit, OS/2 Huffman, RLE24, CMYK and OS/2 bitmap arrays (one image index each).

</details>

<details>
<summary><b>What the ImageIO writers do</b></summary>

- Accept images and rasters with 1 to 4 bands; images other than the standard RGB, ARGB and gray types are converted to sRGB first.
- Keep 16 bits for 16-bit sRGB and gray images and for rasters with bands over 8 bits; write other images as 8-bit RGBA.
- Honor source region and subsampling.
- Write sequences as animations for WebP, APNG, GIF, JPEG XL, AVIF and HEIC.
- Write sequences as pages for TIFF and as entries for ICO.
- Take the frame delay from `SmaltImageMetadata` or the JDK GIF `delayTime`, else 100 ms.
- Take the loop count from the metadata or the NETSCAPE2.0 block, else loop forever.
- GIF quantizes any image with dithering.
- TIFF writes LZW with the horizontal predictor, gray or RGB, 8 or 16 bit, with or without alpha.
- BMP picks the smallest lossless layout: 1, 4 or 8-bit palette, 24-bit, or 32-bit with alpha.

| Image metadata | Gives |
|---|---|
| `SmaltImageMetadata` | EXIF, ICC, XMP, orientation |
| JDK JPEG metadata | ICC, EXIF, XMP |
| JDK PNG metadata | ICC (`iCCP`), XMP (`iTXt`) |
| Standard tree | Orientation, when not `Normal` |

| `ImageWriteParam` compression | Result |
|---|---|
| `MODE_DEFAULT`, `MODE_COPY_FROM_METADATA` | Encoder defaults |
| `MODE_DISABLED` | Lossless |
| `MODE_EXPLICIT` + `"Lossless"` | Lossless |
| `MODE_EXPLICIT` + `"Lossy"` | Quality `round(q * 100)`, clamped to 1-100 |

</details>

### Java Sound

| Format (file type) | Decode | Encode | Notes |
|---|:-:|:-:|---|
| Ogg Opus (`OGG_OPUS`) | ✅ | ✅ | smalt-audio |
| Ogg (`OGG`) | ✅ | ✅ | smalt-audio; reads any decodable codec, writes Vorbis |
| FLAC (`FLAC`) | ✅ | ✅ | smalt-audio; integer input stays bit-exact |
| MP3 (`MP3`) | ✅ | ✅ | smalt-audio; also reads MP2 |
| MPEG-4 (`MPEG4`) | ✅ | ✅ | smalt-mpeg writes AAC in M4A; reads any decodable codec in MP4, MOV, 3GP |
| AAC in ADTS (`AAC`) | ✅ | ✅ | smalt-mpeg |
| AAC in LOAS, ADIF (`LOAS`, `ADIF`) | ✅ | ❌ | smalt-mpeg |
| AMR (`AMR`) | ✅ | ✅ | smalt-mpeg; writes mono AMR-NB or AMR-WB |
| AC-3, E-AC-3 (`AC3`, `EAC3`) | ✅ | ✅ | smalt-mpeg; elementary streams |
| DTS (`DTS`) | ✅ | ❌ | smalt-mpeg; DTS and DTS-HD |
| Matroska, WebM, CAF, Wave64 (`MATROSKA`, `CAF`, `W64`) | ✅ | ❌ | The track codec's artifact; PCM needs none |
| WavPack, Monkey's Audio, Musepack (`WAVPACK`, `APE`, `MUSEPACK`) | ✅ | ❌ | smalt-audio |
| DSF, DSDIFF (`DSF`, `DSDIFF`) | ✅ | ❌ | smalt-core; DST needs smalt-audio |
| AVI, MPEG-TS, MPEG-PS, ASF, FLV, MXF, DV (`AVI`, `MPEG_TS`, `MPEG_PS`, `ASF`, `FLV`, `MXF`, `DV`) | ✅ | ❌ | The track codec's artifact; first audio track only |
| WAVE, AIFF, AU variants (JDK `WAVE`, `AIFF`, `AU`) | ✅ | ❌ | The track codec's artifact; PCM needs none |

The file types are `SmaltAudioFormats` constants, except the JDK's own `AudioFileFormat.Type` for WAVE, AIFF and AU.

Smalt reads only the WAVE, AIFF and AU files the JDK declines: RF64, BW64, compressed WAVE, AIFF-C other than `NONE` and `ulaw`, and AU of other encodings. The JDK reads the rest and writes all three.

`AudioSystem.getAudioInputStream(encoding, stream)` converts compressed audio to PCM and PCM to compressed audio:

| Direction | Target encodings | Notes |
|---|---|---|
| Decode | `PCM_SIGNED`, `PCM_UNSIGNED`, `PCM_FLOAT`, `ULAW`, `ALAW` | Encoding-only targets use a default size |
| Encode | `OPUS`, `VORBIS`, `FLAC`, `MP3`, `AAC`, `AMR_NB`, `AMR_WB`, `AC3`, `EAC3` | The stream's bytes form a whole file (Ogg, ADTS, ...) |

<details>
<summary><b>Java Sound in detail</b></summary>

| Encoding-only decode target | Default size |
|---|---|
| `PCM_SIGNED` | 16-bit little-endian |
| `PCM_FLOAT` | 32-bit |
| `PCM_UNSIGNED`, `ULAW`, `ALAW` | 8-bit |

- A full `AudioFormat` target can resample and remix.
- `AudioSystem.write` copies a stream that the conversion provider already encoded byte for byte, without encoding it again.
- PCM tracks in other containers (CAF, W64, RF64) come back as PCM directly, with no conversion step.
- Integer PCM keeps its stored size (64-bit drops to 32, 8-bit unsigned is `PCM_UNSIGNED`); float comes back at 32 bits.
- G.711 tracks come back as `ULAW` or `ALAW`.
- FLAC writes integer input at its own sample size, G.711 input at 16 bits, and float input at 24 bits.
- AMR writes AMR-WB for input at 16 kHz or more, AMR-NB below, resampled to 16 or 8 kHz.
- Encoding names match `Codec` constants (`"OPUS"`, `"AAC"`); map them with `SmaltAudioFormats.encodingOf` and `codecOf`.
- Any codec that [smalt-audio](../audio/README.md#formats) or [smalt-mpeg](../mpeg/README.md#formats) decodes plays through Java Sound.

</details>

## Known limits

- **No codec artifact, no format.** ImageIO providers deregister, and Java Sound declines the file, when no artifact handles the format.
- **Put the codec artifacts on the path at startup.** ImageIO fixes each provider's format list when it registers the plug-ins.
- **`ImageIO.read` on a TIFF or BMP** uses only the JDK reader and throws if it fails; ask for `SmaltImageIo.TIFF` or `.BMP` by name.
- **DNG and camera raw** are the exception: `ImageIO.read` reaches Smalt first for them.
- **Animated images read as 8-bit** only, and all frames written to an animation must match the first frame's size.
- **Java Sound reads only the first audio track** of a file.
- **ALAC, MP2, ADPCM, G.722, G.726 and GSM 06.10** cannot be encoded through Java Sound.

<details>
<summary><b>ImageIO limits</b></summary>

- The JPEG 2000 writer cannot write JPH or HTJ2K; the JPEG XS writer writes JXS files only, not raw codestreams.
- `prepareWriteSequence` throws `UnsupportedOperationException` for BMP, JPEG 2000 and JPEG XS.
- An empty sequence throws an `IIOException`.
- Animation frames are written as 8-bit RGBA, and orientation is not recorded for animations.
- Reading frame `i` after a later frame decodes again from the start.
- `getNumImages(true)` on a forward-only input with an unknown frame count throws `IllegalStateException`.
- Animation frames have no thumbnails; HEIF thumbnail items are listed only for single-image AVIF and HEIC.
- `getImageTypes`, `getImageMetadata` and `getNumThumbnails` decode the still at full size.
- No stream metadata, no thumbnail writing, no tiling and no progressive mode.
- Pixel density is written only through page sequences; TIFF pages record it.
- Writers reject rasters with 0 or more than 4 bands, and signed 16-bit samples.
- Subsampled reads of formats that shrink while decoding show filtered pixels, not point samples.

</details>

<details>
<summary><b>Java Sound limits</b></summary>

- An `InputStream` without `markSupported()` is declined.
- A non-file `InputStream` or URL is read fully into memory.
- A stream with an ID3v2 tag larger than 64 MiB is declined.
- Decoded and encoded streams have length `NOT_SPECIFIED`.
- FLAC from a conversion stream, or written to an `OutputStream`, has no sample count or MD5 in STREAMINFO.
- Encoder delay and padding (gapless info) are recorded only in Ogg, M4A, and MP3 written to a `File`.
- ADTS, AMR, AC-3 and E-AC-3 files decode to extra samples at the start and end.
- Encoder input needs a positive whole sample rate; rates or layouts the codec cannot code become 48 kHz stereo.
- `SampledAudio` keeps 24 significant bits for integer samples above 24 bits, and rounds 64-bit float to float.

</details>

## Public classes

You rarely need these: most code calls the JDK APIs directly.

| Class | Use it to |
|---|---|
| `SmaltImageIo` | Name the opt-in formats: `TIFF`, `GIF`, `BMP` |
| `SmaltImageWriteParam` | Pick `LOSSY` or `LOSSLESS` and a quality |
| `SmaltImageMetadata` | Read orientation, EXIF, ICC, XMP, density, delay, loop count |
| `BufferedImages` | Convert a `Picture` to `BufferedImage` and back |
| `SmaltAudioFormats` | File types for `AudioSystem.write`; map encodings to `Codec` |
| `SampledAudio` | Convert Java Sound bytes (8-32 bit PCM, float, G.711) to `AudioBuffer` and back |
