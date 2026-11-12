<div align="center">

# smalt-raw

**DNG and camera raw decoding, and DNG writing**

![Artifact](https://img.shields.io/badge/artifact-smalt--raw-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.raw-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | **raw** | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-raw` develops the raw files of digital cameras into pictures, and writes DNG. It depends on
[`smalt-core`](../core/README.md) and [`smalt-image`](../image/README.md).

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Known limits](#known-limits) | [License note](#license-note)

## What it covers

- **Decode:** DNG 1.0 to 1.7 and the raw formats of Canon, Nikon, Sony, Pentax, Fujifilm, Olympus, Minolta,
  Sigma, Panasonic and Leica, Samsung, Epson, Hasselblad, Mamiya, Leaf and Kodak.
- **Develop:** demosaicing, white balance, camera color rendering, lens corrections, exposure and tone curve.
- **Write:** DNG files, and raw to DNG conversion.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-raw</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

## Guide

### Develop a camera raw file

```java
DecodedImage photo = Images.decode(MediaSource.of(Path.of("IMG_0001.CR2")));   // 8-bit sRGB, like a camera JPEG
RawInfo info = RawDecoder.info(MediaSource.of(Path.of("DSC_0001.NEF")));       // camera, ISO, exposure, previews
DecodedImage linear = RawDecoder.decode(MediaSource.of(Path.of("photo.dng")),  // 16-bit linear Rec. 2020
        RawDecoderOptions.linear().toBuilder().whiteBalance(RawWhiteBalance.daylight()).build());
DecodedImage thumb = Images.decode(MediaSource.of(Path.of("DSC_0001.ARW")),    // from the embedded preview
        ImageDecoderOptions.ofMinimumSize(320, 240));
RawImage sensor = RawDecoder.decodeRaw(MediaSource.of(Path.of("photo.dng")));  // the CFA samples, linearized
```

## Format coverage

<details>
<summary><b>How a raw file is detected, developed and returned</b></summary>

`smalt-raw` (JPMS `com.github.auties00.smalt.raw`, package `image.raw`, entry points `RawDecoder` and
`DngEncoder`) decodes DNG and the vendor raw formats and writes DNG. Detection: `ImageFormat.DNG` (a TIFF whose first directory holds
`DNGVersion`) and `ImageFormat.RAW` (every other raw format; `RawInfo.format()` names it).
`ImageFormat.detect(byte[], int)` tells the TIFF-structured raw files from TIFF only when their
directories lie within the bytes it is given; `Images.detect(MediaSource)` follows them through the
source. Decoding renders like a raw converter (sensor data, opcode lists, linearization, Menon or
bilinear demosaicing, white balance, camera matrix to the output space, exposure and tone curve) and
returns 8-bit sRGB by default; `RawDecoderOptions` selects 16-bit, Display P3, Adobe RGB, ProPhoto or
linear sRGB and Rec. 2020 output, the white balance (as shot, a color temperature with a tint along the normal to the
Planckian locus, camera multipliers, or a gray world or white patch estimate from the image), highlight handling,
exposure and tone curve, the lens corrections the vendor's maker note records (`lensCorrections`), the frame of a
pixel-shift file (`frame`, all four merged by default), the second image of a dual pixel file (`dualPixelImage`,
one photodiode of each pixel, where the main image is their sum), and for DNG files the camera profile, the enhanced image, the
user crop and an automatic black subtraction. A size
hint is met from the smallest embedded preview that is large enough, else from the smallest rendering that is: a
reduced-resolution linear raw image a DNG file stores, a half-size rendering or the full one.
`RawDecoder.decodeRaw` returns the linearized CFA samples (three samples per pixel for linear raw data and
Sigma X3F files, two per photosite for the sensors that read each photosite twice, which the rendering merges) with
the camera's color description and the layout of the photosites.

</details>

### Formats

Open a format to see which sensor data is decoded, which is not, where the decoder comes from and what to know
about patents.

<details>
<summary><b>DNG 1.0-1.7</b></summary>

- **Sensor data decoded:** CFA (any repeat up to 16x16: Bayer, X-Trans, others with RGB filters; four-color cyan, yellow, green and magenta or red, green, blue and emerald 2x2 arrays; every CFALayout: rectangular and the staggered layouts 2 to 9 of the Fujifilm SuperCCD and others, demosaiced at the photosites' physical places onto the stored grid; two samples per pixel, as Adobe DNG Converter stores the Fujifilm sensors of two images: merged like the RAF files' readings, or, in a rectangular layout scaled up by about the square root of 2, interleaved as the two photosites of diagonal pairs; and as it stores Canon dual pixel files: the main image and the second image of `RawDecoderOptions.dualPixelImage`, equal to the CR3 decoder's), linear raw, monochrome; uncompressed 1-32-bit integer and 16/24/32-bit float, interleaved or separately stored planes (PlanarConfiguration 2), lossless JPEG (tiles, interleaved components; also JPEG-LS codestreams in its place, without subsampling), Deflate with the integer and floating-point predictors (DNG 1.4), lossy JPEG (DNG 1.4), JPEG XL (DNG 1.7, through `image.jxl`), VC-5 (compression 9, SMPTE ST 2073, the Bayer coding of GoPro's GPR files: every photosite of the 17 CC0 GPR samples of the HERO5 to HERO11 Black and the FUSION equals Adobe DNG Converter's); SubTileBlockSize, RowInterleaveFactor and ColumnInterleaveFactor (DNG 1.7 JPEG XL files store a CFA as interleaved subimages); linearization table, black level pattern and row/column deltas, white levels, active area (the CFA pattern starts at its corner), default crop and scale, DefaultUserCrop (`RawDecoderOptions.userCrop`); opcode lists 1-3 (GainMap, WarpRectilinear, WarpRectilinear2, WarpFisheye, FixVignetteRadial, FixBadPixelsConstant/List, TrimBounds, MapTable, MapPolynomial, Delta/ScalePerRow/Column); the enhanced image (NewSubFileType 16, `RawDecoderOptions.enhancedImage`); size hints met from JPEG and JPEG XL previews (one tile or several) and from the reduced-resolution linear raw previews of fast load data (RawToPreviewGain); ColorMatrix, CameraCalibration (kept only when CameraCalibrationSignature matches the profile's ProfileCalibrationSignature), AnalogBalance, ForwardMatrix with two or three illuminants (DNG 1.6, IlluminantData as xy or spectrum), AsShotNeutral/AsShotWhiteXY, BaselineExposure and BaselineExposureOffset, ProfileHueSatMap, ProfileLookTable, ProfileToneCurve, ProfileGainTableMap and ProfileGainTableMap2 (every gain encoding), RGBTables over semantic masks (weighted sum and sequential, every transfer curve and set of primaries, gamut extension), DefaultBlackRender (honored by the automatic black subtraction of `RawDecoderOptions.autoBlack`), ExtraCameraProfiles and AsShotProfileName (`RawInfo.cameraProfiles`, `RawDecoderOptions.cameraProfile`), ProfileDynamicRange and ProfileGroupName (`RawInfo.cameraProfileDetails`): high-dynamic-range profiles apply their tables of three dimensions, tone curve (a natural cubic spline continued by its end slope) and RGB tables to encoded values and render values above white into a floating-point picture (`RawDecoderOptions.floatingPoint`), matching dng_validate's rendering; four-color files take four multipliers, a twelve-value matrix override and blended highlights, and one without color matrices renders with a generic matrix of its filter colors
- **Not decoded:** standard-range ProfileToneCurve points are joined by lines rather than a spline; semantic masks serve only the RGB tables; JPEG XL previews are not returned by `extractPreview`
- **Source:** Adobe DNG Specification 1.7.1; ITU-T T.81 annex H; Wyman, Sloan and Shirley (2013) for the CIE 1931 color matching functions of spectral IlluminantData; the VC-5 decoder ported from GoPro's gpr SDK (MIT)
- **Patents:** DNG patent license (royalty-free; notice in the artifact's notices)

</details>

<details>
<summary><b>Canon CR2</b></summary>

- **Sensor data decoded:** lossless JPEG with slices; SensorInfo crop, ColorData white balance, black and white levels; mRAW and sRAW (subsampled lossless JPEG, YCbCr to RGB as Adobe DNG Converter does it, ColorData versions 3, 6, 7, 9 to 13 and 15: every photosite of the 31 CC0 sRAW and mRAW files of the 40D, 50D, 5D Mark II, III and IV, 7D and 7D Mark II, 1D Mark IV, 1D X Mark II, 60D, 6D and 6D Mark II, 70D, 80D, 5DS and 5DS R equal to the converter's)
- **Not decoded:** sRAW and mRAW of ColorData versions no sample has shown
- **Source:** Laurent Clevy's CR2 write-up (facts only), ExifTool tag documentation; sRAW and mRAW derived from CC0 samples against Adobe DNG Converter (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Canon CR3</b></summary>

- **Sensor data decoded:** the CRX codec, as every CC0 sample (29 model names) codes it: lossless (the four color planes coded directly, in one or two tiles) and lossy C-RAW (the subbands of a three-level 5/3 wavelet transform of each plane, quantized with one step per subband in codec version 1 and through a coded quantization map in version 2, with the tiles' overlapping coefficients), both coded in raster order with an adaptive Golomb-Rice code and a run mode; the image area and crop of the `IAD1` box; white balance, black and white levels from the color data of the maker note the timed metadata track carries; both images of dual pixel files (the main image, the sum of the two photodiodes of each pixel, and the second, of one of them, through `RawDecoderOptions.dualPixelImage`; every photosite of the second image of the CC0 EOS R5 dual pixel file equals the second sample of Adobe DNG Converter's file); metadata, EXIF and the PRVW and full-size JPEGs
- **Not decoded:** the frames of raw burst rolls (CRX encoding type 3), wavelet depths other than three and quantized third-level subbands in codec version 2 (rejected as unsupported; no sample has them)
- **Source:** Laurent Clevy's CR3 write-up (box and header layout, facts only), Canon's patent application US 2016/0323602 A1 (the three-level wavelet and the Golomb-Rice parameter update); the CRX coding derived clean-room from CC0 samples against Adobe DNG Converter (see below)
- **Patents:** Canon's US 2016/0323602 A1 describes the wavelet coding

</details>

<details>
<summary><b>Canon CRW</b></summary>

- **Sensor data decoded:** the CIFF files of the EOS D30, D60, 10D and 300D (Digital Rebel) and the PowerShot G1, G5 and G6: Canon's lossless compression (blocks of 64 photosites coded with the JPEG-style DC and AC Huffman codes of the file's table set 0, 1 or 2, each photosite predicted from the one two columns to its left; every code of the three sets, those that end a block or stand for a single zero coefficient included) with the two low bits of the 12-bit files; image area, black levels measured on the masked area, white levels, white balance of the shot's setting (estimated from the preview when the file has none), ISO, exposure, aperture, focal length, lens range, capture time and rotation; the large JPEG preview and the thumbnail
- **Not decoded:** files without a decoder table and other table sets (which Adobe DNG Converter refuses too); the colors of the green, magenta, yellow and cyan PowerShot G1 are approximate (its matrix is fitted against its own JPEG preview, see Color below); the headerless CHDK dumps also named `.CRW` are not CRW files
- **Source:** Canon's CIFF specification 1.0 revision 4, ExifTool tag documentation (record and field names); the compression, its Huffman tables, the low-bit layout, the levels and the shot fields derived clean-room from CC0 samples against Adobe DNG Converter, the codes no sample uses from the converter's values for copies of the samples that use them (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Nikon NEF/NRW</b></summary>

- **Sensor data decoded:** lossless and lossy Huffman compression, lossy files that switch tables at a split row, the linearization curves (versions 0x44 0x10, 0x20 and 0x40; the expanded curves of the 32 lossy CC0 samples of 30 models equal Adobe DNG Converter's linearization tables entry for entry, the D3, D300 and D700 12-bit files taking the curve of version 0x44 0x10 as the converter does), uncompressed 16-bit words and 12-bit packing (rows interlaced in the E-series Coolpix files); the high-efficiency HE and HE* data of the Z-series cameras (a JPEG XS-syntax codestream of intoPIX's: per-component wavelet levels, vertically predicted bit-plane counts, a lifting transform of the 2x2 mosaic and a square law whose parameters the picture header carries, every image area of the Z 9, Z 8, Z f, Z 5II, Z 50II and Z 6III samples, equal to Adobe DNG Converter's photosites); white balance (tag 0x000C, or the Coolpix block 0x0014), black levels
- **Not decoded:** HE and HE* codestreams with parameters no sample has shown; Huffman codes no sample has used (rejected as unsupported); the colors of the four-color E5700 are approximate (its matrix is fitted against its own JPEG preview, see Color below)
- **Source:** layout from the ExifTool documentation and Bill Claff's notes; Huffman tables, the Coolpix layout and the HE and HE* coding derived clean-room from CC0 samples against Adobe DNG Converter (see below); HE entropy decoding, dequantization and 5/3 synthesis ported from SVT-JPEG-XS (BSD-2-Clause-Patent)
- **Patents:** none known

</details>

<details>
<summary><b>Sony ARW and ARQ</b></summary>

- **Sensor data decoded:** cRAW (ARW2, with the tone curve), uncompressed 16-bit words (14-bit values in the 12-bit files too), the DSLR-A900's uncompressed 12-bit data under compression 32767 (pairs packed into three bytes least significant bits first, every photosite equal to Adobe DNG Converter's on the CC0 sample, black level 128, white level 4095 and the converter's image area); centered crop from EXIF (the converter's image area for the DSLR-A700 and A900, whose EXIF size is the whole sensor); black level and white balance from the raw directory's `BlackLevel` and `WB_RGGBLevels` tags where the file has them, otherwise the model's black level and the as-shot white balance of the encrypted SR2 directory, in any of its four known layouts (every one of the 69 CC0 files of 54 models without the tag and with the directory: the DSLR-A200 to A900, the RX0 to RX100 V compacts, the ILCE-6000 to 7S II and the SLT and NEX bodies among them, equal to the converter's), and the model's white level (the `WhiteLevel` tag, or the converter's level of the models whose photosites saturate lower): the levels of the 192 CC0 ARW samples of 96 models the converter reads equal its own, and so does the white balance of every file with the tag; the ARQ files Sony's software merges from pixel-shift frames (four samples per pixel, red, two greens and blue), merged to red, green and blue as the converter does, every value equal to its file of the CC0 ILCE-7RM3 sample, and the same files with lossless JPEG sensor data, as one strip or as tiles of four components or of two at twice the width, as the converter reads such copies of that sample (with that sample's own sensor data in each layout, the values of both decoders are those of the uncompressed file; no camera or software is known to write them: Sony's cameras record pixel-shift frames uncompressed and its software writes uncompressed ARQ files); the lossless variable-length coding of compression 32767 of the DSLR-A200, A230, A290, A300, A330, A350, A380 and A390 and of the DSLR-A100, which has no raw directory, every photosite equal to the converter's on the sample of each model, with the black level of the encrypted SR2 directory and white level 4095
- **Not decoded:** the white balance of files without the tag and without an SR2 directory of a known layout or key (none among the decoded CC0 files), estimated from the embedded preview; ARQ files of other compressions than lossless JPEG; the YCbCr images of the smaller lossless sizes
- **Source:** Henry Dietz's description; block layout of the MIT `arw` Go package; levels and the variable-length coding derived from CC0 samples against Adobe DNG Converter; the SR2 layouts and their keystream bytes recovered from the encrypted blocks of the CC0 files, where the files with the unencrypted tags, or the converter's white balance of copies of a CC0 file with block bits flipped, give their plain bytes, without any description of the cipher (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Pentax PEF</b></summary>

- **Sensor data decoded:** Huffman compression with the table of maker note tag 0x220 (13 or 15 codes), or without the tag the 13-code table of the K10D, K20D, K200D, K-7, K-x and K-r, as Adobe DNG Converter reads such files; uncompressed 16-bit words, 12-bit packed (PackBits-labeled) data; the K-3 Mark III Monochrome; pixel-shift files (four frames, the first in the main directory and the others in its SubIFDs, each with its own maker note; and the DNG files the cameras write, of four chained raw images) merged into red, green and blue at every pixel, each frame offset by one photosite, every value equal to the linear DNG file Adobe DNG Converter merges the K-1, K-3 Mark III and K-70 CC0 samples into; `RawDecoderOptions.frame` decodes one frame
- **Not decoded:** motion correction of pixel-shift files (the frames are merged as they are, as the converter merges them)
- **Source:** derived from the files' own tables and samples against Adobe DNG Converter; the implied table from the converter's values for copies of CC0 files without the tag
- **Patents:** none known

</details>

<details>
<summary><b>Fujifilm RAF</b></summary>

- **Sensor data decoded:** uncompressed Bayer and X-Trans (16-bit words; 12-bit packed least significant bit first, or most significant bit first in 32-bit words as the S1 and SL1000 store them), the Bayer array as record 0x130 states it (BGGR in the X10, X-S1, XF1, HS50EXR, F900EXR and HS10, whose record lists the array in the opposite order; every photosite and filter color equals Adobe DNG Converter's on the F550EXR, F600EXR, F770EXR, F900EXR, HS10, HS20EXR, X10, S1 and SL1000 samples, the F-series EXR compacts among them); lossless and lossy compressed RAF, Bayer and X-Trans, of 12 to 16 bits (a JPEG-LS-style context coder over six-row groups of vertical strips, near-lossless in four activity classes when lossy, decoded strip by strip in parallel; every photosite equals Adobe DNG Converter's on the lossless X-T2, X-T4, X-E5, GFX 50S, GFX50S II and 16-bit GFX100S samples and the lossy X-T4, X-E5, GFX50S II and 16-bit GFX100 II ones); the bare sensor words of the older models without a sensor directory: SuperCCD sensors (S2Pro, S3Pro, S5Pro, S5000, S5200, S5600, S6000fd, S6500fd, S7000, S9500, S9600, S100FS and the F700, E550 and E900 compacts, every photosite equal to Adobe DNG Converter's; the F710 and F810, of which no file is published, through copies of the F700 and E550 samples named after them, which the converter's entries of those models read to the same photosites with the siblings' color matrices while refusing the swapped copies; and by the same rules the other SuperCCD models whose files carry the same records) as staggered photosites (DNG CFALayout 2, 2x4 filter array, scaled to square pixels, turned upright when stored transposed or turned), the SuperCCD sensor of eight outputs of the DBP for GX680 (stored as eight vertical stripes, one after the other, as its header states; Adobe DNG Converter does not read it, and its rendering matches the camera's own preview), the two images of the F700 stored row by row in turn and the Bayer S5500 and S200EXR; crop, black levels (equal to Adobe DNG Converter's on every sample), the white level of the older models' single images where their clipped highlights pile up (the converter's on the samples whose highlights clip), white balance; the second image of the sensors of two images per photosite (at the header offset 128, every photosite equal to Adobe DNG Converter's on the S3Pro, S5Pro, F700, S200EXR, F550EXR, F600EXR, F770EXR, F900EXR, HS20EXR and X10 samples): the low-sensitivity photodiodes of SuperCCD SR sensors (S3Pro, S5Pro, F700) and the second exposure (DR mode, S200EXR, F600EXR) or second reading (SN mode, F770EXR, X10) of EXR sensors become the second sample of each photosite (`RawImage.channels()` 2), which the rendering merges: the primary sample up to 70% of its saturation, the other one, brought to its scale by per-color ratios measured on the image, from 90%, so the highlights the primary sample clips render (with a negative `exposure`) as the converter's demosaiced files show them, or the mean of two readings of the same exposure (on the F770EXR the merged values keep each reading's level and about half its noise; the converter's own demosaiced SN files keep too little of the photosites' detail to compare); in EXR HR files (F550EXR, X10, F900EXR, HS20EXR and the SuperCCD EXR S200EXR: record 0x0112 states an output of twice the image's area) the two photosites of each diagonal pair interleave into one staggered grid of twice the width, as the converter's demosaiced files place them (through the lens correction it applies to the X10's and F900EXR's; the S200EXR's second image lies up and left of its first, the EXR CMOS models' down and right), every photosite equal to the converter's on an S200EXR HR sample
- **Not decoded:** the F710 and F810 are checked on renamed F700 and E550 files against the converter's entries of those models, as no file of them is published (the F200EXR, the other SuperCCD EXR compact, records JPEG only); white levels the file does not record and its data does not show: files without clipped highlights, the two-image files and the sensor-directory files keep the bit depth's largest value, where the converter takes lower ones from its camera tables (3900 for the HS10 and the EXR compacts, 4000 for the X10, 16250 for the S200EXR, 15564 for older models whose highlights do not clip)
- **Source:** ExifTool documentation; the compressed coding and the older layouts derived from CC0 samples (and CC BY-NC-SA ones of the F700, E550, E900, S5600, HS10, F600EXR, F900EXR and HS20EXR of raw.pixls.us and of an S200EXR in HR mode and an X10 in SN mode of rawsamples.ch, used for testing only) against Adobe DNG Converter, with ITU-T T.87's context modeling
- **Patents:** none known

</details>

<details>
<summary><b>Olympus ORF</b></summary>

- **Sensor data decoded:** Olympus compression (12-bit prediction residuals in an adaptive Rice code, every compressed model); uncompressed 12-bit packed (including the 10-samples-per-16-bytes layout of high-resolution shots and the strip-spanning, even-rows-first layout of the compacts), 16-bit and left-aligned 10 and 12-bit words; white balance, black levels, embedded camera matrix, crop (also from the old `OLYMP` maker note); preview from the maker note
- **Not decoded:** none known
- **Source:** ExifTool documentation (tag names); compression and layouts derived clean-room from CC0 samples against Adobe DNG Converter (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Minolta MRW</b></summary>

- **Sensor data decoded:** uncompressed 12-bit samples packed two in three bytes or one in each 16-bit word (Dynax, Maxxum and Alpha 7D and 5D, Alpha Sweet Digital, DiMAGE A2, DiMAGE 5); both Bayer patterns (red first, and green first as the DiMAGE A200 writes it, with its white balance levels in the pattern's order and its crop one row higher, as the converter reads a CC0 DiMAGE A2 file whose pattern byte is set to 4); centered crop, white balance, white levels, ISO from the shot settings when the EXIF block lacks it; the maker note preview, its damaged first byte repaired and the letterbox of the 3:2 bodies cut off when rendering from it
- **Not decoded:** other storage methods and bit depths, the MRW structure of Sony cameras (no CC0 sample shows them)
- **Source:** ExifTool tag documentation (block and tag names); layouts, crop, levels and preview derived clean-room from CC0 samples against Adobe DNG Converter (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Sigma X3F</b></summary>

- **Sensor data decoded:** the three layers of a Foveon sensor, returned as linear raw data: the TRUE coding (each layer's Huffman-coded differences, predicted from the photosite two columns to the left) of the DP1 to DP3 Merrill compacts, the SD15 and the SD1 Merrill, the Quattro variants of the dp0 to dp3 Quattro, sd Quattro and sd Quattro H (bottom and middle layers at half the top layer's resolution, brought to it by adding their bicubically upsampled differences from the binned top layer to the full-resolution top layer; the binned layout too), the older Huffman coding of the SD14 and the Polaroid x530 (signed 16-bit running sums per layer and row) and its variant stored without Huffman codes; the camera metadata block (type 2, 4 and 5 obfuscation): active area, black levels measured on the dark shield rows (a Quattro top layer at its own resolution), white levels, the as-shot white balance gains and color correction matrix (or, for the SD14, the illuminant and correction matrices) of the setting the block or the header names, the ISO gain; the bad pixel lists and autofocus photosites, interpolated over, and the lens shading (the spatial gain tables of the white balance setting, or of Merrill and Quattro cameras interpolated between the tables of the nearest apertures and focus distances), applied as a `GainMap` when `RawDecoderOptions.opcodes()` is set; camera, shot and orientation from the preview's EXIF block and the property list; the JPEG previews (formats 18 and 25) and the 8-bit RGB thumbnails (uncompressed, format 3, and Huffman-coded, format 11, reported with `ImageFormat.RAW`)
- **Not decoded:** the sd Quattro H's high-resolution-mode lens shading tables (`SpatialGainHP`, which the reference library leaves unused too); Huffman-coded thumbnails stored with a row size, which the specification excludes
- **Source:** Sigma's published FOVb format specification 1.2 (container, property list, previews); the metadata block, the codings, the levels, the corrections, the Quattro expansion and the color conversion ported from the BSD-licensed X3F tools library; checked against that library built from source and against Adobe DNG Converter (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Panasonic RW2 and RAW, Leica RWL and RAW</b></summary>

- **Sensor data decoded:** Panasonic raw coding versions 4 (14 12-bit samples per 128-bit block: two values and four groups of a code and three 8-bit fields, with missing-photosite markers), 5 (nine 14-bit samples per 128-bit block), 6 (11 samples per block, or 14 for 12-bit data: the GH5 II), 7 (nine 14-bit samples per block without the chunk layout: the S5) and 8 (Huffman-coded differences with the file's own table, in tiles side by side or in a grid, as the S1R II's high-resolution files store them, with the shifts, ceiling and cell origins of its raw tags), and the uncompressed data of the oldest models: version 2 (ten 12-bit samples per 16-byte block, the DMC-FZ8) and the RAW files without a version (12-bit samples in 16-bit words: the DMC-FZ50, DMC-LX1 and Leica Digilux 3), every photosite equal to the converter's; the photosites the camera zeroes or marks missing repaired (kept on the DMC-FZ50 and DMC-LX1, whose zero photosites are dark ones); crop, CFA, black and white levels (versions 5 to 8: the converter's white level of every CC0 model, 16380 for the 14-bit DC-S1, S1H, S1R, S5, S1R II and S1 II, 4088 for the DC-GH5 II, 8000 for the DC-GH5S), white balance
- **Not decoded:** other versions (no CC0 sample shows one); the black and white levels of version 4 files, which differ from the converter's (its black levels are the tags' plus 15, and the white levels of many models are lower, by model and in some models by file)
- **Source:** derived clean-room from CC0 samples against Adobe DNG Converter (see below)
- **Patents:** none known

</details>

<details>
<summary><b>Samsung SRW</b></summary>

- **Sensor data decoded:** uncompressed data: 16-bit words (compression 32769, the EX1 and WB2000, the words kept whole) and 12-bit samples packed least or most significant bit first (compression 32770 at the photosites' bits, the NX5, NX10, NX11, NX20, NX200, NX210, NX1000 and NX1100, as the maker note's `RawDataByteOrder` states); Samsung's compressions of the later models: block-coded 32770 at fewer bits than the photosites (NX300, NX300M, NX30, NX2000, Galaxy NX), the prefix-coded 32772 (NX mini, NX3000, NX3300) and 32773 (NX1, NX500), lossless and quantized ("normal"), at 12 and 14 bits; black levels and as-shot white balance from the maker note (stored plus its key), the valid sensor area (or the EXIF image size centered on it), the model's white level and, where the file states another, its filter array; every photosite, filter color, level and white balance equal to Adobe DNG Converter's on the 24 CC0 samples, and every photosite on 43 copies of the compressed samples with single fields changed to values the cameras never write
- **Not decoded:** a 32773 strip whose header states another coding, which is rejected as unsupported
- **Source:** ExifTool tag documentation (tag names); layouts, codings and levels derived clean-room from CC0 samples and patched copies against Adobe DNG Converter (`tools/testdata/srw-oracle.py`, see below)
- **Patents:** none known

</details>

<details>
<summary><b>Epson ERF</b></summary>

- **Sensor data decoded:** the R-D1, R-D1s and R-D1x: ten 12-bit samples per 16-byte block (compression 32769), the maker note's black levels, the 3008 by 2000 image centered on the sensor, the maker note's JPEG preview (its damaged first byte repaired); every photosite and black level equal to the converter's on the three CC0 samples
- **Not decoded:** the as-shot white balance, which no tag gives readably (estimated from the preview)
- **Source:** ExifTool tag documentation (tag names); layout and levels derived clean-room from CC0 samples against Adobe DNG Converter
- **Patents:** none known

</details>

<details>
<summary><b>Hasselblad 3FR and FFF</b></summary>

- **Sensor data decoded:** the predictor-8 lossless JPEG of the H3D to H5D, CFV-50c and their FFF files (32-bit little-endian words read most significant bit first, without byte stuffing; difference categories coded in pairs ahead of their extra bits, category 16 followed by 16 unused bits; each photosite predicted from the one two columns to its left, 32768 at the start of each row), the signed photosites of the files whose white level is 32767 (stored plus 1024, their black level, modulo 65536, so that the 16-bit words above the signed range remain recoverable), and the uncompressed 16-bit data of the X1D, X1D II, X2D, CFV II and CFV 100C; the DNG tags the files carry (color matrix, as-shot neutral, white level, default crop, the FFF files' black level, whose white level the converter puts at 62914); the camera's sensor calibration of the CMOS cameras' 3FR files (the red, green and blue gains of the maker note's GAIN block, re-encoded to black 1028, or the file's black scaled by 65535/62914 when larger up to 13107, and white 65535, as the converter writes them, with its single-precision rounding); the sensor calibration of the CCD cameras' 3FR files (H3D, H4D: the GAIN block's per-row coefficients of the charge each photosite takes from its vertical neighbors, and its flat-field grid of 50-pixel cells, interpolated between the cells' centers and normalized at the sensor's center in single precision as the Adobe DNG SDK's gain map opcode does, restarted in the converter's tiles from the edge of their buffers, which reach 4 columns left per defective column (the tiles' largest width, 296 photosites with up to one defective column and each step about 0.84 times narrower down to 32 as the buffers widen, measured with 0 to 80 defective columns, and the row split into the fewest equal tiles no wider, as the DNG SDK sizes an area's tiles), with the gain map's origin placed half its span before the sensor's center; the H4D's saturated photosites, 16-bit words outside its signed range above -1024, read as the normalized white level, for themselves and for the correction of their vertical neighbors; the columns the block lists as defective, interpolated after the calibration, relative to the black level, as the DNG SDK's FixBadPixelsList opcode does: from the directions whose gradient is at most 1.5 times the smallest, red and blue held between their row neighbors, green from the greens of both kinds with the local difference between them, and columns near each other or the edges from the nearest usable photosites of their color; the H4D's photosites then re-encoded to black 1028 and white 65535 with the block's gains); the sensor calibration of the CCD camera backs' 3FR files (CFV, CF series: the GAIN block's version 13, a record per row of a gain and the coefficients of the vertical neighbors for each column parity, a green photosite taking the mean of the gains of the red and blue photosites beside and below it, applied in single precision to the 16-bit words, those of a signed file read unsigned, with black 0 and white at 96% of the stated level, then its listed defective columns interpolated as above); every photosite, filter color, level, crop and white balance of the three CC0 FFF samples and of the CC0 X1D, X1D II, CFV-50c and CFV 100C 3FR samples equal to Adobe DNG Converter's, and of the CC0 H3D 3FR sample, its four defective columns included, and those of the CC0 H4D 3FR sample but for the offset ramps below; every photosite of a CFV and a CF132 3FR file with version 13 calibration (not redistributable, used as local oracles), the CFV's two defective columns included
- **Not decoded:** the converter's correction of the seam between the H4D's two readout halves (up to 4 units, per color and half from the top and bottom edges to the center row, applied where the image near the center row is dark; its estimator is not derived)
- **Source:** layouts derived clean-room from CC0 samples against Adobe DNG Converter; the CCD calibration's gain map and column interpolation port the Adobe DNG SDK (DNG SDK License Agreement)
- **Patents:** none known

</details>

<details>
<summary><b>Mamiya MEF</b></summary>

- **Sensor data decoded:** the uncompressed 12-bit data of the ZD (most significant bit first); black level 0, white level 4000, the image four photosites in from each edge; the as-shot white balance from the maker note's second directory: the camera's multipliers (tag 0xB3) times the entry of the multiplier table (tag 0xB2) that the white balance mode (tag 0xA2: modes 0 to 8, or a color temperature of 2800 to 10000 K) and its fine-tuning (tag 0xA3, plus or minus three steps) select; every photosite, filter color, level, crop and orientation of the sample, and the white balance of copies of it with other modes, fine-tunings and multipliers, equal Adobe DNG Converter's
- **Not decoded:** the white balance of files whose maker note has no white balance directory (estimated from the preview)
- **Source:** layout and white balance rule derived clean-room from a CC BY-NC-SA raw.pixls.us sample (used for testing only) and altered copies of it against Adobe DNG Converter
- **Patents:** none known

</details>

<details>
<summary><b>Leaf MOS</b></summary>

- **Sensor data decoded:** uncompressed 16-bit photosites and lossless JPEG (compression 99, two interleaved components, the scan's component selectors one above the frame's identifiers) of the Aptus and AFi backs, with the rows of a sensor read as two fields (from the top and bottom edges, stored alternately, as the AFi-II 12 sample is) put in picture order; the PKTS metadata packets: mosaic pattern, neutral levels as the as-shot white balance, rotation as the orientation, the embedded JPEG preview, the XMP camera name; every photosite, filter color, level, white balance and orientation of the uncompressed CC0 sample equal to the converter's
- **Not decoded:** the white level of models other than the Aptus 22 and the AFi-II 12, which no tag gives (the largest 16-bit value); Leaf Credo files, which are IIQ files
- **Source:** packet layout and levels derived clean-room from CC0 samples against Adobe DNG Converter
- **Patents:** none known

</details>

<details>
<summary><b>Kodak DCR, KDC and TIFF</b></summary>

- **Sensor data decoded:** Kodak's compression 65000 of the DCS 760C and DCS Pro 14n, 14nx, SLR/c and SLR/n (groups of up to 256 photosites: 4-bit difference lengths, then the differences least significant bit first in big-endian 16-bit words, each group on a 4-byte boundary; the rows decoded in parallel from the raw directory's table of group starts), the lossless JPEG of the DCS520C, the 12-bit packed data of the EasyShare Z981 and Z990 and the P712 and P880 (after the metadata's padding or right before the JPEG preview), the 8-bit photosites of the EOS DCS 3 (linearized by the first directory's gray response curve, black level the mean of the two masked columns at each side); Kodak's directories: linearization table, image area, black level per column and row, as-shot white balance, the KDC files' filter array, crop and white balance; every photosite, filter color, level, crop and white balance of the 11 CC0 samples the converter reads equal to its own (the DCS520C's black levels within one unit)
- **Not decoded:** the DC120's compressed data (which the converter does not read either)
- **Source:** layouts derived clean-room from CC0 samples against Adobe DNG Converter
- **Patents:** none known

</details>

<details>
<summary><b>Not read: Phase One IIQ</b></summary>

Not read: the sensor data of Phase One IIQ files (and of the Leaf Credo's, which are IIQ files), recognized as `RawFormat.IIQ` from the signature after their TIFF header and described (camera, capture settings, EXIF) but rejected as unsupported, since Phase One publishes no description of the IIQ L, IIQ S and uncompressed codings and no permissively licensed decoder of them exists.

</details>

### How raw files are handled

<details>
<summary><b>Lens corrections</b></summary>

Lens corrections: the radial distortion, lateral chromatic aberration and vignetting a vendor file records for its
shot are applied after demosaicing, as the `WarpRectilinear` and `FixVignetteRadial` opcodes Adobe DNG Converter
writes for those files, and DNG conversion carries them as opcode list 3: Sony's distortion (fixed-lens cameras) and
chromatic aberration tags, Panasonic's distortion and chromatic aberration tags (0x0119 and 0x011B, the latter four
radii and the red and blue displacements at them, measured in the corrected green plane's source), Olympus'
distortion and chromatic aberration tags, and Fujifilm's distortion, chromatic aberration and vignetting tags,
including those of the staggered images of EXR sensors (applied with the radial opcodes measuring distances in
square pixels of the default scale). The layouts were derived clean-room from CC0 samples against the converter's
opcodes: up to the corner of the default crop, the source radius of every plane is within 1 pixel of the converter's
on most of the Sony, Olympus and Panasonic samples tested (3 pixels on the DSC-RX0, 1.5 to 2.5 on the DMC-GX80,
DMC-FZ1000 and DMC-LX100; Panasonic's red and blue planes within 0.1 to 1 pixel; over 86
Panasonic samples the chromatic aberration model is within 0.1 pixel at the median and 1.5 pixels at worst) and
within 7 pixels (25 on the X-A1, 3 on the X10 EXR HR sample) of corrections of up to 200 pixels on the Fujifilm
samples; the vignetting gain is within 1.5% (6% on the X-A1). Not applied: the vignetting data of Sony, Olympus and
Panasonic (which the converter does not apply either), and the distortion of Sony cameras of interchangeable lenses
(which the converter corrects from lens profiles of its own).

</details>

<details>
<summary><b>Color</b></summary>

Color: DNG files carry their camera matrices. Of the vendor formats ORF embeds a usable camera matrix, and
X3F the gains and the matrix to sRGB of each white balance setting. For 52 camera models (Canon, Nikon, Sony, Fujifilm and others) `CameraMatrices` holds matrices for
illuminants A and D65 derived from the measured spectral sensitivities of rawtoaces-data (Apache-2.0): for
each illuminant, the white-preserving matrix that best predicts its 190 training reflectances in CIELAB
(mean error 2 to 3 dE on the training set), recomputed by a test from the pinned archive. Rendered
against the cameras' own JPEG previews, 20 CC0 Nikon files of nine of those models came out closer in
chroma than with the generic matrix (mean distance 5.9 against 7.5; Adobe DNG Converter's matrices,
measured the same way only for comparison, give 5.4). Other files are rendered with a generic matrix (the
median of seven CC0 camera DNG matrices), so their colors are approximate; `RawDecoderOptions.Builder.colorMatrix`
accepts the model's DNG `ColorMatrix` (for example from an Adobe DNG Converter file).
The four-color cameras without matrices, the cyan, magenta, yellow and green Canon PowerShot G1 and Nikon E5700,
render with a D65 matrix fitted against the camera's own JPEG preview of the CC0 sample (`FourColorMatrices`,
recomputed by a test): the grid cells of the preview and of the linear sensor image give the least-squares matrix
from XYZ to the four camera colors, refined on the CIELAB chroma of the cells as the default rendering renders them
(held near the least-squares matrix, as the chroma leaves one direction of the coefficients almost free), and the
white balance of a shot is estimated from its preview, as the files carry no four-color white balance. On the shot
each is fitted to, the grid cells lie at a mean CIELAB chroma distance of 3.2 (G1) and 3.5 (E5700) from the camera's
preview, against 4.9 and 4.2 with Adobe DNG Converter's matrices (measured the same way only for comparison). Other
four-color files without matrices take a generic matrix of their filter colors (`RawInfo.isColorCalibrated` is `false`).

</details>

<details>
<summary><b>Writing DNG</b></summary>

Writing: `DngEncoder` writes DNG files (DNG 1.7.1, backward compatible to 1.1 unless a CFA layout of 6 to 9 or the
source's opcode list 3, calibration illuminant or JPEG XL tiles need a later version). `DngEncoder.encode` writes a `RawImage`
(from `RawDecoder.decodeRaw`, or built with `RawImage.ofCfa`, `ofLinear` and `ofMonochrome`): CFA images of any
pattern, layout and one or two samples per photosite, four-color filter arrays, linear raw and monochrome images, as
16-bit linear reference values (black 0, white 65535) in tiles, uncompressed, lossless JPEG (two components per
CFA tile row, Huffman tables optimized per tile) or lossless JPEG XL (DNG 1.7: a gray or RGB image per tile, for
one or three samples per pixel), tiles coded in parallel; the color description is the image's
neutral as `AsShotNeutral`, its white-balanced camera to XYZ matrix as `ForwardMatrix1` and the color matrix that
maps the D50 white to the neutral, so `decodeRaw` of the file returns the image's samples, neutral and matrix (to
the precision of the rationals, 1e-9). DNG requires the forward matrix to map a white-balanced neutral to the D50
white: the `RawImage` factories reject a matrix that maps it more than 0.01 away, and the encoder scales the rows
of such a matrix (which only a decoded file can carry) to D50. `DngEncoder.convert` converts any file the decoder reads: the whole active
area of the sensor image, linearized and with opcode lists 1 and 2 applied, with the source's default crop, user
crop, default scale and opcode list 3, its color matrices, illuminants, forward matrices, camera calibration,
analog balance, baseline exposure and camera profile (hue, saturation and value maps, look table, tone curve, gain
table maps, RGB tables), the rendering hints of a DNG source (`ShadowScale`, `BaselineNoise`,
`BayerGreenSplit`, `NoiseProfile` and the others the decoder does not interpret), its extra camera profiles (copied
entry by entry in their own byte order, the profile the decoding options choose named as `AsShotProfileName`), its
semantic masks (re-coded as 8-bit Deflate samples, 16-bit and floating-point ones rounded, since the DNG SDK
accepts no other depth; `SemanticInstanceID`, XMP and `MaskSubArea` kept, moved with the CFA margin below: by whole mask samples where the margin allows it, otherwise
resampled at the image's resolution), so that RGB tables applying to them are kept, its `DNGPrivateData` as it is, or
the maker note of any other source (its EXIF maker note, or a CR3 file's `CMT3` one) held in `DNGPrivateData` in Adobe's `MakN` form with its original offset, which
readers use to resolve the offsets inside it (exiftool reads the same maker note fields from the file as from the
source), the camera, orientation and EXIF metadata with the shot's settings, the decoding options' white balance
and profile choices, and the `NewRawImageDigest` of the stored image (computed as the DNG SDK computes it). A CFA image whose
default crop leaves fewer than two photosites on a side gains a margin of same-color photosites, so that a
converter demosaics the crop's edges from real neighbors. By default the file holds a baseline JPEG preview of 1024
pixels on its longest side and a 256-pixel thumbnail, both sRGB renderings of the written image. Every file passes
the DNG SDK's `dng_validate` without a warning (other than those its DNG source has), the SDK reads back its
samples, and it renders a file converted from a DNG source as it renders the source (`DngValidateTest`).

</details>

<details>
<summary><b>Provenance of the vendor codings</b></summary>

Provenance of the vendor codings. The NEF Huffman tables, the Olympus ORF compression and layouts, the
Panasonic RW2 versions 4 to 8, the Pentax PEF tables, Canon's sRAW and mRAW, Canon's CRW compression
and its tables, Canon's CRX coding of CR3 files, the layout of the Nikon E-series Coolpix files and the Minolta
MRW layouts, crop and levels were derived clean-room, without reading any other decoder: from CC0 sample files of the raw.pixls.us
archive and the sensor values Adobe DNG Converter, run as a black box writing uncompressed DNG files, stores
for them, read back with Smalt's own DNG reader. Every claimed file decodes to the converter's values
photosite for photosite (121 NEF files of 60 models, 63 ORF models, 112 RW2 and RWL models, 29 PEF models,
the 31 CC0 sRAW and mRAW files of the 16 Canon models that write them, the 8 CC0 MRW files of seven model names, whose crop, levels and
white balance also equal the converter's, and the 11 CC0 CRW files of eight model names, whose image area,
white levels and white balance also equal the converter's and whose black levels are within one unit of it;
the converter leaves the low bits out of the photosites after a 12-bit CRW file's last whole group of 256; and
the 41 CC0 CR3 files of 29 model names, lossless and C-RAW of both codec versions, in one and two tiles, a dual
pixel file among them, whose second image also equals the converter's, whose image area and white balance also equal the converter's, whose black levels are
within one unit of it and whose white levels equal it except where the converter writes a full-range level of
its own). The Nikon HE and HE* coding was worked out the same way from the 28 CC0 HE and HE* files of the Z 9, Z 8,
Z f, Z 5II, Z 50II and Z 6III (every image area, FX, DX, 1:1 and 16:9) and the converter's DNG files of them: the
strip is a codestream in JPEG XS syntax (ISO/IEC 21122-1) with intoPIX's capabilities marker, a longer picture
header, per-component decomposition levels, its own packet order and vertically predicted bit-plane counts; the four
components it codes are lifted into the 2x2 mosaic in a square-root domain, which a square law turns into linear
photosites. The square law's parameters (black level, gain and pivot) are fields of the 13 bytes the picture
header adds; they and the law's integer arithmetic were measured with synthetic files, a sample's codestream
replaced by one whose values cover every 16-bit code and each bit of those 13 bytes flipped in turn. Every
photosite of the samples equals the converter's (`NikonHighEfficiencyOracleTest`), as does every code of the
synthetic files (`NikonHighEfficiencyTest`). The HE and HE* files of the Z 50II also match the lossless file of the
same scene within a few percent.
Where no CC0 file uses a code or a field value, the converter's values for a copy of a CC0 file that does were
the oracle: the Pentax table implied without maker note tag 0x220 (copies with the tag renamed, of files that embed
that table, another one and 14-bit data), the shifts, ceiling and cell origins of RW2 version 8 (copies with those
tags set), the Sony SR2 white balance of the layouts no file with the unencrypted tags shows (copies of the DSLR-A450
and A700 files with block bytes inverted, which show the bytes the converter's neutral depends on, and with one bit of
a level flipped, whose effect on the neutral gives the bit; inverted copies of the SLT-A55 file confirm the offsets of
the layout of first byte 157), and the CRW codes no sample uses (copies in which one block uses the code under test, the copy the
converter accepts and the coefficient its photosites show giving the code's run and category; the test-scope
`CrwCodeCopies` writes copies whose streams use every code, each equal to the converter's values photosite for
photosite). Codes no file or copy has shown raise `MediaException.Unsupported` instead of being guessed. The test sources of
`smalt-raw` re-run every derivation (`NikonTableDerivation`, `OlympusDerivation`, `PanasonicDerivation`,
`CrwTableDerivation`, `CrxDerivationTest`) and check the decoders against the converter (`RawOracleTest`,
`RawOracleCoverageTest`, `CrwTablesTest`, `Cr3OracleTest`, `SampleGapsOracleTest`); `tools/testdata/nef-oracle.py` and `raw-oracle.py` write the
oracle files they need on Windows, and the tests skip without them.

</details>

<details>
<summary><b>Provenance of the Sigma X3F decoder</b></summary>

Provenance of the Sigma X3F decoder. The container, the property list and the previews follow Sigma's published
FOVb specification 1.2; the camera metadata block, the TRUE, Quattro and older codings, the dark shield, white
level, bad pixel, lens shading, Quattro expansion and color conversion entries are ported from the X3F tools
library of Roland and Erik Karlsson (BSD 3-Clause, commit `e8b48ca`). Adobe DNG Converter 18.0 converts the CC0
DP1, SD14 and SD15 samples but refuses the Merrill and Quattro ones; for X3F it stores denoised,
color-converted values, not the layers, so it checks the image area (its linear DNG covers exactly the
`ActiveImageArea`), the linear relation of the layers to its values (an affine fit of 16x16 block means leaves
under 0.5% of the variance) and the rendering. The X3F tools library, built from source and run as a reference,
checks the rest: the decoded layers of the six CC0 samples (DP1, SD14, SD15, SD1 Merrill, DP2 Merrill and the
dp2 Quattro's three layers of two sizes) and the dp2 Quattro's type 5 metadata block are bit-identical to its
own, so are the layers after its bad pixel interpolation on all six, its lens shading tables (the white balance
setting's table, or the Merrill and Quattro tables interpolated in aperture and lens position) match to the
precision of the 32-bit gains, and the sRGB rendering with the bad pixels and the lens shading corrected
matches its own (its Quattro expansion run with OpenCV 4.10, which it calls for the resize) within one 8-bit
level on average over 36x24 cells of the active area, leaving out the cells that hold nearly saturated
photosites, which that library renders without highlight handling. The codings no CC0 sample shows (the
uncompressed variant of the older coding, the Polaroid x530's, the Huffman-coded thumbnail) are checked on
synthetic files, which that library decodes to the same values (the thumbnail's running sums wrap at 8 bits as
the specification says, where that library's signed 16-bit sums read as 0 past 32767). The black level is taken
from the dark shield rows only, where that library also averages the column ranges (its reference renderings
use the shield rows too): on the Merrill samples the right column range reads about twice the level of the
other shields, and the shield rows reproduce the balance of the camera's own preview. `X3fParserTest` and
`X3fReferenceTest` pin the digests and the cell means, and `RawOracleTest` the comparisons with the converter.

</details>

<details>
<summary><b>Probing raw files</b></summary>

`Media.probe` describes DNG and raw files from their headers (and from the raw decoder when present) and
fills the tags `make`, `model`, `lens`, `iso`, `exposure_time`, `f_number`, `focal_length` and
`creation_time`. The desktop ImageIO reader serves the names `dng` and `raw`.

</details>

## Known limits

> [!WARNING]
> The vendor formats are undocumented, so some codings are decoded only as far as sample files show them.

<details>
<summary><b>Camera raw</b></summary>

Nikon HE and HE* codestreams with other parameters than the samples show (the bits of the picture
header extension that select the form of the square law, component layouts, progression) are refused as
unsupported, since no specification of the format is published. The
frames of CR3 raw burst rolls (CRX encoding type 3) are not decoded; a two-sample DNG file of a Canon dual pixel
file that carries an opcode list 2 (Adobe DNG Converter writes none) has its samples merged instead of split into its
two images; CRX images of more than two tile columns or of several tile rows, and C-RAW images of
codec version 2 in more than one tile, which no CC0 sample shows, are decoded by the rules of the files that do; the lens distortion and chromatic aberration
correction Adobe DNG Converter applies to the PowerShot compacts' CR3 files (as a WarpRectilinear opcode) is not
applied, so their renderings keep the lens's barrel distortion; the Nikon E5700 and the PowerShot G1 carry no color matrices or four-color white balance, so they render
with a matrix fitted to a single shot against the camera's JPEG preview and a white balance estimated from that
preview, and their colors are approximate (a twelve-value `RawDecoderOptions.colorMatrix`, for example from an
Adobe DNG Converter file of the model, replaces it); RW2 version 8 gain and offset tags
(0x39, 0x3A) are not applied, as the converter does not apply them. The Fujifilm lens corrections scale the image to fill its area and
measure the knots in the radius the file records, rules fitted to Adobe DNG Converter's opcodes rather than
documented: the converter's own scale and radius vary from model to model (it leaves the F900EXR unscaled, 64
pixels from Smalt's correction at the corners, and applies no distortion to the GFX100 and GFX100S, GFX50S II and
X-E5, whose files record some), and the Olympus radius scale (`k0^1.5`) is fitted too, the XZ-10 differing most.
`DngEncoder` drops the maker note and the private data when the options replace the EXIF metadata, and a semantic
mask that cannot be decoded (its RGB tables then apply nowhere, as in the source). Cameras outside the 52 profiled
models (Minolta's and the CRW bodies among them) use a generic color matrix, and the white balance of Sony files without the tag and an SR2 directory of a known layout (no decoded CC0 file is one), and that of the CRW files
without white balance levels, is estimated from the embedded preview. The uncompressed and Polaroid x530 variants of the older
X3F coding are verified only on synthetic files (no CC0 sample shows them), Sigma's noise reduction is not
reproduced, and Merrill files render darker and greener than the camera's own preview, which Sigma's
processing brightens and desaturates.

</details>

Related limits elsewhere: [region decoding](../image/README.md#decoding-options) in image,
[AOT training](../aot/README.md#known-limits) in aot.

## License note

`smalt-raw` includes DNG technology under license by Adobe (the royalty-free DNG patent license).
