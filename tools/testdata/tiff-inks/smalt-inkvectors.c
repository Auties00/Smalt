/*
 * Writes the multi-ink TIFF vectors of smalt-image's TiffInksTest with Little CMS (2.16 or later; checked with
 * 2.17) and libtiff (4.x; checked with 4.7.1).
 *
 *   smalt-inkvectors <directory>
 *
 * writes into <directory>:
 *   ink5.icc           a version 4.3 input profile of five colorants ('5CLR'), Lab connection space, whose AToB0
 *                      tag is a lutAtoBType with a CLUT of 5 grid points per ink between identity curves;
 *   ink6.icc           a version 2.1 input profile of six colorants ('6CLR') whose AToB0 tag is a lut16Type
 *                      with 4 grid points per ink;
 *   inks5-8.tif        41x29, five 8-bit inks, interleaved, LZW, InkSet 2, NumberOfInks 5, InkNames, ink5.icc;
 *   inks6-16a.tif      33x21, six 16-bit inks then an unassociated alpha sample, separate planes, Deflate with
 *                      the horizontal predictor, 7 rows per strip, InkSet 2, NumberOfInks 6, InkNames, ink6.icc;
 *   inks5-8.ref.rgb    the samples of inks5-8.tif converted by Little CMS from ink5.icc to its built-in sRGB,
 *                      8-bit RGB;
 *   inks6-16a.ref.rgba the samples of inks6-16a.tif converted from ink6.icc to sRGB, 16-bit RGBA (little-endian),
 *                      the alpha copied.
 *
 * The conversions use the perceptual intent and Little CMS's default flags (cmsFLAGS_COPY_ALPHA for the alpha),
 * as Little CMS's tificc does; tificc itself cannot read these files, since libtiff 4.7 takes every sample of a
 * separated image after the fourth as an extra sample.
 *
 * Both profiles model subtractive inks with the absorptions of the table below, a product of transmittances
 * in linear light converted to XYZ with the D50-adapted sRGB matrix. The samples are a pseudo-random pattern
 * (the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407 seeded with 0x9E3779B97F4A7C15, top
 * byte or top 16 bits of each state) with a gradient of the first ink across each row and an alpha of zero at
 * every fifth diagonal. The creation dates are cleared, so the output depends only on the Little CMS and
 * libtiff versions.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcms2.h"
#include "tiffio.h"

static const double ABSORPTION[6][3] = {
    {0.90, 0.12, 0.05},  /* cyan */
    {0.10, 0.85, 0.20},  /* magenta */
    {0.02, 0.10, 0.90},  /* yellow */
    {0.95, 0.95, 0.93},  /* black */
    {0.05, 0.55, 0.95},  /* orange */
    {0.85, 0.10, 0.75},  /* green */
};

static const char INK_NAMES[] = "Cyan\0Magenta\0Yellow\0Black\0Orange\0Green\0";

typedef struct {
    int inks;
    int v4;
} Cargo;

static int sample(CMSREGISTER const cmsUInt16Number in[], CMSREGISTER cmsUInt16Number out[],
                  CMSREGISTER void *cargo) {
    const Cargo *c = (const Cargo *) cargo;
    double rgb[3] = {1, 1, 1};
    cmsCIEXYZ xyz;
    cmsCIELab lab;
    int i, k;
    for (i = 0; i < c->inks; i++) {
        double amount = in[i] / 65535.0;
        for (k = 0; k < 3; k++) {
            rgb[k] *= 1 - amount * ABSORPTION[i][k];
        }
    }
    xyz.X = 0.4360747 * rgb[0] + 0.3850649 * rgb[1] + 0.1430804 * rgb[2];
    xyz.Y = 0.2225045 * rgb[0] + 0.7168786 * rgb[1] + 0.0606169 * rgb[2];
    xyz.Z = 0.0139322 * rgb[0] + 0.0971045 * rgb[1] + 0.7141733 * rgb[2];
    cmsXYZ2Lab(cmsD50_XYZ(), &lab, &xyz);
    if (c->v4) {
        cmsFloat2LabEncoded(out, &lab);
    } else {
        cmsFloat2LabEncodedV2(out, &lab);
    }
    return 1;
}

static void fail(const char *what) {
    fprintf(stderr, "smalt-inkvectors: %s\n", what);
    exit(1);
}

static void write_profile(const char *path, int inks, int grid, int v4) {
    cmsHPROFILE profile = cmsCreateProfilePlaceholder(NULL);
    cmsPipeline *lut = cmsPipelineAlloc(NULL, inks, 3);
    cmsStage *clut = cmsStageAllocCLut16bit(NULL, grid, inks, 3, NULL);
    cmsMLU *description = cmsMLUalloc(NULL, 1);
    Cargo cargo = {inks, v4};
    cmsSetProfileVersion(profile, v4 ? 4.3 : 2.1);
    cmsSetDeviceClass(profile, cmsSigInputClass);
    cmsSetColorSpace(profile, inks == 5 ? cmsSig5colorData : cmsSig6colorData);
    cmsSetPCS(profile, cmsSigLabData);
    cmsSetHeaderRenderingIntent(profile, INTENT_PERCEPTUAL);
    cmsStageSampleCLut16bit(clut, sample, &cargo, 0);
    if (v4) {
        /* identity A and B curves around the CLUT, the structure of a lutAtoBType */
        cmsPipelineInsertStage(lut, cmsAT_BEGIN, cmsStageAllocToneCurves(NULL, inks, NULL));
        cmsPipelineInsertStage(lut, cmsAT_END, clut);
        cmsPipelineInsertStage(lut, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, NULL));
    } else {
        cmsPipelineInsertStage(lut, cmsAT_BEGIN, clut);
    }
    cmsWriteTag(profile, cmsSigAToB0Tag, lut);
    cmsMLUsetASCII(description, "en", "US", v4 ? "Smalt five inks" : "Smalt six inks");
    cmsWriteTag(profile, cmsSigProfileDescriptionTag, description);
    cmsWriteTag(profile, cmsSigMediaWhitePointTag, cmsD50_XYZ());
    if (!cmsSaveProfileToFile(profile, path)) {
        fail("cannot write a profile");
    }
    cmsMLUfree(description);
    cmsPipelineFree(lut);
    cmsCloseProfile(profile);
}

static unsigned char *read_file(const char *path, cmsUInt32Number *length) {
    FILE *f = fopen(path, "rb");
    unsigned char *data;
    long size;
    if (f == NULL) {
        fail("cannot read a profile");
    }
    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fseek(f, 0, SEEK_SET);
    data = malloc(size);
    if (fread(data, 1, size, f) != (size_t) size) {
        fail("cannot read a profile");
    }
    fclose(f);
    *length = (cmsUInt32Number) size;
    return data;
}

/* Clears the creation date (bytes 24..35) and the profile ID (bytes 84..99) of a profile file. */
static void clear_date(const char *path) {
    cmsUInt32Number length;
    unsigned char *data = read_file(path, &length);
    FILE *f;
    memset(data + 24, 0, 12);
    memset(data + 84, 0, 16);
    f = fopen(path, "wb");
    fwrite(data, 1, length, f);
    fclose(f);
    free(data);
}

static unsigned long long state = 0x9E3779B97F4A7C15ULL;

static unsigned int next_bits(int bits) {
    state = state * 6364136223846793005ULL + 1442695040888963407ULL;
    return (unsigned int) (state >> (64 - bits));
}

static void write_vector(const char *directory, const char *name, const char *profileName, int width, int height,
                         int inks, int bits, int alpha, int planar, int compression, int predictor,
                         int rowsPerStrip) {
    char path[4096];
    char profilePath[4096];
    TIFF *tif;
    cmsUInt32Number profileLength;
    unsigned char *profile;
    int samples = inks + (alpha ? 1 : 0);
    int bytes = bits / 8;
    size_t rowSize = (size_t) width * (planar ? 1 : samples) * bytes;
    unsigned char *row = malloc(rowSize);
    size_t count = (size_t) width * height * samples;
    unsigned short *values = malloc(sizeof(unsigned short) * count);
    unsigned char *input8 = malloc(count);
    unsigned short extra = EXTRASAMPLE_UNASSALPHA;
    int x, y, s, names = 0, i;
    unsigned int max = (1u << bits) - 1;
    cmsHPROFILE in, out;
    cmsHTRANSFORM transform;
    size_t outSamples = (size_t) width * height * (alpha ? 4 : 3);
    void *reference = malloc(outSamples * bytes);
    FILE *f;
    snprintf(profilePath, sizeof profilePath, "%s/%s", directory, profileName);
    profile = read_file(profilePath, &profileLength);
    for (i = 0; i < inks; i++) {
        names += (int) strlen(INK_NAMES + names) + 1;
    }
    for (y = 0; y < height; y++) {
        for (x = 0; x < width; x++) {
            for (s = 0; s < samples; s++) {
                unsigned int v = next_bits(bits);
                if (s == 0) {
                    v = (unsigned int) ((unsigned long long) x * max / (width - 1));
                }
                if (s == inks && (x + y) % 5 == 0) {
                    v = 0;
                }
                values[(y * width + x) * samples + s] = (unsigned short) v;
                input8[(y * width + x) * samples + s] = (unsigned char) v;
            }
        }
    }
    snprintf(path, sizeof path, "%s/%s.tif", directory, name);
    tif = TIFFOpen(path, "w");
    if (tif == NULL) {
        fail("cannot write a TIFF file");
    }
    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, bits);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, samples);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_SEPARATED);
    TIFFSetField(tif, TIFFTAG_INKSET, INKSET_MULTIINK);
    TIFFSetField(tif, TIFFTAG_NUMBEROFINKS, inks);
    TIFFSetField(tif, TIFFTAG_INKNAMES, names, INK_NAMES);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, planar ? PLANARCONFIG_SEPARATE : PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, compression);
    if (predictor != 1) {
        TIFFSetField(tif, TIFFTAG_PREDICTOR, predictor);
    }
    TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, rowsPerStrip);
    TIFFSetField(tif, TIFFTAG_ICCPROFILE, profileLength, profile);
    if (alpha) {
        TIFFSetField(tif, TIFFTAG_EXTRASAMPLES, 1, &extra);
    }
    for (s = 0; s < (planar ? samples : 1); s++) {
        for (y = 0; y < height; y++) {
            for (x = 0; x < width * (planar ? 1 : samples); x++) {
                unsigned short v = planar ? values[(y * width + x) * samples + s] : values[y * width * samples + x];
                if (bytes == 1) {
                    row[x] = (unsigned char) v;
                } else {
                    ((unsigned short *) row)[x] = v;
                }
            }
            if (TIFFWriteScanline(tif, row, y, (tsample_t) s) < 0) {
                fail("cannot write a TIFF scanline");
            }
        }
    }
    TIFFClose(tif);
    in = cmsOpenProfileFromMem(profile, profileLength);
    out = cmsCreate_sRGBProfile();
    transform = cmsCreateTransform(in,
            COLORSPACE_SH(inks == 5 ? PT_MCH5 : PT_MCH6) | CHANNELS_SH(inks) | EXTRA_SH(alpha ? 1 : 0)
                    | BYTES_SH(bytes),
            out, (alpha ? TYPE_RGBA_8 : TYPE_RGB_8) & ~BYTES_SH(7) | BYTES_SH(bytes),
            INTENT_PERCEPTUAL, alpha ? cmsFLAGS_COPY_ALPHA : 0);
    if (transform == NULL) {
        fail("cannot create the transform");
    }
    cmsDoTransform(transform, bytes == 1 ? (void *) input8 : (void *) values, reference,
            (cmsUInt32Number) (width * height));
    snprintf(path, sizeof path, "%s/%s.ref.%s", directory, name, alpha ? "rgba" : "rgb");
    f = fopen(path, "wb");
    fwrite(reference, bytes, outSamples, f);
    fclose(f);
    cmsDeleteTransform(transform);
    cmsCloseProfile(in);
    cmsCloseProfile(out);
    free(reference);
    free(row);
    free(values);
    free(input8);
    free(profile);
}

static void log_error(cmsContext context, cmsUInt32Number code, const char *text) {
    (void) context;
    fprintf(stderr, "lcms error %u: %s\n", code, text);
}

int main(int argc, char **argv) {
    char profile[4096];
    cmsSetLogErrorHandler(log_error);
    if (argc != 2) {
        fprintf(stderr, "usage: smalt-inkvectors <directory>\n");
        return 2;
    }
    snprintf(profile, sizeof profile, "%s/ink5.icc", argv[1]);
    write_profile(profile, 5, 5, 1);
    clear_date(profile);
    write_vector(argv[1], "inks5-8", "ink5.icc", 41, 29, 5, 8, 0, 0, COMPRESSION_LZW, 1, 29);
    snprintf(profile, sizeof profile, "%s/ink6.icc", argv[1]);
    write_profile(profile, 6, 4, 0);
    clear_date(profile);
    write_vector(argv[1], "inks6-16a", "ink6.icc", 33, 21, 6, 16, 1, 1, COMPRESSION_ADOBE_DEFLATE, 2, 7);
    return 0;
}
