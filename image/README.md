# smalt-image

> Pure-Java still image codecs (JPEG, PNG, WebP, GIF, TIFF, JPEG 2000, JPEG XL and more), EXIF and image metadata editing, lossless JPEG transforms, and seven video codecs such as VP8 and Motion-JPEG.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-image)

## At a glance

- Decode JPEG, PNG, WebP, GIF, TIFF, JPEG 2000, JPEG XL and more ([Formats](#formats)); all but PSD, OpenEXR, DDS and JPEG XR also encode.
- Put the jar on the path and core's `Images` finds these codecs by itself; you can also call a codec class such as `JpegEncoder` directly.
- Rotate and crop JPEGs without re-encoding, and edit EXIF, XMP, IPTC and ICC data in place.
- Read and write animations (GIF, APNG, WebP, JPEG XL, ANI) and multi-page files (TIFF, ICO, ICNS).
- Seven video codecs (VP8, Motion-JPEG, Motion JPEG 2000, HAP, and PNG, TIFF or GIF frames) plug into core's `VideoReader` and `VideoWriter`.
- Depends only on [smalt-core](../core/README.md#at-a-glance). Optional: `--add-modules jdk.incubator.vector` turns on the faster vector code (see [JVM flags](../README.md#jvm-flags)).

Using `ImageIO`? Add [smalt-desktop](../desktop/README.md) and `ImageIO.read` opens these formats. AVIF is in [smalt-video](../video/README.md#formats), HEIC and JPEG XS in [smalt-mpeg](../mpeg/README.md#formats), camera raw and DNG in [smalt-raw](../raw/README.md#formats).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-image</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle. On the module path, add `requires com.github.auties00.smalt.image;`.

## Examples

Imports: `Images` and `ImageFormat` are in `com.github.auties00.smalt.image` (core); each codec has a package of its own, such as `...smalt.image.jpeg` or `...smalt.image.jxl`.

### Convert a photo to WebP

`Images.decode` returns a `DecodedImage` (pixels, format, orientation, ICC, EXIF, XMP), and `metadataOf` carries its orientation and color profile into the WebP.

```java
var image = Images.decode(Path.of("photo.jpg"));
// quality runs from 1 (smallest) to 100 (best)
var options = ImageEncoderOptions.builder().quality(80).metadataOf(image).build();
byte[] webp = Images.encode(image.picture(), ImageFormat.WEBP, options);
Files.write(Path.of("photo.webp"), webp);
```

### Rotate a JPEG losslessly

`JpegTransforms` moves the coded blocks, so the pixels are never decoded or re-compressed.

```java
try (var source = MediaSource.of(Path.of("photo.jpg"))) {
    Files.write(Path.of("rotated.jpg"), JpegTransforms.rotate(source, 90));
}
```

### Strip location data from a photo

`ImageMetadataEditor` rewrites the file in place and copies the image data unchanged.

```java
ImageMetadataEditor.apply(Path.of("photo.jpg"), ImageMetadataEdit.stripLocation());
```

### Turn an animated GIF into an animated WebP

Any animation reader feeds any animation writer, frame by frame; pass the loop count on, or the WebP loops forever.

```java
try (var source = MediaSource.of(Path.of("loop.gif"));
     var reader = Images.openAnimation(source);
     var sink = MediaSink.of(Path.of("loop.webp"));
     var writer = Images.openAnimationWriter(ImageFormat.WEBP, reader.width(), reader.height(),
             ImageEncoderOptions.builder().loopCount(reader.loopCount()).build(), sink)) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        writer.write(frame);
    }
    writer.finish();
}
```

### Split a multi-page TIFF into PNGs

`Images.openPages` gives you the page count and decodes each page by index.

```java
try (var source = MediaSource.of(Path.of("scan.tiff"));
     var pages = Images.openPages(source)) {
    for (var i = 0; i < pages.pageCount(); i++) {
        var page = pages.read(i);
        Files.write(Path.of("page" + i + ".png"), Images.encode(page.picture(), ImageFormat.PNG));
    }
}
```

### Recompress a JPEG into JPEG XL

`JxlEncoder.recompressJpeg` stores the JPEG losslessly; `JxlDecoder.reconstructJpeg` gives back the original bytes.

```java
try (var source = MediaSource.of(Path.of("photo.jpg"))) {
    Files.write(Path.of("photo.jxl"), JxlEncoder.recompressJpeg(source));
}
try (var source = MediaSource.of(Path.of("photo.jxl"))) {
    Files.write(Path.of("restored.jpg"), JxlDecoder.reconstructJpeg(source).orElseThrow());
}
```

## Formats

### Still images

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| JPEG | ✅ | ✅ | Baseline, progressive, arithmetic, lossless; 8 and 12 bit |
| JPEG-LS | ✅ | ✅ <sup>1</sup> | Lossless and near-lossless, 2-16 bit |
| Hierarchical JPEG | ✅ | ❌ | All hierarchical modes |
| JPEG 2000 (JP2, JPX, J2K, JPH) | ✅ | ✅ | Decode Part 1, Part 2, High-Throughput (HTJ2K); encode Part 1, HTJ2K, no JPX |
| JPEG XL | ✅ | ✅ | Lossless, lossy, animation, JPEG recompression |
| PNG and APNG | ✅ | ✅ | 1-16 bit, interlaced, HDR |
| GIF | ✅ | ✅ | Animation; palette quantization and dithering |
| WebP | ✅ | ✅ | Lossy, lossless, animation |
| BMP | ✅ | ✅ | OS/2 to V5 headers, RLE |
| TIFF and BigTIFF | ✅ | ✅ | Multi-page; LZW, Deflate, Zstd, JPEG, CCITT and more |
| ICO and CUR | ✅ | ✅ | BMP or PNG entries, cursor hotspots |
| ANI | ✅ | ✅ | Animated cursors |
| ICNS | ✅ | ✅ | 16 to 1024 px icons |
| Netpbm (PBM, PGM, PPM, PAM, PFM, PHM) | ✅ | ✅ | Text and binary, float maps |
| TGA | ✅ | ✅ | Raw and RLE |
| QOI | ✅ | ✅ | Matches the reference `qoi.h` both ways |
| Radiance HDR | ✅ | ✅ | RGBE and XYZE decode, RGBE encode; float samples with `keepChannels` |
| PSD and PSB | ✅ | ❌ | Stored composite; rendered from layers when none is stored |
| OpenEXR | ✅ | ❌ | Multi-part, tiled, deep (many samples per pixel); 14 compressions |
| DDS | ✅ | ❌ | GPU-compressed textures, cubes, mipmaps |
| JPEG XR | ✅ | ❌ | Matches the reference jxrlib |

✅ supported · ❌ not supported · ➖ not applicable

<sup>1</sup> JPEG-LS has no `ImageFormat` of its own: decode it as `ImageFormat.JPEG`, encode it with `JpegLsEncoder`.

The `ImageFormat` constant is the format name, except `JP2` (JPEG 2000), `JXL` (JPEG XL), `PNM` (Netpbm), `HDR` (Radiance), `EXR` (OpenEXR) and `JXR` (JPEG XR). With this module present, HEIF files whose items are JPEG 2000 (`j2k1`, ISO/IEC 15444-16) decode too.

### Animations and pages

| Capability | Formats | Entry point |
|---|---|---|
| Read animations | GIF, APNG, WebP, JPEG XL, JPX, ANI | `Images.openAnimation` |
| Write animations | GIF, APNG (`ImageFormat.PNG`), WebP, JPEG XL, ANI | `Images.openAnimationWriter` |
| Read pages | TIFF, ICO and CUR, ICNS, JPX layers, BMP arrays, DDS surfaces, EXR parts, Netpbm | `Images.openPages` |
| Write pages | TIFF, ICO (icons, not cursors), ICNS | `Images.openPageWriter` |

A still image opened with `Images.openAnimation` reads as one frame.

### Video codecs

| Codec | Decode | Encode | Notes |
|---|:-:|:-:|---|
| VP8 | ✅ | ✅ | Bit-exact with libvpx; alpha in WebM |
| Motion-JPEG | ✅ | ✅ | Interlaced fields; baseline encode |
| Motion JPEG 2000 | ✅ | ✅ | Lossy through `VideoWriter`; `Mj2Encoder` lossless by default |
| PNG video | ✅ | ✅ | In QuickTime, AVI, Matroska |
| TIFF video | ✅ | ✅ | In QuickTime, Matroska |
| GIF video | ✅ | ❌ | In QuickTime, Matroska |
| HAP | ✅ | ✅ | All 7 variants; `VideoWriter` encodes HAP (DXT1) only |

### Metadata

`ImageMetadata` reads and `ImageMetadataEditor` edits metadata without touching the image data.

| Format | Read | Edit | What an edit can change |
|---|:-:|:-:|---|
| JPEG | ✅ | ✅ | EXIF, XMP, IPTC, ICC, comments |
| PNG and APNG | ✅ | ✅ | EXIF, XMP, ICC, comments, text |
| WebP | ✅ | ✅ | EXIF, XMP, ICC |
| GIF | ✅ | ✅ | XMP, ICC, comments |
| HEIC and AVIF | ✅ | ✅ | EXIF, XMP, ICC |
| TIFF and BigTIFF | ✅ | ✅ | EXIF, XMP, IPTC, ICC |
| DNG, CR2, NEF, NRW, ARW, SR2, SRF, PEF, SRW | ✅ | ✅ | As TIFF |
| JPEG XL | ✅ | ✅ | EXIF, XMP |
| JPEG 2000 (JP2, JPX) | ✅ | ✅ | EXIF, XMP, IPTC, ICC |
| BMP | ✅ | ✅ | ICC only |
| ORF, RW2, CR3, RAF, MRW | ✅ | ❌ | ➖ |

- Metadata of HEIC, AVIF and raw files works with smalt-image alone.
- `ImageMetadata` throws `MediaException.Unsupported` for ICO, ANI, Netpbm, TGA, QOI, HDR, ICNS, PSD, EXR, JPEG XR, DDS, JPEG XS, CRW and X3F.
- The PSD and JPEG XR decoders still return EXIF, XMP and ICC on the `DecodedImage`.

### Per-format details

Open a block for the exact profiles and options of each codec.

<details>
<summary><b>JPEG and JPEG-LS</b></summary>

- **Decode:** SOF0-3 and SOF9-11, Huffman and arithmetic, 1-255 components, DNL (height after the scan), 16-bit quantization tables.
- **Decode output:** bit-exact with `djpeg -dct int` at 8 and 12 bit; 1/2, 1/4 and 1/8 reduced sizes.
- **Encode:** SOF0/1/2/3/9/10/11; YCbCr, gray, RGB, CMYK and YCCK; any sampling; scan scripts; custom quantization tables.
- **Encode output:** byte-identical to `cjpeg -dct int` with matching switches.
- **Ultra HDR:** `JpegDecoder.decodeGainMap` reads ISO 21496-1 and `hdrgm` gain maps; `JpegEncoder.encode(picture, gainMap, ...)` writes them.
- **JPEG-LS:** interleave none/line/sample, preset parameters, mapping tables, HP1-3 color transforms, SPIFF.
- **JPEG-LS encoder:** byte-identical to CharLS without restart intervals.
- **Transforms:** rotate, flip, transpose, crop by MCU (8x8 or 16x16 block), extend, wipe, drop, grayscale, progressive and sequential.
- **Transform output:** byte-identical to `jpegtran` under `JpegTransformOptions.jpegtran()`.
- **Via `Images`:** default quality 75; `ImageEncoderOptions.lossless()` writes lossless JPEG (SOF3).

</details>

<details>
<summary><b>JPEG 2000 and JPEG XL</b></summary>

- **JPEG 2000 decode:** full Part 1 (up to 16384 components, 1-38 bit), most of Part 2, full HTJ2K and JPH.
- **JPEG 2000 decode output:** bit-identical to `opj_decompress`; reduced resolution, layer and region decode.
- **JPEG 2000 encode:** 5-3 lossless or 9-7 lossy, rate or PSNR per layer, tiles, progression orders, ROI.
- **JPEG 2000 profiles:** `forCinema2k`, `forCinema4k`, `forImf`.
- **JPEG 2000 encode output:** lossless Part 1 output is byte-identical to `opj_compress`.
- **JPEG 2000 via `Images`:** lossless unless a quality is set.
- **JPEG XL decode:** modular and VarDCT, patches, splines, noise, animation, extra channels, previews, gain maps.
- **JPEG XL encode:** lossless modular (effort 1-10, default 7) or lossy VarDCT at a distance (default 1.0).
- **JPEG XL channels:** 8/16-bit int or 32-bit float; gray, RGB or CMYK plus alpha, depth and spot channels.
- **JPEG XL recompression:** `recompressJpeg` and `reconstructJpeg` round-trip a JPEG byte for byte.

</details>

<details>
<summary><b>PNG, GIF, WebP and BMP</b></summary>

- **PNG decode:** every color type and depth, Adam7 interlacing, cICP, mDCV, cLLI, iCCP, eXIf and XMP.
- **PNG encode:** picks the smallest lossless color type and depth; writes 16 bit from 16-bit pictures.
- **APNG encode:** changed rectangles, dispose and blend chosen by size, exact fraction delays, fallback image.
- **GIF encode:** exact palette up to 256 colors (255 with transparency), else median cut or NeuQuant with ordered or error-diffusion dithering.
- **GIF via `Images`:** quality `q` gives a palette of `round(256 * q / 100)` colors, at least 2.
- **WebP decode:** byte-identical to `dwebp` and the libwebp animation decoder.
- **WebP lossy encode:** byte-identical to cwebp 1.6.0 (see [Known limits](#known-limits)).
- **WebP options:** presets, method, SNS, filters, segments, target size or PSNR, near-lossless.
- **BMP decode:** 1-64 bit, bit masks, RLE4/RLE8, OS/2 Huffman and RLE24, embedded JPEG and PNG, CMYK.
- **BMP encode:** palette 1/4/8 bit (RLE for 4 and 8 bit), 555, 565, 888, XRGB and ARGB; INFO, V4 or V5 headers.

</details>

<details>
<summary><b>TIFF</b></summary>

| Compression | Decode | Encode |
|---|:-:|:-:|
| None, PackBits, LZW, Deflate, Zstd | ✅ | ✅ |
| CCITT RLE, T.4, T.6 | ✅ | ✅ |
| JPEG (TN2) | ✅ | ✅ |
| WebP, JPEG 2000, JPEG XL | ✅ | ✅ |
| Old JPEG, LZMA, PixarLog, NeXT, ThunderScan | ✅ | ❌ |
| SGILog, JBIG, LERC | ✅ | ❌ |

- **Decode:** strips, tiles, planar data; 1-32 bit integer, 16-64 bit float, CMYK, Lab, YCbCr and ink sets.
- **Encode:** gray, RGB, CMYK, palette, bilevel or YCbCr; 8, 16 bit or float; strips or tiles; BigTIFF.
- **Presets:** `TiffEncoderOptions.defaults()` (LZW), `uncompressed()`, `deflate()`, `zstd()`, `fax()`, `jpeg()`.
- **Streaming:** `new TiffEncoder(options, sink).addPage(...)` writes one page at a time.

</details>

<details>
<summary><b>Icons and the other formats</b></summary>

- **ICO and CUR:** `IcoEncoderOptions.windowsSizes()` writes 16, 24, 32, 48, 64 and 256 px entries from one picture.
- **ICNS:** `IcnsEncoderOptions.macSizes()`; ARGB RLE, 24-bit RLE with mask, or PNG elements.
- **ANI:** `AniEncoder.encode(frames, steps, options)`; title and artist in the INFO list.
- **Netpbm:** pick the variant with `PnmEncoderOptions.of(PnmVariant)`; any maximum sample value (maxval), plain or raw.
- **TGA:** 8-bit gray, 24-bit BGR or 32-bit BGRA, optional RLE; TGA 2.0 footer always written.
- **QOI:** lossless 8-bit RGBA; colorspace 1 decodes as linear BT.709.
- **Radiance HDR:** flat, adaptive and old RLE; 8 orientations; RLE or flat on encode.
- **PSD:** renders from layers only when no composite is stored (gray, duotone, RGB, CMYK); all blend modes, masks, clipping, groups, artboards.
- **OpenEXR:** NONE, RLE, ZIPS, ZIP, PIZ, PXR24, B44, B44A, DWAA, DWAB, HTJ2K256, HTJ2K32, LJ2K and Zstd.
- **DDS:** BC1-BC7 bit-exact with bcdec; arrays, cubes, volumes and mips read as pages.
- **JPEG XR:** container or bare codestream; gray, YUV, CMYK and up to 16 channels.

</details>

<details>
<summary><b>Video codecs</b></summary>

- **VP8 decode:** RFC 6386, bit-exact with libvpx on the vp80-00 to vp80-06 vectors.
- **VP8 encode:** real-time CBR (constant bitrate), good-quality and best-quality modes; one or two pass via `Vp8FirstPass`.
- **VP8 alpha:** the encoder writes alpha as a second VP8 stream; `VideoDecoders` decodes it back.
- **Motion-JPEG decode:** DCT or lossless frames; AVI1, QuickTime A and B fields; missing tables default to the standard (Annex K) ones.
- **Motion-JPEG encode:** optional DHT (Huffman table) omission with `MjpegEncoderOptions.forAvi()`.
- **Motion JPEG 2000:** MJ2, MP4, MOV, Matroska and AVI; `Mj2Encoder` takes any `Jp2EncoderOptions`.
- **PNG video:** pixel-equal to ffmpeg in both directions; 16 bit kept with `ImageDecoderOptions.highBitDepth`.
- **HAP:** HAP, Alpha, Q, Q Alpha, Alpha Only, R and HDR; Snappy and chunked frames.

</details>

## Known limits

- Region decoding (decoding only a rectangle) is native for JPEG, WebP, JPEG 2000 and TIFF; other formats decode in full, then crop.
- An edit a format cannot store throws `MediaException.Unsupported`; nothing is dropped silently.

<details>
<summary><b>JPEG and JPEG-LS</b></summary>

- The JPEG encoder ignores alpha: flatten the picture first.
- 16-bit DCT precision is rejected.
- Lossless, JPEG-LS and hierarchical images ignore the reduced-size hint and decode at full size.
- CMYK and YCCK decode without color management unless `ImageDecoderOptions.convertToSrgb` is set and a CMYK profile is embedded.
- The encoder uses the accurate integer DCT only and writes no hierarchical frames.
- The JPEG-LS encoder writes no mapping tables, and MAXVAL is always 2^P-1.
- JPEG-LS part 2 (T.870) extensions are rejected.
- Lossless transforms refuse lossless, JPEG-LS and hierarchical files.
- Transforms trim partial edge MCUs by default; `JpegTransformOptions` `perfect(true)` refuses such images instead.
- 12-bit Huffman transform output always gets optimized tables.

</details>

<details>
<summary><b>JPEG 2000 and JPEG XL</b></summary>

- JPEG 2000 rejects the TBDWT (single-sample-overlap wavelet) flag.
- JPX fragments in other files need `Jp2DecoderOptions.dataReferences`.
- The JPEG 2000 encoder writes Part 1 and HTJ2K only, no Part 2 (JPX).
- The HTJ2K encoder codes at most 30 magnitude bit-planes (lossless up to 27 bit).
- JPEG 2000 cinema and IMF profiles reject alpha.
- The JPEG XL encoder writes no patches, splines, noise, progressive passes or lossy modular.
- JPEG XL recompression takes 8-bit Huffman JPEGs with 1 or 3 components only.

</details>

<details>
<summary><b>PNG, GIF, WebP and BMP</b></summary>

- APNG frames decode at 8 bit, and the APNG encoder writes 8 bit only.
- The APNG encoder does not keep the color of fully transparent pixels.
- GIF transparency is on or off per pixel, set by an alpha threshold.
- WebP ignores the reduced-size hint.
- WebP lossy output matches cwebp only with one token partition and no target size.
- BMP linked color profiles are never opened; OS/2 icon and pointer entries are skipped.
- A fully transparent ARGB BMP reads back as opaque.

</details>

<details>
<summary><b>TIFF</b></summary>

- `Images` always writes TIFF with the LZW defaults (`encode` and `openPageWriter`); use `TiffEncoder` for other compressions.
- TIFF decode rejects CFA and LinearRaw photometrics, and LERC 34888/34889.
- TIFF JPEG pages encode 8-bit gray or YCbCr only; WebP pages 8-bit RGB or RGBA only.

</details>

<details>
<summary><b>Icons and the other formats</b></summary>

- ICO entries are at most 256 px; `Images` scales larger pictures into a 256 px square.
- ANI files with raw (non-icon) frames are rejected; frames are drawn top-left, unscaled.
- The ANI animation writer only loops forever (loop count 0).
- The ICNS encoder writes no dark-appearance icons.
- TGA Huffman types 32 and 33 are rejected.
- Radiance HDR `EXPOSURE` and `COLORCORR` are not applied; PFM scale factors are not applied.
- Rendering PSD layers is limited to gray, duotone, RGB and CMYK documents.
- A visible adjustment layer, layer effect, knockout, Blend If range, channel restriction or feathered mask makes the PSD decode throw `MediaException.Unsupported`.
- DDS 420_OPAQUE is rejected.

</details>

<details>
<summary><b>Video codecs</b></summary>

- VP8 decodes to 8-bit 4:2:0 only; key-frame scale fields are reported, not applied.
- VP8 encodes sizes up to 16383 px; real-time mode is CBR only.
- VP8 good-quality mode looks ahead up to 25 frames, so the last packets arrive at flush.
- Motion-JPEG frames must be gray or YCbCr; the encoder writes baseline frames only.
- Through `VideoWriter`, Motion JPEG 2000 codes lossy at the bitrate; use `Mj2Encoder` for lossless.
- Motion JPEG 2000 frames decode at full resolution with every quality layer.
- `VideoWriter` encodes plain HAP (DXT1) only; use `HapEncoder` for the other six variants.
- HAP decode can differ from ffmpeg by up to 4 levels (DXT textures) or 12 (HAP Q, scaled YCoCg).
- The HAP encoder uses one BC7 mode (6) and one BC6H mode (11, unsigned).

</details>

<details>
<summary><b>Metadata</b></summary>

- TIFF edits change the first directory only, and cannot add comments, text or an EXIF thumbnail.
- HEIC and AVIF edits change only the primary item's ICC profile, and refuse files without a primary item.
- JPEG 2000 edits cannot change raw codestreams or files with fragment tables.
- JPEG XL edits cannot change the ICC profile; with reconstruction data, EXIF and XMP keep their length.
- BMP edits refuse OS/2 bitmap arrays and OS/2 Huffman or RLE24 files.
- A WebP stream edit holds the whole file in memory.

</details>
