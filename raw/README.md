# smalt-raw

> DNG and camera raw decoding: Canon, Nikon, Sony, Fujifilm, Olympus, Panasonic, Pentax, Minolta and Sigma raw files and more, with demosaicing and camera color rendering; DNG writing and raw to DNG conversion.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-raw)

## At a glance

- Add the jar and `Images.decode` opens DNG and camera raw files; no extra code.
- The decoder rebuilds full color from the sensor mosaic (demosaicing), then applies white balance, camera color and a tone curve.
- Get the linear sensor data with `RawDecoder.decodeRaw`, or read camera and shot info without decoding.
- Write DNG from sensor data, or convert any file this module decodes into DNG, with `DngEncoder`.
- Pulls in [smalt-core](../core/README.md#at-a-glance) and [smalt-image](../image/README.md#at-a-glance), whose JPEG, JPEG-LS, JPEG XL and Deflate codecs read previews and sensor data and write DNG.
- No JVM flag is required. Demosaicing and color run faster with `--add-modules jdk.incubator.vector` (see [JVM flags](../README.md#jvm-flags)).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-raw</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.

On the module path, add `requires com.github.auties00.smalt.raw;` to call `RawDecoder` or `DngEncoder` directly; smalt-core comes with it.

## Examples

### Develop a camera raw file

`Images.decode` detects the raw format and renders (develops) it with the default settings; `metadataOf` keeps the orientation and EXIF.

```java
var photo = Images.decode(Path.of("IMG_0001.CR2"));
var metadata = ImageEncoderOptions.builder().metadataOf(photo).build();
Files.write(Path.of("IMG_0001.jpg"), Images.encode(photo.picture(), ImageFormat.JPEG, metadata));
```

### Tune the rendering

`RawDecoderOptions` sets white balance, exposure, highlights, color space and bit depth; see [Rendering options](#rendering-options).

```java
var options = RawDecoderOptions.builder()
        .whiteBalance(RawWhiteBalance.temperature(5600, 10))
        .exposure(0.5)
        .highlights(RawHighlights.BLEND)
        .colorSpace(RawColorSpace.DISPLAY_P3)
        .highBitDepth(true)
        .build();
try (var source = MediaSource.of(Path.of("DSC_0001.NEF"))) {
    var image = RawDecoder.decode(source, options);
    var metadata = ImageEncoderOptions.builder().metadataOf(image).build();
    Files.write(Path.of("DSC_0001.png"), Images.encode(image.picture(), ImageFormat.PNG, metadata));
}
```

### Read camera and shot info

`RawDecoder.info` reads the headers only; `isDecodable()` tells you whether the file will decode, and `unsupportedFeature()` says why not.

```java
try (var source = MediaSource.of(Path.of("DSC_0001.ARW"))) {
    var info = RawDecoder.info(source);
    var size = info.width() + "x" + info.height();
    System.out.println(info.format() + " " + info.model().orElse("?") + " " + size);
    System.out.println("ISO " + info.iso().orElse(0) + ", decodable: " + info.isDecodable());
    System.out.println("Missing feature: " + info.unsupportedFeature().orElse("none"));
}
```

### Make a fast thumbnail

A size hint lets the decoder use the embedded preview or a half-size render; `extractPreview` returns the camera's largest JPEG as stored, without decoding the sensor data.

```java
try (var source = MediaSource.of(Path.of("DSCF0001.RAF"))) {
    var thumb = RawDecoder.decode(source, RawDecoderOptions.ofMinimumSize(320, 240));
    var metadata = ImageEncoderOptions.builder().metadataOf(thumb).build();
    Files.write(Path.of("thumb.jpg"), Images.encode(thumb.picture(), ImageFormat.JPEG, metadata));
    var preview = RawDecoder.extractPreview(source);
    if (preview.isPresent()) Files.write(Path.of("preview.jpg"), preview.get());
}
```

### Convert a raw file to DNG

`DngEncoder.convert` keeps the file's color data and crops, and stores any vendor lens corrections as opcode list 3. Pass `DngEncoderOptions` to change the compression, here to lossless JPEG XL tiles.

```java
try (var source = MediaSource.of(Path.of("IMG_0001.CR3"))) {
    Files.write(Path.of("IMG_0001.dng"), DngEncoder.convert(source));
    var options = DngEncoderOptions.builder().compression(DngCompression.JPEG_XL).build();
    var tiled = DngEncoder.convert(source, RawDecoderOptions.defaults(), options);
    Files.write(Path.of("IMG_0001-jxl.dng"), tiled);
}
```

### Read linear sensor data

`RawImage` holds 16-bit linear samples, where 0 is black and 65535 is white; `DngEncoder.encode` writes them back as DNG.

```java
try (var source = MediaSource.of(Path.of("IMG_0001.CR2"))) {
    RawImage sensor = RawDecoder.decodeRaw(source);
    System.out.println(sensor.width() + "x" + sensor.height());
    System.out.println(sensor.channels() + " samples per pixel");
    Files.write(Path.of("IMG_0001.dng"), DngEncoder.encode(sensor));
}
```

## Formats

### Camera formats

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| DNG (up to 1.7.1) | ✅ | ✅ | Opcodes, camera profiles, HDR, enhanced image |
| GoPro GPR | ✅ | ❌ | DNG with GoPro VC-5 compression |
| Canon CR3 | ✅ | ❌ | Lossless and lossy C-RAW, dual pixel |
| Canon CR2 | ✅ | ❌ | Includes sRAW and mRAW |
| Canon CRW | ✅ | ❌ | Older CIFF files |
| Nikon NEF, NRW | ✅ | ❌ | Lossless, lossy, High Efficiency (HE, HE*) |
| Sony ARW, ARQ, SR2 | ✅ | ❌ | Lossy cRAW, lossless, ARQ pixel shift |
| Fujifilm RAF | ✅ | ❌ | Bayer, X-Trans, SuperCCD, EXR |
| Olympus and OM System ORF | ✅ | ❌ | Compressed and uncompressed |
| Panasonic RW2, RAW and Leica RWL | ✅ | ❌ | Format versions 2 and 4-8 |
| Pentax PEF | ✅ | ❌ | Pixel shift, K-3 III Monochrome |
| Samsung SRW | ✅ | ❌ | Compressed and uncompressed |
| Epson ERF | ✅ | ❌ | R-D1 rangefinders |
| Hasselblad 3FR, FFF | ✅ | ❌ | CMOS and CCD calibration |
| Phase One IIQ, Leaf Credo | ✅ | ❌ | IIQ L and older IIQ S |
| Leaf MOS | ✅ | ❌ | Uncompressed and lossless JPEG |
| Mamiya MEF | ✅ | ❌ | ZD camera and back |
| Kodak DCR, KDC, DCS TIFF | ✅ | ❌ | DCS, EasyShare and P series |
| Minolta and Konica Minolta MRW | ✅ | ❌ | Uncompressed 12-bit |
| Sigma X3F | ✅ | ❌ | Foveon sensors: TRUE, Merrill, Quattro |
| Other TIFF/EP raw | ✅ | ❌ | Generic fallback for TIFF-based raw files |

✅ supported · ❌ not supported · ➖ not applicable

Every format in this table converts to DNG with `DngEncoder.convert`. `Images.encode` cannot write DNG: use `DngEncoder`.

<details>
<summary><b>Canon, Nikon and Sony in detail</b></summary>

- **CR3:** CRX lossless and C-RAW wavelet, codec versions 1 and 2, multi-tile; WB and levels from the CTMD maker note.
- **CR3 extras:** dual pixel second image; PRVW and full-size JPEG previews.
- **CR2:** sliced lossless JPEG; sRAW (4:2:2) and mRAW (4:2:0) for ColorData versions 3, 6, 7, 9-13 and 15.
- **CRW:** Canon lossless compression with Huffman table sets 0-2; 10 and 12 bit; the G1 is a 4-color GMYC sensor.
- **NEF and NRW:** lossless and lossy Huffman, split-row lossy, linearization curves, uncompressed 16-bit and 12-bit packed.
- **Coolpix NEF:** interlaced rows of the E-series models; the 4-color E5700 sensor.
- **Nikon HE and HE*:** JPEG XS-syntax codestream, 4 components, 5/3 wavelet, decoded to 14-bit.
- **ARW:** cRAW with tone curve, uncompressed 16-bit, Sony lossless, DSLR-A900 12-bit packed, DSLR-A100 to A390 coding.
- **ARW white balance:** from tags or the encrypted SR2 directory.
- **ARQ:** 4-sample pixel shift merged to RGB, uncompressed or lossless JPEG.

</details>

<details>
<summary><b>Fujifilm, Olympus, Panasonic and Pentax in detail</b></summary>

- **RAF:** uncompressed Bayer and X-Trans (16-bit, 12-bit packed); compressed lossless and lossy, 12-16 bit.
- **RAF sensors:** older SuperCCD files (staggered layout); SuperCCD SR and EXR two-image sensors.
- **ORF:** Olympus 12-bit compression; uncompressed packed and 16-bit variants; embedded camera to sRGB matrix.
- **RW2, RAW, RWL:** version 2 (uncompressed), 4-7 (packed 128-bit blocks), 8 (Huffman-coded tiles, 12-16 bit).
- **Versionless RAW:** the 12-bit files of the DMC-FZ50, DMC-LX1 and Digilux 3.
- **PEF:** Pentax Huffman, uncompressed 16-bit and 12-bit packed; K-3 III Monochrome as 1-sample linear raw.
- **Pentax pixel shift:** 4 frames in PEF or Pentax DNG, merged to RGB or picked one at a time with `frame(i)`.
- **Lens corrections:** distortion, lateral CA and vignetting from Canon PowerShot, Fujifilm, Olympus, Panasonic and Sony maker notes.

</details>

<details>
<summary><b>Medium format and other makers in detail</b></summary>

- **SRW:** TIFF compression codes 32769 and 32770 (uncompressed, packed); 32770 at reduced bit depth, 32772 and 32773 (compressed).
- **ERF:** R-D1, R-D1s and R-D1x, 12-bit packed.
- **3FR and FFF:** uncompressed and lossless JPEG; CMOS gain calibration; CCD charge, flat-field and per-row calibration; defective columns.
- **IIQ:** IIQ L, older backs' IIQ S and uncompressed; black, gain, grid and defect calibration.
- **MOS:** uncompressed and lossless JPEG (compression 99); two-field readout reordered.
- **MEF:** uncompressed 12-bit; WB from maker note tables.
- **DCR and KDC:** compression 65000 (DCS 760C, Pro 14n), lossless JPEG (DCS520C), KDC 12-bit packed, EOS DCS 8-bit.
- **MRW:** storage 0x52 (16-bit words) and 0x59 (packed); preview with damaged-byte repair.
- **X3F:** Foveon layers as 3-sample linear raw; TRUE, Quattro (lower layers upsampled) and older Huffman (SD14, Polaroid x530).
- **X3F corrections:** bad pixels and lens shading applied as a DNG gain map when opcodes are on.

</details>

### DNG sensor compressions

| Compression | Decode | Encode | Notes |
|---|:-:|:-:|---|
| Uncompressed | ✅ | ✅ | |
| Lossless JPEG | ✅ | ✅ | Default when writing; JPEG-LS streams also read |
| JPEG XL | ✅ | ✅ | Lossless when writing; DNG 1.7 |
| Deflate | ✅ | ❌ | Integer and float predictors |
| Lossy JPEG | ✅ | ❌ | |
| VC-5 | ✅ | ❌ | GoPro GPR |

<details>
<summary><b>DNG reading and writing in detail</b></summary>

- **Samples:** integer 1-32 bit and floating-point 16, 24 and 32 bit (HDR DNG).
- **Color filter array (CFA):** any pattern up to 16x16 (Bayer, X-Trans, 4-color CYGM and RGBE), all 9 CFA layouts, 2-sample CFA.
- **Other images:** linear raw, monochrome, enhanced image, reduced-resolution linear raw previews.
- **Opcodes:** all 14 DNG opcodes, in lists 1, 2 and 3.
- **Color:** ColorMatrix and ForwardMatrix (1-3 illuminants), calibration, HueSatMap, LookTable, ProfileToneCurve, GainTableMap, RGBTables.
- **Profiles:** pick an embedded camera profile with `cameraProfile(name)`; list them with `RawInfo.cameraProfiles()`.
- **Writer:** DNG 1.7.1, little-endian classic TIFF, 16-bit tiles of 256 px by default (16-4096, multiple of 16).
- **Writer previews:** baseline JPEG sRGB preview (1024 px, quality 90 by default) plus a thumbnail up to 256 px.
- **Writer metadata:** make, model, unique camera model, orientation, EXIF, baseline exposure, pixel aspect ratio.
- **Convert keeps:** crops, color matrices, profiles, tone curve, semantic masks, opcode list 3, private data.
- **Convert adds:** vendor lens corrections as opcode list 3.

</details>

## Known limits

- Vendor formats were built from sample files: a variant no sample covered throws `MediaException.Unsupported` instead of guessing.
- Decoding a region (`ImageDecoderOptions` region) renders the whole image at the hinted size, then crops it.
- `RawDecoder.extractPreview` returns JPEG previews only, never JPEG XL ones.
- Lens corrections are skipped when a size hint picks a half-size render (see [Make a fast thumbnail](#make-a-fast-thumbnail)).
- Cameras with no color matrix in the file, other than the built-in ones (see [Rendering options](#rendering-options)), render with approximate colors; `RawInfo.isColorCalibrated()` returns false for them.

<details>
<summary><b>Per-format gaps</b></summary>

- **CR3:** raw burst roll frames (CRX encoding type 3) are not decoded.
- **CR2:** sRAW and mRAW of ColorData versions other than 3, 6, 7, 9-13 and 15 are not decoded.
- **Nikon HE and HE*:** header or component parameters outside the samples throw `Unsupported`.
- **SRF:** the sensor data of the four-color DSC-F828 is not decoded.
- **ARQ:** only uncompressed or lossless JPEG with RGGB sample colors.
- **RW2:** only versions 2, 4-8 and versionless; v8 gain and offset tags 0x39/0x3A are not applied.
- **SRW:** a 32773 strip with another coding is not decoded.
- **IIQ:** the IIQ S of newer backs (compression 6) is recognized but not decoded.
- **IIQ:** four-output backs (P40+, P65+, IQ140, Credo) get no seam correction.
- **3FR and FFF:** no H4D seam or offset-ramp correction.
- **MOS:** white level known only for Aptus 22 and AFi-II 12; others use the bit-depth maximum.
- **DCR:** Kodak DC120 compressed data is not decoded.
- **MRW:** only storage 0x52 and 0x59 and Bayer patterns 1 and 4; Sony MRW files are not decoded.
- **X3F:** sd Quattro H high-resolution lens shading is not applied.
- **White balance:** estimated from the preview when the file records none (ERF, MEF, some ARW and CRW).

</details>

<details>
<summary><b>DNG and rendering gaps</b></summary>

- Opcodes from later DNG versions are skipped, so the image renders uncorrected.
- Standard-range ProfileToneCurve points are joined by lines, not a cubic spline (HDR curves use a spline).
- A 2-sample dual pixel DNG with opcode list 2 is merged instead of split.
- A 2-sample diagonal-pair DNG with differing white levels is not decoded.
- Pixel shift: no motion correction, and opcode list 2 is not applied to merged frames.
- A 4-color sensor whose file has 3-color matrices needs a 12-value `colorMatrix`.
- A `colorMatrix` whose row count differs from the sensor colors throws `Unsupported`.
- A missing `cameraProfile`, out-of-range `frame` or absent dual pixel image throws `Unsupported`.

</details>

<details>
<summary><b>DNG writer gaps</b></summary>

- JPEG XL cannot hold two-sample sensor data (dual pixel, two values per sensor pixel).
- Files are classic TIFF, so the limit is 4 GiB.
- Replacing EXIF through the options drops the maker note and DNG private data.
- A semantic mask that cannot be decoded is dropped on convert.

</details>

## Rendering options

Each `RawDecoderOptions.Builder` method sets one stage of the rendering.

| Option | Choices | Default |
|---|---|---|
| `demosaic` | `MENON` (sharper), `BILINEAR` (faster) | `MENON` |
| `whiteBalance` | `asShot()`, `daylight()`, `temperature(k, tint)`, `automatic()`, `multipliers(r, g, b)` | `asShot()` |
| `highlights` | `CLIP`, `BLEND` | `CLIP` |
| `toneCurve` | `STANDARD`, `LINEAR` | `STANDARD` |
| `exposure` | -10 to 10 stops | 0 |
| `autoBlack` | true, false | false |
| `colorSpace` | any `RawColorSpace` | `SRGB` |
| `highBitDepth` / `floatingPoint` | 16-bit RGBA / float RGB | 8-bit RGBA |
| `defaultCrop` / `userCrop` | true, false | true / false |
| `opcodes` | true, false | true |
| `lensCorrections` | true, false | true |
| `enhancedImage` | true, false | false |
| `embeddedPreview` | true, false | true |

Color spaces: `SRGB`, `DISPLAY_P3`, `ADOBE_RGB`, `PROPHOTO_RGB`, `LINEAR_SRGB`, `LINEAR_REC2020`.

Presets: `defaults()`, `fast()` (bilinear demosaic), `linear()` (16-bit linear Rec. 2020, no tone curve) and `ofMinimumSize(w, h)` (preview or half-size when large enough).

<details>
<summary><b>All decoder options</b></summary>

| Option | What it does | Default |
|---|---|---|
| `demosaic` | Rebuilds full color from the sensor mosaic | `MENON` |
| `whiteBalance` | As shot, daylight, kelvin (2000-25000) and tint, gray world or white patch, multipliers | As shot |
| `highlights` | Clips or reconstructs clipped highlights | `CLIP` |
| `toneCurve` | Standard tone curve, or linear output | `STANDARD` |
| `exposure` | Stops added to the file's baseline exposure | 0 |
| `autoBlack` | Subtracts a measured black point (flare), standard curve only | Off |
| `colorSpace` | Output color space | `SRGB` |
| `highBitDepth` | 16-bit RGBA output | Off |
| `floatingPoint` | Float RGB output; keeps HDR values above 1 | Off |
| `opcodes` | Applies DNG opcode lists and X3F corrections | On |
| `defaultCrop` | Crops to the file's default crop | On |
| `userCrop` | Crops further to the photographer's DNG user crop | Off |
| `lensCorrections` | Applies vendor maker-note lens corrections | On |
| `enhancedImage` | Renders a DNG's enhanced image instead of the raw image | Off |
| `embeddedPreview` | Lets a size hint return the camera's JPEG preview | On |
| `minSize(w, h)` / `fullSize()` | Sets or clears the size hint | No hint |
| `colorMatrix(m)` / `fileColorMatrix()` | Replaces the file's XYZ to camera matrix (9 or 12 values) | File's matrix |
| `cameraProfile(name)` / `asShotProfile()` | Picks a DNG camera profile by name | As-shot profile |
| `frame(i)` / `allFrames()` | Decodes one pixel-shift frame, or merges all | All, merged |
| `dualPixelImage` | Decodes the second image of a dual pixel file | Off |
| `limits` | Resource bounds checked before every allocation | `MediaLimits.DEFAULT` |

</details>

<details>
<summary><b>Color without embedded matrices</b></summary>

- 52 camera models without matrices in their files get built-in matrices derived from rawtoaces-data spectral sensitivities.
- Covered makers: ARRI, Arashi Vision, Canon, Fujifilm, Hasselblad, Nikon, Panasonic and Sony.
- The four-color Canon PowerShot G1 and Nikon Coolpix E5700 get matrices fitted to their JPEG previews.
- Pass your own matrix with `colorMatrix(double[])` (9 values, or 12 for 4-color sensors).

</details>

## Licensing

This product includes DNG technology under license by Adobe. The Adobe DNG patent license is royalty-free for readers and writers of DNG-compliant files; see `META-INF/THIRD-PARTY-NOTICES` in the jar.

Parts are ported from or derived from X3F tools, GoPro gpr, the Adobe DNG SDK, rawtoaces-data and others; their licenses are in the same file.
