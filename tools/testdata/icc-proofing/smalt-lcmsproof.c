/*
 * Writes the Little CMS reference outputs of the proofing, black-preserving, floating-point and profile building
 * tests of smalt-core (ProofingTransformTest, FloatTransformTest, BlackPreservationTest, ProfileBuildingTest).
 *
 *   smalt-lcmsproof <conformance cache> <advanced profiles> <smalt profiles or -> <directory>
 *
 * <conformance cache> holds the pinned third-party profiles of core's lcms/profiles.tsv (fetched by the tests into
 * ~/.smalt/cache/conformance), <advanced profiles> the profiles of core's image/color/advanced resources, and
 * <smalt profiles> the profiles the main method of the Java test class ProofingFixtures writes (or "-" to skip the
 * cases that read them). The directory receives:
 *   cases.tsv            one transform per line: name, kind (chain or proof), profiles (comma separated), intent
 *                        (P, R, S, A; K + P/R/S for the black-only preserving intents, B + P/R/S for the black-plane
 *                        preserving ones), black point compensation (0, 1), proofing intent, gamut check (0, 1) and
 *                        the alarm values (hexadecimal, comma separated), "-" where they do not apply
 *   <case>.8, .16        the outputs for the 8-bit and 16-bit input sets (16-bit samples little-endian)
 *   <case>.x16           the outputs for the 16-bit input set with cmsFLAGS_NOOPTIMIZE: the linked pipeline evaluated
 *                        on every pixel, without lookup tables
 *   <case>.f32           the outputs for the floating-point input set, little-endian, device values nominal 0..1
 *                        (ink percents divided by 100)
 *   <case>.g8, .g16      for gamut checks, one byte per input pixel: 1 when the gamut check pipeline marks it
 *   ktone.tsv            the black curves of black-preserving chains: name, profiles, intent, bpc; and <name>.kt,
 *                        the 4096 16-bit samples of the curve
 *   tac.tsv              cmsDetectTAC of the output profiles, in percent
 * Profiles that Little CMS creates (cmsCreateRGBProfile and the other virtual profiles) are saved to the directory,
 * reloaded, and read by their file name without ".icc"; they are not test resources.
 *
 * The input sets are those of the Java test (ProofingFixtures):
 *   8-bit, n channels  : every value 0..255 for 1 channel; else a lattice of L levels round(i * 255 / (L - 1)) per
 *                        channel, the first channel slowest (L = 17 for 3 channels, 9 for 4), then 2048 * n bytes of
 *                        noise
 *   16-bit, n channels : a lattice of M levels round(i * 65535 / (M - 1)) per channel (M = 257 for 1 channel, 9 for
 *                        3, 5 for 4), then 1024 * n samples of 16-bit noise
 *   float, n channels  : a lattice of F levels lo + (hi - lo) * i / (F - 1) per channel (F = 101 for 1 channel, 9 for 3,
 *                        6 for 4), then 256 * n values lo + (hi - lo) * (u * 1.4 - 0.2); lo..hi is 0..1 for device
 *                        values, 0..100 then -128..127 for L*a*b* and 0..1 for XYZ
 *   index              : 0 .. count - 1, count, count + 1, 1000, 65535 (named color sources)
 * Noise comes from the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407: bytes are the top 8 bits of each
 * state from the seed 0x9E3779B97F4A7C15, 16-bit samples the top 16 bits from 0xD1B54A32D192ED03, and u the top 24 bits
 * divided by 2^24 from 0x2545F4914F6CDD1D.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcms2_internal.h"

static const char *cache_dir;
static const char *advanced_dir;
static const char *smalt_dir;
static const char *directory;
static FILE *cases_file;

static void fail(const char *what) {
    fprintf(stderr, "smalt-lcmsproof: %s\n", what);
    exit(1);
}

static void error_handler(cmsContext context, cmsUInt32Number code, const char *text) {
    (void) context;
    fprintf(stderr, "lcms error %u: %s\n", (unsigned) code, text);
}

static char *join(const char *dir, const char *name) {
    static char path[4096];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    return path;
}

/* ---- profiles ---- */

typedef struct {
    const char *name;
    const char *file;
    int where; /* 0 cache, 1 advanced, 2 output directory (Little CMS virtual), 3 Smalt */
} Entry;

static const Entry ENTRIES[] = {
    {"srgb", "compact-icc-sRGB-v4.icc", 0},
    {"p3", "compact-icc-DisplayP3-v4.icc", 0},
    {"prophoto", "compact-icc-ProPhoto-v4.icc", 0},
    {"sgrey", "compact-icc-sGrey-v4.icc", 0},
    {"fogra", "skia-icc-misc-Coated_FOGRA39_CMYK.icc", 0},
    {"swop", "skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc", 0},
    {"japan", "skia-icc-misc-Japan_Color_2001_Coated.icc", 0},
    {"mpe-rgb", "mpe-rgb.icc", 1},
    {"mpe-cmyk", "mpe-cmyk.icc", 1},
    {"named-cmyk", "named-cmyk.icc", 1},
    {"lcms-lab4", "lcms-lab4.icc", 2},
    {"lcms-xyz", "lcms-xyz.icc", 2},
    {"lcms-ink280", "lcms-ink280.icc", 2},
    {"lcms-bchsw", "lcms-bchsw.icc", 2},
    {"lcms-lincmyk", "lcms-lincmyk.icc", 2},
    {"lcms-rgbparam", "lcms-rgbparam.icc", 2},
    {"lcms-graytab", "lcms-graytab.icc", 2},
    {"smalt-rgbparam", "smalt-rgbparam.icc", 3},
    {"smalt-graytab", "smalt-graytab.icc", 3},
    {"smalt-lab", "smalt-lab.icc", 3},
    {"smalt-xyz", "smalt-xyz.icc", 3},
    {"smalt-ink280", "smalt-ink280.icc", 3},
    {"smalt-bchsw", "smalt-bchsw.icc", 3},
    {"smalt-lincmyk", "smalt-lincmyk.icc", 3},
    {"smalt-link-sgrey-srgb", "smalt-link-sgrey-srgb.icc", 3},
    {"smalt-link-named", "smalt-link-named.icc", 3},
    {"smalt-retag-srgb", "smalt-retag-srgb.icc", 3},
    {NULL, NULL, 0}
};

static cmsHPROFILE load(const char *name) {
    const Entry *entry;
    for (entry = ENTRIES; entry->name != NULL; entry++) {
        if (strcmp(entry->name, name) == 0) {
            const char *dir = entry->where == 0 ? cache_dir : entry->where == 1 ? advanced_dir
                    : entry->where == 2 ? directory : smalt_dir;
            cmsHPROFILE profile = cmsOpenProfileFromFile(join(dir, entry->file), "r");
            if (profile == NULL) {
                fail(entry->file);
            }
            return profile;
        }
    }
    fail(name);
    return NULL;
}

static int is_smalt(const char *names) {
    return strstr(names, "smalt-") != NULL;
}

static void save(cmsHPROFILE profile, const char *file) {
    if (!cmsSaveProfileToFile(profile, join(directory, file))) {
        fail(file);
    }
    cmsCloseProfile(profile);
}

/* the lookup table cmsCreateBCHSWabstractProfile samples, between identity curves in a version 4.3 profile: Little
   CMS writes that profile as version 2.1, whose lut16Type it reads with the version 2 L*a*b* encoding */
static void save_bchsw(void) {
    cmsHPROFILE bchsw = cmsCreateBCHSWabstractProfile(17, 5.0, 1.1, 15.0, 1.2, 5000, 6500), copy;
    cmsPipeline *lut = cmsPipelineDup((cmsPipeline *) cmsReadTag(bchsw, cmsSigAToB0Tag));
    if (lut == NULL || !cmsPipelineInsertStage(lut, cmsAT_BEGIN, cmsStageAllocToneCurves(NULL, 3, NULL))
            || !cmsPipelineInsertStage(lut, cmsAT_END, cmsStageAllocToneCurves(NULL, 3, NULL))) {
        fail("bchsw");
    }
    copy = cmsCreateProfilePlaceholder(NULL);
    cmsSetProfileVersion(copy, 4.3);
    cmsSetDeviceClass(copy, cmsSigAbstractClass);
    cmsSetColorSpace(copy, cmsSigLabData);
    cmsSetPCS(copy, cmsSigLabData);
    cmsWriteTag(copy, cmsSigMediaWhitePointTag, cmsD50_XYZ());
    if (!cmsWriteTag(copy, cmsSigAToB0Tag, lut)) {
        fail("bchsw");
    }
    cmsPipelineFree(lut);
    cmsCloseProfile(bchsw);
    save(copy, "lcms-bchsw.icc");
}

static void create_virtual(void) {
    cmsCIExyY d65 = {0.3127, 0.3290, 1.0};
    cmsCIExyYTRIPLE primaries = {{0.68, 0.32, 1.0}, {0.265, 0.69, 1.0}, {0.15, 0.06, 1.0}};
    cmsFloat64Number p4[7] = {2.6, 0.95, 0.05, 0.08, 0.06, 0.01, 0.0};
    cmsFloat64Number p2[4] = {2.0, 0.9, 0.1, 0.02};
    cmsToneCurve *curves[4];
    cmsUInt16Number table[11] = {0, 1200, 4000, 9000, 16000, 25000, 34000, 44000, 53000, 61000, 65535};
    cmsFloat64Number gamma = 1.8;
    cmsCIExyY d50;
    curves[0] = cmsBuildParametricToneCurve(NULL, 5, p4);
    curves[1] = cmsBuildParametricToneCurve(NULL, 3, p2);
    curves[2] = cmsBuildGamma(NULL, 2.2);
    save(cmsCreateRGBProfile(&d65, &primaries, curves), "lcms-rgbparam.icc");
    cmsFreeToneCurve(curves[0]);
    cmsFreeToneCurve(curves[1]);
    cmsFreeToneCurve(curves[2]);
    curves[0] = cmsBuildTabulatedToneCurve16(NULL, 11, table);
    cmsXYZ2xyY(&d50, cmsD50_XYZ());
    save(cmsCreateGrayProfile(&d50, curves[0]), "lcms-graytab.icc");
    cmsFreeToneCurve(curves[0]);
    save(cmsCreateLab4Profile(NULL), "lcms-lab4.icc");
    save(cmsCreateXYZProfile(), "lcms-xyz.icc");
    save(cmsCreateInkLimitingDeviceLink(cmsSigCmykData, 280), "lcms-ink280.icc");
    save_bchsw();
    curves[0] = cmsBuildGamma(NULL, gamma);
    curves[1] = cmsBuildGamma(NULL, 0.7);
    curves[2] = cmsBuildParametricToneCurve(NULL, 3, p2);
    curves[3] = cmsBuildTabulatedToneCurve16(NULL, 11, table);
    save(cmsCreateLinearizationDeviceLink(cmsSigCmykData, curves), "lcms-lincmyk.icc");
    cmsFreeToneCurve(curves[0]);
    cmsFreeToneCurve(curves[1]);
    cmsFreeToneCurve(curves[2]);
    cmsFreeToneCurve(curves[3]);
}

/* ---- noise and input sets ---- */

static unsigned long long lcg(unsigned long long *state) {
    *state = *state * 6364136223846793005ULL + 1442695040888963407ULL;
    return *state;
}

static int levels8(int channels) {
    return channels == 3 ? 17 : channels == 4 ? 9 : 4;
}

static int levels16(int channels) {
    return channels == 1 ? 257 : channels == 3 ? 9 : channels == 4 ? 5 : 3;
}

static int levels_float(int channels) {
    return channels == 1 ? 101 : channels == 3 ? 9 : channels == 4 ? 6 : 4;
}

static int power(int base, int exponent) {
    int total = 1, i;
    for (i = 0; i < exponent; i++) {
        total *= base;
    }
    return total;
}

static int pixels8(int channels) {
    return channels == 1 ? 256 : power(levels8(channels), channels) + 2048;
}

static int pixels16(int channels) {
    return power(levels16(channels), channels) + 1024;
}

static int pixels_float(int channels) {
    return power(levels_float(channels), channels) + 256;
}

static unsigned char *inputs8(int channels) {
    int pixels = pixels8(channels), levels = levels8(channels), index, c;
    unsigned char *out = malloc((size_t) pixels * channels);
    unsigned long long state = 0x9E3779B97F4A7C15ULL;
    if (channels == 1) {
        for (index = 0; index < 256; index++) {
            out[index] = (unsigned char) index;
        }
        return out;
    }
    for (index = 0; index < power(levels, channels); index++) {
        int rest = index;
        for (c = channels - 1; c >= 0; c--) {
            out[index * channels + c] = (unsigned char) (((rest % levels) * 255 + (levels - 1) / 2) / (levels - 1));
            rest /= levels;
        }
    }
    for (index = power(levels, channels) * channels; index < pixels * channels; index++) {
        out[index] = (unsigned char) (lcg(&state) >> 56);
    }
    return out;
}

static cmsUInt16Number *inputs16(int channels) {
    int pixels = pixels16(channels), levels = levels16(channels), index, c;
    cmsUInt16Number *out = malloc((size_t) pixels * channels * 2);
    unsigned long long state = 0xD1B54A32D192ED03ULL;
    for (index = 0; index < power(levels, channels); index++) {
        int rest = index;
        for (c = channels - 1; c >= 0; c--) {
            out[index * channels + c] = (cmsUInt16Number) (((rest % levels) * 65535 + (levels - 1) / 2) / (levels - 1));
            rest /= levels;
        }
    }
    for (index = power(levels, channels) * channels; index < pixels * channels; index++) {
        out[index] = (cmsUInt16Number) (lcg(&state) >> 48);
    }
    return out;
}

static void range_of(cmsColorSpaceSignature space, int channel, double *lo, double *hi) {
    if (space == cmsSigLabData) {
        *lo = channel == 0 ? 0 : -128;
        *hi = channel == 0 ? 100 : 127;
    } else {
        *lo = 0;
        *hi = 1;
    }
}

static cmsFloat32Number *inputs_float(cmsColorSpaceSignature space, int channels) {
    int pixels = pixels_float(channels), levels = levels_float(channels), index, c;
    cmsFloat32Number *out = malloc((size_t) pixels * channels * 4);
    unsigned long long state = 0x2545F4914F6CDD1DULL;
    double lo, hi;
    for (index = 0; index < power(levels, channels); index++) {
        int rest = index;
        for (c = channels - 1; c >= 0; c--) {
            range_of(space, c, &lo, &hi);
            out[index * channels + c] = (cmsFloat32Number) (lo + (hi - lo) * (rest % levels) / (levels - 1));
            rest /= levels;
        }
    }
    for (index = power(levels, channels) * channels; index < pixels * channels; index++) {
        double u = (double) (lcg(&state) >> 40) / 16777216.0;
        range_of(space, index % channels, &lo, &hi);
        out[index] = (cmsFloat32Number) (lo + (hi - lo) * (u * 1.4 - 0.2));
    }
    return out;
}

/* ---- outputs ---- */

static void write_bytes(const char *name, const void *data, size_t size) {
    FILE *file = fopen(join(directory, name), "wb");
    if (file == NULL || fwrite(data, 1, size, file) != size) {
        fail(name);
    }
    fclose(file);
}

static void write_words(const char *name, const cmsUInt16Number *data, size_t count) {
    unsigned char *bytes = malloc(count * 2);
    size_t i;
    for (i = 0; i < count; i++) {
        bytes[i * 2] = (unsigned char) data[i];
        bytes[i * 2 + 1] = (unsigned char) (data[i] >> 8);
    }
    write_bytes(name, bytes, count * 2);
    free(bytes);
}

static void write_floats(const char *name, const cmsFloat32Number *data, size_t count) {
    unsigned char *bytes = malloc(count * 4);
    size_t i;
    for (i = 0; i < count; i++) {
        cmsUInt32Number bits;
        memcpy(&bits, &data[i], 4);
        bytes[i * 4] = (unsigned char) bits;
        bytes[i * 4 + 1] = (unsigned char) (bits >> 8);
        bytes[i * 4 + 2] = (unsigned char) (bits >> 16);
        bytes[i * 4 + 3] = (unsigned char) (bits >> 24);
    }
    write_bytes(name, bytes, count * 4);
    free(bytes);
}

static char *file_name(const char *name, const char *extension) {
    static char buffer[512];
    snprintf(buffer, sizeof(buffer), "%s.%s", name, extension);
    return buffer;
}

/* ---- transforms ---- */

static int is_pcs(cmsColorSpaceSignature space) {
    return space == cmsSigXYZData || space == cmsSigLabData;
}

/* the entry and exit color spaces, as GetXFormColorSpaces (cmsxform.c) finds them */
static void spaces(cmsHPROFILE *profiles, int count, cmsColorSpaceSignature *entry, cmsColorSpaceSignature *exit_space,
                   int *named) {
    cmsColorSpaceSignature post = cmsGetColorSpace(profiles[0]);
    int i;
    *named = cmsGetDeviceClass(profiles[0]) == cmsSigNamedColorClass;
    *entry = post;
    for (i = 0; i < count; i++) {
        cmsProfileClassSignature cls = cmsGetDeviceClass(profiles[i]);
        cmsColorSpaceSignature in, out;
        if (cls == cmsSigNamedColorClass) {
            in = cmsSig1colorData;
            out = count > 1 ? cmsGetPCS(profiles[i]) : cmsGetColorSpace(profiles[i]);
        } else if (!is_pcs(post) || cls == cmsSigLinkClass) {
            in = cmsGetColorSpace(profiles[i]);
            out = cmsGetPCS(profiles[i]);
        } else {
            in = cmsGetPCS(profiles[i]);
            out = cmsGetColorSpace(profiles[i]);
        }
        if (i == 0) {
            *entry = in;
        }
        post = out;
    }
    *exit_space = post;
}

static cmsUInt32Number format(cmsColorSpaceSignature space, int bytes) {
    return COLORSPACE_SH(_cmsLCMScolorSpace(space)) | CHANNELS_SH(cmsChannelsOfColorSpace(space)) | BYTES_SH(bytes);
}

static cmsUInt32Number float_format(cmsColorSpaceSignature space) {
    if (space == cmsSigLabData) {
        return TYPE_Lab_FLT;
    }
    if (space == cmsSigXYZData) {
        return TYPE_XYZ_FLT;
    }
    return FLOAT_SH(1) | COLORSPACE_SH(_cmsLCMScolorSpace(space)) | CHANNELS_SH(cmsChannelsOfColorSpace(space))
           | BYTES_SH(4);
}

static int is_ink(cmsColorSpaceSignature space) {
    switch (_cmsLCMScolorSpace(space)) {
        case PT_CMY: case PT_CMYK: case PT_MCH5: case PT_MCH6: case PT_MCH7: case PT_MCH8: case PT_MCH9:
        case PT_MCH10: case PT_MCH11: case PT_MCH12: case PT_MCH13: case PT_MCH14: case PT_MCH15:
            return 1;
        default:
            return 0;
    }
}

static cmsUInt32Number intent_code(const char *intent) {
    if (intent[0] == 'K' || intent[0] == 'B') {
        int base = intent[1] == 'P' ? 0 : intent[1] == 'R' ? 1 : 2;
        return (intent[0] == 'K' ? INTENT_PRESERVE_K_ONLY_PERCEPTUAL : INTENT_PRESERVE_K_PLANE_PERCEPTUAL) + base;
    }
    switch (intent[0]) {
        case 'P': return INTENT_PERCEPTUAL;
        case 'R': return INTENT_RELATIVE_COLORIMETRIC;
        case 'S': return INTENT_SATURATION;
        default: return INTENT_ABSOLUTE_COLORIMETRIC;
    }
}

static int load_list(const char *names, cmsHPROFILE *profiles) {
    char list[512], *token;
    int count = 0;
    strncpy(list, names, sizeof(list) - 1);
    list[sizeof(list) - 1] = 0;
    for (token = strtok(list, ","); token != NULL; token = strtok(NULL, ",")) {
        profiles[count++] = load(token);
    }
    return count;
}

/* runs the three depths of a transform built by create; for proofing transforms, writes the gamut masks too */
typedef cmsHTRANSFORM (*Factory)(void *context, cmsUInt32Number in, cmsUInt32Number out, cmsUInt32Number flags);

static void mask(const char *name, const char *extension, cmsHTRANSFORM transform, const cmsUInt16Number *words,
                 int channels, int pixels) {
    _cmsTRANSFORM *x = (_cmsTRANSFORM *) transform;
    unsigned char *marks = malloc((size_t) pixels);
    cmsUInt16Number in[cmsMAXCHANNELS], out;
    int i, c;
    if (x->GamutCheck == NULL) {
        fail("no gamut check pipeline");
    }
    for (i = 0; i < pixels; i++) {
        memset(in, 0, sizeof(in));
        for (c = 0; c < channels; c++) {
            in[c] = words[i * channels + c];
        }
        cmsPipelineEval16(in, &out, x->GamutCheck);
        marks[i] = out >= 1;
    }
    write_bytes(file_name(name, extension), marks, (size_t) pixels);
    free(marks);
}

static void run_depths(const char *name, Factory create, void *context, cmsColorSpaceSignature entry,
                       cmsColorSpaceSignature exit_space, int gamut) {
    int inputs = cmsChannelsOfColorSpace(entry), outputs = cmsChannelsOfColorSpace(exit_space), pixels, i;
    unsigned char *in8 = inputs8(inputs), *out8;
    cmsUInt16Number *in16 = inputs16(inputs), *out16, *wide;
    cmsFloat32Number *inf = inputs_float(entry, inputs), *outf;
    cmsHTRANSFORM t8 = create(context, format(entry, 1), format(exit_space, 1), 0);
    cmsHTRANSFORM t16 = create(context, format(entry, 2), format(exit_space, 2), 0);
    cmsHTRANSFORM tx = create(context, format(entry, 2), format(exit_space, 2), cmsFLAGS_NOOPTIMIZE);
    cmsHTRANSFORM tf = create(context, float_format(entry), float_format(exit_space), 0);
    if (t8 == NULL || t16 == NULL || tx == NULL || tf == NULL) {
        fail(name);
    }
    pixels = pixels8(inputs);
    out8 = calloc((size_t) pixels * outputs, 1);
    cmsDoTransform(t8, in8, out8, (cmsUInt32Number) pixels);
    write_bytes(file_name(name, "8"), out8, (size_t) pixels * outputs);
    if (gamut) {
        wide = malloc((size_t) pixels * inputs * 2);
        for (i = 0; i < pixels * inputs; i++) {
            wide[i] = (cmsUInt16Number) (in8[i] * 257);
        }
        mask(name, "g8", t8, wide, inputs, pixels);
        free(wide);
    }
    pixels = pixels16(inputs);
    out16 = calloc((size_t) pixels * outputs, 2);
    cmsDoTransform(t16, in16, out16, (cmsUInt32Number) pixels);
    write_words(file_name(name, "16"), out16, (size_t) pixels * outputs);
    cmsDoTransform(tx, in16, out16, (cmsUInt32Number) pixels);
    write_words(file_name(name, "x16"), out16, (size_t) pixels * outputs);
    if (gamut) {
        mask(name, "g16", t16, in16, inputs, pixels);
    }
    pixels = pixels_float(inputs);
    if (is_ink(entry)) {
        for (i = 0; i < pixels * inputs; i++) {
            inf[i] *= 100.0f;
        }
    }
    outf = calloc((size_t) pixels * outputs, 4);
    cmsDoTransform(tf, inf, outf, (cmsUInt32Number) pixels);
    if (is_ink(exit_space)) {
        for (i = 0; i < pixels * outputs; i++) {
            outf[i] /= 100.0f;
        }
    }
    write_floats(file_name(name, "f32"), outf, (size_t) pixels * outputs);
    cmsDeleteTransform(t8);
    cmsDeleteTransform(t16);
    cmsDeleteTransform(tx);
    cmsDeleteTransform(tf);
    free(in8);
    free(out8);
    free(in16);
    free(out16);
    free(inf);
    free(outf);
}

typedef struct {
    cmsHPROFILE profiles[8];
    int count;
    cmsUInt32Number intent;
    cmsUInt32Number flags;
} Chain;

static cmsHTRANSFORM create_chain(void *context, cmsUInt32Number in, cmsUInt32Number out, cmsUInt32Number flags) {
    Chain *chain = context;
    return cmsCreateMultiprofileTransform(chain->profiles, (cmsUInt32Number) chain->count, in, out, chain->intent,
                                          chain->flags | flags);
}

static void chain(const char *name, const char *names, const char *intent, int bpc) {
    Chain context;
    cmsColorSpaceSignature entry, exit_space;
    int named, i;
    if (is_smalt(names) && smalt_dir == NULL) {
        return;
    }
    context.count = load_list(names, context.profiles);
    context.intent = intent_code(intent);
    context.flags = bpc ? cmsFLAGS_BLACKPOINTCOMPENSATION : 0;
    spaces(context.profiles, context.count, &entry, &exit_space, &named);
    fprintf(cases_file, "%s\tchain\t%s\t%s\t%d\t-\t-\t-\n", name, names, intent, bpc);
    if (named) {
        cmsNAMEDCOLORLIST *colors = cmsReadTag(context.profiles[0], cmsSigNamedColor2Tag);
        int count = (int) cmsNamedColorCount(colors), outputs = cmsChannelsOfColorSpace(exit_space), pixels = count + 4;
        cmsUInt16Number *in = malloc((size_t) pixels * 2), *out16 = calloc((size_t) pixels * outputs, 2);
        cmsHTRANSFORM t16 = cmsCreateMultiprofileTransform(context.profiles, (cmsUInt32Number) context.count,
                TYPE_NAMED_COLOR_INDEX, format(exit_space, 2), context.intent, context.flags);
        if (t16 == NULL) {
            fail(name);
        }
        for (i = 0; i < count; i++) {
            in[i] = (cmsUInt16Number) i;
        }
        in[count] = (cmsUInt16Number) count;
        in[count + 1] = (cmsUInt16Number) (count + 1);
        in[count + 2] = 1000;
        in[count + 3] = 65535;
        cmsDoTransform(t16, in, out16, (cmsUInt32Number) pixels);
        write_words(file_name(name, "16"), out16, (size_t) pixels * outputs);
        cmsDeleteTransform(t16);
        free(in);
        free(out16);
    } else {
        run_depths(name, create_chain, &context, entry, exit_space, 0);
    }
    for (i = 0; i < context.count; i++) {
        cmsCloseProfile(context.profiles[i]);
    }
}

typedef struct {
    cmsHPROFILE source, proof, target;
    cmsUInt32Number intent, proof_intent, flags;
} Proof;

static cmsHTRANSFORM create_proof(void *context, cmsUInt32Number in, cmsUInt32Number out, cmsUInt32Number flags) {
    Proof *proof = context;
    return cmsCreateProofingTransform(proof->source, in, proof->target, out, proof->proof, proof->intent,
                                      proof->proof_intent, proof->flags | flags);
}

static void proof(const char *name, const char *names, const char *intent, int bpc, const char *proof_intent,
                  int gamut, const char *alarm) {
    Proof context;
    cmsHPROFILE profiles[8];
    cmsUInt16Number codes[cmsMAXCHANNELS];
    cmsColorSpaceSignature entry = 0, exit_space = 0;
    char list[128], *token;
    int count, i = 0;
    count = load_list(names, profiles);
    if (count != 3) {
        fail(name);
    }
    memset(codes, 0, sizeof(codes));
    strncpy(list, alarm, sizeof(list) - 1);
    list[sizeof(list) - 1] = 0;
    for (token = strtok(list, ","); token != NULL; token = strtok(NULL, ",")) {
        codes[i++] = (cmsUInt16Number) strtol(token, NULL, 16);
    }
    cmsSetAlarmCodes(codes);
    context.source = profiles[0];
    context.proof = profiles[1];
    context.target = profiles[2];
    context.intent = intent_code(intent);
    context.proof_intent = intent_code(proof_intent);
    context.flags = cmsFLAGS_SOFTPROOFING | (gamut ? cmsFLAGS_GAMUTCHECK : 0)
            | (bpc ? cmsFLAGS_BLACKPOINTCOMPENSATION : 0);
    entry = cmsGetColorSpace(profiles[0]);
    exit_space = cmsGetColorSpace(profiles[2]);
    fprintf(cases_file, "%s\tproof\t%s\t%s\t%d\t%s\t%d\t%s\n", name, names, intent, bpc, proof_intent, gamut, alarm);
    run_depths(name, create_proof, &context, entry, exit_space, gamut);
    for (i = 0; i < 3; i++) {
        cmsCloseProfile(profiles[i]);
    }
}

static void ktone(FILE *file, const char *name, const char *names, const char *intent, int bpc) {
    cmsHPROFILE profiles[8];
    cmsUInt32Number intents[8];
    cmsBool bpcs[8];
    cmsFloat64Number adaptation[8];
    cmsToneCurve *curve;
    int count = load_list(names, profiles), i;
    for (i = 0; i < count; i++) {
        intents[i] = intent_code(intent);
        bpcs[i] = bpc;
        adaptation[i] = 1.0;
    }
    curve = _cmsBuildKToneCurve(NULL, 4096, (cmsUInt32Number) count, intents, profiles, bpcs, adaptation, 0);
    if (curve == NULL) {
        fail(name);
    }
    write_words(file_name(name, "kt"), cmsGetToneCurveEstimatedTable(curve), 4096);
    fprintf(file, "%s\t%s\t%s\t%d\n", name, names, intent, bpc);
    cmsFreeToneCurve(curve);
    for (i = 0; i < count; i++) {
        cmsCloseProfile(profiles[i]);
    }
}

int main(int argc, char **argv) {
    FILE *kt, *tac;
    const char *outputs[] = {"fogra", "swop", "japan", NULL};
    int i;
    if (argc != 5) {
        fprintf(stderr, "usage: smalt-lcmsproof <conformance cache> <advanced profiles> <smalt profiles or -> <dir>\n");
        return 2;
    }
    cache_dir = argv[1];
    advanced_dir = argv[2];
    smalt_dir = strcmp(argv[3], "-") == 0 ? NULL : argv[3];
    directory = argv[4];
    cmsSetLogErrorHandler(error_handler);
    create_virtual();
    cases_file = fopen(join(directory, "cases.tsv"), "w");
    if (cases_file == NULL) {
        fail("cases.tsv");
    }

    proof("proof-srgb-fogra-srgb", "srgb,fogra,srgb", "P", 0, "P", 0, "7f00,7f00,7f00");
    proof("proof-srgb-fogra-srgb-abs", "srgb,fogra,srgb", "P", 0, "A", 0, "7f00,7f00,7f00");
    proof("proof-p3-swop-srgb-relbpc", "p3,swop,srgb", "R", 1, "R", 0, "7f00,7f00,7f00");
    proof("proof-swop-fogra-p3", "swop,fogra,p3", "S", 0, "R", 0, "7f00,7f00,7f00");
    proof("gamut-srgb-fogra-srgb", "srgb,fogra,srgb", "P", 0, "P", 1, "7f00,7f00,7f00");
    proof("gamut-p3-swop-p3-rel", "p3,swop,p3", "R", 0, "R", 1, "ffff,0,ffff");
    proof("gamut-srgb-japan-prophoto-abs", "srgb,japan,prophoto", "R", 1, "A", 1, "7f00,7f00,7f00");
    proof("gamut-swop-fogra-srgb", "swop,fogra,srgb", "P", 0, "R", 1, "0,ffff,0");
    proof("gamut-prophoto-srgb-prophoto", "prophoto,srgb,prophoto", "P", 0, "P", 1, "7f00,7f00,7f00");
    proof("gamut-sgrey-fogra-srgb", "sgrey,fogra,srgb", "R", 0, "R", 1, "ffff,0,0");
    proof("gamut-srgb-fogra-swop", "srgb,fogra,swop", "P", 0, "R", 1, "0,0,0,ffff");
    proof("gamut-srgb-fogra-srgb-konly", "srgb,fogra,srgb", "KP", 0, "R", 1, "7f00,7f00,7f00");
    proof("proof-swop-fogra-japan-konly", "swop,fogra,japan", "KR", 0, "R", 0, "7f00,7f00,7f00");
    proof("gamut-fogra-swop-fogra-kplane", "fogra,swop,fogra", "BP", 0, "P", 1, "0,0,0,ffff");

    chain("konly-swop-fogra", "swop,fogra", "KP", 0);
    chain("konly-fogra-japan-relbpc", "fogra,japan", "KR", 1);
    chain("konly-japan-swop-sat", "japan,swop", "KS", 0);
    chain("kplane-swop-fogra", "swop,fogra", "BP", 0);
    chain("kplane-fogra-swop-rel", "fogra,swop", "BR", 0);
    chain("kplane-swop-fogra-ink280", "swop,fogra,lcms-ink280", "BP", 0);
    chain("konly-swop-fogra-ink280", "swop,fogra,lcms-ink280", "KP", 0);
    chain("konly-srgb-fogra", "srgb,fogra", "KP", 0);
    chain("kplane-swop-fogra-fogra-srgb", "swop,fogra,fogra,srgb", "BP", 0);

    chain("float-srgb-p3", "srgb,p3", "P", 0);
    chain("float-fogra-srgb", "fogra,srgb", "P", 0);
    chain("float-srgb-swop-relbpc", "srgb,swop", "R", 1);
    chain("float-lab-srgb", "lcms-lab4,srgb", "P", 0);
    chain("float-srgb-lab", "srgb,lcms-lab4", "R", 0);
    chain("float-p3-xyz", "p3,lcms-xyz", "R", 0);
    chain("float-sgrey-srgb", "sgrey,srgb", "P", 0);
    chain("float-mpe-rgb-srgb", "mpe-rgb,srgb", "P", 0);
    chain("float-mpe-cmyk-p3", "mpe-cmyk,p3", "R", 0);
    chain("float-swop-fogra-abs", "swop,fogra", "A", 0);

    chain("lcms-rgbparam-srgb", "lcms-rgbparam,srgb", "P", 0);
    chain("lcms-graytab-srgb", "lcms-graytab,srgb", "P", 0);
    chain("lcms-bchsw", "srgb,lcms-bchsw,srgb", "P", 0);
    chain("lcms-ink280", "lcms-ink280", "P", 0);
    chain("lcms-lincmyk", "lcms-lincmyk", "P", 0);
    chain("lcms-srgb-xyz", "srgb,lcms-xyz", "R", 0);

    chain("smalt-rgbparam-srgb", "smalt-rgbparam,srgb", "P", 0);
    chain("smalt-graytab-srgb", "smalt-graytab,srgb", "P", 0);
    chain("smalt-bchsw", "srgb,smalt-bchsw,srgb", "P", 0);
    chain("smalt-ink280", "smalt-ink280", "P", 0);
    chain("smalt-lincmyk", "smalt-lincmyk", "P", 0);
    chain("smalt-lab-srgb", "smalt-lab,srgb", "P", 0);
    chain("smalt-srgb-xyz", "srgb,smalt-xyz", "R", 0);
    chain("smalt-link-sgrey-srgb", "smalt-link-sgrey-srgb", "P", 0);
    chain("smalt-link-named", "smalt-link-named", "P", 0);
    chain("smalt-retag-srgb-p3", "smalt-retag-srgb,p3", "P", 0);

    kt = fopen(join(directory, "ktone.tsv"), "w");
    ktone(kt, "ktone-swop-fogra", "swop,fogra", "P", 0);
    ktone(kt, "ktone-fogra-japan-relbpc", "fogra,japan", "R", 1);
    ktone(kt, "ktone-japan-swop-sat", "japan,swop", "S", 0);
    fclose(kt);

    tac = fopen(join(directory, "tac.tsv"), "w");
    for (i = 0; outputs[i] != NULL; i++) {
        cmsHPROFILE profile = load(outputs[i]);
        fprintf(tac, "%s\t%.9g\n", outputs[i], cmsDetectTAC(profile));
        cmsCloseProfile(profile);
    }
    fclose(tac);
    fclose(cases_file);
    return 0;
}
