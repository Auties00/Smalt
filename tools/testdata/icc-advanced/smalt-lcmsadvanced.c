/*
 * Writes the ICC profiles and the Little CMS reference outputs of the advanced color management tests of
 * smalt-core: profiles with floating-point multiProcessElement tags (D2Bn and B2Dn), device links, abstract
 * profiles and named color profiles.
 *
 *   smalt-lcmsadvanced <directory>
 *
 * The directory receives:
 *   <profile>.icc          the profiles, with the creation date cleared so that each depends only on the Little
 *                          CMS version
 *   cases.tsv              one transform per line: name, profiles (comma separated), intent (P, R, S, A), black
 *                          point compensation (0, 1), input (a channel count, or "index" for named color indexes)
 *   <case>.8, <case>.16    the outputs of the case for the 8-bit and the 16-bit input sets (16-bit samples
 *                          little-endian); for "index" cases both come from the 16-bit index input
 *   <case>.x16             the outputs for the 16-bit input set with cmsFLAGS_NOOPTIMIZE: the linked and
 *                          simplified pipeline evaluated on every pixel, without lookup tables (the same as
 *                          <case>.16 for "index" cases, whose pipelines Little CMS never optimizes)
 *   pipelines.tsv          one multiProcessElement tag per line: profile, tag, input ranges (lo:hi per channel)
 *   <profile>.<tag>.f32    cmsPipelineEvalFloat of the tag over the float input set, little-endian
 *   named.tsv              cmsNamedColorInfo of every color of the named color profiles
 *
 * Every computation reads the profiles back from the directory, so that the outputs describe the files.
 * The input sets are those of the Java test (AdvancedProfilesTest):
 *   8-bit, 1 channel   : every value 0..255
 *   8-bit, n channels  : a lattice of L levels round(i * 255 / (L - 1)) per channel, the first channel slowest
 *                        (L = 17 for 2 and 3 channels, 9 for 4, 4 for 5 and 6), then 2048 * n bytes of noise
 *   16-bit, n channels : a lattice of M levels round(i * 65535 / (M - 1)) per channel, the first channel slowest
 *                        (M = 257 for 1 channel, 9 for 2 and 3, 5 for 4, 3 for 5 and 6), then 1024 * n samples
 *                        of 16-bit noise
 *   index              : 0 .. count - 1, count, count + 1, 1000, 65535
 *   float, n channels  : a lattice of F levels lo + (hi - lo) * i / (F - 1) (F = 101, 17, 9, 6, 5, 4 for 1 to 6
 *                        channels), then 256 points lo + (hi - lo) * (u * 1.4 - 0.2), u uniform in [0, 1)
 * Noise comes from the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407: bytes are the top 8 bits of
 * each state from the seed 0x9E3779B97F4A7C15, 16-bit samples the top 16 bits from 0xD1B54A32D192ED03, and u the
 * top 24 bits divided by 2^24 from 0x2545F4914F6CDD1D.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcms2.h"

static const char *directory;
static FILE *cases_file;
static FILE *pipelines_file;
static FILE *named_file;

static void fail(const char *what) {
    fprintf(stderr, "smalt-lcmsadvanced: %s\n", what);
    exit(1);
}

static void error_handler(cmsContext context, cmsUInt32Number code, const char *text) {
    (void) context;
    fprintf(stderr, "lcms error %u: %s\n", (unsigned) code, text);
}

static char *path_of(const char *name) {
    static char path[4096];
    snprintf(path, sizeof(path), "%s/%s", directory, name);
    return path;
}

static void save(cmsHPROFILE profile, const char *name) {
    static const unsigned char zeros[12] = {0};
    FILE *file;
    if (!cmsSaveProfileToFile(profile, path_of(name))) {
        fail(name);
    }
    cmsCloseProfile(profile);
    file = fopen(path_of(name), "r+b");
    if (file == NULL || fseek(file, 24, SEEK_SET) != 0 || fwrite(zeros, 1, 12, file) != 12) {
        fail(name);
    }
    fclose(file);
}

static cmsHPROFILE load(const char *name) {
    cmsHPROFILE profile = cmsOpenProfileFromFile(path_of(name), "r");
    if (profile == NULL) {
        fail(name);
    }
    return profile;
}

static void describe(cmsHPROFILE profile, const char *text) {
    cmsMLU *mlu = cmsMLUalloc(NULL, 1);
    cmsMLUsetASCII(mlu, "en", "US", text);
    cmsWriteTag(profile, cmsSigProfileDescriptionTag, mlu);
    cmsMLUsetASCII(mlu, "en", "US", "No copyright, use freely");
    cmsWriteTag(profile, cmsSigCopyrightTag, mlu);
    cmsMLUfree(mlu);
}

static cmsHPROFILE placeholder(double version, cmsProfileClassSignature cls, cmsColorSpaceSignature space,
                               cmsColorSpaceSignature pcs, const char *text) {
    cmsHPROFILE profile = cmsCreateProfilePlaceholder(NULL);
    cmsSetProfileVersion(profile, version);
    cmsSetDeviceClass(profile, cls);
    cmsSetColorSpace(profile, space);
    cmsSetPCS(profile, pcs);
    cmsSetHeaderRenderingIntent(profile, INTENT_PERCEPTUAL);
    describe(profile, text);
    cmsWriteTag(profile, cmsSigMediaWhitePointTag, cmsD50_XYZ());
    return profile;
}

/* ---- noise and input sets ---- */

static unsigned long long lcg(unsigned long long *state) {
    *state = *state * 6364136223846793005ULL + 1442695040888963407ULL;
    return *state;
}

static int levels8(int channels) {
    switch (channels) {
        case 2: case 3: return 17;
        case 4: return 9;
        default: return 4;
    }
}

static int lattice_size(int channels) {
    int levels = levels8(channels), total = 1, c;
    if (channels == 1) {
        return 256;
    }
    for (c = 0; c < channels; c++) {
        total *= levels;
    }
    return total;
}

static int lattice16_size(int channels);

static int input_pixels(int channels, int wide) {
    if (wide) {
        return lattice16_size(channels) + 1024;
    }
    return channels == 1 ? 256 : lattice_size(channels) + 2048;
}

static void lattice8(int channels, unsigned char *out) {
    int levels = levels8(channels), total = lattice_size(channels), index, c;
    if (channels == 1) {
        for (index = 0; index < 256; index++) {
            out[index] = (unsigned char) index;
        }
        return;
    }
    for (index = 0; index < total; index++) {
        int rest = index;
        for (c = channels - 1; c >= 0; c--) {
            int i = rest % levels;
            out[index * channels + c] = (unsigned char) ((i * 255 + (levels - 1) / 2) / (levels - 1));
            rest /= levels;
        }
    }
}

static unsigned char *inputs8(int channels) {
    int pixels = input_pixels(channels, 0), total = lattice_size(channels), i;
    unsigned char *out = malloc((size_t) pixels * channels);
    unsigned long long state = 0x9E3779B97F4A7C15ULL;
    lattice8(channels, out);
    if (channels > 1) {
        for (i = total * channels; i < pixels * channels; i++) {
            out[i] = (unsigned char) (lcg(&state) >> 56);
        }
    }
    return out;
}

static int levels16(int channels) {
    switch (channels) {
        case 1: return 257;
        case 2: case 3: return 9;
        case 4: return 5;
        default: return 3;
    }
}

static int lattice16_size(int channels) {
    int total = 1, c;
    for (c = 0; c < channels; c++) {
        total *= levels16(channels);
    }
    return total;
}

static cmsUInt16Number *inputs16(int channels) {
    int pixels = input_pixels(channels, 1), levels = levels16(channels), total = lattice16_size(channels), index, c;
    cmsUInt16Number *out = malloc((size_t) pixels * channels * 2);
    unsigned long long state = 0xD1B54A32D192ED03ULL;
    for (index = 0; index < total; index++) {
        int rest = index;
        for (c = channels - 1; c >= 0; c--) {
            int i = rest % levels;
            out[index * channels + c] = (cmsUInt16Number) ((i * 65535 + (levels - 1) / 2) / (levels - 1));
            rest /= levels;
        }
    }
    for (index = total * channels; index < pixels * channels; index++) {
        out[index] = (cmsUInt16Number) (lcg(&state) >> 48);
    }
    return out;
}

/* ---- outputs ---- */

static void write_bytes(const char *name, const void *data, size_t size) {
    FILE *file = fopen(path_of(name), "wb");
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

static cmsUInt32Number intent_code(char intent) {
    switch (intent) {
        case 'P': return INTENT_PERCEPTUAL;
        case 'R': return INTENT_RELATIVE_COLORIMETRIC;
        case 'S': return INTENT_SATURATION;
        default: return INTENT_ABSOLUTE_COLORIMETRIC;
    }
}

/* runs one case: names is a comma separated list of profile files */
static void run(const char *name, const char *names, char intent, int bpc) {
    cmsHPROFILE profiles[8];
    char list[512], *token, file[256];
    int count = 0, named, channels, outputs, pixels;
    cmsColorSpaceSignature entry, exit_space;
    cmsUInt32Number flags = bpc ? cmsFLAGS_BLACKPOINTCOMPENSATION : 0;
    cmsHTRANSFORM transform8, transform16;
    strncpy(list, names, sizeof(list) - 1);
    list[sizeof(list) - 1] = 0;
    for (token = strtok(list, ","); token != NULL; token = strtok(NULL, ",")) {
        snprintf(file, sizeof(file), "%s.icc", token);
        profiles[count++] = load(file);
    }
    spaces(profiles, count, &entry, &exit_space, &named);
    outputs = cmsChannelsOfColorSpace(exit_space);
    if (named) {
        cmsNAMEDCOLORLIST *list_of_colors = cmsReadTag(profiles[0], cmsSigNamedColor2Tag);
        int colors = (int) cmsNamedColorCount(list_of_colors), i;
        cmsUInt16Number *in;
        cmsUInt16Number *out16;
        unsigned char *out8;
        pixels = colors + 4;
        in = malloc((size_t) pixels * 2);
        for (i = 0; i < colors; i++) {
            in[i] = (cmsUInt16Number) i;
        }
        in[colors] = (cmsUInt16Number) colors;
        in[colors + 1] = (cmsUInt16Number) (colors + 1);
        in[colors + 2] = 1000;
        in[colors + 3] = 65535;
        transform8 = cmsCreateMultiprofileTransform(profiles, count, TYPE_NAMED_COLOR_INDEX, format(exit_space, 1),
                                                    intent_code(intent), flags);
        transform16 = cmsCreateMultiprofileTransform(profiles, count, TYPE_NAMED_COLOR_INDEX, format(exit_space, 2),
                                                     intent_code(intent), flags);
        if (transform8 == NULL || transform16 == NULL) {
            fail(name);
        }
        out8 = malloc((size_t) pixels * outputs);
        out16 = malloc((size_t) pixels * outputs * 2);
        cmsDoTransform(transform8, in, out8, pixels);
        cmsDoTransform(transform16, in, out16, pixels);
        snprintf(file, sizeof(file), "%s.8", name);
        write_bytes(file, out8, (size_t) pixels * outputs);
        snprintf(file, sizeof(file), "%s.16", name);
        write_words(file, out16, (size_t) pixels * outputs);
        snprintf(file, sizeof(file), "%s.x16", name);
        write_words(file, out16, (size_t) pixels * outputs);
        fprintf(cases_file, "%s\t%s\t%c\t%d\tindex\n", name, names, intent, bpc);
        free(in);
        free(out8);
        free(out16);
    } else {
        unsigned char *in8, *out8;
        cmsUInt16Number *in16, *out16;
        int pixels16;
        channels = cmsChannelsOfColorSpace(entry);
        if (entry == cmsSigRgbData && cmsDetectRGBProfileGamma(profiles[0], 0.1) > 0
                && cmsDetectRGBProfileGamma(profiles[0], 0.1) < 1.6) {
            fprintf(stderr, "warning: %s starts with a linear RGB profile; 16-bit optimizations are disabled\n",
                    name);
        }
        transform8 = cmsCreateMultiprofileTransform(profiles, count, format(entry, 1), format(exit_space, 1),
                                                    intent_code(intent), flags);
        transform16 = cmsCreateMultiprofileTransform(profiles, count, format(entry, 2), format(exit_space, 2),
                                                     intent_code(intent), flags);
        if (transform8 == NULL || transform16 == NULL) {
            fail(name);
        }
        pixels = input_pixels(channels, 0);
        pixels16 = input_pixels(channels, 1);
        in8 = inputs8(channels);
        in16 = inputs16(channels);
        out8 = malloc((size_t) pixels * outputs);
        out16 = malloc((size_t) pixels16 * outputs * 2);
        cmsDoTransform(transform8, in8, out8, pixels);
        cmsDoTransform(transform16, in16, out16, pixels16);
        snprintf(file, sizeof(file), "%s.8", name);
        write_bytes(file, out8, (size_t) pixels * outputs);
        snprintf(file, sizeof(file), "%s.16", name);
        write_words(file, out16, (size_t) pixels16 * outputs);
        {
            cmsHTRANSFORM exact = cmsCreateMultiprofileTransform(profiles, count, format(entry, 2),
                                                                 format(exit_space, 2), intent_code(intent),
                                                                 flags | cmsFLAGS_NOOPTIMIZE);
            if (exact == NULL) {
                fail(name);
            }
            cmsDoTransform(exact, in16, out16, pixels16);
            snprintf(file, sizeof(file), "%s.x16", name);
            write_words(file, out16, (size_t) pixels16 * outputs);
            cmsDeleteTransform(exact);
        }
        fprintf(cases_file, "%s\t%s\t%c\t%d\t%d\n", name, names, intent, bpc, channels);
        free(in8);
        free(in16);
        free(out8);
        free(out16);
    }
    cmsDeleteTransform(transform8);
    cmsDeleteTransform(transform16);
    while (count > 0) {
        cmsCloseProfile(profiles[--count]);
    }
    printf("%s\n", name);
}

/*
 * The named color case of an XYZ connection space, which Little CMS reads as L*a*b*: the reference is the
 * transform of the colors' XYZ values from Little CMS's XYZ identity profile, relative colorimetric, unoptimized,
 * which evaluates the pipeline Smalt builds for the named color profile.
 */
static void run_named_xyz(const char *name, const char *named_name, const char *target_name) {
    char file[256];
    cmsHPROFILE named, profiles[2];
    cmsNAMEDCOLORLIST *colors;
    cmsHTRANSFORM transform8, transform16;
    int count, pixels, i, outputs;
    cmsUInt16Number *in, *out16;
    unsigned char *out8;
    snprintf(file, sizeof(file), "%s.icc", named_name);
    named = load(file);
    colors = cmsReadTag(named, cmsSigNamedColor2Tag);
    count = (int) cmsNamedColorCount(colors);
    snprintf(file, sizeof(file), "%s.icc", target_name);
    profiles[0] = cmsCreateXYZProfile();
    profiles[1] = load(file);
    outputs = cmsChannelsOfColorSpace(cmsGetColorSpace(profiles[1]));
    pixels = count;
    in = malloc((size_t) pixels * 3 * 2);
    for (i = 0; i < count; i++) {
        cmsNamedColorInfo(colors, (cmsUInt32Number) i, NULL, NULL, NULL, in + i * 3, NULL);
    }
    transform8 = cmsCreateMultiprofileTransform(profiles, 2, TYPE_XYZ_16, format(cmsGetColorSpace(profiles[1]), 1),
                                                INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOOPTIMIZE);
    transform16 = cmsCreateMultiprofileTransform(profiles, 2, TYPE_XYZ_16, format(cmsGetColorSpace(profiles[1]), 2),
                                                 INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOOPTIMIZE);
    if (transform8 == NULL || transform16 == NULL) {
        fail(name);
    }
    out8 = malloc((size_t) pixels * outputs);
    out16 = malloc((size_t) pixels * outputs * 2);
    cmsDoTransform(transform8, in, out8, pixels);
    cmsDoTransform(transform16, in, out16, pixels);
    snprintf(file, sizeof(file), "%s.8", name);
    write_bytes(file, out8, (size_t) pixels * outputs);
    snprintf(file, sizeof(file), "%s.16", name);
    write_words(file, out16, (size_t) pixels * outputs);
    snprintf(file, sizeof(file), "%s.x16", name);
    write_words(file, out16, (size_t) pixels * outputs);
    fprintf(cases_file, "%s\t%s,%s\tR\t0\tindex\n", name, named_name, target_name);
    cmsDeleteTransform(transform8);
    cmsDeleteTransform(transform16);
    cmsCloseProfile(profiles[0]);
    cmsCloseProfile(profiles[1]);
    cmsCloseProfile(named);
    free(in);
    free(out8);
    free(out16);
    printf("%s\n", name);
}

/* ---- float pipelines ---- */

static int levels_float(int channels) {
    static const int levels[] = {101, 17, 9, 6, 5, 4};
    return channels <= 6 ? levels[channels - 1] : 3;
}

/* evaluates a multiProcessElement tag over the float input set; ranges is "lo:hi,lo:hi,..." */
static void dump(const char *profile_name, const char *tag_name, const char *ranges) {
    char file[256], copy[256], *token;
    double lo[16], hi[16];
    int channels = 0, outputs, levels, lattice = 1, total, index, c;
    cmsHPROFILE profile;
    cmsPipeline *pipeline;
    cmsTagSignature tag = (cmsTagSignature) (((cmsUInt32Number) tag_name[0] << 24)
            | ((cmsUInt32Number) tag_name[1] << 16) | ((cmsUInt32Number) tag_name[2] << 8)
            | (cmsUInt32Number) tag_name[3]);
    cmsFloat32Number in[16], *out;
    unsigned long long state = 0x2545F4914F6CDD1DULL;
    snprintf(file, sizeof(file), "%s.icc", profile_name);
    profile = load(file);
    pipeline = cmsReadTag(profile, tag);
    if (pipeline == NULL) {
        fail(tag_name);
    }
    strncpy(copy, ranges, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = 0;
    for (token = strtok(copy, ","); token != NULL; token = strtok(NULL, ",")) {
        if (sscanf(token, "%lf:%lf", &lo[channels], &hi[channels]) != 2) {
            fail(ranges);
        }
        channels++;
    }
    if (channels != (int) cmsPipelineInputChannels(pipeline)) {
        fail("range count");
    }
    outputs = (int) cmsPipelineOutputChannels(pipeline);
    levels = levels_float(channels);
    for (c = 0; c < channels; c++) {
        lattice *= levels;
    }
    total = lattice + 256;
    out = malloc((size_t) total * outputs * sizeof(cmsFloat32Number));
    for (index = 0; index < total; index++) {
        if (index < lattice) {
            int rest = index;
            for (c = channels - 1; c >= 0; c--) {
                in[c] = (cmsFloat32Number) (lo[c] + (hi[c] - lo[c]) * (double) (rest % levels) / (levels - 1));
                rest /= levels;
            }
        } else {
            for (c = 0; c < channels; c++) {
                double u = (double) (lcg(&state) >> 40) / 16777216.0;
                in[c] = (cmsFloat32Number) (lo[c] + (hi[c] - lo[c]) * (u * 1.4 - 0.2));
            }
        }
        cmsPipelineEvalFloat(in, out + (size_t) index * outputs, pipeline);
    }
    snprintf(file, sizeof(file), "%s.%s.f32", profile_name, tag_name);
    write_floats(file, out, (size_t) total * outputs);
    fprintf(pipelines_file, "%s\t%s\t%s\n", profile_name, tag_name, ranges);
    free(out);
    cmsCloseProfile(profile);
}

static void dump_named(const char *profile_name) {
    char file[256], name[256], prefix[256], suffix[256];
    cmsHPROFILE profile;
    cmsNAMEDCOLORLIST *colors;
    cmsUInt16Number pcs[3], colorant[cmsMAXCHANNELS];
    cmsUInt32Number i, c, count;
    snprintf(file, sizeof(file), "%s.icc", profile_name);
    profile = load(file);
    colors = cmsReadTag(profile, cmsSigNamedColor2Tag);
    count = cmsNamedColorCount(colors);
    for (i = 0; i < count; i++) {
        cmsNamedColorInfo(colors, i, name, prefix, suffix, pcs, colorant);
        fprintf(named_file, "%s\t%u\t%s\t%s\t%s\t%u,%u,%u\t", profile_name, (unsigned) i, prefix, name, suffix,
                pcs[0], pcs[1], pcs[2]);
        for (c = 0; c < (cmsUInt32Number) cmsChannelsOfColorSpace(cmsGetColorSpace(profile)); c++) {
            fprintf(named_file, c == 0 ? "%u" : ",%u", colorant[c]);
        }
        fprintf(named_file, "\n");
    }
    cmsCloseProfile(profile);
}

/* ---- building blocks ---- */

static cmsToneCurve *formula(int type, double a, double b, double c, double d, double e) {
    cmsCurveSegment segment;
    memset(&segment, 0, sizeof(segment));
    segment.x0 = -1e22f;
    segment.x1 = 1e22f;
    segment.Type = type;
    segment.Params[0] = a;
    segment.Params[1] = b;
    segment.Params[2] = c;
    segment.Params[3] = d;
    segment.Params[4] = e;
    return cmsBuildSegmentedToneCurve(NULL, 1, &segment);
}

/* the sRGB decoding curve: a line below 0, a sampled segment up to 0.04045 and a power function above */
static cmsToneCurve *srgb_like(double scale) {
    cmsCurveSegment segments[3];
    cmsFloat32Number samples[9];
    int i;
    memset(segments, 0, sizeof(segments));
    segments[0].x0 = -1e22f;
    segments[0].x1 = 0.0f;
    segments[0].Type = 6;
    segments[0].Params[0] = 1.0;
    segments[0].Params[1] = scale / 12.92;
    segments[1].x0 = 0.0f;
    segments[1].x1 = 0.04045f;
    segments[1].Type = 0;
    segments[1].nGridPoints = 9;
    for (i = 0; i < 9; i++) {
        samples[i] = (cmsFloat32Number) (scale * (0.04045 * i / 8.0) / 12.92);
    }
    segments[1].SampledPoints = samples;
    segments[2].x0 = 0.04045f;
    segments[2].x1 = 1e22f;
    segments[2].Type = 6;
    segments[2].Params[0] = 2.4;
    segments[2].Params[1] = pow(scale, 1 / 2.4) / 1.055;
    segments[2].Params[2] = pow(scale, 1 / 2.4) * 0.055 / 1.055;
    return cmsBuildSegmentedToneCurve(NULL, 3, segments);
}

/* a logarithmic segment up to 0.5 and an exponential one above */
static cmsToneCurve *log_exp(void) {
    cmsCurveSegment segments[2];
    memset(segments, 0, sizeof(segments));
    segments[0].x0 = -1e22f;
    segments[0].x1 = 0.5f;
    segments[0].Type = 7;
    segments[0].Params[0] = 1.0;
    segments[0].Params[1] = 0.6;
    segments[0].Params[2] = 9.0;
    segments[0].Params[3] = 1.0;
    segments[0].Params[4] = 0.0;
    segments[1].x0 = 0.5f;
    segments[1].x1 = 1e22f;
    segments[1].Type = 8;
    segments[1].Params[0] = 0.25;
    segments[1].Params[1] = 3.0;
    segments[1].Params[2] = 1.2;
    segments[1].Params[3] = -0.1;
    segments[1].Params[4] = 0.2;
    return cmsBuildSegmentedToneCurve(NULL, 2, segments);
}

/* a power curve sampled at 33 points over 0..1, with lines outside */
static cmsToneCurve *sampled_power(double gamma) {
    cmsCurveSegment segments[3];
    cmsFloat32Number samples[33];
    int i;
    memset(segments, 0, sizeof(segments));
    segments[0].x0 = -1e22f;
    segments[0].x1 = 0.0f;
    segments[0].Type = 6;
    segments[0].Params[0] = 1.0;
    segments[0].Params[1] = 0.05;
    segments[1].x0 = 0.0f;
    segments[1].x1 = 1.0f;
    segments[1].Type = 0;
    segments[1].nGridPoints = 33;
    for (i = 0; i < 33; i++) {
        samples[i] = (cmsFloat32Number) pow(i / 32.0, gamma);
    }
    segments[1].SampledPoints = samples;
    segments[2].x0 = 1.0f;
    segments[2].x1 = 1e22f;
    segments[2].Type = 6;
    segments[2].Params[0] = 1.0;
    segments[2].Params[1] = gamma;
    segments[2].Params[2] = 1.0 - gamma;
    return cmsBuildSegmentedToneCurve(NULL, 3, segments);
}

static cmsStage *curves(int count, cmsToneCurve **list) {
    cmsStage *stage = cmsStageAllocToneCurves(NULL, (cmsUInt32Number) count, list);
    int i;
    for (i = 0; i < count; i++) {
        cmsFreeToneCurve(list[i]);
    }
    return stage;
}

static cmsStage *matrix(int rows, int columns, const double *values, const double *offset) {
    return cmsStageAllocMatrix(NULL, (cmsUInt32Number) rows, (cmsUInt32Number) columns, values, offset);
}

static const double SRGB_TO_XYZ[] = {
    0.4360747, 0.3850649, 0.1430804,
    0.2225045, 0.7168786, 0.0606169,
    0.0139322, 0.0971045, 0.7141733
};

static void invert3(const double *m, double *out) {
    double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6]);
    out[0] = (m[4] * m[8] - m[5] * m[7]) / det;
    out[1] = (m[2] * m[7] - m[1] * m[8]) / det;
    out[2] = (m[1] * m[5] - m[2] * m[4]) / det;
    out[3] = (m[5] * m[6] - m[3] * m[8]) / det;
    out[4] = (m[0] * m[8] - m[2] * m[6]) / det;
    out[5] = (m[2] * m[3] - m[0] * m[5]) / det;
    out[6] = (m[3] * m[7] - m[4] * m[6]) / det;
    out[7] = (m[1] * m[6] - m[0] * m[7]) / det;
    out[8] = (m[0] * m[4] - m[1] * m[3]) / det;
}

static int xyz_tweak(const cmsFloat32Number in[], cmsFloat32Number out[], void *cargo) {
    (void) cargo;
    out[0] = (cmsFloat32Number) (in[0] * (0.95 + 0.05 * in[1]));
    out[1] = (cmsFloat32Number) (in[1] * (0.97 + 0.03 * in[2]));
    out[2] = (cmsFloat32Number) (in[2] * (0.94 + 0.06 * in[0]));
    return 1;
}

static int rgb_mix(const cmsFloat32Number in[], cmsFloat32Number out[], void *cargo) {
    (void) cargo;
    out[0] = (cmsFloat32Number) (0.9 * in[0] + 0.1 * in[1] * in[2]);
    out[1] = (cmsFloat32Number) (0.85 * in[1] + 0.15 * in[0] * in[0]);
    out[2] = (cmsFloat32Number) (0.8 * in[2] + 0.2 * sqrt(in[0] * in[1]));
    return 1;
}

/* a simple ink model from n colorants to L*a*b* normalized to 0..1 */
static int inks_to_lab(const cmsFloat32Number in[], cmsFloat32Number out[], void *cargo) {
    int inks = *(int *) cargo, i;
    static const double a_of[] = {-40, 60, -5, 0, 30, -25};
    static const double b_of[] = {-45, -5, 80, 0, 20, 35};
    double light = 1.0, a = 0, b = 0;
    for (i = 0; i < inks; i++) {
        double k = in[i];
        light *= 1.0 - (i == 3 && inks == 4 ? 0.9 : 0.55) * k;
        a += a_of[i % 6] * k * (1.0 - 0.3 * k);
        b += b_of[i % 6] * k * (1.0 - 0.3 * k);
    }
    out[0] = (cmsFloat32Number) (light * 0.96 + 0.02);
    out[1] = (cmsFloat32Number) ((a * light + 128.0) / 255.0);
    out[2] = (cmsFloat32Number) ((b * light + 128.0) / 255.0);
    return 1;
}

/* the inverse direction of inks_to_lab, roughly: L*a*b* normalized to 0..1 to CMYK */
static int lab_to_cmyk(const cmsFloat32Number in[], cmsFloat32Number out[], void *cargo) {
    double light = in[0], a = in[1] * 255.0 - 128.0, b = in[2] * 255.0 - 128.0;
    double k = light < 0.3 ? (0.3 - light) / 0.3 : 0.0;
    double c = 1.0 - light - 0.004 * a - 0.002 * b;
    double m = 1.0 - light + 0.006 * a;
    double y = 1.0 - light + 0.005 * b;
    (void) cargo;
    out[0] = (cmsFloat32Number) (c < 0 ? 0 : c > 1 ? 1 : c);
    out[1] = (cmsFloat32Number) (m < 0 ? 0 : m > 1 ? 1 : m);
    out[2] = (cmsFloat32Number) (y < 0 ? 0 : y > 1 ? 1 : y);
    out[3] = (cmsFloat32Number) k;
    return 1;
}

static int gray_to_xyz(const cmsFloat32Number in[], cmsFloat32Number out[], void *cargo) {
    (void) cargo;
    out[0] = (cmsFloat32Number) (0.9642 * in[0] * (0.98 + 0.02 * in[0]));
    out[1] = (cmsFloat32Number) in[0];
    out[2] = (cmsFloat32Number) (0.8249 * in[0] * (1.01 - 0.01 * in[0]));
    return 1;
}

static cmsStage *clut(int inputs, int outputs, int points, cmsSAMPLERFLOAT sampler, void *cargo) {
    cmsUInt32Number grid[cmsMAXCHANNELS];
    cmsStage *stage;
    int i;
    for (i = 0; i < inputs; i++) {
        grid[i] = (cmsUInt32Number) points;
    }
    stage = cmsStageAllocCLutFloatGranular(NULL, grid, (cmsUInt32Number) inputs, (cmsUInt32Number) outputs, NULL);
    if (stage == NULL || !cmsStageSampleCLutFloat(stage, sampler, cargo, 0)) {
        fail("clut");
    }
    return stage;
}

static cmsPipeline *pipeline(int inputs, int outputs) {
    return cmsPipelineAlloc(NULL, (cmsUInt32Number) inputs, (cmsUInt32Number) outputs);
}

static void add(cmsPipeline *lut, cmsStage *stage) {
    if (stage == NULL || !cmsPipelineInsertStage(lut, cmsAT_END, stage)) {
        fail("stage");
    }
}

static void tag(cmsHPROFILE profile, cmsTagSignature signature, cmsPipeline *lut) {
    if (!cmsWriteTag(profile, signature, lut)) {
        fail("tag");
    }
    cmsPipelineFree(lut);
}

static const double LAB_FROM_UNIT[] = {100, 0, 0, 0, 255, 0, 0, 0, 255};
static const double LAB_FROM_UNIT_OFFSET[] = {0, -128, -128};
static const double LAB_TO_UNIT[] = {1 / 100.0, 0, 0, 0, 1 / 255.0, 0, 0, 0, 1 / 255.0};
static const double LAB_TO_UNIT_OFFSET[] = {0, 128 / 255.0, 128 / 255.0};

/* ---- profiles ---- */

static void write_builtins(void) {
    cmsCIExyY d65 = {0.3127, 0.3290, 1.0};
    cmsCIExyYTRIPLE p3 = {{0.680, 0.320, 1.0}, {0.265, 0.690, 1.0}, {0.150, 0.060, 1.0}};
    cmsFloat64Number parameters[] = {2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045};
    cmsToneCurve *curve = cmsBuildParametricToneCurve(NULL, 4, parameters);
    cmsToneCurve *triple[3] = {curve, curve, curve};
    cmsHPROFILE p3_profile = cmsCreateRGBProfile(&d65, &p3, triple);
    describe(p3_profile, "Little CMS Display P3");
    save(cmsCreate_sRGBProfile(), "lcms-srgb.icc");
    save(p3_profile, "lcms-p3.icc");
    cmsFreeToneCurve(curve);
}

static void write_mpe_rgb(void) {
    double inverse[9];
    static const double offset[] = {0.001, -0.002, 0.0005};
    cmsHPROFILE profile = placeholder(4.3, cmsSigDisplayClass, cmsSigRgbData, cmsSigXYZData,
                                      "Smalt float RGB, XYZ PCS");
    cmsToneCurve *list[3];
    cmsPipeline *lut;
    cmsCIEXYZ red = {0.4360747, 0.2225045, 0.0139322}, green = {0.3850649, 0.7168786, 0.0971045},
            blue = {0.1430804, 0.0606169, 0.7141733};
    cmsToneCurve *gamma2 = cmsBuildGamma(NULL, 2.0);
    static const double scaled[9] = {
        0.4360747 / 1.999969482421875, 0.3850649 / 1.999969482421875, 0.1430804 / 1.999969482421875,
        0.2225045 / 1.999969482421875, 0.7168786 / 1.999969482421875, 0.0606169 / 1.999969482421875,
        0.0139322 / 1.999969482421875, 0.0971045 / 1.999969482421875, 0.7141733 / 1.999969482421875
    };
    invert3(SRGB_TO_XYZ, inverse);
    cmsWriteTag(profile, cmsSigRedColorantTag, &red);
    cmsWriteTag(profile, cmsSigGreenColorantTag, &green);
    cmsWriteTag(profile, cmsSigBlueColorantTag, &blue);
    cmsWriteTag(profile, cmsSigRedTRCTag, gamma2);
    cmsLinkTag(profile, cmsSigGreenTRCTag, cmsSigRedTRCTag);
    cmsLinkTag(profile, cmsSigBlueTRCTag, cmsSigRedTRCTag);

    lut = pipeline(3, 3);
    list[0] = srgb_like(1.0);
    list[1] = log_exp();
    list[2] = formula(6, 2.2, 1.0, 0.0, 0.0, 0.0);
    add(lut, curves(3, list));
    add(lut, matrix(3, 3, SRGB_TO_XYZ, offset));
    add(lut, clut(3, 3, 9, xyz_tweak, NULL));
    tag(profile, cmsSigDToB0Tag, lut);

    lut = pipeline(3, 3);
    list[0] = formula(6, 2.2, 1.0, 0.0, 0.0, 0.0);
    list[1] = formula(6, 2.2, 1.0, 0.0, 0.0, 0.0);
    list[2] = formula(6, 2.2, 1.0, 0.0, 0.0, 0.0);
    add(lut, curves(3, list));
    add(lut, matrix(3, 3, SRGB_TO_XYZ, NULL));
    tag(profile, cmsSigDToB3Tag, lut);

    lut = pipeline(3, 3);
    add(lut, matrix(3, 3, inverse, NULL));
    {
        cmsCurveSegment segments[2];
        cmsToneCurve *encode;
        memset(segments, 0, sizeof(segments));
        segments[0].x0 = -1e22f;
        segments[0].x1 = 0.0031308f;
        segments[0].Type = 6;
        segments[0].Params[0] = 1.0;
        segments[0].Params[1] = 12.92;
        segments[1].x0 = 0.0031308f;
        segments[1].x1 = 1e22f;
        segments[1].Type = 6;
        segments[1].Params[0] = 1 / 2.4;
        segments[1].Params[1] = pow(1.055, 2.4);
        segments[1].Params[3] = -0.055;
        encode = cmsBuildSegmentedToneCurve(NULL, 2, segments);
        list[0] = encode;
        list[1] = cmsDupToneCurve(encode);
        list[2] = cmsDupToneCurve(encode);
    }
    add(lut, curves(3, list));
    tag(profile, cmsSigBToD0Tag, lut);

    lut = pipeline(3, 3);
    list[0] = cmsBuildGamma(NULL, 1.8);
    list[1] = cmsBuildGamma(NULL, 1.8);
    list[2] = cmsBuildGamma(NULL, 1.8);
    add(lut, curves(3, list));
    add(lut, matrix(3, 3, scaled, NULL));
    list[0] = cmsBuildGamma(NULL, 1.0);
    list[1] = cmsBuildGamma(NULL, 1.0);
    list[2] = cmsBuildGamma(NULL, 1.0);
    add(lut, curves(3, list));
    tag(profile, cmsSigAToB0Tag, lut);
    cmsFreeToneCurve(gamma2);
    save(profile, "mpe-rgb.icc");
}

static void write_mpe_cmyk(void) {
    cmsCIEXYZ white = {0.95, 0.98, 0.80};
    cmsHPROFILE profile = placeholder(4.3, cmsSigOutputClass, cmsSigCmykData, cmsSigLabData,
                                      "Smalt float CMYK, Lab PCS");
    cmsToneCurve *list[4];
    cmsPipeline *lut;
    int inks = 4;
    cmsWriteTag(profile, cmsSigMediaWhitePointTag, &white);

    lut = pipeline(4, 3);
    list[0] = formula(6, 1.2, 1.0, 0.0, 0.0, 0.0);
    list[1] = sampled_power(0.8);
    list[2] = formula(8, 0.5, 2.0, 1.0, 0.0, -0.5);
    list[3] = formula(7, 1.0, 1.0, 9.0, 1.0, 0.0);
    add(lut, curves(4, list));
    add(lut, clut(4, 3, 6, inks_to_lab, &inks));
    add(lut, matrix(3, 3, LAB_FROM_UNIT, LAB_FROM_UNIT_OFFSET));
    tag(profile, cmsSigDToB0Tag, lut);
    cmsLinkTag(profile, cmsSigDToB1Tag, cmsSigDToB0Tag);
    cmsLinkTag(profile, cmsSigDToB3Tag, cmsSigDToB0Tag);

    lut = pipeline(3, 4);
    add(lut, matrix(3, 3, LAB_TO_UNIT, LAB_TO_UNIT_OFFSET));
    add(lut, clut(3, 4, 9, lab_to_cmyk, NULL));
    list[0] = formula(6, 1.0, 0.98, 0.01, 0.0, 0.0);
    list[1] = formula(6, 1.1, 1.0, 0.0, 0.0, 0.0);
    list[2] = formula(6, 0.9, 1.0, 0.0, 0.0, 0.0);
    list[3] = sampled_power(1.3);
    add(lut, curves(4, list));
    tag(profile, cmsSigBToD0Tag, lut);
    cmsLinkTag(profile, cmsSigBToD1Tag, cmsSigBToD0Tag);
    cmsLinkTag(profile, cmsSigBToD3Tag, cmsSigBToD0Tag);
    save(profile, "mpe-cmyk.icc");
}

static void write_mpe_gray(void) {
    static const double pick_y[] = {0, 1, 0};
    cmsHPROFILE profile = placeholder(4.3, cmsSigDisplayClass, cmsSigGrayData, cmsSigXYZData,
                                      "Smalt float gray, XYZ PCS");
    cmsToneCurve *list[1];
    cmsPipeline *lut = pipeline(1, 3);
    list[0] = sampled_power(2.2);
    add(lut, curves(1, list));
    add(lut, clut(1, 3, 17, gray_to_xyz, NULL));
    tag(profile, cmsSigDToB0Tag, lut);
    lut = pipeline(3, 1);
    add(lut, matrix(1, 3, pick_y, NULL));
    list[0] = formula(6, 1 / 2.2, 1.0, 0.0, 0.0, 0.0);
    add(lut, curves(1, list));
    tag(profile, cmsSigBToD0Tag, lut);
    save(profile, "mpe-gray.icc");
}

static void write_mpe_inks(int inks, cmsColorSpaceSignature space, const char *name, int points) {
    char text[64];
    cmsHPROFILE profile;
    cmsPipeline *lut = pipeline(inks, 3);
    snprintf(text, sizeof(text), "Smalt float %d inks, Lab PCS", inks);
    profile = placeholder(4.3, cmsSigOutputClass, space, cmsSigLabData, text);
    add(lut, clut(inks, 3, points, inks_to_lab, &inks));
    add(lut, matrix(3, 3, LAB_FROM_UNIT, LAB_FROM_UNIT_OFFSET));
    tag(profile, cmsSigDToB0Tag, lut);
    save(profile, name);
}

static void write_link(const char *source, const char *target, double version, const char *name) {
    char file[256];
    cmsHPROFILE profiles[2], link;
    cmsHTRANSFORM transform;
    cmsColorSpaceSignature entry, exit_space;
    int named;
    snprintf(file, sizeof(file), "%s.icc", source);
    profiles[0] = strcmp(source, "lab4") == 0 ? cmsCreateLab4Profile(NULL) : load(file);
    snprintf(file, sizeof(file), "%s.icc", target);
    profiles[1] = strcmp(target, "lab4") == 0 ? cmsCreateLab4Profile(NULL) : load(file);
    spaces(profiles, 2, &entry, &exit_space, &named);
    transform = cmsCreateMultiprofileTransform(profiles, 2, format(entry, 2), format(exit_space, 2), INTENT_PERCEPTUAL,
                                               cmsFLAGS_NOCACHE | cmsFLAGS_NOOPTIMIZE);
    if (transform == NULL) {
        fail(name);
    }
    link = cmsTransform2DeviceLink(transform, version, cmsFLAGS_GRIDPOINTS(17));
    if (link == NULL) {
        fail(name);
    }
    describe(link, name);
    save(link, name);
    cmsDeleteTransform(transform);
    cmsCloseProfile(profiles[0]);
    cmsCloseProfile(profiles[1]);
}

static void write_mpe_links(void) {
    static const double mix[] = {0.8, 0.15, 0.05, 0.1, 0.85, 0.05, 0.02, 0.08, 0.9};
    static const double mix_offset[] = {0.01, 0.0, -0.01};
    cmsHPROFILE profile = placeholder(4.3, cmsSigLinkClass, cmsSigRgbData, cmsSigRgbData, "Smalt float RGB link");
    cmsToneCurve *list[3];
    cmsPipeline *lut = pipeline(3, 3);
    list[0] = srgb_like(1.0);
    list[1] = srgb_like(0.9);
    list[2] = log_exp();
    add(lut, curves(3, list));
    add(lut, matrix(3, 3, mix, mix_offset));
    add(lut, clut(3, 3, 9, rgb_mix, NULL));
    list[0] = formula(6, 1 / 2.2, 1.0, 0.0, 0.0, 0.0);
    list[1] = formula(6, 1 / 2.0, 1.0, 0.0, 0.0, 0.0);
    list[2] = sampled_power(0.45);
    add(lut, curves(3, list));
    tag(profile, cmsSigDToB0Tag, lut);
    save(profile, "link-mpe-rgb.icc");

    profile = placeholder(4.3, cmsSigLinkClass, cmsSigLabData, cmsSigRgbData, "Smalt float Lab to RGB link");
    lut = pipeline(3, 3);
    add(lut, matrix(3, 3, LAB_TO_UNIT, LAB_TO_UNIT_OFFSET));
    add(lut, clut(3, 3, 9, rgb_mix, NULL));
    tag(profile, cmsSigDToB0Tag, lut);
    save(profile, "link-mpe-lab-rgb.icc");
}

static void write_abstracts(void) {
    static const double adapt[] = {1.02, 0.03, -0.02, 0.01, 0.99, 0.0, -0.01, 0.02, 1.05};
    static const double chroma[] = {1, 0, 0, 0, 0.7, 0, 0, 0, 0.7};
    static const double chroma_offset[] = {2, 3, -2};
    cmsHPROFILE bchsw = cmsCreateBCHSWabstractProfile(17, 5.0, 1.1, 15.0, 1.2, 0, 0), copy;
    cmsPipeline *lut;
    describe(bchsw, "Smalt BCHSW abstract v2");
    save(bchsw, "abstract-bchsw-v2.icc");
    bchsw = load("abstract-bchsw-v2.icc");
    copy = placeholder(4.3, cmsSigAbstractClass, cmsSigLabData, cmsSigLabData, "Smalt BCHSW abstract v4");
    if (!cmsWriteTag(copy, cmsSigAToB0Tag, cmsReadTag(bchsw, cmsSigAToB0Tag))) {
        fail("abstract v4");
    }
    save(copy, "abstract-bchsw-v4.icc");
    cmsCloseProfile(bchsw);

    copy = placeholder(4.3, cmsSigAbstractClass, cmsSigXYZData, cmsSigXYZData, "Smalt float XYZ abstract");
    lut = pipeline(3, 3);
    add(lut, matrix(3, 3, adapt, NULL));
    tag(copy, cmsSigDToB0Tag, lut);
    save(copy, "abstract-mpe-xyz.icc");

    copy = placeholder(4.3, cmsSigAbstractClass, cmsSigLabData, cmsSigLabData, "Smalt float Lab abstract");
    lut = pipeline(3, 3);
    add(lut, matrix(3, 3, chroma, chroma_offset));
    tag(copy, cmsSigDToB0Tag, lut);
    save(copy, "abstract-mpe-lab.icc");
}

static void write_named(void) {
    cmsHPROFILE profile = placeholder(4.3, cmsSigNamedColorClass, cmsSigCmykData, cmsSigLabData,
                                      "Smalt named CMYK colors");
    cmsNAMEDCOLORLIST *list = cmsAllocNamedColorList(NULL, 50, 4, "Smalt ", " C");
    unsigned long long state = 0x5851F42D4C957F2DULL;
    int i, c;
    for (i = 0; i < 50; i++) {
        char name[32];
        cmsUInt16Number pcs[3], colorant[cmsMAXCHANNELS];
        memset(colorant, 0, sizeof(colorant));
        snprintf(name, sizeof(name), i % 7 == 3 ? "Warm Red %03d" : "Tone %03d", i);
        pcs[0] = (cmsUInt16Number) (i * 65280 / 49);
        pcs[1] = (cmsUInt16Number) (lcg(&state) >> 48);
        pcs[2] = (cmsUInt16Number) (lcg(&state) >> 48);
        for (c = 0; c < 4; c++) {
            colorant[c] = (cmsUInt16Number) (lcg(&state) >> 48);
        }
        if (!cmsAppendNamedColor(list, name, pcs, colorant)) {
            fail("named");
        }
    }
    cmsWriteTag(profile, cmsSigNamedColor2Tag, list);
    cmsFreeNamedColorList(list);
    save(profile, "named-cmyk.icc");

    profile = placeholder(2.1, cmsSigNamedColorClass, cmsSigRgbData, cmsSigXYZData, "Smalt named RGB colors, XYZ");
    list = cmsAllocNamedColorList(NULL, 20, 3, "", "");
    for (i = 0; i < 20; i++) {
        char name[32];
        cmsUInt16Number pcs[3], colorant[cmsMAXCHANNELS];
        memset(colorant, 0, sizeof(colorant));
        snprintf(name, sizeof(name), "Swatch %d", i);
        pcs[0] = (cmsUInt16Number) (lcg(&state) >> 49);
        pcs[1] = (cmsUInt16Number) (i * 32768 / 19);
        pcs[2] = (cmsUInt16Number) (lcg(&state) >> 49);
        for (c = 0; c < 3; c++) {
            colorant[c] = (cmsUInt16Number) (lcg(&state) >> 48);
        }
        if (!cmsAppendNamedColor(list, name, pcs, colorant)) {
            fail("named");
        }
    }
    cmsWriteTag(profile, cmsSigNamedColor2Tag, list);
    cmsFreeNamedColorList(list);
    save(profile, "named-rgb-xyz.icc");
}

int main(int argc, char **argv) {
    char file[4096];
    if (argc != 2) {
        fprintf(stderr, "usage: smalt-lcmsadvanced <directory>\n");
        return 2;
    }
    directory = argv[1];
    cmsSetLogErrorHandler(error_handler);
    write_builtins();
    write_mpe_rgb();
    write_mpe_cmyk();
    write_mpe_gray();
    write_mpe_inks(2, cmsSig2colorData, "mpe-2clr.icc", 9);
    write_mpe_inks(6, cmsSig6colorData, "mpe-6clr.icc", 3);
    write_link("lcms-srgb", "mpe-cmyk", 4.3, "link-rgb-cmyk-v4.icc");
    write_link("lcms-srgb", "lcms-p3", 2.1, "link-rgb-rgb-v2.icc");
    write_link("lab4", "lcms-srgb", 2.1, "link-lab-rgb-v2.icc");
    write_link("lcms-srgb", "lab4", 4.3, "link-rgb-lab-v4.icc");
    write_mpe_links();
    write_abstracts();
    write_named();

    snprintf(file, sizeof(file), "%s/cases.tsv", directory);
    cases_file = fopen(file, "wb");
    snprintf(file, sizeof(file), "%s/pipelines.tsv", directory);
    pipelines_file = fopen(file, "wb");
    snprintf(file, sizeof(file), "%s/named.tsv", directory);
    named_file = fopen(file, "wb");
    if (cases_file == NULL || pipelines_file == NULL || named_file == NULL) {
        fail("manifests");
    }

    run("rgb-to-srgb", "mpe-rgb,lcms-srgb", 'P', 0);
    run("rgb-to-srgb-rel", "mpe-rgb,lcms-srgb", 'R', 0);
    run("rgb-to-srgb-relbpc", "mpe-rgb,lcms-srgb", 'R', 1);
    run("rgb-to-srgb-abs", "mpe-rgb,lcms-srgb", 'A', 0);
    run("srgb-to-rgb", "lcms-srgb,mpe-rgb", 'P', 0);
    run("srgb-to-rgb-rel", "lcms-srgb,mpe-rgb", 'R', 0);
    run("rgb-to-cmyk", "mpe-rgb,mpe-cmyk", 'P', 0);
    run("cmyk-to-srgb", "mpe-cmyk,lcms-srgb", 'P', 0);
    run("cmyk-to-srgb-relbpc", "mpe-cmyk,lcms-srgb", 'R', 1);
    run("cmyk-to-srgb-abs", "mpe-cmyk,lcms-srgb", 'A', 0);
    run("srgb-to-cmyk", "lcms-srgb,mpe-cmyk", 'P', 0);
    run("srgb-to-cmyk-relbpc", "lcms-srgb,mpe-cmyk", 'R', 1);
    run("srgb-to-cmyk-abs", "lcms-srgb,mpe-cmyk", 'A', 0);
    run("cmyk-to-cmyk", "mpe-cmyk,mpe-cmyk", 'P', 0);
    run("gray-to-srgb", "mpe-gray,lcms-srgb", 'P', 0);
    run("srgb-to-gray", "lcms-srgb,mpe-gray", 'P', 0);
    run("2clr-to-srgb", "mpe-2clr,lcms-srgb", 'P', 0);
    run("6clr-to-p3", "mpe-6clr,lcms-p3", 'P', 0);

    run("link-rgb-cmyk", "link-rgb-cmyk-v4", 'P', 0);
    run("link-rgb-cmyk-rel", "link-rgb-cmyk-v4", 'R', 0);
    run("link-rgb-cmyk-chain", "link-rgb-cmyk-v4,mpe-cmyk,lcms-srgb", 'P', 0);
    run("link-rgb-rgb", "link-rgb-rgb-v2", 'P', 0);
    run("link-rgb-rgb-to-xyz", "link-rgb-rgb-v2,lcms-srgb", 'P', 0);
    run("link-lab-rgb", "link-lab-rgb-v2", 'P', 0);
    run("srgb-to-link-lab-rgb", "lcms-srgb,link-lab-rgb-v2", 'P', 0);
    run("link-rgb-lab", "link-rgb-lab-v4", 'P', 0);
    run("link-rgb-lab-to-p3", "link-rgb-lab-v4,lcms-p3", 'P', 0);
    run("link-mpe-rgb", "link-mpe-rgb", 'P', 0);
    run("link-mpe-lab-rgb", "link-mpe-lab-rgb", 'P', 0);
    run("srgb-to-link-mpe-lab-rgb", "lcms-srgb,link-mpe-lab-rgb", 'P', 0);

    run("abstract-v4", "lcms-srgb,abstract-bchsw-v4,lcms-srgb", 'P', 0);
    run("abstract-v4-relbpc", "lcms-srgb,abstract-bchsw-v4,lcms-srgb", 'R', 1);
    run("abstract-v4-abs", "lcms-srgb,abstract-bchsw-v4,lcms-srgb", 'A', 0);
    run("abstract-v4-to-cmyk", "lcms-srgb,abstract-bchsw-v4,mpe-cmyk", 'P', 0);
    run("abstract-v2", "lcms-srgb,abstract-bchsw-v2,lcms-srgb", 'P', 0);
    run("abstract-xyz", "lcms-srgb,abstract-mpe-xyz,lcms-p3", 'P', 0);
    run("abstract-xyz-rel", "lcms-srgb,abstract-mpe-xyz,lcms-p3", 'R', 0);
    run("abstract-lab", "lcms-srgb,abstract-mpe-lab,lcms-srgb", 'P', 0);
    run("abstract-twice", "lcms-srgb,abstract-mpe-lab,abstract-bchsw-v4,lcms-p3", 'P', 0);

    run("named", "named-cmyk", 'P', 0);
    run("named-to-srgb", "named-cmyk,lcms-srgb", 'P', 0);
    run("named-to-srgb-relbpc", "named-cmyk,lcms-srgb", 'R', 1);
    run("named-to-srgb-abs", "named-cmyk,lcms-srgb", 'A', 0);
    run("named-to-cmyk", "named-cmyk,mpe-cmyk", 'P', 0);
    run("named-rgb", "named-rgb-xyz", 'P', 0);
    run_named_xyz("named-xyz-to-srgb", "named-rgb-xyz", "lcms-srgb");

    dump("mpe-rgb", "D2B0", "0:1,0:1,0:1");
    dump("mpe-rgb", "D2B3", "0:1,0:1,0:1");
    dump("mpe-rgb", "B2D0", "0:0.9642,0:1,0:0.8249");
    dump("mpe-cmyk", "D2B0", "0:1,0:1,0:1,0:1");
    dump("mpe-cmyk", "B2D0", "0:100,-128:127,-128:127");
    dump("mpe-gray", "D2B0", "0:1");
    dump("mpe-gray", "B2D0", "0:0.9642,0:1,0:0.8249");
    dump("mpe-2clr", "D2B0", "0:1,0:1");
    dump("mpe-6clr", "D2B0", "0:1,0:1,0:1,0:1,0:1,0:1");
    dump("link-mpe-rgb", "D2B0", "0:1,0:1,0:1");
    dump("link-mpe-lab-rgb", "D2B0", "0:100,-128:127,-128:127");
    dump("abstract-mpe-xyz", "D2B0", "0:1,0:1,0:1");
    dump("abstract-mpe-lab", "D2B0", "0:100,-128:127,-128:127");

    dump_named("named-cmyk");
    dump_named("named-rgb-xyz");
    fclose(cases_file);
    fclose(pipelines_file);
    fclose(named_file);
    return 0;
}
