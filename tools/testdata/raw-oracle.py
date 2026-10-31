#!/usr/bin/env python3
"""Writes the oracle files of the raw module's RawOracleTest, RafOracleTest, OlympusDerivationTest,
PanasonicDerivationTest and RawOracleCoverageTest.

The tests check that the decoder reproduces, exactly, the sensor values Adobe DNG Converter stores
for CC0 sample files of the formats whose coding was worked out against it (ORF, RW2,
PEF variants, Canon mRAW and sRAW, Canon CRW, Canon CR3, interlaced Coolpix NEF, Minolta MRW), check the image
area and the rendering of the Sigma X3F samples it converts (DP1, SD14, SD15), re-derive the Olympus compression and
Panasonic's raw format version 4 from witness samples, and compare the rendering of the
converter's four-color DNG files with the previews it renders into them (Canon PowerShot G1, Nikon
E5700, Sony DSC-F828), render the staggered photosites of two Fujifilm SuperCCD cameras (FinePix S2Pro and
S6500fd) against the previews the converter renders into their DNG files, and check RafOracleTest's Fujifilm RAF
decoding photosite for photosite: compressed files of X-Trans and GFX cameras, the bare sensor words of the FinePix
S-series and compacts (SuperCCD, transposed or not, the F700's rows of two images in turn, and Bayer) and the 12-bit
packings of the FinePix compacts with EXR and other CMOS sensors (F550EXR, F770EXR, X10, S1, SL1000, HS10, and the
EXR HR and DR files of the F600EXR, F900EXR and HS20EXR, an S200EXR file in EXR HR mode and an X10 file in EXR SN
mode). The F700, E550, E900, S5600, HS10, F600EXR, F900EXR and HS20EXR samples of raw.pixls.us and the S200EXR and
X10 samples of rawsamples.ch and the Mamiya ZD sample of raw.pixls.us are licensed CC BY-NC-SA 4.0
(tools/testdata/licences.tsv); like every sample they are only downloaded into the local cache for testing, never
committed or redistributed. This script produces those values: it downloads Adobe DNG
Converter 18.0 for Windows and innoextract 1.9 (both pinned by SHA-256), extracts the converter
without installing it, and runs it as a black box on each sample, writing an uncompressed DNG file
(-u) per sample into <conformance cache>/raw-oracle, where the tests look by default
(-Dsmalt.test.rawOracle overrides it). For the samples of JXL_SAMPLES it also writes <stem>-jxl.dng, a DNG 1.7 file
with the sensor data in lossless JPEG XL tiles stored as four interleaved subimages (RowInterleaveFactor and
ColumnInterleaveFactor 2), JPEG XL previews and the reduced-resolution linear raw previews of fast load data
(-losslessJXL -jxl -p2 -fl), which DngSpecOracleTest decodes against the uncompressed file. For the samples of
SUMMARY_SAMPLES, whose DNG files take 100 to 200 MB each, it keeps instead a <stem>.photosites text file and deletes
the DNG file: the size, color filter array and CFALayout of the stored image, and for each sample of a pixel the
SHA-256 of its values over the whole image (little-endian 16-bit words, row by row) and the values of its first row,
which RafOracleTest uses to place the image in the decoded one. For the Fujifilm samples of two images per photosite
(LINEAR_SAMPLES) it also converts each into a demosaiced linear DNG (-u -l) and keeps a <stem>.linear crop of its
image instead, the window (1536 by 1024 pixels at most) holding the most pixels at or above the 99th percentile of
the brightest channel, or, for the EXR HR files, the centered one: the magic "SMALTLIN", then as little-endian 32-bit integers the version 1, the width and height
of the linear image and the left, top, width and height of the crop, then the crop's red, green and blue values as
little-endian 16-bit words, pixel by pixel and row by row. RafOracleTest compares the merged and interleaved photosites
with them. Of the FinePix F710 and F810 no file is published, so it converts copies of the F700 and E550 samples
named after them (RENAMED_SAMPLES, <stem>.dng each), which RafOracleTest compares photosite for photosite as it does
the samples, and checks that the converter refuses the swapped copies (REFUSED_SAMPLES, the E550's file named F710
and the F700's named F810, a <stem>.refused file each): its entries of the two models read their siblings' layouts and
no other. The sample files are fetched first when the cache lacks them.

With --coverage it instead converts every ORF sample of vectors.tsv and every RW2 or RWL sample of
raw format version 4 (about 160 files; the DNG files take about 6 GB) into
<conformance cache>/raw-oracle-coverage, which RawOracleCoverageTest checks photosite for
photosite (-Dsmalt.test.rawCoverage overrides the directory), and every Sony ARW sample of vectors.tsv (one per
camera model) into a <stem>.levels file there, whose black and white levels and white balance VendorOracleTest
checks.

Windows only: the converter is a Windows executable. Run the script with a Windows build of Python;
under an MSYS2 Python the converter fails to open its input. Version 18.0 stores the same values as 14.0
for every sample both support (checked on RW2 files); 18.0 is needed for the 2022 and later
Panasonic models. The converter is free to download and use; nothing of it is redistributed.

For the samples of VENDOR_SAMPLES (the Samsung SRW, Epson ERF, GoPro GPR, Hasselblad 3FR and FFF, Leaf MOS,
Mamiya MEF and Kodak DCR, KDC and TIFF files, the packed DSLR-A900 ARW file, the DSLR-A450 and A700 cRAW files, the
DSLR-A200 file whose coding the decoder does not read, and the uncompressed Panasonic RAW files of the oldest models)
and the copies of samples with bytes changed (PATCHED_SAMPLES: a Minolta DiMAGE A2 file whose
Bayer pattern byte is set to 4, and Mamiya ZD files with other white balance values), it keeps instead of the DNG file a
<stem>.photosites summary and a <stem>.levels file, and for the samples of CURVE_SAMPLES (the lossy Nikon NEF
files and the 8-bit Kodak EOS DCS 3 file, whose photosites the summary cannot hold) only the
<stem>.levels file. A <stem>.levels file holds, one per line after a comment line, the converter's camera
(model, its UniqueCameraModel), the stored size (size, width and height), the active area (active, top, left,
bottom and right), the default crop relative to it (crop, x, y, width and height), the filter array (cfa, its
columns, rows and colors), the black levels (black, the repeat's rows and columns, then the levels), the white
levels (white), the as-shot neutral (neutral) and the linearization table (linearization, its length, then its
entries); VendorOracleTest compares the decoder with them.

The samples of GAP_SAMPLES (the sRAW and mRAW files of 16 Canon bodies, Pentax files and Panasonic files of raw
format versions 6, 7 and 8) are kept as <stem>.photosites summaries, the Panasonic files with a <stem>.levels file too,
and so are the copies of PATCHED_SAMPLES that rename the Huffman table tag of Pentax files or change the version 8
parameters of a Panasonic file, and the copies
of CODE_COPIES, CRW files whose streams use every code of their table sets, which the script builds as the raw
module's test-scope CrwCodeCopies does (both are pinned by SHA-256).

Naming sample files converts only those (for example the MRW samples of SAMPLES), leaving the other
oracle files in place.

    python tools/testdata/raw-oracle.py [--coverage] [sample ...]
"""
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

CONVERTER_URL = 'https://download.adobe.com/pub/adobe/dng/win/AdobeDNGConverter_x64_18_0.exe'
CONVERTER_SHA256 = 'd551ec18cd81a8cb60dd2a1a0cb28b0a5c29230ff09b251670ff13d24bb71164'
INNOEXTRACT_URL = 'https://github.com/dscharrer/innoextract/releases/download/1.9/innoextract-1.9-windows.zip'
INNOEXTRACT_SHA256 = '6989342c9b026a00a72a38f23b62a8e6a22cc5de69805cf47d68ac2fec993065'

SAMPLES = [
    'rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2',
    'rawpixls-canon-eos-7d-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-7d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-10d-raw-3-2-3164.crw',
    'rawpixls-canon-eos-10d-raw-3-2-3165.crw',
    'rawpixls-canon-eos-10d-raw-3-2.crw',
    'rawpixls-canon-eos-300d-raw-3-2-3162.crw',
    'rawpixls-canon-eos-300d-raw-3-2.crw',
    'rawpixls-canon-eos-d30-raw-3-2.crw',
    'rawpixls-canon-eos-d60-raw-3-2.crw',
    'rawpixls-canon-eos-digital-rebel-raw-3-2.crw',
    'rawpixls-canon-powershot-g1-raw-4-3.crw',
    'rawpixls-canon-powershot-g5-raw-4-3.crw',
    'rawpixls-canon-powershot-g6-raw-4-3.crw',
    'rawpixls-minolta-alpha-7-digital-3-2-3919.mrw',
    'rawpixls-minolta-alpha-7-digital-3-2-7778.mrw',
    'rawpixls-minolta-alpha-sweet-digital-3-2.mrw',
    'rawpixls-minolta-dimage-5-4-3.mrw',
    'rawpixls-minolta-dimage-a2-4-3.mrw',
    'rawpixls-minolta-dynax-5d-3-2.mrw',
    'rawpixls-minolta-dynax-7d-3-2.mrw',
    'rawpixls-minolta-maxxum-7d-3-2.mrw',
    'rawpixls-nikon-coolpix-e8800-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-olympus-c5060wz-12bit-4-3.orf',
    'rawpixls-olympus-e-1-16bit-4-3.orf',
    'rawpixls-olympus-e-10-16bit-4-3.orf',
    'rawpixls-olympus-e-400-16bit-4-3.orf',
    'rawpixls-olympus-e-410-16bit-4-3.orf',
    'rawpixls-olympus-e-m1-16bit-4-3.orf',
    'rawpixls-olympus-e-m10-16bit-4-3.orf',
    'rawpixls-olympus-e-p1-16bit-4-3.orf',
    'rawpixls-olympus-e-p3-16bit-4-3.orf',
    'rawpixls-olympus-e-p5-16bit-4-3.orf',
    'rawpixls-olympus-e-pl6-16bit-4-3.orf',
    'rawpixls-om-system-om-1-16bit-4-3-5283.orf',
    'rawpixls-om-system-tg-7-16bit-4-3.orf',
    'rawpixls-pentax-ist-d-12bit-3-2.pef',
    'rawpixls-pentax-ist-dl-12bit-3-2.pef',
    'rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef',
    'rawpixls-pentax-k-5-14bit-3-2.pef',
    'rawpixls-pentax-k-s1-12bit-3-2.pef',
    'rawpixls-panasonic-dc-g9-4-3-2348.rw2',
    'rawpixls-panasonic-dc-g9m2-4-3-6999.rw2',
    'rawpixls-panasonic-dc-gh5s-4-3-2603.rw2',
    'rawpixls-panasonic-dc-s1-3-2.rw2',
    'rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2',
    'rawpixls-panasonic-dmc-gf6-4-3.rw2',
    'rawpixls-panasonic-dmc-gh1-1-1.rw2',
    'rawpixls-panasonic-dmc-lx3-1-1.rw2',
    'rawpixls-sony-dsc-f828-4-3.srf',
    'rawpixls-fujifilm-finepix-s2pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s6500fd-4-3.raf',
    'rawpixls-fujifilm-finepix-s3pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s5pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s5000-4-3.raf',
    'rawpixls-fujifilm-finepix-s5200-4-3.raf',
    'rawpixls-fujifilm-finepix-s5500-4-3.raf',
    'rawpixls-fujifilm-finepix-s6000fd-4-3.raf',
    'rawpixls-fujifilm-finepix-s7000-4-3.raf',
    'rawpixls-fujifilm-finepix-s9500-4-3.raf',
    'rawpixls-fujifilm-finepix-s9600-4-3.raf',
    'rawpixls-fujifilm-finepix-s100fs-4-3.raf',
    'rawpixls-fujifilm-finepix-s200exr-4-3.raf',
    'rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf',
    'rawpixls-fujifilm-x-e5-14bit-lossless-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf',
    'rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf',
    'rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-x-e5-14bit-lossy-compressed-3-2.raf',
    'rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f770exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-sl1000-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf',
    'rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf',
    'rawpixls-fujifilm-finepix-e550-4-3.raf',
    'rawpixls-fujifilm-finepix-e900-4-3.raf',
    'rawpixls-fujifilm-finepix-s5600-4-3.raf',
    'rawpixls-fujifilm-finepix-hs10-hs11-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf',
    'rawsamples-fujifilm-finepix-s200exr-hr.raf',
    'rawsamples-fujifilm-x10-sn.raf',
    'rawpixls-sigma-dp1-3-2-1116.x3f',
    'rawpixls-sigma-sigma-sd14-3-2-4462.x3f',
    'rawpixls-sigma-sigma-sd15-3-2-1863.x3f',
    'rawpixls-canon-eos-1d-x-mark-iii-3-2.cr3',
    'rawpixls-canon-eos-200d-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-250d-c-raw-3-2.cr3',
    'rawpixls-canon-eos-250d-raw-3-2.cr3',
    'rawpixls-canon-eos-850d-3-2.cr3',
    'rawpixls-canon-eos-90d-3-2.cr3',
    'rawpixls-canon-eos-kiss-m-3-2.cr3',
    'rawpixls-canon-eos-m200-craw-3-2.cr3',
    'rawpixls-canon-eos-m200-raw-3-2.cr3',
    'rawpixls-canon-eos-m50-3-2.cr3',
    'rawpixls-canon-eos-m50-craw-3-2.cr3',
    'rawpixls-canon-eos-m50-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-m6-mark-ii-c-raw-3-2.cr3',
    'rawpixls-canon-eos-m6-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-eos-r-3-2.cr3',
    'rawpixls-canon-eos-r10-3-2.cr3',
    'rawpixls-canon-eos-r100-craw-3-2.cr3',
    'rawpixls-canon-eos-r100-raw-3-2.cr3',
    'rawpixls-canon-eos-r3-3-2.cr3',
    'rawpixls-canon-eos-r5-3-2.cr3',
    'rawpixls-canon-eos-r5-mark-ii-craw-3-2.cr3',
    'rawpixls-canon-eos-r5-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3',
    'rawpixls-canon-eos-r50-craw-3-2.cr3',
    'rawpixls-canon-eos-r50-raw-3-2.cr3',
    'rawpixls-canon-eos-r50-v-raw-3-2.cr3',
    'rawpixls-canon-eos-r6-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-iii-craw-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3',
    'rawpixls-canon-eos-r7-3-2.cr3',
    'rawpixls-canon-eos-r8-3-2.cr3',
    'rawpixls-canon-eos-rebel-sl3-3-2.cr3',
    'rawpixls-canon-eos-rp-3-2.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-c-raw-1-1.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3',
    'rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3',
    'rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3',
    'rawpixls-canon-powershot-v1-craw-3-2.cr3',
]


# kept as <stem>.photosites and <stem>.levels summaries instead of DNG files
VENDOR_SAMPLES = [
    'rawpixls-leica-digilux-3-4-3.raw',
    'rawpixls-panasonic-dmc-fz50-16-9.raw',
    'rawpixls-panasonic-dmc-fz8-4-3.raw',
    'rawpixls-panasonic-dmc-lx1-16-9.raw',
    'rawpixls-samsung-nx1-14bit-3-2.srw',
    'rawpixls-samsung-nx200-12bit-3-2.srw',
    'rawpixls-samsung-nx30-12bit-3-2.srw',
    'rawpixls-samsung-nx3000-12bit-3-2.srw',
    'rawpixls-samsung-ex1-14bit-4-3.srw',
    'rawpixls-samsung-nx210-12bit-3-2.srw',
    'rawpixls-samsung-nx300-12bit-3-2.srw',
    'rawpixls-samsung-nx1000-12bit-3-2.srw',
    'rawpixls-samsung-nx500-12bit-12bit-normal-compression-3-2.srw',
    'rawpixls-samsung-nx500-12bit-12bit-lossless-compression-3-2.srw',
    'rawpixls-samsung-nx500-14bit-14bit-normal-compression-3-2.srw',
    'rawpixls-samsung-nx500-14bit-14bit-lossless-compression-3-2.srw',
    'rawpixls-samsung-nx-mini-12bit-3-2.srw',
    'rawpixls-samsung-wb2000-14bit-4-3.srw',
    'rawpixls-samsung-nx1100-12bit-3-2-4422.srw',
    'rawpixls-samsung-nx1100-12bit-3-2-4423.srw',
    'rawpixls-samsung-nx1100-12bit-3-2-4424.srw',
    'rawpixls-samsung-nx2000-12bit-3-2.srw',
    'rawpixls-samsung-nx20-12bit-3-2.srw',
    'rawpixls-samsung-nx5-12bit-3-2.srw',
    'rawpixls-samsung-nx10-12bit-3-2.srw',
    'rawpixls-samsung-nx11-12bit-3-2.srw',
    'rawpixls-samsung-nx3300-12bit-3-2.srw',
    'rawpixls-samsung-ek-gn120-12bit-3-2.srw',
    'rawpixls-epson-r-d1-12bit-3-2.erf',
    'rawpixls-epson-r-d1s-12bit-3-2.erf',
    'rawpixls-epson-r-d1x-12bit-3-2.erf',
    'rawpixls-gopro-hero5-black-16bit-4-3-936.gpr',
    'rawpixls-gopro-hero5-black-16bit-4-3-1634.gpr',
    'rawpixls-gopro-hero6-black-16bit-4-3-2556.gpr',
    'rawpixls-gopro-hero6-black-16bit-4-3-2557.gpr',
    'rawpixls-gopro-hero6-black-16bit-4-3-2558.gpr',
    'rawpixls-gopro-hero7-black-16bit-4-3-2786.gpr',
    'rawpixls-gopro-hero7-black-16bit-4-3-2846.gpr',
    'rawpixls-gopro-hero7-black-16bit-4-3-2908.gpr',
    'rawpixls-gopro-hero8-black-16bit-4-3-3949.gpr',
    'rawpixls-gopro-hero9-black-16bit-4-3.gpr',
    'rawpixls-gopro-hero10-black-16bit-4-3-4883.gpr',
    'rawpixls-gopro-hero10-black-16bit-4-3-4894.gpr',
    'rawpixls-gopro-hero7-black-16bit-4-3-5766.gpr',
    'rawpixls-gopro-hero8-black-16bit-4-3-5767.gpr',
    'rawpixls-gopro-hero11-black-16bit-5-4.gpr',
    'rawpixls-gopro-fusion-16bit-1-1-7370.gpr',
    'rawpixls-gopro-fusion-16bit-1-1-7371.gpr',
    'rawpixls-hasselblad-h3d-16bit-4-3.3fr',
    'rawpixls-hasselblad-hasselblad-h4d-16bit-4-3.3fr',
    'rawpixls-hasselblad-cfv-50c-16bit-4-3.3fr',
    'rawpixls-hasselblad-x1d-16bit-4-3.3fr',
    'rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr',
    'rawpixls-hasselblad-cfv-100c-16bit-4-3.3fr',
    'rawpixls-hasselblad-h4d-40-16bit-4-3.fff',
    'rawpixls-hasselblad-cfv-50c-16bit-4-3-7199.fff',
    'rawpixls-hasselblad-cfv-50c-16bit-4-3-7218.fff',
    'rawpixls-leaf-aptus-22-16bit-4-3-3197.mos',
    'rawpixls-kodak-easyshare-z981.kdc',
    'rawpixls-kodak-easyshare-z990-nan.kdc',
    'rawpixls-kodak-p880-nan.kdc',
    'rawpixls-kodak-kodak-p712-zoom-digital-camera-nan.kdc',
    'rawpixls-kodak-dcs520c-12bit-3-2.tif',
    'rawpixls-kodak-dcs760c-12bit-3-2.dcr',
    'rawpixls-kodak-dcs-pro-14n-12bit-3-2.dcr',
    'rawpixls-kodak-dcs-pro-14nx-12bit-3-2.dcr',
    'rawpixls-kodak-dcs-pro-slr-c-12bit-3-2.dcr',
    'rawpixls-kodak-dcs-pro-slr-n-12bit-3-2.dcr',
    'rawpixls-sony-dslr-a900-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-dslr-a450-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-dslr-a700-8bit-8bit-compressed-3-2.arw',
    'rawpixls-sony-dslr-a200-12bit-12bit-compressed-3-2.arw',
    'rawpixls-mamiya-zd.mef',
]

# kept as <stem>.levels files instead of DNG files, for their linearization tables
CURVE_SAMPLES = [
    'rawpixls-kodak-eos-dcs-3-8bit-4-3.tif',
    'rawpixls-nikon-1-j1-12bit-12bit-compressed-lossy-type-2-3-2-1017.nef',
    'rawpixls-nikon-coolpix-a-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d3-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d3-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d300-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d300s-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d3100-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d3s-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d4-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d5-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d500-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d5000-12bit-12bit-compressed-lossy-type-2-3-2-1341.nef',
    'rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef',
    'rawpixls-nikon-d6-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d6-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d700-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef',
    'rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef',
    'rawpixls-nikon-d750-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d780-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d780-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d810-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d850-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d90-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-df-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-5-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-5-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-6-2-14bit-14bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-7-2-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-z-7-2-14bit-14bit-compressed-lossy-type-2-3-2.nef',
]

A2 = 'rawpixls-minolta-dimage-a2-4-3.mrw'

# copies of samples with bytes changed, each the source sample and its (offset of a block name, offset within its
# body, new byte) change: the DiMAGE A2 file with the Bayer pattern byte of its picture description (offset 23 of
# the PRD block's body) set to 4, the green-first pattern no CC0 file has; kept as summaries like VENDOR_SAMPLES
PATCHED_SAMPLES = {
    'patched-minolta-dimage-a2-gbrg.mrw': (A2, b'\0PRD', 23, 4),
}

# copies of Pentax samples whose maker note tag 0x0220 (the Huffman table) is renamed 0x7FFF, at the offset of its
# directory entry (in the maker note's byte order): files that embed the table the converter implies without one
# (K10D), another table (K2000), and 14-bit data (K-70)
for _name, _source, _offset, _value in (
        ('k10d', 'rawpixls-pentax-k10d-12bit-12bit-compressed-3-2.pef', 1688, b'\x7f\xff'),
        ('k2000', 'rawpixls-pentax-k2000-12bit-12bit-compressed-3-2.pef', 1796, b'\x7f\xff'),
        ('k-70', 'rawpixls-pentax-k-70-14bit-3-2.pef', 1910, b'\xff\x7f')):
    PATCHED_SAMPLES[f'patched-pentax-{_name}-no-table.pef'] = (_source, None, _offset, _value)

S5M2 = 'rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2'

# copies of a Panasonic raw format version 8 sample with the parameters of its raw tags changed, a list of (absolute
# offset, bytes) changes each: the gains (tag 0x39, 798) and offsets (tag 0x3A, 824) of six 32-bit values, which the
# converter ignores, the ceiling (tag 0x3B, 406) and the origins of the four cells (tags 0x3C to 0x3F, 418, 430, 442
# and 454); and the shifts of the 17 Huffman values (tag 0x41, 920), on short and long codes, some longer than the
# codes they shift
PATCHED_SAMPLES['patched-panasonic-dc-s5m2-ceiling-origins.rw2'] = (S5M2, None, [
    (798, struct.pack('<6I', *[2 << 16] * 6)), (824, struct.pack('<6I', *[100] * 6)), (406, struct.pack('<H', 4000)),
    (418, struct.pack('<H', 1000)), (430, struct.pack('<H', 2000)), (442, struct.pack('<H', 3000)),
    (454, struct.pack('<H', 60000))], None)
PATCHED_SAMPLES['patched-panasonic-dc-s5m2-shifts.rw2'] = (S5M2, None, [
    (920, struct.pack('<17H', 0, 0, 1, 2, 3, 4, 5, 6, 7, 3, 2, 1, 0, 0, 2, 3, 4))], None)
PATCHED_SAMPLES['patched-panasonic-dc-s5m2-long-shifts.rw2'] = (S5M2, None, [
    (920, struct.pack('<17H', 0, 0, 0, 0, 0, 7, 6, 0, 0, 15, 0, 6, 7, 8, 9, 0, 16))], None)

# copies of CRW samples whose streams use every code of their table sets (crw_code_copy, as the raw module's
# test-scope CrwCodeCopies writes them), each its source sample and SHA-256; kept as summaries
CODE_COPIES = {
    'codes-canon-powershot-g1-raw-4-3.crw': ('rawpixls-canon-powershot-g1-raw-4-3.crw',
                                             'b75f1cab12dac178165d615aa37cd8c5b1a4e2c347712a2418eafc8201832b61'),
    'codes-canon-eos-d60-raw-3-2.crw': ('rawpixls-canon-eos-d60-raw-3-2.crw',
                                        'a730b913631e0486f6980e0827645e673712f4d8acf9b9753c42302b0aaf9bc4'),
    'codes-canon-eos-10d-raw-3-2-3164.crw': ('rawpixls-canon-eos-10d-raw-3-2-3164.crw',
                                             'b13377268ae4c9d16b1dbd905220d40108bb6ad71941278760259d06e35a4a6e'),
}

ZD = 'rawpixls-mamiya-zd.mef'

# copies of the Mamiya ZD sample with values of its maker note's white balance directory changed (an absolute
# offset, no block name, and the bytes from there): the white balance mode (tag 0xA2, at 7402) and its fine-tuning
# (tag 0xA3, at 7414, the entry bytes between kept), or the red multiplier of the camera's white balance (tag 0xB3,
# at 100392); VendorOracleTest compares the as-shot neutral of each with the converter's
_BETWEEN = bytes.fromhex('000000a3000300000001')
for _name, _offset, _value in (
        ('mode-5-fine-2', 7402, bytes.fromhex('0005') + _BETWEEN + bytes.fromhex('0002')),
        ('mode-6-fine-minus-3', 7402, bytes.fromhex('0006') + _BETWEEN + bytes.fromhex('0007')),
        ('mode-12', 7402, bytes.fromhex('000c')),
        ('temperature-4000', 7402, bytes.fromhex('0028') + _BETWEEN + bytes.fromhex('0001')),
        ('no-red-multiplier', 100392, bytes(4))):
    PATCHED_SAMPLES[f'patched-mamiya-zd-{_name}.mef'] = (ZD, None, _offset, _value)

# converted a second time into DNG 1.7 files with JPEG XL data, previews and fast load data
JXL_SAMPLES = [
    'rawpixls-canon-eos-10d-raw-3-2.crw',
    'rawpixls-canon-powershot-g1-raw-4-3.crw',
    'rawpixls-fujifilm-finepix-s6500fd-4-3.raf',
]

JXL_FLAGS = ['-losslessJXL', '-jxl', '-p2', '-fl']

# the sRAW and mRAW files of the other bodies, the Pentax files whose maker note table copies of PATCHED_SAMPLES
# leave out, and Panasonic raw format versions 6 (12-bit data), 7 and 8 (a grid of tiles among them); kept as summaries
GAP_SAMPLES = [
    'rawpixls-canon-eos-1d-mark-iv-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-1d-mark-iv-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-1d-x-mark-ii-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-1d-x-mark-ii-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-50d-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-50d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-ii-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-ii-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-iii-sraw1-mraw.cr2',
    'rawpixls-canon-eos-5d-mark-iv-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-iv-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5ds-r-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-5ds-r-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5ds-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-5ds-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-60d-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-60d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-6d-mark-ii-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-6d-mark-ii-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-6d-sraw1-mraw.cr2',
    'rawpixls-canon-eos-6d-sraw2-sraw.cr2',
    'rawpixls-canon-eos-70d-sraw1-mraw.cr2',
    'rawpixls-canon-eos-70d-sraw2-sraw.cr2',
    'rawpixls-canon-eos-7d-mark-ii-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-7d-mark-ii-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-80d-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-80d-sraw2-sraw-3-2.cr2',
    'rawpixls-pentax-k-70-14bit-3-2.pef',
    'rawpixls-pentax-k10d-12bit-12bit-compressed-3-2.pef',
    'rawpixls-pentax-k2000-12bit-12bit-compressed-3-2.pef',
    'rawpixls-panasonic-dc-gh5m2-4-3-5082.rw2',
    'rawpixls-panasonic-dc-gh7-4-3-8062.rw2',
    'rawpixls-panasonic-dc-s1rm2-3-2-7723.rw2',
    'rawpixls-panasonic-dc-s1rm2-3-2-7724.rw2',
    'rawpixls-panasonic-dc-s5-3-2-4098.rw2',
    'rawpixls-panasonic-dc-s5-3-2-6339.rw2',
    'rawpixls-panasonic-dc-s9-3-2-7702.rw2',
]

SAMPLES += GAP_SAMPLES

# kept as <stem>.photosites summaries instead of DNG files, for their size
SUMMARY_SAMPLES = GAP_SAMPLES + [
    'rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf',
    'rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf',
]

# converted a second time into demosaiced linear DNG files, kept as <stem>.linear crops: of the brightest window, or
# of the center for the EXR HR files, where the lens correction the converter applies to the demosaiced image of some
# of them (its WarpRectilinear) moves the pixels least
LINEAR_SAMPLES = {
    'rawpixls-fujifilm-finepix-s3pro-3-2.raf': 'brightest',
    'rawpixls-fujifilm-finepix-s5pro-3-2.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-s200exr-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawsamples-fujifilm-finepix-s200exr-hr.raf': 'center',
}

LINEAR_CROP = (1536, 1024)

F700 = 'rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf'
E550 = 'rawpixls-fujifilm-finepix-e550-4-3.raf'

# copies of the samples of a camera under the model name of a sibling of which no file is published (every
# occurrence of the model name replaced, in the RAF header and in the Exif data), each the source sample, its model
# name and the sibling's: the converter's entry of the F710 must read the F700's file, and that of the F810 the E550's
RENAMED_SAMPLES = {
    'renamed-fujifilm-finepix-f710.raf': (F700, 'FinePix F700', 'FinePix F710'),
    'renamed-fujifilm-finepix-f810.raf': (E550, 'FinePix E550', 'FinePix F810'),
}

# the swapped copies the converter must refuse, which shows that its entries expect the layout they read: for each it
# writes a <stem>.refused file instead of a DNG file
REFUSED_SAMPLES = {
    'renamed-fujifilm-finepix-f700-as-f810.raf': (F700, 'FinePix F700', 'FinePix F810'),
    'renamed-fujifilm-finepix-e550-as-f710.raf': (E550, 'FinePix E550', 'FinePix F710'),
}

TIFF_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8}
TIFF_FORMATS = {1: 'B', 2: 'B', 3: 'H', 4: 'I', 6: 'b', 7: 'B', 8: 'h', 9: 'i', 13: 'I', 16: 'Q'}


def windows(path):
    return os.path.abspath(path).replace('/', '\\')


def rw2_version(path):
    """Returns the raw format version (tag 0x002D of the first directory) of an RW2 or RWL file."""
    with open(path, 'rb') as source:
        data = source.read(1 << 16)
    start = struct.unpack('<I', data[4:8])[0]
    count = struct.unpack('<H', data[start:start + 2])[0]
    for index in range(count):
        entry = start + 2 + 12 * index
        tag, kind, number, value = struct.unpack('<HHII', data[entry:entry + 12])
        if tag == 0x2D:
            return value & 0xFFFF
    return 0


def cfa_image(path):
    """Returns the tags of the CFA image of an uncompressed 16-bit DNG file, its byte order and the file's bytes."""
    with open(path, 'rb') as source:
        data = source.read()
    order = '<' if data[:2] == b'II' else '>'

    def directory(offset):
        count = struct.unpack(order + 'H', data[offset:offset + 2])[0]
        tags = {}
        for index in range(count):
            entry = offset + 2 + 12 * index
            tag, kind, number = struct.unpack(order + 'HHI', data[entry:entry + 8])
            size = TIFF_SIZES.get(kind, 1) * number
            if kind not in TIFF_FORMATS or size > len(data):
                continue
            at = entry + 8 if size <= 4 else struct.unpack(order + 'I', data[entry + 8:entry + 12])[0]
            tags[tag] = list(struct.unpack(order + TIFF_FORMATS[kind] * number, data[at:at + size]))
        following = struct.unpack(order + 'I', data[offset + 2 + 12 * count:offset + 6 + 12 * count])[0]
        return tags, following

    queue = [struct.unpack(order + 'I', data[4:8])[0]]
    while queue:
        offset = queue.pop(0)
        if offset <= 0 or offset >= len(data):
            continue
        tags, following = directory(offset)
        queue.extend(tags.get(330, []))
        if following:
            queue.append(following)
        if tags.get(254, [1])[0] == 0 and tags.get(262, [0])[0] in (32803, 34892):
            if tags.get(259, [1])[0] != 1 or tags.get(258, [0])[0] != 16:
                sys.exit(f'not an uncompressed 16-bit DNG: {path}')
            return tags, order, data
    sys.exit(f'no CFA image in {path}')


def image_words(path):
    """Returns the width, height, samples per pixel and samples (pixel-interleaved, native order) of the main image
    of an uncompressed 16-bit DNG file."""
    from array import array
    tags, order, data = cfa_image(path)
    width, height, spp = tags[256][0], tags[257][0], tags.get(277, [1])[0]
    line = width * spp * 2
    rows = bytearray()
    if 273 in tags:
        for offset, count in zip(tags[273], tags[279]):
            rows += data[offset:offset + count]
    else:
        tile_width, tile_height = tags[322][0], tags[323][0]
        across = (width + tile_width - 1) // tile_width
        offsets = tags[324]
        for top in range(0, height, tile_height):
            for y in range(min(tile_height, height - top)):
                for column in range(across):
                    start = offsets[top // tile_height * across + column] + y * tile_width * spp * 2
                    rows += data[start:start + min(tile_width, width - column * tile_width) * spp * 2]
    del data
    if len(rows) < line * height:
        sys.exit(f'truncated image data in {path}')
    words = array('H')
    words.frombytes(bytes(rows[:line * height]))
    del rows
    if (order == '<') != (sys.byteorder == 'little'):
        words.byteswap()
    return tags, width, height, spp, words


def write_linear_crop(path, target, window):
    """Writes the <stem>.linear crop of a demosaiced linear DNG file (see the module documentation): the brightest
    window, or the centered one."""
    from array import array
    _, width, height, spp, words = image_words(path)
    if spp != 3:
        sys.exit(f'not a three-channel linear DNG: {path}')
    crop_width, crop_height = min(LINEAR_CROP[0], width), min(LINEAR_CROP[1], height)
    if window == 'center':
        write_crop(target, words, width, height, (width - crop_width) // 2, (height - crop_height) // 2, crop_width,
                   crop_height)
        return

    def brightest(y):
        row = words[y * width * 3:(y + 1) * width * 3]
        return list(map(max, row[0::3], row[1::3], row[2::3]))

    sample = sorted(value for y in range(0, height, 16) for value in brightest(y)[::7])
    threshold = sample[int(len(sample) * 0.99)]
    # bright pixels per cell of 64 by 64, then the window of whole cells with the most
    cell = 64
    across, down = (width + cell - 1) // cell, (height + cell - 1) // cell
    counts = [0] * (across * down)
    for y in range(height):
        base = (y // cell) * across
        for x, value in enumerate(brightest(y)):
            if value >= threshold:
                counts[base + x // cell] += 1
    best, left, top = -1, 0, 0
    span_x, span_y = crop_width // cell, crop_height // cell
    for cy in range(0, max(1, down - span_y + 1)):
        for cx in range(0, max(1, across - span_x + 1)):
            total = sum(sum(counts[(cy + j) * across + cx:(cy + j) * across + cx + span_x]) for j in range(span_y))
            if total > best:
                best, left, top = total, cx * cell, cy * cell
    left, top = min(left, width - crop_width), min(top, height - crop_height)
    write_crop(target, words, width, height, left, top, crop_width, crop_height)


def write_crop(target, words, width, height, left, top, crop_width, crop_height):
    """Writes a crop of a three-channel image as a <stem>.linear file."""
    from array import array
    out = array('H')
    for y in range(top, top + crop_height):
        out.extend(words[(y * width + left) * 3:(y * width + left + crop_width) * 3])
    if sys.byteorder != 'little':
        out.byteswap()
    with open(target, 'wb') as output:
        output.write(b'SMALTLIN')
        output.write(struct.pack('<7I', 1, width, height, left, top, crop_width, crop_height))
        output.write(out.tobytes())


def write_summary(path, target):
    """Writes the photosites summary of an uncompressed DNG file (see the module documentation)."""
    import hashlib
    tags, width, height, spp, words = image_words(path)
    if sys.byteorder != 'little':
        words.byteswap()
    lines = [f'# the stored image of {os.path.basename(path)}, written by tools/testdata/raw-oracle.py',
             f'width {width}', f'height {height}', f'samples {spp}']
    if 33421 in tags:
        # a linear raw image (the sRAW and mRAW files) has no filter array
        dimensions = tags[33421]
        lines.append('cfa ' + ' '.join(str(value) for value in [dimensions[1], dimensions[0], *tags[33422]]))
    lines.append(f'layout {tags.get(50711, [1])[0]}')
    for sample in range(spp):
        values = words[sample::spp] if spp > 1 else words
        lines.append(f'digest {sample} {hashlib.sha256(values.tobytes()).hexdigest()}')
        lines.append(f'row {sample} {values[:width].tobytes().hex()}')
    with open(target, 'w', newline='\n') as output:
        output.write('\n'.join(lines) + '\n')


def number(value):
    """Formats a level: an integer when it is one."""
    return str(int(value)) if float(value).is_integer() else repr(float(value))


def dng_directories(data):
    """Returns the first directory's tags and those of the main raw image of a DNG file, values as numbers and text."""
    order = '<' if data[:2] == b'II' else '>'

    def directory(offset):
        count = struct.unpack(order + 'H', data[offset:offset + 2])[0]
        tags = {}
        for index in range(count):
            entry = offset + 2 + 12 * index
            tag, kind, number = struct.unpack(order + 'HHI', data[entry:entry + 8])
            size = TIFF_SIZES.get(kind, 1) * number
            if size > len(data):
                continue
            at = entry + 8 if size <= 4 else struct.unpack(order + 'I', data[entry + 8:entry + 12])[0]
            if kind in (5, 10):
                parts = struct.unpack(order + ('I' if kind == 5 else 'i') * (2 * number), data[at:at + size])
                tags[tag] = [parts[2 * k] / parts[2 * k + 1] if parts[2 * k + 1] else 0.0 for k in range(number)]
            elif kind == 2:
                tags[tag] = data[at:at + size].split(b'\0')[0].decode('latin-1')
            elif kind in TIFF_FORMATS:
                tags[tag] = list(struct.unpack(order + TIFF_FORMATS[kind] * number, data[at:at + size]))
        following = struct.unpack(order + 'I', data[offset + 2 + 12 * count:offset + 6 + 12 * count])[0]
        return tags, following

    first = None
    queue = [struct.unpack(order + 'I', data[4:8])[0]]
    while queue:
        offset = queue.pop(0)
        if offset <= 0 or offset >= len(data):
            continue
        tags, following = directory(offset)
        if first is None:
            first = tags
        queue.extend(tags.get(330, []))
        if following:
            queue.append(following)
        if tags.get(254, [1])[0] == 0 and tags.get(262, [0])[0] in (32803, 34892):
            return first, tags
    sys.exit('no raw image in the DNG file')


def write_levels(path, target):
    """Writes the <stem>.levels file of a DNG file (see the module documentation)."""
    with open(path, 'rb') as source:
        data = source.read()
    first, raw = dng_directories(data)
    width, height = raw[256][0], raw[257][0]
    active = raw.get(50829, [0, 0, height, width])
    lines = [f'# the levels of {os.path.basename(path)}, written by tools/testdata/raw-oracle.py',
             f'model {first.get(50708, "")}', f'size {width} {height}',
             'active ' + ' '.join(str(int(v)) for v in active)]
    origin = raw.get(50719, [0, 0])
    size = raw.get(50720, [active[3] - active[1], active[2] - active[0]])
    lines.append('crop ' + ' '.join(number(v) for v in (*origin, *size)))
    if 33421 in raw:
        dimensions = raw[33421]
        lines.append('cfa ' + ' '.join(str(v) for v in [dimensions[1], dimensions[0], *raw[33422]]))
    repeat = raw.get(50713, [1, 1])
    lines.append('black ' + ' '.join(number(v) for v in [*repeat, *raw.get(50714, [0])]))
    lines.append('white ' + ' '.join(number(v) for v in raw.get(50717, [(1 << raw.get(258, [16])[0]) - 1])))
    if 50728 in first:
        lines.append('neutral ' + ' '.join(repr(float(v)) for v in first[50728]))
    if 50712 in raw:
        lines.append('linearization ' + ' '.join(str(v) for v in [len(raw[50712]), *raw[50712]]))
    with open(target, 'w', newline='\n') as output:
        output.write('\n'.join(lines) + '\n')


def patched(data, block, offset, value):
    """Returns a copy of a file with bytes changed: given a block name, one byte of an MRW header block's body;
    without one, the bytes of value at an absolute offset, or with a list of (offset, bytes) changes as offset."""
    data = bytearray(data)
    if block is None:
        for at, change in (offset if isinstance(offset, list) else [(offset, value)]):
            data[at:at + len(change)] = change
        return bytes(data)
    length = struct.unpack('>I', data[4:8])[0]
    position = 8
    while position + 8 <= 8 + length:
        size = struct.unpack('>I', data[position + 4:position + 8])[0]
        if bytes(data[position:position + 4]) == block:
            data[position + 8 + offset] = value
            return bytes(data)
        position += 8 + size
    sys.exit(f'no {block} block')


CRW_TABLES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'raw', 'src', 'main', 'java', 'com',
                          'github', 'auties00', 'smalt', 'image', 'raw', 'internal', 'CrwTables.java')

CRW_ZERO = -1


def crw_tables():
    """Returns the CRW Huffman tables of the raw module's CrwTables, as (length, code, value) lists in code order."""
    text = open(CRW_TABLES, encoding='utf-8').read()
    body = text[text.index('TABLES = {'):]
    body = body[:body.index('};')]
    tables = []
    for block in re.findall(r'\{([^{}]*)\}', body):
        spec = [CRW_ZERO if token == 'ZERO' else int(token, 0)
                for token in re.findall(r'0x[0-9A-Fa-f]+|\d+|ZERO', block)]
        codes, code, index = [], 0, 16
        for length in range(1, 17):
            for _ in range(spec[length - 1]):
                codes.append((length, code, spec[index]))
                index += 1
                code += 1
            code <<= 1
        tables.append(codes)
    return tables


def ciff_records(data):
    """Returns the records of a CIFF file as {kind: (absolute offset, length)}, the first of each kind."""
    order = '<' if data[:2] == b'II' else '>'
    records = {}

    def walk(start, end, depth):
        directory = struct.unpack(order + 'I', data[end - 4:end])[0] + start
        count = struct.unpack(order + 'H', data[directory:directory + 2])[0]
        for index in range(count):
            at = directory + 2 + 10 * index
            tag, size, offset = struct.unpack(order + 'HII', data[at:at + 10])
            kind = tag & 0x3FFF
            if tag >> 14 == 1:
                records.setdefault(kind, (at + 2, 8))
                continue
            records.setdefault(kind, (start + offset, size))
            if kind & 0x3800 in (0x2800, 0x3000) and depth < 8:
                walk(start + offset, start + offset + size, depth + 1)

    walk(struct.unpack(order + 'I', data[2:6])[0], len(data), 0)
    return order, records


def crw_code_copy(data):
    """Returns the copy of a CRW file that the raw module's test-scope CrwCodeCopies writes: the stream's blocks as
    they are, except in a region of whole rows from the middle row on, where blocks flat at 512 use every code of the
    file's table set (see CrwCodeCopies)."""
    order, records = ciff_records(data)

    def words(kind):
        offset, size = records[kind]
        return struct.unpack(order + 'H' * (size // 2), data[offset:offset + size // 2 * 2])

    raw_offset, raw_length = records[0x2005]
    info = words(0x1031)
    width, height = info[1], info[2]
    table_offset, _ = records[0x1835]
    table_set = struct.unpack(order + 'I', data[table_offset:table_offset + 4])[0]
    skip = struct.unpack(order + 'I', data[table_offset + 8:table_offset + 12])[0]
    sample = words(0x1030) if 0x1030 in records else ()
    bits_per_sample = sample[5] if len(sample) > 5 else 12
    low = (width * height + 3) // 4 if bits_per_sample == 12 else 0
    stream_offset = raw_offset + low + skip
    room = raw_offset + raw_length - stream_offset
    tables = crw_tables()
    dc, ac = tables[2 * table_set], tables[2 * table_set + 1]
    total = width * height
    if total % 64:
        sys.exit('the CRW sensor does not hold whole blocks')
    blocks = total // 64

    # the stream without its stuffed bytes, as a string of bits
    stream = data[stream_offset:stream_offset + room]
    unstuffed = bytearray()
    index = 0
    while index < len(stream):
        value = stream[index]
        if value == 0xFF:
            if index + 1 < len(stream) and stream[index + 1] != 0:
                break
            index += 1
        unstuffed.append(value)
        index += 1
    bits = ''.join(format(value, '08b') for value in unstuffed)

    def category(value):
        return 0 if value == 0 else abs(value).bit_length()

    def field(value, size):
        return value if value >= 0 else value + (1 << size) - 1

    def primary(codes, value):
        for code in codes:
            if code[2] == value:
                return code
        sys.exit(f'no CRW code of value {value}')

    def code_bits(code):
        return format(code[1], '0%db' % code[0])

    def number_bits(value, size):
        return format(value, '0%db' % size) if size else ''

    # the region's blocks: [values, forced codes by index, DC code, end code, skip]
    def empty():
        return [[0] * 64, {}, None, None, 0]

    queue = []
    for code in ac:
        value = code[2]
        block = empty()
        if value == 0:
            block[3] = code
        elif value == CRW_ZERO or value == 0xF0:
            at = 17 if value == 0xF0 else 2
            block[1][1] = code
            block[0][at] = -1
            block[0][at + 2] = 1
            block[4] = 16 if value == 0xF0 else 1
        else:
            run, size = value >> 4, value & 15
            block[0][run + 1] = -(1 << (size - 1))
            block[0][run + 3] = 1 << (size - 1)
            block[1][run + 1] = code
        queue.append([block])
    for code in dc:
        if code[2] == 0:
            block = empty()
            block[2] = code
            queue.append([block])
        elif code[2] <= 10:
            raise_by = 1 << (code[2] - 1)
            lower, raised, back = empty(), empty(), empty()
            lower[0][62] = -raise_by
            raised[0][0] = raise_by
            back[2] = code
            queue.append([lower, raised, back])

    def fits(unit, block):
        high = [0] * (64 * len(unit))
        for position in range(len(high)):
            column = (64 * block + position) % width
            predictor = 512 if column < 2 or position < 2 else high[position - 2]
            value = predictor + unit[position // 64][0][position % 64]
            if value < 0 or value > 1023:
                return False
            high[position] = value
        return True

    rows = 1
    while rows * width % 64:
        rows += 1
    start = (height // 2 // rows) * rows * width // 64
    region = []
    block = start
    while queue or (64 * block) % width:
        unit = queue[0] if queue else [empty()]
        if fits(unit, block):
            if queue:
                queue.pop(0)
        else:
            unit = [empty()]
        region += unit
        block += len(unit)
    end = block
    if end >= blocks:
        sys.exit('the CRW image is too small for the region')

    # the file's blocks up to the region's end: where each starts and its first value
    def decoder(codes):
        return {(length, code): value for length, code, value in codes}

    dc_codes, ac_codes = decoder(dc), decoder(ac)
    position = 0

    def decode(codes):
        nonlocal position
        code = 0
        for length in range(1, 17):
            code = (code << 1) | (bits[position] == '1' if position < len(bits) else 0)
            position += 1
            if (length, code) in codes:
                return codes[(length, code)]
        sys.exit(f'no CRW code at bit {position}')

    def read(count):
        nonlocal position
        piece = bits[position:position + count].ljust(count, '0')
        position += count
        return int(piece, 2) if count else 0

    firsts, starts, dc_ends = [0] * (end + 1), [0] * (end + 1), [0] * (end + 1)
    first = 0
    for block in range(end + 1):
        starts[block] = position
        size = decode(dc_codes)
        value = read(size)
        first += value if size == 0 or value >= 1 << (size - 1) else value - (1 << size) + 1
        firsts[block] = first
        dc_ends[block] = position
        index = 1
        while index < 64:
            value = decode(ac_codes)
            if value == 0:
                break
            if value != CRW_ZERO:
                index += value >> 4
                position += value & 15
            index += 1

    out = [bits[:starts[start]]]
    previous = firsts[start - 1]
    for values, forced, dc_code, end_code, skip_by in region:
        difference = values[0] - previous
        size = category(difference)
        code = dc_code if dc_code is not None else primary(dc, size)
        if code[2] != size:
            sys.exit(f'DC code of category {code[2]} for {difference}')
        out.append(code_bits(code) + number_bits(field(difference, size), size))
        index, last = 1, 63
        while last > 0 and values[last] == 0:
            last -= 1
        while index <= last:
            if index in forced and skip_by > 0:
                out.append(code_bits(forced[index]))
                index += skip_by
                continue
            run = 0
            while values[index + run] == 0:
                run += 1
            while run >= 16:
                out.append(code_bits(primary(ac, 0xF0)))
                run -= 16
                index += 16
            value = values[index + run]
            size = category(value)
            symbol = (run << 4) | size
            chosen = forced.get(index + run)
            if chosen is None or chosen[2] != symbol:
                chosen = primary(ac, symbol)
            out.append(code_bits(chosen) + number_bits(field(value, size), size))
            index += run + 1
        if index <= 63:
            out.append(code_bits(end_code if end_code is not None else primary(ac, 0)))
        previous = values[0]
    difference = firsts[end] - previous
    size = category(difference)
    out.append(code_bits(primary(dc, size)) + number_bits(field(difference, size), size))
    out.append(bits[dc_ends[end]:])
    text = ''.join(out)
    text += '1' * (-len(text) % 8)
    stuffed = bytearray()
    for index in range(0, len(text), 8):
        value = int(text[index:index + 8], 2)
        stuffed.append(value)
        if value == 0xFF:
            stuffed.append(0)
    if len(stuffed) > room:
        sys.exit('the CRW copy\'s stream is longer than the file\'s')
    copy = bytearray(data)
    copy[stream_offset:stream_offset + len(stuffed)] = stuffed
    return bytes(copy)


def main():
    if os.name != 'nt':
        sys.exit('Adobe DNG Converter for Windows is needed; run this script on Windows')
    directory = testdata.conformance_dir()
    vectors = {row['name']: row for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)}
    coverage = '--coverage' in sys.argv[1:]
    named = [argument for argument in sys.argv[1:] if not argument.startswith('--')]
    if coverage:
        samples = sorted(name for name in vectors if name.startswith('rawpixls-') and name.endswith(('.orf', '.rw2', '.rwl')))
        samples += sorted(name for name in vectors if name.startswith('rawpixls-sony-') and name.endswith('.arw'))
    else:
        samples = SAMPLES + VENDOR_SAMPLES + CURVE_SAMPLES
    if named:
        unknown = [name for name in named if name not in samples and name not in PATCHED_SAMPLES
                   and name not in CODE_COPIES]
        if unknown:
            sys.exit(f'not an oracle sample: {", ".join(unknown)}')
        samples = [name for name in named if name not in PATCHED_SAMPLES and name not in CODE_COPIES]
    unavailable = []
    for name in samples:
        row = vectors[name]
        path = os.path.join(directory, name)
        if not testdata.valid(path, row['sha256']):
            print(f'fetching {name}')
            try:
                testdata.download(row['url'], path, row['sha256'])
            except (OSError, ValueError) as error:
                # a host that stops serving a sample, or serves other bytes, leaves only its tests without oracle
                # files, which skip
                print(f'skipping {name}: {error}', file=sys.stderr)
                unavailable.append(name)
    samples = [name for name in samples if name not in unavailable]
    if coverage:
        samples = [name for name in samples
                   if name.endswith(('.orf', '.arw')) or rw2_version(os.path.join(directory, name)) == 4]
    output = os.path.join(directory, 'raw-oracle-coverage' if coverage else 'raw-oracle')
    os.makedirs(output, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='raw-oracle-') as work:
        installer = os.path.join(work, 'AdobeDNGConverter_x64_18_0.exe')
        archive = os.path.join(work, 'innoextract.zip')
        print('fetching Adobe DNG Converter 18.0')
        testdata.download(CONVERTER_URL, installer, CONVERTER_SHA256)
        print('fetching innoextract 1.9')
        testdata.download(INNOEXTRACT_URL, archive, INNOEXTRACT_SHA256)
        with zipfile.ZipFile(archive) as source:
            source.extract('innoextract.exe', work)
        subprocess.run([os.path.join(work, 'innoextract.exe'), '-s', '-e', '-I', 'app', '-d', work, installer], check=True)
        os.remove(installer)
        converter = os.path.join(work, 'app', 'Adobe DNG Converter.exe')
        for name in samples:
            print(f'converting {name}')
            # the converter only accepts backslash-separated paths
            subprocess.run([converter, '-u', '-d', windows(output), windows(os.path.join(directory, name))], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            target = os.path.join(output, os.path.splitext(name)[0] + '.dng')
            if not os.path.isfile(target):
                sys.exit(f'Adobe DNG Converter wrote no {target}')
            if not coverage and name in SUMMARY_SAMPLES:
                write_summary(target, os.path.splitext(target)[0] + '.photosites')
                if name in GAP_SAMPLES and name.endswith('.rw2'):
                    write_levels(target, os.path.splitext(target)[0] + '.levels')
                os.remove(target)
            if not coverage and name in VENDOR_SAMPLES:
                write_summary(target, os.path.splitext(target)[0] + '.photosites')
                write_levels(target, os.path.splitext(target)[0] + '.levels')
                os.remove(target)
            if (not coverage and name in CURVE_SAMPLES) or (coverage and name.endswith('.arw')):
                write_levels(target, os.path.splitext(target)[0] + '.levels')
                os.remove(target)
            if not coverage and name in LINEAR_SAMPLES:
                staging = os.path.join(work, 'linear')
                os.makedirs(staging, exist_ok=True)
                subprocess.run([converter, '-u', '-l', '-d', windows(staging), windows(os.path.join(directory, name))],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                staged = os.path.join(staging, os.path.splitext(name)[0] + '.dng')
                if not os.path.isfile(staged):
                    sys.exit(f'Adobe DNG Converter wrote no {staged}')
                write_linear_crop(staged, os.path.join(output, os.path.splitext(name)[0] + '.linear'),
                                  LINEAR_SAMPLES[name])
                os.remove(staged)
            if not coverage and name in JXL_SAMPLES:
                staging = os.path.join(work, 'jxl')
                os.makedirs(staging, exist_ok=True)
                subprocess.run([converter, *JXL_FLAGS, '-d', windows(staging), windows(os.path.join(directory, name))],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                staged = os.path.join(staging, os.path.splitext(name)[0] + '.dng')
                if not os.path.isfile(staged):
                    sys.exit(f'Adobe DNG Converter wrote no {staged}')
                os.replace(staged, os.path.join(output, os.path.splitext(name)[0] + '-jxl.dng'))
        renamed = 0
        for name, (source, digest) in CODE_COPIES.items():
            if coverage or (named and name not in named):
                continue
            row = vectors[source]
            origin = os.path.join(directory, source)
            if not testdata.valid(origin, row['sha256']):
                print(f'fetching {source}')
                testdata.download(row['url'], origin, row['sha256'])
            print(f'converting {name}')
            with open(origin, 'rb') as stream:
                copy = crw_code_copy(stream.read())
            if hashlib.sha256(copy).hexdigest() != digest:
                sys.exit(f'the copy {name} is not the one CrwCodeCopies writes')
            path = os.path.join(work, name)
            with open(path, 'wb') as stream:
                stream.write(copy)
            subprocess.run([converter, '-u', '-d', windows(output), windows(path)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            target = os.path.join(output, os.path.splitext(name)[0] + '.dng')
            if not os.path.isfile(target):
                sys.exit(f'Adobe DNG Converter wrote no {target}')
            write_summary(target, os.path.splitext(target)[0] + '.photosites')
            os.remove(target)
            os.remove(path)
            renamed += 1
        for name, (source, block, offset, value) in PATCHED_SAMPLES.items():
            if coverage or (named and name not in named):
                continue
            row = vectors[source]
            path = os.path.join(directory, source)
            if not testdata.valid(path, row['sha256']):
                print(f'fetching {source}')
                testdata.download(row['url'], path, row['sha256'])
            print(f'converting {name}')
            with open(path, 'rb') as stream:
                data = stream.read()
            copy = os.path.join(work, name)
            with open(copy, 'wb') as stream:
                stream.write(patched(data, block, offset, value))
            subprocess.run([converter, '-u', '-d', windows(output), windows(copy)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            os.remove(copy)
            target = os.path.join(output, os.path.splitext(name)[0] + '.dng')
            if not os.path.isfile(target):
                sys.exit(f'Adobe DNG Converter wrote no {target}')
            write_summary(target, os.path.splitext(target)[0] + '.photosites')
            write_levels(target, os.path.splitext(target)[0] + '.levels')
            os.remove(target)
            renamed += 1
        for copies, accepted in ((RENAMED_SAMPLES, True), (REFUSED_SAMPLES, False)):
            for name, (source, model, sibling) in copies.items():
                if coverage or source not in samples:
                    continue
                print(f'converting {name}')
                with open(os.path.join(directory, source), 'rb') as stream:
                    data = stream.read()
                copy = os.path.join(work, name)
                with open(copy, 'wb') as stream:
                    stream.write(data.replace(model.encode('ascii'), sibling.encode('ascii')))
                stem = os.path.join(output, os.path.splitext(name)[0])
                for stale in (stem + '.dng', stem + '.refused'):
                    if os.path.isfile(stale):
                        os.remove(stale)
                subprocess.run([converter, '-u', '-d', windows(output), windows(copy)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                os.remove(copy)
                if os.path.isfile(stem + '.dng') != accepted:
                    sys.exit(f'Adobe DNG Converter {"refused" if accepted else "converted"} {name}')
                if not accepted:
                    with open(stem + '.refused', 'w', encoding='ascii') as stream:
                        stream.write(f'Adobe DNG Converter 18.0 writes no DNG file of {source} named {sibling}\n')
                renamed += 1
    print(f'wrote {len(samples) + renamed} oracle files into {output}')


if __name__ == '__main__':
    main()
