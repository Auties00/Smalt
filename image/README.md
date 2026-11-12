<div align="center">

# smalt-image

**Still image codecs, image metadata, and the video codecs built on them**

![Artifact](https://img.shields.io/badge/artifact-smalt--image-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.image-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | **image** | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-image` decodes and encodes the still image formats, reads and edits their metadata, and carries the
video codecs that are image codecs at heart. It depends on [`smalt-core`](../core/README.md) only.

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Working with image files](#working-with-image-files) |
[Metadata](#metadata) | [Known limits](#known-limits)

## What it covers

- **Still images, decode and encode:** JPEG (with lossless transforms and JPEG-LS), PNG and APNG, GIF, WebP, BMP,
  TIFF and BigTIFF, ICO and CUR, ANI, Netpbm, TGA, QOI, Radiance HDR, ICNS, JPEG 2000, JPEG XL.
- **Still images, decode only:** PSD and PSB, OpenEXR, DDS, JPEG XR.
- **Metadata:** EXIF, XMP, IPTC-IIM, ICC profiles, comments and text, read from every image format and from
  camera raw files, and edited in place.
- **Video:** VP8 (lossy WebP is VP8), Motion-JPEG, Motion JPEG 2000, PNG, TIFF and GIF video, and Vidvox HAP.

> [!TIP]
> AVIF lives in [`smalt-video`](../video/README.md) and HEIC and JPEG XS in [`smalt-mpeg`](../mpeg/README.md),
> next to the video codecs that code them. Camera raw files are [`smalt-raw`](../raw/README.md). Resizing,
> color management and the other picture operations are in [`smalt-core`](../core/README.md#images-and-color).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-image</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).
 Codecs are found at run time, so `Images.decode`, `Media.probe` and the
transcoder of `smalt-core` start handling these formats as soon as the artifact is on the module path or class path.

## Guide

One task, one example. The tasks that work on any picture, whatever its format
([thumbnails](../core/README.md#make-an-image-thumbnail), [regions](../core/README.md#decode-a-region-of-a-large-image),
[edits](../core/README.md#edit-a-photo), [color management](../core/README.md#show-display-p3-adobe-rgb-and-cmyk-photos-correctly)),
are in the core guide.

### Strip location from a photo

```java
ImageMetadataEditor.apply(Path.of("photo.jpg"), ImageMetadataEdit.stripLocation());

var metadata = ImageMetadata.read(Path.of("photo.jpg"));   // EXIF, XMP, IPTC, ICC, comments
var clean = ImageMetadataEditor.apply(Files.readAllBytes(Path.of("scan.png")),
        ImageMetadataEdit.stripAll().toBuilder().orientation(Orientation.ROTATE_90).build());
```

The edits rewrite only the metadata segments, chunks, blocks or items of JPEG, PNG, WebP, GIF, HEIC and AVIF
files and copy the compressed pixels byte for byte. `stripLocation()` removes the EXIF GPS directory, the XMP coordinates and
place names and the IPTC place fields; `stripAll()` removes every kind but the ICC profile, which defines the
colors. EXIF edits keep the maker note and every unchanged value at its offset and zero what they remove.

### Turn a GIF into an animated WebP

```java
try (var source = MediaSource.of(Path.of("clip.gif"));
     var reader = Images.openAnimation(source);
     var output = MediaSink.of(Path.of("clip.webp"));
     var writer = Images.openAnimationWriter(ImageFormat.WEBP, reader.width(), reader.height(),
             ImageEncoderOptions.ofQuality(75).toBuilder().loopCount(reader.loopCount()).build(), output)) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        writer.write(frame);
    }
    writer.finish();
}
```

Any animation `Images.openAnimation` reads (GIF, APNG, animated WebP, or a still image as one frame) can be
written this way, and `ImageFormat.GIF` or `ImageFormat.PNG` (APNG) work in place of `ImageFormat.WEBP`. For
settings beyond quality and looping, use `WebpAnimationEncoder`, `GifAnimationEncoder` (palette size,
quantization method, dithering, palette sharing, interlacing) or `ApngEncoder` (compression, filters, a fallback image)
with their options directly:

```java
var encoder = new GifAnimationEncoder(width, height, GifEncoderOptions.builder()
        .dithering(Dithering.ORDERED).maxColors(128).loopCount(0).build());
encoder.addFrame(frame, 4); // delays in hundredths of a second
byte[] gif = encoder.finish();
```

### Read the pages of a TIFF and write one

```java
try (var source = MediaSource.of(Path.of("scan.tif"));
     var pages = Images.openPages(source)) {          // a multi-page TIFF, or the entries of an icon
    for (var i = 0; i < pages.pageCount(); i++) {
        DecodedImage page = pages.read(i);            // RgbaPicture, or Rgba16Picture for 16-bit pages
    }                                                 // with highBitDepth(true) in ImageDecoderOptions
}
byte[] tiff = TiffEncoder.encode(List.of(first, second),
        TiffEncoderOptions.deflate().toBuilder().tiles(256, 256).build());
byte[] fax = TiffEncoder.encode(scan, TiffEncoderOptions.fax().toBuilder()      // bilevel, CCITT Group 4
        .density(PixelDensity.ofDotsPerInch(300)).build());
byte[] icon = IcoEncoder.encode(logo, IcoEncoderOptions.windowsSizes());   // 16 to 256 pixels in one file
try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {         // or ImageFormat.ICO
    for (var i = 0; i < pages.pageCount(); i++) {
        writer.write(pages.read(i));                  // keeps orientation, ICC, density, EXIF and XMP
    }
    writer.finish();
}
```

`TiffDecoder.openPages` also describes each page (`TiffPages.page(i)`: compression, layout, photometric
interpretation, pixel density, ICC profile) and `IcoDecoder.entries` each icon or cursor entry (size, depth,
hotspot).

### Rotate, crop or recompress a JPEG losslessly

```java
byte[] upright = JpegTransforms.autoOrient(MediaSource.of(Path.of("photo.jpg"))); // EXIF orientation applied
byte[] rotated = JpegTransforms.rotate(MediaSource.of(Path.of("photo.jpg")), 90);
byte[] smaller = JpegTransforms.transform(source, JpegTransformOptions.builder()
        .crop(new Region(64, 32, 800, 600))
        .progressive(true)                              // coefficients moved, never re-quantized
        .build());
```

The DCT coefficients are moved and mirrored block by block, so nothing is lost; partial edge blocks are
trimmed by default, and `JpegTransformOptions.jpegtran()` reproduces `jpegtran`'s output byte for byte.
`JpegTransforms.wipe` grays out a region and `JpegTransforms.drop` pastes another JPEG image into one, both
without touching the rest of the image.

### Decode and encode JPEG 2000

```java
DecodedImage image = Images.decode(MediaSource.of(Path.of("scan.jp2")));   // JP2, JPX, JPH or a codestream
Jp2Image raw = Jp2Decoder.decodeChannels(MediaSource.of(Path.of("ct.j2k")), // every component, 1-38-bit,
        Jp2DecoderOptions.builder().reduction(2).build());                  // at a quarter of the size
byte[] lossless = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless());
byte[] layered = Jp2Encoder.encode(picture,
        Jp2EncoderOptions.lossy(20).toBuilder().compressionRatios(80, 40, 20).build());
byte[] jph = Jp2Encoder.encode(picture,                                     // HTJ2K (Part 15), a JPH file
        Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).build());
byte[] dcp = Jp2Encoder.encode(frame12Bit, Jp2EncoderOptions.forCinema2k(24)); // a DCI 2K frame
byte[] served = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(30).toBuilder()
        .regionOfInterest(new Jp2RegionOfInterest.Rectangle(100, 80, 200, 150)) // the face first
        .tilePartDivision(Jp2TilePartDivision.RESOLUTION).tlmMarkers(true).pltMarkers(true)
        .exif(exif).xmp(xmp).build());
byte[] tagged = ImageMetadataEditor.apply(served, ImageMetadataEdit.keepAll().toBuilder()
        .iptc(iptc).build());                                               // uuid boxes, codestream untouched
try (PageReader layers = Jp2Decoder.openPages(MediaSource.of(Path.of("stack.jpx")));  // JPX compositing layers
     AnimationReader movie = Jp2Decoder.openAnimation(MediaSource.of(Path.of("movie.jpx")))) { // its composition
    DecodedImage second = layers.read(1);
    AnimationFrame first = movie.read();
}
```

## Format coverage

Open a format to see what is decoded, what is encoded, the package that implements it (relative to
`com.github.auties00.smalt`), where its code comes from and what to know about patents. In *Source*, "spec"
means the code was written from the specification alone; a named project means code was ported or derived from it
(see [Acknowledgments](../README.md#acknowledgments)).

### Still images

<details>
<summary><b>JPEG</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** baseline, extended, progressive, arithmetic coding, 8-bit and 12-bit (bit-exact with `djpeg -dct int` at both depths); shrink-on-load; lossless Huffman and arithmetic (SOF3, SOF11) at 2 to 16 bits with any sampling factors, in one scan or several (bit-exact on the ITU-T T.83 compliance streams), and the RGB files of ffmpeg's lossless encoder (reversible color transform); frames of 2 or 5 to 255 components (raw channel pictures with `keepChannels`, the meaning of their channels from the ICC profile); hierarchical coding (DHP, EXP; SOF5, SOF6, SOF7, SOF13, SOF14, SOF15 differential frames over SOF0 to SOF3, SOF9 to SOF11 frames: DCT frames at 8 and 12 bits, sequential or progressive, Huffman or arithmetic, a final lossless frame, lossless hierarchies at 2 to 16 bits, any sampling factors, restart intervals, DNL; decoded at full size, exact when the last frames are lossless); JPEG-LS (ITU-T T.87: lossless and near-lossless, 2 to 16 bits, every interleave mode, subsampled components, preset coding parameters and MAXVAL, restart intervals, oversize dimensions, DNL, point transform, mapping tables as scalar maps or RGB palettes, the HP color transforms; bit-exact on the T.87 conformance data and against CharLS, the ISO/IEC 10918-7 reference software and ffmpeg); samples above 8 bits reduced to 8 bits or kept with `ImageDecoderOptions.highBitDepth` (gray and YCbCr as `Yuv16Picture`, RGB as `Rgba16Picture`); CMYK and YCCK kept as CMYK channel pictures with `keepChannels`; HDR gain maps of Ultra HDR and Adobe gain map files (ISO 21496-1 `APP2` metadata or `hdrgm` XMP, `JpegDecoder.decodeGainMap`) and Apple gain maps converted to ISO 21496-1; the secondary images of the Multi-Picture Format index or of the XMP container directory (thumbnails, stereo views, disparity and depth images, gain maps) listed and decoded (`JpegDecoder.auxiliaryImages`, `decodeAuxiliaryImage`)
- **Encode:** baseline and extended sequential (one scan or a script of several), progressive (the default script or any custom one), arithmetic coding (SOF9, SOF10, SOF11) and lossless (SOF3, SOF11: predictors 1 to 7, point transform, 2 to 16 bits, also through `ImageEncoderOptions.lossless`); 8-bit and 12-bit; YCbCr (JFIF, with pixel density) and gray, RGB, CMYK and YCCK (Adobe `APP14`); ICC profiles (`APP2 ICC_PROFILE`), EXIF (`APP1 Exif`) and XMP (`APP1`, a large packet split into Extended XMP segments); any sampling factors (4:4:0, 4:1:1 and others), custom quantization tables, slots and per-component quality, 16-bit quantizers on request; byte-identical to `cjpeg -dct int` with the matching switches (libjpeg-turbo's bit tests included) and, for CMYK and YCCK, to the TurboJPEG API; lossless transforms of any decodable non-hierarchical 8-bit or 12-bit DCT JPEG of any number of components, each with its own sampling factors, Huffman or arithmetic coded (`JpegTransforms`: rotations, flips, transpose, transverse, crop on MCU boundaries with trimming, perfect-only or exact sizes, crop extension beyond the image filled gray, flat or by reflection, wipe of a region filled the same ways, drop of another JPEG image with common or requantized tables, grayscale, sequential or progressive output with standard or optimized Huffman tables or arithmetic coding, restart markers every N MCUs or N MCU rows, marker copying, EXIF auto-orientation with the thumbnail transformed), byte-identical to `jpegtran`, and also written where `jpegtran` refuses to (a sequential file of more than four components, or of an MCU over 10 blocks, gets one scan per component); JPEG-LS (`JpegLsEncoder`): lossless and near-lossless, 2 to 16 bits, gray, RGB and Y'CbCr in its stored subsampling, every interleave mode, preset coding parameters, the HP color transforms, restart intervals, oversize dimensions, a SPIFF header, byte-identical to CharLS; Ultra HDR files with a gain map (`JpegEncoder.encode(picture, gainMap, ...)`: ISO 21496-1 and `hdrgm` XMP metadata, container directory, Multi-Picture Format index, the caller's XMP packets of both images), decoded by libultrahdr as Smalt renders them
- **Artifact:** `smalt-image`
- **Package:** `image.jpeg`
- **Source:** libjpeg-turbo and the IJG software (including jpegtran's transupp.c and jctrans.c, and the encoder's jcarith.c, jclossls.c and jclhuff.c); stb_image (Huffman lookup); CharLS (JPEG-LS); spec (ITU-T T.81 annex H for arithmetic-coded lossless, decoding and encoding, and annex J for hierarchical decoding; ITU-T T.87; Google's Ultra HDR image format and libultrahdr (gain map file layout), CIPA DC-007 (Multi-Picture Format))
- **Patents:** none in force

</details>

<details>
<summary><b>PNG, APNG</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every color type and bit depth, interlacing, animation; `cICP` with `mDCV`/`cLLI` HDR metadata
- **Encode:** PNG: every color type, the smallest lossless one chosen from the pixels (indexed and grayscale at 1, 2, 4 or 8 bits, grayscale with alpha, truecolor, truecolor with alpha; 16 bits for `Rgba16Picture` and for `Yuv16Picture` deeper than 8 bits; `tRNS` palettes and color keys), Adam7 interlacing, `sBIT`, `gAMA`, `cHRM`, `sRGB`, `pHYs`, ICC (`iCCP`) or PQ/HLG (`cICP`, `mDCV`, `cLLI`, then the ICC form in `iCCP` unless grayscale) profiles, EXIF (`eXIf`) and XMP (`iTXt XML:com.adobe.xmp`); APNG (changed rectangles, disposal and blend chosen by size, one color type for the file: indexed, truecolor or truecolor with alpha; exact fraction delays; optional fallback image; ICC or PQ/HLG profile, EXIF and XMP)
- **Artifact:** `smalt-image`
- **Package:** `image.png`
- **Source:** spec; libpng (vector filters), stb_image (Paeth)
- **Patents:** none

</details>

<details>
<summary><b>GIF</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** still and animated; ICC profiles (`ICCRGBG1` extension, as `colorProfile`) and XMP (`XMP DataXMP` extension)
- **Encode:** still and animated: exact palettes up to 256 colors, otherwise median cut or NeuQuant quantization with none, ordered, Floyd-Steinberg or Sierra dithering; binary transparency; global or local palettes; changed rectangles with transparent unchanged pixels and disposal chosen per frame; looping, interlacing; an RGB ICC profile and an XMP packet
- **Artifact:** `smalt-image` (quantizers in `smalt-core`)
- **Package:** `image.gif`, `image.gif.internal`, core `internal.quantize`
- **Source:** spec (GIF89a, ICC.1 Annex B); giflib (LZW, byte-identical), NeuQuant
- **Patents:** LZW patents expired (2004)

</details>

<details>
<summary><b>WebP</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** lossy, lossless, alpha, animated
- **Encode:** lossy (byte-identical to `cwebp` 1.6.0 at equal settings: content presets, sharp or dithered RGB to Y'CbCr, spatial noise shaping, loop filter strength, sharpness, simple filter and per-segment automatic filter, segments, entropy passes, target PSNR; also token partitions and a target size), lossless and near-lossless (byte-identical to `cwebp -lossless` and `-near_lossless`), alpha, animated; ICC profiles, EXIF and XMP (`VP8X` with `ICCP`, `EXIF` and `XMP ` chunks), still and animated
- **Artifact:** `smalt-image`
- **Package:** `image.webp`
- **Source:** libwebp
- **Patents:** Google's royalty-free WebP grant (`PATENTS` in libwebp)

</details>

<details>
<summary><b>BMP</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** Windows and OS/2 headers (core, OS/2 2.x, `BITMAPINFOHEADER` to V5) and OS/2 bitmap arrays (`BA`, the largest bitmap or the best fit for a size hint, every bitmap as a page); 1, 2, 4 and 8-bit palettes, 16 and 32-bit pixels with any color masks (scaled by rounding, 16-bit output with `highBitDepth` for masks over 8 bits), 24-bit, 64-bit linear s2.13 pixels (sRGB-encoded, 16-bit with `highBitDepth`); RLE8, RLE4, OS/2 RLE24 and Huffman 1D (ITU-T T.4 modified Huffman); embedded JPEG and PNG files; CMYK pixels and color tables (`BI_CMYK`, `BI_CMYKRLE8`, `BI_CMYKRLE4`; naive conversion, CMYK channel pictures with `keepChannels`, converted through an embedded CMYK profile on `convertToSrgb`); V5 embedded ICC profiles and V4/V5 calibrated color spaces (endpoints and per-channel gammas as `colorProfile`); damaged files decoded as far as they go (BMP Suite references)
- **Encode:** 1, 4 and 8-bit palettes (exact or quantized with median cut or NeuQuant and any dithering), RLE4 and RLE8, 16-bit 5-5-5 and 5-6-5, 24-bit, 32-bit with an unused byte or with alpha (V5 alpha mask); the smallest lossless layout by default; `BITMAPINFOHEADER`, V4 or V5 headers, top-down rows, resolution, an embedded ICC profile (`BmpEncoder`, `Images.encode`, ImageIO `smalt-bmp`)
- **Artifact:** `smalt-image`
- **Package:** `image.bmp`
- **Source:** spec (Microsoft bitmap headers, OS/2 2.x bitmap documentation, ITU-T T.4 through the TIFF fax decoder)
- **Patents:** none

</details>

<details>
<summary><b>TIFF, BigTIFF</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** strips and tiles, interleaved and separate planes, every page (`TiffDecoder.openPages`, `Images.openPages`); none, PackBits, LZW (also pre-6.0), Deflate, JPEG (with `JPEGTables`; 8-bit and 12-bit DCT samples, and lossless JPEG, lossless hierarchical JPEG and JPEG-LS strips and tiles of 2 to 16-bit samples without subsampling), old-style JPEG (8-bit), CCITT modified Huffman, T.4 and T.6 fax, ThunderScan, Zstandard (codes 50000 and 34926), LZMA (XZ streams with the delta and branch filters, or .lzma), LERC (Lerc2 versions 1-6 and legacy Lerc1, bare or in Deflate or Zstandard per `LercParameters`, masks as alpha or NaN), PixarLog (8/16-bit and float samples), NeXT 2-bit, JBIG (every self-contained ITU-T T.82 entity), WebP (8-bit RGB and RGBA), JPEG XL (codes 50002 and DNG's 52546; up to 16-bit integer and 16/32-bit floating-point samples), JPEG 2000 (code 34712 and the Aperio codes 33003 and 33005); horizontal (also on YCbCr data units, as libtiff writes it) and floating-point predictors; bilevel, gray, palette, RGB, CMYK (naive conversion, ICC profile reported), named-ink separations (`InkSet` 2, through their `nCLR` ICC profile), YCbCr (any subsampling, interleaved or in separate planes, 1-16 bits), CIE, ICC and ITU L*a*b* of any integer depth or floating point and SGILog LogL/LogLuv (rendered for display as libtiff does), associated and unassociated alpha, transparency masks (photometric 4) applied to the page they mask; 1-32-bit integer, 16/24/32/64-bit floating-point and complex (decoded to the magnitude) samples, `BitsPerSample` differing between samples (16-bit output on request); reduced-resolution pyramid levels (`SubIFDs` or reduced main-chain pages) for the size hint; EXIF and GPS directories, orientation, ICC (CMYK through its profile on `convertToSrgb`); with `keepChannels`, CMYK and named-ink (`InkSet` 2) separations, floating-point samples and extra samples kept as stored in channel pictures
- **Encode:** none, PackBits, LZW, Deflate, Zstandard, with the horizontal predictor on integer samples and the floating-point predictor on floating-point samples, CCITT modified Huffman (also word-aligned), T.4 (one- or two-dimensional, fill bits) and T.6 fax (byte-identical to libtiff's), JPEG (Technical Note 2: abbreviated strips or tiles, tables in `JPEGTables`, gray or Y'CbCr with the JPEG encoder's subsampling, quality and Huffman optimization), WebP (8-bit RGB and RGBA), JPEG 2000 (every integer layout) and JPEG XL (code 50002, or DNG's 52546 on request; every layout but palette, bilevel and Y'CbCr at 8 or 16 bits or 32-bit floating point, interleaved or in separate planes, with the channel layout GDAL writes), lossless by default; gray, RGB or CMYK (separated without color management, decoding back exactly), with or without alpha, 8 or 16 bits or 32-bit floating point; palette (1, 2, 4 or 8-bit indexes, exact or quantized); bilevel (threshold or dithering); Y'CbCr (4:4:4, 4:2:2, 4:2:0 data units, a Y'CbCr picture's own matrix and range kept); channel pictures written as stored: CMYK, named inks, gray and RGB with extra samples, 8 or 16-bit integer or 32-bit floating-point samples; interleaved or separate planes; strips or tiles; either byte order; BigTIFF; multi-page (streamed, settings per page, `Images.openPageWriter`); resolution, orientation, ICC, EXIF (Exif, GPS and interoperability directories), XMP and IPTC
- **Artifact:** `smalt-image`
- **Package:** `image.tiff`, `image.tiff.internal`
- **Source:** spec (TIFF 6.0, Technical Note 2, Adobe Deflate and floating-point predictor notes, ITU-T T.4/T.6, ITU-T T.82 for JBIG, RFC 8878 for Zstandard, the .xz format for LZMA); LibTIFF (YCbCr tables, L*a*b* and SGILog conversions, LZW schedule, predictors, ThunderScan, PixarLog, NeXT, fax line handling and coding, tag semantics); Esri LERC (Lerc2 and Lerc1 decoding); XZ for Java (LZMA and the branch filters); the strip and tile conventions of LibTIFF and GDAL for WebP and JPEG XL and of Aperio for JPEG 2000
- **Patents:** none (LZW patents expired 2004; JPEG baseline none in force)

</details>

<details>
<summary><b>ICO, CUR</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every entry (`IcoDecoder.entries`), the largest by default or the best fit for a size hint; bitmap entries of 1-32 bits with AND mask or alpha, PNG entries; cursor hotspots
- **Encode:** bitmap (32-bit BGRA with AND mask) or PNG entries, one per picture or one square entry per size scaled from one picture; cursors with a hotspot scaled per entry
- **Artifact:** `smalt-image`
- **Package:** `image.ico`
- **Source:** spec (Microsoft ICO layout); bitmap entries through the BMP decoder
- **Patents:** none

</details>

<details>
<summary><b>Netpbm (PBM, PGM, PPM, PAM), PFM, PHM</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** plain and raw rasters, every maxval (8 bits up to 255, 16 bits beyond with `highBitDepth`), several images per file as pages, PAM tuple types (gray, black and white, RGB, CMYK and their alpha forms, extra channels with `keepChannels`), float maps in both byte orders as linear BT.709
- **Encode:** every variant, plain or raw, at any maxval (`PnmEncoder`, `Images.encode`)
- **Artifact:** `smalt-image`
- **Package:** `image.pnm`
- **Source:** spec (Netpbm format documentation)
- **Patents:** none

</details>

<details>
<summary><b>TGA</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** color-mapped, true-color and gray, raw and run-length coded (runs across scanlines), 15/16/24/32-bit pixels and map entries, every origin, alpha from the attribute bits and the TGA 2.0 extension area (premultiplied alpha divided out)
- **Encode:** 8-bit gray, 24-bit BGR or 32-bit BGRA, raw or run-length coded, with a TGA 2.0 footer and extension area
- **Artifact:** `smalt-image`
- **Package:** `image.tga`
- **Source:** spec (Truevision TGA 2.0)
- **Patents:** none

</details>

<details>
<summary><b>QOI</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** QOI 1.0, bit-exact with `qoi.h`; the linear colorspace flag as linear BT.709
- **Encode:** lossless, byte-identical to `qoi.h`
- **Artifact:** `smalt-image`
- **Package:** `image.qoi`
- **Source:** spec (QOI 1.0, CC0)
- **Patents:** none

</details>

<details>
<summary><b>Radiance RGBE (HDR)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** `32-bit_rle_rgbe` and `32-bit_rle_xyze`, adaptive and old run-length scanlines, every orientation, `PRIMARIES` as the color profile, linear float pixels with `keepChannels`
- **Encode:** run-length or flat scanlines with a `PRIMARIES` line
- **Artifact:** `smalt-image`
- **Package:** `image.hdr`
- **Source:** spec (Radiance RGBE file format description)
- **Patents:** none

</details>

<details>
<summary><b>ICNS</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every element type: 1, 4 and 8-bit icons in the Mac OS palettes with their 1-bit masks, 24-bit icons (run-length coded or stored) with 8-bit masks, ARGB icons, PNG and JPEG 2000 images, the dark appearance; the largest image by default or the best fit for a size hint, every image as a page
- **Encode:** ARGB, 24-bit run-length with 8-bit mask, or PNG elements of 16 to 1024 pixels with a table of contents, one per picture or one per size scaled from one picture
- **Artifact:** `smalt-image`
- **Package:** `image.icns`
- **Source:** spec (Apple icon family layout)
- **Patents:** none

</details>

<details>
<summary><b>PSD, PSB</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** the composite image of versions 1 and 2: bitmap, gray, indexed, RGB, CMYK, multichannel, duotone and L*a*b* modes at 1, 8, 16 and 32 bits, raw, PackBits, ZIP and ZIP with prediction; transparency, alpha and spot channels (`keepChannels`), ICC, EXIF, XMP and resolution resources; a document saved without a composite (compatibility maximizing off) is rendered from its layers in the gray, duotone, RGB and CMYK modes: every blend mode, opacity and fill opacity, user masks and their density, the stored renderings of vector masks, clipping groups, isolated and pass-through groups, artboards
- **Encode:** none
- **Artifact:** `smalt-image`
- **Package:** `image.psd`
- **Source:** spec (Adobe Photoshop File Formats Specification)
- **Patents:** none

</details>

<details>
<summary><b>OpenEXR</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** single and multi-part files; scanline, tiled (mipmap and ripmap levels, chosen by the size hint), deep scanline and deep tiled parts (flattened); none, RLE, ZIPS, ZIP, PIZ, PXR24, B44, B44A, DWAA, DWAB and the methods of OpenEXR 3.4 and later (HTJ2K256 and HTJ2K32 through the JPEG 2000 decoder, LJ2K with its lossy RGB channels, ZSTD for flat and deep parts), matching the reference library sample for sample (lossy LJ2K within the rounding of its wavelet); half, float and unsigned integer channels, subsampled channels, luminance and chroma converted as OpenEXR's RGBA interface converts them, layers, display and data windows, chromaticities; every channel with `keepChannels`
- **Encode:** none
- **Artifact:** `smalt-image`
- **Package:** `image.exr`
- **Source:** OpenEXR (BSD-3-Clause)
- **Patents:** none

</details>

<details>
<summary><b>DDS</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** legacy and DX10 headers: BC1 to BC7 (bit-exact with `bcdec`), the mask-described RGB, luminance, alpha, bump and YUV formats, 8-bit palettes, the 8 to 32-bit normalized, integer and float DXGI formats, packed float and shared-exponent formats, the depth-stencil formats, `R1_UNORM`, the palette index formats (`P8`, `A8P8`, `AI44`, `IA44`, whose DX10 files carry no palette, as gray indices) and the video formats (`AYUV`, `Y410`, `Y416`, `YUY2`, `UYVY`, `Y210`, `Y216`, `NV12`, `P010`, `P016`, `NV11`, `P208`, `V208`, `V408`, converted to RGB with DirectXTex's integer arithmetic and decoded exactly as DirectXTex decodes the formats it reads); texture arrays, cube maps and volume textures with their mipmap levels as pages
- **Encode:** none
- **Artifact:** `smalt-image`
- **Package:** `image.dds`
- **Source:** spec (Microsoft DDS, block compression and DXGI format documentation); BC6H and BC7 after `bcdec` (MIT); the YUV conversions after DirectXTex (MIT)
- **Patents:** none

</details>

<details>
<summary><b>JPEG XR (HD Photo)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** the container of ITU-T T.832 Annex A (or a bare codestream) with its image and planar alpha codestreams; every bit depth (1-bit black and white, 8 and 16-bit integer, 16 and 32-bit fixed point, half and 32-bit float, 5-5-5, 5-6-5 and 10-10-10 packed, RGBE shared exponent), every internal color format (gray, YUV 4:2:0, 4:2:2 and 4:4:4, CMYK, 1 to 16 channels), interleaved alpha, every quantization (lossless, uniform, per tile, per macroblock, per channel), frequency and spatial order, tiles with soft or hard boundaries, every overlap level, trimmed flexbits, windowed images, the codec subversions 0, 1 and 9; premultiplied alpha, orientation (reported), ICC, EXIF and XMP metadata and resolution; bit-exact with `JxrDecApp`; raw YUV output as YUV pictures; CMYK and channel pictures and float samples with `keepChannels`, independent channels otherwise converted to sRGB through an embedded multi-ink (`nCLR`) profile
- **Encode:** none
- **Artifact:** `smalt-image`
- **Package:** `image.jxr`, `image.jxr.internal`
- **Source:** jxrlib 1.1 (BSD-2-Clause)
- **Patents:** none (Microsoft Community Promise)

</details>

<details>
<summary><b>ANI</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** animated cursors (RIFF `ACON`): `anih`, `rate` and `seq ` steps played forever as an `AnimationReader` that seeks to any step, `INFO` title and author (`AniDecoder.header`), icon and cursor frames (`AniDecoder.frame`) drawn on a canvas as large as the largest frame, the entry chosen by the size hint
- **Encode:** frames and steps (`AniEncoder.encode`, with `rate` and `seq ` chunks only when needed), `Images.encode` (one step), `Images.openAnimationWriter` (durations rounded to jiffies on the running end time, repeated pictures stored once); icon or cursor frames as `IcoEncoderOptions` set them; `INFO` title and author
- **Artifact:** `smalt-image`
- **Package:** `image.ico`
- **Source:** spec (Microsoft RIFF and ANI layouts); frames through the ICO decoder and encoder
- **Patents:** none

</details>

<details>
<summary><b>JPEG 2000</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** JP2 and JPX files (`jp2h`, `ihdr`, `bpcc`, `colr` enumerated and ICC chosen by precedence, `res`, `pclr`/`cmap` palettes, `cdef` channels including alpha, EXIF and XMP boxes; JPX fragment tables (fragments in other files read through `ImageDecoderOptions.dataReferences` or `Jp2DecoderOptions.dataReferences`, such as `DataReferenceResolver.directory`), several codestreams with `jpch`/`jplh` headers, `cgrp`, `opct` opacity and chroma keys, `creg` registration, `cref` cross-references, compositing layers as pages (`Jp2Decoder.openPages`) and the `comp` composition as an animation (`Jp2Decoder.openAnimation`)) and raw codestreams; tiles, subsampled components, 1-38-bit signed and unsigned samples, reference grids up to 2^32, every progression order and POC, precincts, every code-block style (bypass, reset, termination, vertical causal, segmentation symbols), SOP/EPH, PPM/PPT, scalar derived and expounded quantization, 5-3 and 9-7 wavelets, RCT/ICT, ROI max-shift; Part 2 extensions: variable DC offsets, variable deadzones, trellis coded quantization (full and approximate dequantization), precinct-dependent quantization (`QPD`/`QPC`, with the 16-bit `TPsot`/`TNsot` of their capability), derived step sizes of sub-bands split in one direction (decoded as Kakadu decodes them), arbitrary decompositions (`DFS`/`ADS`), arbitrary wavelet kernels (`ATK`), array-based and wavelet-based multiple component transformations (`MCT`/`MCC`/`MCO`/`CBD`), non-linear point transformations (`NLT`, with OpenJPH's experimental type 4 that OpenEXR's LJ2K compression writes), rectangle and ellipse regions of interest, visual masking (`VMS`, undone on irreversible components), single sample overlap wavelet transformations (the 5-3, 9-7 and whole-sample symmetric `ATK` kernels) and tiles overlapping by one row and column (TSSO); quality-layer and resolution reduction (size hints decode a lower resolution); sYCC, e-sRGB, e-sYCC, YCbCr(1-3), YPbPr, PhotoYCC, CMY, CMYK, YCCK, CIE L*a*b* (with its enumerated parameters and illuminant), CIECAM97s J*a*b* and ROMM converted to RGB (CMYK and its alpha kept as a channel picture with `keepChannels`); tiles and code-blocks decoded in parallel; HTJ2K (ISO/IEC 15444-15): HT cleanup, SigProp and MagRef passes, placeholder passes, several HT sets (the last one is decoded), HT and Part 1 code-blocks mixed in a tile-component, code-blocks of up to 74 magnitude bit-planes (38-bit samples, and regions of interest whose shift reaches the HTJ2K bound of 2^74, in 128-bit arithmetic), U-VLC extensions, regions of interest, `CAP` and `CPF` segments, and JPH files (the `jph ` brand, the any-ICC and parameterized `colr` methods), every HTJ2K conformance codestream and JPH file of ISO/IEC 15444-4:2024 decoded within the statistics of its Kakadu transcode; bit-exact with `opj_decompress` built without `-ffast-math`, HTJ2K included wherever OpenJPEG decodes it
- **Encode:** lossless 5-3 and lossy 9-7 (compression ratios or PSNR targets per quality layer, rate allocation by post-compression rate-distortion) of signed and unsigned channels of 1 to 38 bits, JP2 (with `colr`, `cdef` for alpha or premultiplied alpha, `bpcc`, the `res` capture resolution) or raw codestream, CMYK channel pictures as inks, tiles, image and tile origins on the reference grid, code-block sizes, precinct sizes per resolution, progression orders and progression changes (`POC`), tile-parts by resolution, layer or component, `TLM` and `PLT` markers, code-block styles, SOP/EPH, RCT/ICT, guard bits, regions of interest by max-shift (a whole component, as `opj_compress -ROI`, or a rectangle of every component, its `RGN` segments in the tile-part headers), codestream and per-component size limits, the digital cinema 2K and 4K and IMF profiles (`Jp2EncoderOptions.forCinema2k`, `forCinema4k`, `forImf`, with their image checks), comments, and EXIF, XMP and IPTC `uuid` boxes; lossless output byte-identical to `opj_compress` with the same options (`-d`, `-T`, `-TP`, `-TLM`, `-PLT`, `-POC`, `-ROI`, `-GuardBits`, `-c` lists and `-IMF` included); HTJ2K (`highThroughput`): one HT set per code-block (cleanup, SigProp and MagRef passes), its bit-plane chosen per code-block by rate-distortion optimization over the layers' targets, vertically causal contexts, JPH files or HTJ2K codestreams, decoded by `opj_decompress` and OpenJPH's `ojph_expand` as Smalt decodes them (single-layer output; `opj_decompress` also the layers that keep each code-block in one packet)
- **Artifact:** `smalt-image`
- **Package:** `image.jp2`, `image.jp2.internal`
- **Source:** OpenJPEG 2.5.3; OpenJPH 0.21.3 and OpenHTJ2K (HTJ2K); spec (ISO/IEC 15444-2, Rec. ITU-T T.801) and the CIE TC1-34 CIECAM97s report for Part 2 and JPX
- **Patents:** none known in force for Part 1 (ISO/IEC 15444-1 was made available royalty-free); Part 15 (HTJ2K) was developed to be royalty-free, like Part 1

</details>

<details>
<summary><b>JPEG XL</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** bare codestreams and containers (`jxlc`, `jxlp`, `Exif`, `xml `, `jbrd`, every `brob` box decompressed, all boxes listed by `JxlDecoder.readBoxes`); lossless and lossy modular (MA trees, every predictor, RCT, palette, squeeze), VarDCT (every transform, chroma from luma, adaptive LF smoothing, LF frames, progressive passes), Gaborish and EPF, patches, splines, noise, upsampling; animation (blending, reference frames, crops) as `AnimationReader`; XYB, YCbCr and enumerated color encodings and ICC profiles (ICC as `colorProfile`, gray and RGB XYB images converted to their embedded profile, HDR PQ/HLG described by CICP), CMYK through its profile; spot color channels composited into RGBA output as libjxl renders them (kept apart in channel pictures and `decodeFloat`); alpha (straight or premultiplied) and extra channels (`JxlDecoder.decodeFloat`: every channel at full precision as planes; `keepChannels`: CMYK, floating-point and extra-channel images as channel pictures); 8-bit by default, 16-bit with `highBitDepth`; orientation reported; the preview frame (`JxlDecoder.decodePreview`), and under a size hint the preview, the LF image (1/8) of a VarDCT frame or a decode from the first progressive passes (1/2, 1/4); gain maps (`jhgm`, plain or in `brob`: ISO 21496-1 metadata, alternate color space, gain map image, `JxlDecoder.decodeGainMap`, `JxlGainMap.toGainMap` for the format-independent model); seeks starting at the independent frames of the `jxli` frame index; recompressed JPEG files rebuilt byte for byte (`JxlDecoder.reconstructJpeg`)
- **Encode:** lossless modular frames: global and per-group palettes and channel compaction, reversible color transforms (YCoCg, or the best of up to 19 per group), meta-adaptive context trees learned from the image at efforts 4 to 10 (every predictor at effort 10) and fixed gradient or weighted-predictor trees at efforts 1 to 3, the fast lossless path, runs coded as LZ77 copies; 8 or 16-bit integer and 32-bit floating-point samples; gray, RGB and CMYK with alpha and extra channels (depth, spot color, selection mask, thermal, others); lossy VarDCT frames in XYB at a Butteraugli distance (`JxlEncoderOptions.distance`; `ofQuality` maps `cjxl -q`), with Gaborish and edge-preserving filtering and lossless extra channels; lossless JPEG recompression (`JxlEncoder.recompressJpeg`: the quantized coefficients with integer chroma from luma and a `jbrd` box, the ICC profile, EXIF and XMP moved to the codestream and their boxes, rebuilt byte for byte by `JxlDecoder.reconstructJpeg` and djxl); animations (`JxlAnimationEncoder`, `Images.openAnimationWriter`: millisecond ticks, changed rectangles coded over a reference frame, repeated frames merged, loop count); enumerated sRGB, PQ and HLG color encodings or an embedded ICC profile; orientation; `Exif` and `xml ` boxes, and a `jxll` box for images that need conformance level 10
- **Artifact:** `smalt-image`
- **Package:** `image.jxl`, `image.jxl.internal.*`
- **Source:** jxl-oxide (decoder, JPEG reconstruction); libjxl (palette, transfer functions, render order, spot color stage, gain map bundle, MCU grid of single-component JPEG scans; the encoder: entropy coding, tree learning, transforms, quantization, JPEG recompression); Brotli (reference Java decoder, dictionary, Huffman code construction)
- **Patents:** libjxl's royalty-free patent grant (`PATENTS` in libjxl)

</details>

<details>
<summary><b>EXIF</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** IFD0, Exif, GPS, interoperability and thumbnail directories; the EXIF 3.0 `UTF-8` type (129) and the `IFD` type (13); JPEG and uncompressed (strip) thumbnails
- **Encode:** fresh layout, or an edit that keeps unchanged values (the maker note, sub-directory offsets of type 13, a thumbnail's strips) at their offsets and zeroes what it removes; uncompressed thumbnails as one strip
- **Artifact:** `smalt-image`
- **Package:** `image.exif`
- **Source:** spec (EXIF 2.32 and CIPA DC-008-2023 (EXIF 3.0), TIFF 6.0 and Technical Note 1)
- **Patents:** none

</details>

### Video codecs

<details>
<summary><b>VP8</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes (also the core of lossy WebP); transparent WebM video (`AlphaMode` 1, the alpha frames of `BlockAdditional` 1) into pictures with an alpha plane, bit-exact with libvpx
- **Encode:** real-time (one-pass constant bitrate, libvpx speeds 4-16); good quality (`Vp8EncoderOptions.ofBitrate`, `ofQuality`, `forArchive`: lookahead of up to 25 frames, temporally filtered hidden alternate references in packets of their own flagged `PacketFlag.DISCARD`, rate-distortion mode decision with split motion vectors at speeds 0-3, trellis quantization at speed 0, variable bitrate, constrained quality and constant quality, one pass or two passes with `Vp8FirstPass`, libvpx good speeds 0-4); best quality (`Vp8EncoderUsage.BEST_QUALITY`, libvpx `--best`: the good-quality coding with the lowest mode thresholds and the full split search, exhaustive for poorly predicted partitions); transparent video (`Vp8EncoderOptions.alpha`, `VideoEncoderOptions.alpha`, `VideoWriterOptions.alpha`): the alpha planes as a second VP8 stream at a constant quantizer (`alphaQuality`), in the packets' `PacketSideData.alphaFrame`, with key frames wherever the main stream has them, as WebM stores it
- **Artifact:** `smalt-image`
- **Package:** `video.vp8`
- **Source:** libvpx, libwebp
- **Patents:** Google's royalty-free VP8 grant (`PATENTS` in libvpx)

</details>

<details>
<summary><b>Motion-JPEG</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** gray and YCbCr frames, DCT or lossless (SOF3 and SOF11, the RGB frames of ffmpeg's `ljpeg` included), as the JPEG decoder reads them (`MjpegDecoder`, also for camera buffers); interlaced frames of two field images (OpenDML `AVI1`, QuickTime `mjpa`) woven in the track's field order, single-field frames of interlaced tracks doubled; QuickTime Motion-JPEG format B (`mjpb`); frames above 8 bits rounded to 8 bits unless `VideoDecoderOptions.highBitDepth`
- **Encode:** baseline frames (`MjpegEncoder`, also through `VideoEncoders`), each the file `JpegEncoder` writes: optimized or Annex K Huffman tables, the AVI convention without DHT segments, restart markers, interlaced frames as two field images with `AVI1` segments, constant quality or rate control
- **Artifact:** `smalt-image`
- **Package:** `image.jpeg`
- **Source:** libjpeg-turbo and the IJG software; Apple QuickTime File Format (`mjpa`, `mjpb`); OpenDML AVI File Format Extensions 1.02
- **Patents:** none in force

</details>

<details>
<summary><b>Motion JPEG 2000</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every JPEG 2000 image the JP2 decoder reads, as a JP2 file, a raw codestream or the Contiguous Codestream boxes of an ISO/IEC 15444-3 sample (`Mj2Decoder`, also through `VideoDecoders`), under a `jp2h` box among the frame's boxes or else the track's (from the `mjp2` sample entry) when the frame is no JP2 file; gray and sYCC frames as planar Y'CbCr of their stored subsampling (4:0:0, 4:4:4, 4:2:2, 4:2:0) without color conversion, others (RGB, alpha, palettes, the other color spaces) as RGBA; above 8 bits rounded to 8 unless `highBitDepth`; interlaced frames of two field codestreams woven in the track's field order; MJ2, MP4 and QuickTime `mjp2` (and the `mp4v` object type 0x6E), Matroska `V_JPEG2000` (and `V_MS/VFW/FOURCC`, `V_QUICKTIME` `mjp2`), AVI `MJ2C`, `mjp2`, `LJ2C`, `LJ2K`, `IPJ2`, `AVj2`
- **Encode:** `Mj2Encoder` (also through `VideoEncoders`) over `Jp2Encoder`: gray, 4:2:0, 4:2:2 or 4:4:4 Y'CbCr declared sYCC, or RGB and RGBA through the component transform, 8 to 16 bits, lossless or lossy with every `Jp2EncoderOptions` setting (HTJ2K and the cinema profiles included), JP2 file or codestream frames, interlaced frames as two field codestreams, a per-frame size cap from a target bitrate; the track configuration is the frames' `jp2h` box
- **Artifact:** `smalt-image`
- **Package:** `image.jp2`
- **Source:** OpenJPEG 2.5.3 (codec); ISO/IEC 15444-3 file format as OpenJPEG 1.5's `mj2` tools write it; the Matroska codec specification (`V_JPEG2000`)
- **Patents:** as JPEG 2000

</details>

<details>
<summary><b>PNG, TIFF and GIF video</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** PNG (QuickTime `png `, AVI and Matroska `MPNG`/`PNG1`, MP4 `mp4v` of object type `0x6D`), TIFF (QuickTime `tiff`, Matroska `V_QUICKTIME`) and GIF (QuickTime `gif `, Matroska `V_QUICKTIME`) tracks: every frame decodes as the image decoder decodes the file (`PngVideoDecoder`, `TiffVideoDecoder`, the first image of a TIFF frame; `GifVideoDecoder` composes each frame over the previous one by its disposal method), 16-bit samples kept with `VideoDecoderOptions.builder().highBitDepth(true)`; pixel for pixel equal to ffmpeg's decode
- **Encode:** PNG and TIFF (`PngVideoEncoder`, `TiffVideoEncoder`: every frame the file the image encoder writes, lossless for 8 and 16-bit RGB, RGBA and gray), in QuickTime, AVI (PNG) and Matroska; decoded by ffmpeg to the source pixels
- **Artifact:** `smalt-image`
- **Package:** `image.png`, `image.tiff`, `image.gif`
- **Source:** the image formats' specifications; Apple QuickTime File Format
- **Patents:** none

</details>

<details>
<summary><b>Vidvox HAP</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every texture format: HAP (DXT1), HAP Alpha (DXT5), HAP Q (scaled YCoCg DXT5), HAP Q Alpha (with an RGTC1 alpha texture), HAP Alpha Only (RGTC1), HAP R (BC7) and HAP HDR (BC6H, signed and unsigned); uncompressed, Snappy and chunked frames, the chunks expanded in parallel; QuickTime `Hap1`/`Hap5`/`HapY`/`HapM`/`HapA`/`Hap7`/`HapH`, AVI and Matroska (`HapDecoder`)
- **Encode:** every texture format (`HapEncoder`, `HapFormat`): BC1 blocks fitted along the principal axis of each block, BC3 alpha and BC4 blocks fitted by least squares and an endpoint search, in their six-value mode where it is closer, scaled YCoCg, BC7 mode 6 and unsigned BC6H mode 11, in one or more chunks, Snappy compressed where it helps; QuickTime, AVI and Matroska; decoded by ffmpeg
- **Artifact:** `smalt-image`
- **Package:** `image.hap`
- **Source:** Vidvox HAP specification, the S3TC, RGTC and BPTC extensions, the Snappy format description, van Waveren and Castano's Real-Time YCoCg-DXT Compression
- **Patents:** none

</details>

## Working with image files

What the decoders and encoders of every image artifact share. The entry points (`Images`, `Media.probe`,
`ImageDecoderOptions`, `ImageOps`) are in `smalt-core`.

<details>
<summary><b>Probing, pages and animations</b></summary>

`Media.probe` describes every image format above from its headers alone, without the image
artifact: size, bit depth, transparency, EXIF or `irot`/`imir` orientation, and for GIF, APNG and
animated WebP the frame count, loop count and duration, for TIFF (and BigTIFF) the page count and for
icons the entry count, for animated cursors the step count, loop and duration (for JPEG XL, the image header: size, orientation, depth, alpha and whether it animates). `Images.openPages` reads the pages of a multi-page TIFF, the entries of an icon
and the master images of a HEIF file (HEIC or AVIF, each page decoded by the artifact of its coding, with the file's
stereo pairs, bursts and other entity groups as `PageReader.groups`) in any order (pages are independent images, not
timed frames); every other image is one page. `Images.openPageWriter`
writes them: one TIFF directory per page (a `DecodedImage` page keeps its orientation, color profile, EXIF and XMP),
or one icon entry per page (larger pages are scaled into a 256-pixel square). `Images.openAnimation` readers of GIF, APNG,
WebP, JPEG XL and the AVIF and HEIC image sequences can `seek(micros)`, and so can the animated cursor (ANI) reader
and the one-frame reader of every still image. `Images.openAnimationWriter` writes
animated WebP, GIF, APNG and ANI: GIF delays are hundredths of a second, APNG delays fractions of 16-bit terms
and ANI display times jiffies (sixtieths of a second), and these writers round each frame's end time, never its
duration, so the rounding does not accumulate. An animated cursor always loops forever.

</details>

<details>
<summary><b>Color profiles</b></summary>

Color profiles: the JPEG (`APP2 ICC_PROFILE`), PNG (`iCCP`, `cICP`, `sRGB`, `gAMA`, `cHRM`), WebP (`ICCP`), GIF
(`ICCRGBG1` application extension) and BMP (V5 embedded profile, V4 and V5 calibrated color space) decoders report the declared color space in `DecodedImage.colorProfile()` and convert to sRGB
with `ImageDecoderOptions.builder().convertToSrgb(true)` (CMYK and YCCK JPEGs through their embedded CMYK
profile); a high-dynamic-range profile (PNG `cICP` with its `mDCV` and `cLLI` metadata, or an ICC profile whose
version 4.4 `cicp` tag declares PQ or HLG, in any of these formats, TIFF, JPEG 2000, HEIC or AVIF) reports its
description in `ColorProfile.colorInfo()`. The JPEG, PNG, APNG, WebP, animated WebP, GIF, animated GIF, TIFF, JPEG 2000 and BMP encoders
embed the profile set with `colorProfile(...)` on their options, a PQ or HLG profile in its ICC form (a version 4.4
matrix/TRC profile with a `cicp` tag); the PNG and APNG encoders write a PQ or HLG profile as `cICP`, `mDCV` and
`cLLI` followed by that ICC form in `iCCP` (left out of a grayscale PNG, which an RGB profile cannot describe).

</details>

<details>
<summary><b>Bit depth</b></summary>

Decoders output 8-bit pictures by default, rounding 9 to 16-bit video and 16-bit PNG and TIFF; the AV1, VP9,
H.264, HEVC, ProRes, DNxHD, AVIF, HEIC, PNG, TIFF and JPEG XL decoders keep the full depth on request
(`highBitDepth(true)` on their options' builder, giving `Yuv16Picture` or `Rgba16Picture`). The AVIF and HEIC
animation readers then return `Rgba16Picture` frames for sequences deeper than 8 bits; `AnimationFrame.picture()`
is an `RgbaPicture` otherwise.

</details>

<details>
<summary><b>Decoding a region</b></summary>

Regions: `ImageDecoderOptions.builder().region(x, y, width, height)` decodes a rectangle of the stored image, every
decoder giving exactly the crop of its whole decode (`ImageOps.crop`) with the metadata of the whole image; with a
size hint, the hint applies to the rectangle and the decoder returns the rectangle of its reduced picture. The TIFF,
JPEG, JPEG 2000, WebP, HEIC and AVIF decoders decode only what the rectangle needs, and their limits bound what they
decode rather than the image, so a region of a tiled slide or satellite image larger than `MediaLimits.maxPixels`
decodes: the strips or tiles of a TIFF page (and of the reduced-resolution level a hint selects) that it intersects,
read alone from the source; the tiles of a JPEG 2000 codestream that hold its samples and, in them, the code-blocks
those samples depend on, a raw codestream or a file of one codestream reading only those tiles' data
(`Jp2DecoderOptions.region` decodes the channels of an area, as `opj_decompress -d` does); the grid images of a HEIC
or AVIF grid that it intersects, with their alpha images; the MCUs of a JPEG frame from its first row to the last
row it needs, those outside it entropy-decoded without being transformed, and the restart intervals it does not
meet skipped altogether; and the rows of a WebP image down to its last one. The other decoders decode the whole
image and crop it, and so does `Images` for the providers of other libraries (`ImageCodecProvider.decodesRegions`).
The ImageIO reader of `smalt-desktop` decodes the source region of a read that does not subsample.

</details>

<details>
<summary><b>CMYK, separations, extra channels and floating-point samples</b></summary>

What packed RGBA and planar Y'CbCr cannot hold, CMYK and multi-ink separations, extra channels and
floating-point samples, is converted to RGBA by default and kept on request with
`ImageDecoderOptions.builder().keepChannels(true)`: the JPEG (CMYK, YCCK), TIFF (separated pages of any ink set,
floating-point pages, extra samples), JPEG XL (CMYK, floating-point, extra channels) and JPEG 2000 (CMYK) decoders
then return a
`ChannelPicture`, a `Channel8Picture`, `Channel16Picture` or `ChannelFloatPicture` whose `PixelLayout` names
each channel (gray, RGB, the CMYK inks or named inks, then alpha, spot, depth or unspecified extras), with the
embedded profile as its color profile. `ImageOps.toRgba` and `toRgba16` convert one without color management,
giving exactly the default decoder output for CMYK JPEGs with an Adobe segment and for the TIFF pages
`TiffDecoder` lists; `ImageOps.toSrgb(DecodedImage)` and `convertColor` convert it through its profile (a CMYK
JPEG, or a CMYK TIFF without alpha, exactly as `convertToSrgb` does); the TIFF encoder writes it back sample for
sample, and the JPEG 2000 encoder a CMYK one with its alpha. Named inks and spot colors are matched by name to the
colorants of a multi-ink (`nCLR`) profile (`ColorProfile.colorants()`), so a separation converts through a profile
that lists its inks in another order, and a CMYK picture with spot channels through a profile that adds them to the
process inks; `convertColor` to a multi-ink profile separates an RGB picture into inks named after the target's
colorants:

```java
var inks = ColorProfile.parse(hexachromeIcc);                          // 6CLR: Cyan, Magenta, ..., Orange, Green
var separation = (ChannelPicture) ImageOps.convertColor(rgba, ColorProfile.srgb(), inks);
var preview = ImageOps.convertColor(separation, inks, ColorProfile.srgb());
```

</details>

## Metadata

`image.metadata` (`smalt-image`) reads the metadata of every image format and of camera raw files, and edits it
in JPEG, PNG, WebP, GIF, HEIC, AVIF, TIFF, DNG, JPEG XL, JPEG 2000 and BMP files and the camera raw files with a TIFF signature
without re-encoding the pixels (`ImageMetadata`, `ImageMetadataEdit`, `ImageMetadataEditor`).
`metadata` (`smalt-core`) reads and writes the tags, cover art, chapters and XMP packets of audio and video files
(`TagEditor`, `MediaTags`, with `TagEditor.stripLocation` removing where a video or a recording was made), holds the
tag vocabulary `Media.probe` shares (`StandardTag`), and models XMP packets (`XmpPacket`). The image decoders return the embedded XMP packet with the pixels, as
`DecodedImage.xmp()` (JPEG, with Extended XMP merged; PNG; WebP; GIF; TIFF; JPEG 2000; JPEG XL; HEIC; AVIF), next to
the EXIF block and the ICC profile, and the physical pixel density (or only the pixel aspect ratio) the file
declares, as `DecodedImage.density()`: a `PixelDensity` in the unit the file uses (the JFIF header of a JPEG, or
when it is absent or gives only an aspect ratio a physical resolution in its EXIF `IFD0`; the PNG `pHYs` chunk; the TIFF `XResolution`, `YResolution` and
`ResolutionUnit` fields; the BMP pixels per meter; the JPEG 2000 capture resolution box, or the display one). The
JPEG, PNG, TIFF, BMP and JPEG 2000 encoders write it from their `density` options, each in the unit and precision its
field holds, and the TIFF page writer and the ImageIO reader (`Dimension/HorizontalPixelSize`) carry it. The
encoders write metadata too: `ImageEncoderOptions` carries a color profile, an EXIF block, an XMP packet and an
orientation to every encoder behind `Images.encode`, `Images.openAnimationWriter` and `Images.openPageWriter`
(`ImageEncoderOptions.Builder.metadataOf(decoded)` takes them from a decoded image), and the JPEG, PNG, APNG, WebP
and animated WebP encoders take `exif` and `xmp` options (GIF an `xmp` one) that embed the bytes as given, the JPEG
encoder splitting a large packet into Extended XMP segments. A format records what the list below gives for it;
a profile it cannot record (in ICO and ANI files, a non-RGB profile in GIF, WebP, BMP and AVIF, CMYK inks in a
format without CMYK) converts the pixels to sRGB instead, and a still format without an orientation field (GIF,
BMP, ICO, ANI) turns the picture upright. The EXIF block is embedded byte for byte, maker note included, with
only its orientation patched in place; TIFF and JPEG 2000 rebuild it from its entries. Everything
here is written from the specifications (EXIF 2.32, XMP parts 1-3, IPTC
IIM 4.2, ID3v2.3/2.4, APEv1/APEv2, Lyrics3, Vorbis comment, FLAC format, Apple QTFF, its keyed metadata and iTunes
metadata, 3GPP TS 26.244 asset boxes, ISO 6709, Matroska tags); the tests use exiftool and ffprobe as independent
readers.

What is read and edited, format by format:

- **JPEG**
  - *EXIF:* `APP1 Exif`
  - *XMP:* `APP1`, Extended XMP split and merged
  - *IPTC-IIM:* `APP13` Photoshop resources (other resources kept, digest recomputed)
  - *ICC:* `APP2 ICC_PROFILE` chunks
  - *Comments, text:* `COM`
  - *Editing:* yes; the scan data is copied, `APP2 MPF` image offsets are corrected
- **PNG, APNG**
  - *EXIF:* `eXIf`
  - *XMP:* `iTXt XML:com.adobe.xmp`
  - *IPTC-IIM:* none
  - *ICC:* `iCCP` (a replaced profile also replaces `sRGB`, `cICP`, `mDCV` and `cLLI`; a PQ or HLG profile is written as `cICP`, `mDCV`, `cLLI` and `iCCP`)
  - *Comments, text:* `tEXt`, `zTXt`, `iTXt`
  - *Editing:* yes; the image chunks are copied
- **WebP**
  - *EXIF:* `EXIF`
  - *XMP:* `XMP `
  - *IPTC-IIM:* none
  - *ICC:* `ICCP`
  - *Comments, text:* none
  - *Editing:* yes; the `VP8X` flags follow the chunks
- **GIF**
  - *EXIF:* none
  - *XMP:* `XMP DataXMP` application extension
  - *IPTC-IIM:* none
  - *ICC:* `ICCRGBG1` application extension (RGB profiles)
  - *Comments, text:* comment extensions
  - *Editing:* yes (XMP, ICC, comments)
- **HEIC, AVIF**
  - *EXIF:* `Exif` item
  - *XMP:* `mime` item
  - *IPTC-IIM:* none
  - *ICC:* `colr` property of the primary item (`prof` or `rICC`; the `nclx` one is kept)
  - *Comments, text:* none
  - *Editing:* EXIF and XMP items, ICC property (replaced in place, or added and associated when shared; dropped when unused); `meta` rebuilt, item offsets shifted, removed item data zeroed; image sequences too, the `stco`, `co64`, `saio`, `tfhd` and `tfra` offsets of their tracks moved with the data
- **TIFF, BigTIFF**
  - *EXIF:* the descriptive entries of IFD0 and its sub-directories
  - *XMP:* tag 700
  - *IPTC-IIM:* tag 33723, or the Photoshop resources of tag 34377 (edited there too)
  - *ICC:* tag 34675
  - *Comments, text:* none
  - *Editing:* yes; IFD0 and its EXIF directories are rebuilt in place, layout entries, strips, tiles and other pages keep their offsets, freed values are zeroed
- **DNG; CR2, NEF, NRW, ARW, SR2, SRF, PEF, SRW**
  - *EXIF:* as TIFF (DNG tags kept out)
  - *XMP:* as TIFF
  - *IPTC-IIM:* as TIFF
  - *ICC:* as TIFF
  - *Comments, text:* none
  - *Editing:* yes, as TIFF; the sensor data keeps its offsets
- **ORF, RW2**
  - *EXIF:* as TIFF, from a TIFF structure with the vendor's signature (Panasonic sensor tags below 254 left out)
  - *XMP:* as TIFF
  - *IPTC-IIM:* as TIFF
  - *ICC:* as TIFF
  - *Comments, text:* none
  - *Editing:* read only
- **CR3**
  - *EXIF:* `CMT1`, `CMT2` and `CMT4` of the Canon `uuid` box, combined (the `CMT3` maker note left out)
  - *XMP:* XMP `uuid` box
  - *IPTC-IIM:* none
  - *ICC:* none
  - *Comments, text:* none
  - *Editing:* read only
- **RAF**
  - *EXIF:* the JPEG preview's `APP1 Exif`
  - *XMP:* the JPEG preview's XMP
  - *IPTC-IIM:* none
  - *ICC:* none
  - *Comments, text:* none
  - *Editing:* read only
- **MRW**
  - *EXIF:* the TIFF structure of the `TTW` block
  - *XMP:* none
  - *IPTC-IIM:* none
  - *ICC:* none
  - *Comments, text:* none
  - *Editing:* read only
- **JPEG XL**
  - *EXIF:* `Exif` box (or `brob`)
  - *XMP:* `xml ` box (or `brob`)
  - *IPTC-IIM:* none
  - *ICC:* codestream profile (read)
  - *Comments, text:* none
  - *Editing:* EXIF and XMP boxes replaced by plain ones in place or before the codestream, kept boxes copied byte for byte (compressed ones included), a bare codestream wrapped in a container; the codestream and `jbrd` copied unchanged
- **JPEG 2000 (JP2, JPX)**
  - *EXIF:* `uuid` box `JpgTiffExif->JP2`
  - *XMP:* XMP `uuid` box, or an `xml ` box holding XMP
  - *IPTC-IIM:* IPTC `uuid` box
  - *ICC:* `colr` of the JP2 header (restricted or any ICC, chosen by precedence; a new profile is written as restricted ICC ahead of the enumerated specifications, removal leaves or adds an enumerated one)
  - *Comments, text:* none
  - *Editing:* yes; EXIF, XMP and IPTC boxes replaced in place or added after the JP2 header, the codestream boxes copied unchanged; raw codestreams and files with fragment tables read only
- **BMP**
  - *EXIF:* none
  - *XMP:* none
  - *IPTC-IIM:* none
  - *ICC:* V5 embedded profile (a new profile follows the pixels; a smaller header is widened to V5, its masks and color table carried over; removal declares sRGB)
  - *Comments, text:* none
  - *Editing:* ICC only; the color table and pixel data are copied unchanged

## Known limits

> [!WARNING]
> These are the cases where Smalt rejects a file, decodes it differently from another library, or leaves a
> feature out. Open the entry of the format you work with.

### Still image codecs

<details>
<summary><b>Interchange raster formats</b></summary>

A PSD or PSB document saved without a composite is rendered from its layers in floating
point with a single rounding, so a sample can be a unit away from the composite Photoshop would save (Photoshop
rounds its 8-bit blends down), more where Photoshop re-renders a gradient fill; dissolve scatters its pixels in a
pattern of its own. Layers that need an adjustment, a layer effect (effects that are present but disabled are fine),
knockout, Blend If ranges, channel restrictions, a feathered mask, mask densities beside both a user and a vector
mask, a disabled user mask beside a vector mask, a vector mask stored without its rendering, a clipping group whose
clipped layers do not blend as a group, a CMYK artboard with a colored background, a fill opacity below 100% in one
of the modes that apply it inside the blend (color burn, linear burn, color dodge, linear dodge, vivid light, linear
light, hard mix, difference), or pixels Photoshop does not store (the fill and shape layers of 16 and 32-bit
documents, and of documents other applications wrote without them) make the document unsupported, as do the layers
of L*a*b*, multichannel, indexed and bitmap documents. A saved composite of a
transparent RGB or gray document decodes as stored, its colors matted on white, where a rendered one has straight
colors. DDS's opaque `420_OPAQUE` video format and TGA's Huffman
types (32 and 33) are unsupported. OpenEXR's DWA methods decode through the reference library's scalar
transforms, which its vectorized ones may round one half unit apart in a few samples (a few units once mapped to
linear values); deep parts decode flattened. LJ2K's lossy channels go through a floating-point irreversible wavelet
that rounds apart from OpenJPH's in the last units of some samples. ICNS writes no dark appearance. JPEG XR follows
jxrlib where it departs from the standard: the orientation of a file is the container's (the image header's only
for a bare codestream), and a file in spatial order with trimmed flexbits and interleaved alpha decodes as jxrlib
decodes it. JPEG XR files whose output format is raw YUV, which `JxrDecApp` cannot write, decode to YUV pictures
checked against the same codestreams relabeled as RGB, not against a reference YUV output. There are no PSD,
OpenEXR, DDS or JPEG XR encoders.

</details>

<details>
<summary><b>JPEG 2000 decoding</b></summary>

JPEG 2000 decodes no Part 2 code-block anchor points, block coder extensions or TBDWT flag of the single sample
overlap parameters, which no edition of ISO/IEC 15444-2 defines (rejected as unsupported). The single sample overlap
transformation uses the cell sizes of the normative inverse procedure (`XC / 2^lev` at level `lev`), where the
informative forward procedure starts from `XC`, and divides a sample on a cell boundary by the factor `B_s` of
equation G-1 at each lifting step that updates its parity, where the informative 9-7 example writes other factors
(a stream whose cells of one sample meet a `B_s` of zero, as the irreversible 5-3 kernel's `B_0`, is rejected as
malformed); visual masking applies to irreversible components only (annex E defines it for them), a reversible
component decoding as if no `VMS` segment applied. A `DFS` segment with fewer elements than the decomposition levels
Kakadu means (it writes one per level of `Cdecomp`, reusing the last for the remaining levels) decodes with as many
levels as elements. Variable deadzones reconstruct a negative index as the mirror of the positive one, as the
quantizer of ISO/IEC 15444-2 equation C-4 implies, where equation C-3 as printed subtracts the deadzone on both
sides; trellis coded code-blocks whose last cleanup pass was not decoded use the approximate dequantization of D.4.2.
JPX codestream fragments in other files are rejected unless the data reference resolver of the options supplies
the files, and compositing layer extension boxes (`jclx`) are ignored, as ISO/IEC 15444-2 allows. A JPX
composition scales layers by sample replication, and an instruction set that repeats indefinitely stops after
the first of its executions that assigns no new compositing layer. CIECAM97s J*a*b* samples convert to sRGB under
the viewing conditions of the first worked example of the CIE TC1-34 report (D65 white, 318.31 cd/m^2, 20%
background, average surround), since the file format signals none, reading a and b as `C cos h` and `C sin h`.
No Part 2 encoder. The HTJ2K encoder codes one HT set per code-block,
at the precision the last quality layer needs, so an earlier layer holds a code-block's cleanup pass,
possibly without its refinement passes, or nothing of it. OpenJPEG 2.5.3 misreads layered HTJ2K output whose
refinement passes follow a code-block's cleanup pass in a later layer, which a lossy last layer gives, and
OpenJPH reads single-layer codestreams only. The HTJ2K encoder codes at most
30 magnitude bit-planes, which limits lossless HTJ2K encoding to channels of up to 27 bits, and lossless coding
with the ISO/IEC 15444-1 block coder is exact for channels of up to 24 bits only.
The parameterized color specification of a JPH file is applied to full-range R'G'B' samples only, and others
are ignored. A 12-bit Huffman-coded output of the lossless JPEG transforms always has optimized Huffman
tables. 16-bit DCT JPEG, which T.81
does not define, is rejected as unsupported.

</details>

<details>
<summary><b>Hierarchical JPEG</b></summary>

Hierarchical JPEG decodes at full size only (a size hint does not stop at a smaller frame), and the inverse DCT
of its differential frames is the accurate integer transform without the level shift, whose output T.81 leaves
to the decoder: its DCT frames decode within a few levels of the ISO/IEC 10918-7 reference software, which has
its own transform, while its lossless frames decode exactly as J.1.1.2 upsamples their references. The
reference software's encoder rounds those references its own way, so its lossless hierarchies decode one level
off its source on some interpolated samples (a sample below zero is clamped to zero), and it takes the reference
of a lossless frame over DCT frames from the DCT reconstruction before the clamp of T.81 A.3.1, so its samples
near the ends of the range decode off its source by the excess. The lossless transforms reject hierarchical
images.

</details>

<details>
<summary><b>JPEG-LS</b></summary>

JPEG-LS mapping tables of entries of 4 to 255 bytes, and tables mapping the components of one frame to
different precisions, are rejected as unsupported, since T.87 gives their entries no meaning; a SPIFF header's
video-range Y'CbCr color spaces decode as full-range Y'CbCr, and its other color spaces (PhotoYCC, CMY, CIE
Lab) are not converted. JPEG-LS part 2 (ITU-T T.870) streams are rejected as unsupported. The JPEG-LS encoder
writes no mapping tables and no explicit MAXVAL. A line-interleaved JPEG-LS scan of subsampled components is
read with the lines that complete its last minimum coded unit, as T.87 B.4 requires; a stream that leaves them
out, as the ISO/IEC 10918-7 reference software writes one, decodes its last unit wrongly.

</details>

<details>
<summary><b>JPEG encoder</b></summary>

The JPEG encoder writes no hierarchical frames (SOF5 to SOF7, SOF13 to SOF15) and uses only the accurate
integer forward DCT (cjpeg's `-dct fast`, `-dct float` and `-smooth` have no counterpart) and the default
arithmetic conditioning; its restart interval is the same number of MCUs in every scan, so cjpeg's
`-restart` in rows, which counts each scan's own MCU rows, has no counterpart in a scan script that mixes
interleaved and single-component scans.

</details>

<details>
<summary><b>APNG encoder</b></summary>

The APNG encoder writes 8-bit indexed, truecolor or truecolor with alpha frames only, so a deeper frame is
written with 8 bits per sample.

</details>

<details>
<summary><b>ANI</b></summary>

Frames stored as raw data instead of icon files (an `anih` chunk without its icon flag) are
unsupported, since no published document specifies their layout; frames are drawn at the top-left corner
of the canvas, unscaled, and the reader does not report cursor hotspots (read them from
`AniDecoder.frame` with `IcoDecoder.entries`). ANI files hold no metadata `ImageMetadata` reads.

</details>

<details>
<summary><b>JPEG XL decoding</b></summary>

JPEG XL decoding reduces only a first displayed frame that replaces the whole canvas after LF and
reference-only frames; a frame whose modular channels (an unsqueezed alpha channel, for example) arrive in
its last pass decodes at full size under a size hint. The gain map metadata is handed over as stored by
`JxlGainMap` and parsed by `JxlGainMap.toGainMap`. JPEG XL metadata editing writes plain (uncompressed) `Exif` and `xml ` boxes, since Smalt has no
Brotli encoder; it cannot change the ICC profile, which is coded in the codestream header, and in a file
with JPEG reconstruction data (`jbrd`) it refuses an EXIF or XMP change that alters the length the
reconstruction records for the original JPEG's segment.

</details>

<details>
<summary><b>JPEG XL encoding</b></summary>

JPEG XL encoding writes no patches, splines, noise, lossy modular frames or progressive passes; the VarDCT
encoder chooses among the 8x8, 4x4, 4x8, 2x2, identity and AFV transforms and merged DCTs up to 64x64 (like
libjxl, never the 8x32, 128 or 256-sample DCTs) and codes coefficients in the natural order. At the same distance
its files are within a few percent of cjxl's size and SSIMULACRA 2 score on photographs, and at effort 7 and above up to about
a quarter larger on screen content with repeated glyphs, which cjxl codes as patches. The `jbrd` box of a
recompressed JPEG file holds uncompressed Brotli meta-blocks, about the size of the JPEG headers. JPEG
recompression rejects arithmetic-coded, 12-bit, lossless and four-component (CMYK) JPEG files, files whose
height a DNL marker defines and sampling factors JPEG XL cannot hold.

</details>

<details>
<summary><b>JPEG 2000 encoder: deep lossless channels</b></summary>

A codestream gives a sub-band at most 37 magnitude bit-planes (a five-bit exponent and seven guard bits), so the
JPEG 2000 encoder codes a lossless channel of 35 to 38 bits only when its wavelet coefficients fit them, as those
of images with any spatial correlation do; noise or a checkerboard over the full range of a 38-bit channel is
rejected. With the default guard bits, channels of up to 34 bits always fit. OpenJPEG's block decoder holds 30
magnitude bit-planes, so `opj_decompress` decodes lossless files of up to 27 bits per channel.

</details>

<details>
<summary><b>JPEG 2000 encoder: region of interest</b></summary>

The JPEG 2000 encoder's rectangle region of interest scales the coefficients of the code-blocks it covers
in part by one bit more than the background needs, so a scaled coefficient takes about twice the bit-planes of
the channel: rectangles take channels of up to about 26 bits (55 magnitude bit-planes, the 163 coding passes a
packet signals), lossless or lossy, and a whole-component region has no such bound. Above 30 bit-planes, from
about 13-bit channels, `opj_decompress` cannot decode the result. Its lossy output is not byte-identical to
`opj_compress`'s, whose `-ROI` option only declares the shift: Smalt gives the region priority in the rate
allocation. Progression changes are written as `opj_compress -POC` writes them, but when a
change ends at a later layer than the change before it Smalt still writes the packets OpenJPEG 2.5.3 skips, and
under a tile-part division Smalt writes each packet once where OpenJPEG 2.5.3 repeats or drops packets (it
matches for a division by layer, and for a division by resolution of changes that split the components). A
tile-part without packets gets no `PLT` segment, since a segment lists at least one length, where
`opj_compress` writes an empty one.

</details>

<details>
<summary><b>TIFF</b></summary>

Interleaved subsampled YCbCr with extra samples is unsupported, since TIFF 6.0 defines data units of Y,
Cb and Cr only and no writer defines where extra samples go; YCbCr above 16 bits per sample is unsupported;
samples of different sizes, complex samples and L*a*b* other than 8- and 16-bit integers are read only from
uncompressed, PackBits, LZW, Deflate, Zstandard or LZMA strips and tiles. PixarLog samples of 11 or 12 bits
are unsupported (libtiff writes them in 16-bit words, inconsistently with their `BitsPerSample`), and so are
the LERC codes 34888 and 34889, which Esri reserves without a definition, and JBIG
entities that continue the layers or the prediction table of another entity. The TIFF encoder writes
Zstandard; LZMA, LERC, JBIG, PixarLog, NeXT, ThunderScan, SGILog, old-style JPEG and L*a*b* pages are read
only. It writes Y'CbCr pages at 8 bits without alpha, JPEG pages as 8-bit gray or Y'CbCr only (no CMYK, no
alpha, no separate planes), and no return-to-control sequence after Group 3 data (as libtiff writes it).
libtiff 4.7.1 itself misreads compression 32771 (word-aligned modified Huffman) rows that end inside the last
byte its bit reader has loaded, so such pages, its own included, read correctly in Smalt but not always in
libtiff.

</details>

<details>
<summary><b>WebP</b></summary>

A target size is met by a search over whole encodes, so the file differs from `cwebp -size`; with more than
one token partition at methods 3 to 6 the partitions are kept where libwebp's encoder writes one. libwebp's
segment smoothing (`-pre 1`), partition limit, quality range of the PSNR search, JPEG-like size emulation and image
hints are not offered, and the animation encoder takes the quality and method only.

</details>

<details>
<summary><b>WebP, JPEG 2000 and JPEG XL pages in TIFF</b></summary>

WebP TIFF pages hold only interleaved 8-bit RGB or RGBA, and JPEG 2000 TIFF pages no floating-point samples.
JPEG XL TIFF pages store CMYK and named inks as a gray channel plus extra channels, as GDAL does, so a lossy
JPEG XL setting codes only the first ink lossily; mainline libtiff has no JPEG XL codec, so its tools read the
structure of these pages but not their samples (GDAL's libtiff does). Subsampled JPEG 2000 chroma in a TIFF
page is replicated, not interpolated.

</details>

<details>
<summary><b>BMP</b></summary>

No sample or oracle of `BI_CMYK` files exists, so their CMYK values are read in the byte order of the
Windows `CMYK` color macro (black, yellow, magenta, cyan). Linked profiles (`PROFILE_LINKED`) are never opened,
and the icon and pointer entries of OS/2 bitmap arrays are skipped. The encoder writes no delta codes, OS/2
compressions, 64-bit or CMYK pixels, and a picture whose every pixel is fully transparent is read back as
opaque, since readers take an alpha channel that is zero everywhere as unused. Metadata editing replaces or
removes only the ICC profile, and not in bitmap arrays or OS/2 Huffman and RLE24 files.

</details>

### Decoding options

<details>
<summary><b>Region decoding (<code>ImageDecoderOptions.region</code>)</b></summary>

Region decoding (`ImageDecoderOptions.region`): a progressive or 12-bit JPEG frame keeps the coefficients of the
whole frame, since its scans refine every block, so the limits bound the whole frame, and it transforms only the
blocks the region needs; the limits of a JPEG 2000 region bound each tile it decodes, at full resolution, so the
region of an untiled codestream is bounded as the whole image is; a WebP image is decoded down to the region's last
row, the rows above it included; a JPEG 2000 compositing layer of several codestreams, a composition, a HEIC or AVIF
image that is not a grid of coded images, a thumbnail and the first frame of a sequence decode whole and are
cropped, as do the PNG, GIF, BMP, ICO, ANI, JPEG XL and camera raw decoders. The data below a WebP or sequential
JPEG region, and outside the tiles of a TIFF, JPEG 2000, HEIC or AVIF region, is not read, so damage there goes
undetected. The picture of a region of a lossless WebP image without the alpha hint of its header declares alpha
only when a pixel of the decoded rows is not opaque, where the whole decode looks at every pixel. The ImageIO reader
decodes the whole image for a read that subsamples a source region.

</details>

### Metadata and encoder options

<details>
<summary><b>JPEG 2000 metadata editing</b></summary>

JPEG 2000 metadata editing changes the color specification of the JP2 header only (not those of JPX
codestream or compositing layer headers), cannot store comments (the codestream's `COM` segments are not
rewritten) or the metadata of a raw codestream, and refuses to move the boxes of a file whose codestream is
held in fragment tables, whose offsets would no longer point at it.

</details>

<details>
<summary><b>Pixel density</b></summary>

GIF's pixel aspect ratio byte, ICO and ANI entries, and the EXIF resolution of WebP, HEIC, AVIF
and JPEG XL files are not reported as `DecodedImage.density()` (the EXIF block itself is returned). A density
written to JPEG, PNG or BMP is rounded to the whole values of the field (dots per meter in PNG and BMP, so
300 dots per inch reads back as 11811 dots per meter); BMP and JPEG 2000 store no aspect ratio, so the encoders
leave one out.

</details>

<details>
<summary><b>TIFF metadata editing</b></summary>

TIFF metadata editing changes only the first page's directory; it cannot store comments, text or an EXIF
thumbnail, never moves the metadata of other pages, and reuses space an earlier edit freed in the middle of
the file only while the edit still knows it as freed (zeros it left behind are reclaimed only at the end of
the file, and not when a maker note, an entry of an unknown type or a `LONG` entry of an unrecognized tag,
which may hold an offset, is kept). The same holds for DNG files and the camera raw files with a TIFF
signature; ORF, RW2, CR3, RAF and MRW metadata is read only, and CRW and X3F files, which hold no EXIF block,
have no metadata `ImageMetadata` reads.

</details>

<details>
<summary><b>HEIF metadata editing</b></summary>

HEIF metadata editing changes the ICC profile of the primary item only (the tiles of a grid and the other
items keep theirs), never adds or edits an `nclx` property (a high-dynamic-range profile is written in its
ICC form), refuses image sequences without a primary item and tracks that locate items of their own (a
track-level `meta` box with `iloc`), and fails when a moved 32-bit track offset no longer fits.

</details>

<details>
<summary><b>Encoder metadata (<code>ImageEncoderOptions</code>)</b></summary>

GIF, BMP, ICO and ANI files record no EXIF block and BMP, ICO and ANI
no XMP packet, so these leave them out; GIF and ANI animations cannot record an orientation and reject one. TIFF
and JPEG 2000 rebuild the EXIF block from its entries, which moves its values (a maker note with offsets relative
to the block breaks), and leave out a block or an XMP packet that does not parse. An Ultra HDR JPEG
(`JpegEncoder.encode(picture, gainMap, ...)`) parses the XMP packets of the picture and the gain map, rejecting
one that does not parse, and writes them in canonical form after its own description, without their `hdrgm` and
`Container` properties, which the file states itself, and with the `hdrgm`, `Container` and `Item` prefixes
renamed when they name other namespaces. An
XMP packet a format has to parse and cannot is left out: by JPEG when it is too large for one segment, and by
GIF when it holds a zero byte (a UTF-16 or UTF-32 packet, which GIF writes again in UTF-8 when it parses).
The transcode leaves out of a JPEG output an EXIF block larger than one `APP1` segment (65527 bytes, after
its thumbnail is removed) and an input's EXIF block that is not a TIFF structure, and its `STRIP_LOCATION`
keeps maker notes as stored, so a position a manufacturer records inside one stays.

</details>

<details>
<summary><b>EXIF</b></summary>

A fresh layout leaves out the entries of type `IFD` (13), whose sub-directories it does not hold;
uncompressed thumbnails are written as one strip, and a lossless JPEG transform moves uncompressed thumbnails
of 8 or 16-bit gray, RGB or 4:4:4 YCbCr samples and removes the others.

</details>

### Video codecs

<details>
<summary><b>HAP</b></summary>

S3TC endpoints are widened by rounding, as the Direct3D specification has them, where ffmpeg repeats their
high bits, so decoded DXT1 and DXT5 pixels may differ from ffmpeg's by a few levels, more after the scaled YCoCg
transform of HAP Q. HAP HDR is encoded as unsigned BC6H (negative values clamp to 0), and every HAP texture is
encoded with one BC7 mode and one BC6H mode, which costs some quality against multi-mode texture compressors.

</details>

<details>
<summary><b>Motion-JPEG</b></summary>

Motion-JPEG format B frames that take their quantization tables from the sample description (an `mjqt` box)
are rejected as unsupported. AVI records no field order, so the two fields of an interlaced AVI frame are
woven with the first field on top unless the track handed to the decoder declares a bottom-first scan;
players commonly take the first field of an AVI frame as the bottom one.

</details>

<details>
<summary><b>Motion JPEG 2000</b></summary>

Motion JPEG 2000 frames decode at full resolution with every quality layer; an MJ2 file's `jp2p` and `jp2x`
boxes are neither read nor written, and the MJ2 brand carries no PCM, whose MJ2 sound sample entries are not
written. The `jp2h` box of an `mjp2` sample entry describes only the frames that carry no JP2 header of their own;
ffmpeg reads the color space from the frames instead, so it decodes a raw-codestream 4:4:4 Y'CbCr track of an MP4,
MOV or MJ2 file as RGB (Matroska and AVI frames carry the header, which the muxers add).

</details>

Related limits elsewhere: [HEIF files, gain maps, channel pictures and color management](../core/README.md#images-color-and-hdr)
in core, [HEIC and JPEG XS](../mpeg/README.md#known-limits) in mpeg, [AVIF and AV1](../video/README.md#known-limits)
in video, [ImageIO](../desktop/README.md#known-limits) in desktop.
