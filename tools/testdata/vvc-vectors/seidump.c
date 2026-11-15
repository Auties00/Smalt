/* Dumps the H.274 viewing SEI messages VVdeC attaches to each output frame, as raw syntax values. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "vvdec/vvdec.h"

static int frameIndex = 0;

static void dump(vvdecDecoder *dec, vvdecFrame *frame) {
    vvdecSEI *sei;
    printf("frame %d\n", frameIndex);
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_AMBIENT_VIEWING_ENVIRONMENT, frame))) {
        vvdecSEIAmbientViewingEnvironment *p = sei->payload;
        printf("  ambient %u %u %u\n", p->ambientIlluminance, p->ambientLightX, p->ambientLightY);
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_CONTENT_COLOUR_VOLUME, frame))) {
        vvdecSEIContentColourVolume *p = sei->payload;
        printf("  ccv cancel=%d persist=%d", p->ccvCancelFlag, p->ccvPersistenceFlag);
        if (!p->ccvCancelFlag) {
            if (p->ccvPrimariesPresentFlag) {
                for (int c = 0; c < 3; c++) printf(" p%d=%d,%d", c, p->ccvPrimariesX[c], p->ccvPrimariesY[c]);
            }
            if (p->ccvMinLuminanceValuePresentFlag) printf(" min=%u", p->ccvMinLuminanceValue);
            if (p->ccvMaxLuminanceValuePresentFlag) printf(" max=%u", p->ccvMaxLuminanceValue);
            if (p->ccvAvgLuminanceValuePresentFlag) printf(" avg=%u", p->ccvAvgLuminanceValue);
        }
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_SAMPLE_ASPECT_RATIO_INFO, frame))) {
        vvdecSEISampleAspectRatioInfo *p = sei->payload;
        printf("  sari cancel=%d", p->sariCancelFlag);
        if (!p->sariCancelFlag) {
            printf(" persist=%d idc=%d", p->sariPersistenceFlag, p->sariAspectRatioIdc);
            if (p->sariAspectRatioIdc == 255) printf(" %dx%d", p->sariSarWidth, p->sariSarHeight);
        }
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_EQUIRECTANGULAR_PROJECTION, frame))) {
        vvdecSEIEquirectangularProjection *p = sei->payload;
        printf("  erp cancel=%d", p->erpCancelFlag);
        if (!p->erpCancelFlag) {
            printf(" persist=%d", p->erpPersistenceFlag);
            if (p->erpGuardBandFlag) printf(" guard=%d,%d,%d", p->erpGuardBandType, p->erpLeftGuardBandWidth,
                                            p->erpRightGuardBandWidth);
        }
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_GENERALIZED_CUBEMAP_PROJECTION, frame))) {
        vvdecSEIGeneralizedCubemapProjection *p = sei->payload;
        printf("  gcmp cancel=%d", p->gcmpCancelFlag);
        if (!p->gcmpCancelFlag) {
            int faces = p->gcmpPackingType == 4 || p->gcmpPackingType == 5 ? 5 : 6;
            printf(" persist=%d packing=%d mapping=%d", p->gcmpPersistenceFlag, p->gcmpPackingType,
                   p->gcmpMappingFunctionType);
            for (int i = 0; i < faces; i++) {
                printf(" f%d=%d,%d,%d,%d,%d,%d", i, p->gcmpFaceIndex[i], p->gcmpFaceRotation[i], p->gcmpFunctionCoeffU[i],
                       p->gcmpFunctionUAffectedByVFlag[i], p->gcmpFunctionCoeffV[i], p->gcmpFunctionVAffectedByUFlag[i]);
            }
            if (p->gcmpGuardBandFlag) printf(" guard=%d,%d,%d", p->gcmpGuardBandType,
                                             p->gcmpGuardBandBoundaryExteriorFlag, p->gcmpGuardBandSamples);
        }
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_SPHERE_ROTATION, frame))) {
        vvdecSEISphereRotation *p = sei->payload;
        printf("  rotation cancel=%d", p->sphereRotationCancelFlag);
        if (!p->sphereRotationCancelFlag) printf(" persist=%d %d %d %d", p->sphereRotationPersistenceFlag,
                                                 p->sphereRotationYaw, p->sphereRotationPitch, p->sphereRotationRoll);
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_REGION_WISE_PACKING, frame))) {
        vvdecSEIRegionWisePacking *p = sei->payload;
        printf("  rwp cancel=%d", p->rwpCancelFlag);
        if (!p->rwpCancelFlag) {
            printf(" persist=%d matching=%d proj=%dx%d packed=%dx%d", p->rwpPersistenceFlag,
                   p->constituentPictureMatchingFlag, p->projPictureWidth, p->projPictureHeight,
                   p->packedPictureWidth, p->packedPictureHeight);
            for (int i = 0; i < p->numPackedRegions; i++) {
                printf(" r%d=%d,%u,%u,%u,%u,%u,%u,%u,%u", i, p->rwpTransformType[i], p->projRegionWidth[i],
                       p->projRegionHeight[i], p->rwpProjRegionTop[i], p->projRegionLeft[i], p->packedRegionWidth[i],
                       p->packedRegionHeight[i], p->packedRegionTop[i], p->packedRegionLeft[i]);
                if (p->rwpGuardBandFlag[i]) {
                    printf(",guard=%d,%d,%d,%d,%d,%d,%d,%d,%d", p->rwpLeftGuardBandWidth[i], p->rwpRightGuardBandWidth[i],
                           p->rwpTopGuardBandHeight[i], p->rwpBottomGuardBandHeight[i],
                           p->rwpGuardBandNotUsedForPredFlag[i], p->rwpGuardBandType[4 * i],
                           p->rwpGuardBandType[4 * i + 1], p->rwpGuardBandType[4 * i + 2], p->rwpGuardBandType[4 * i + 3]);
                }
            }
        }
        printf("\n");
    }
    if ((sei = vvdec_find_frame_sei(dec, VVDEC_OMNI_VIEWPORT, frame))) {
        vvdecSEIOmniViewport *p = sei->payload;
        printf("  viewport id=%u cancel=%d", p->omniViewportId, p->omniViewportCancelFlag);
        if (!p->omniViewportCancelFlag) {
            printf(" persist=%d", p->omniViewportPersistenceFlag);
            for (int i = 0; i < p->omniViewportCnt; i++) {
                vvdecOmniViewportRegion *r = &p->omniViewportRegions[i];
                printf(" v%d=%d,%d,%d,%u,%u", i, r->azimuthCentre, r->elevationCentre, r->tiltCentre, r->horRange,
                       r->verRange);
            }
        }
        printf("\n");
    }
    frameIndex++;
}

int main(int argc, char **argv) {
    FILE *in = fopen(argv[1], "rb");
    if (!in) return 1;
    fseek(in, 0, SEEK_END);
    long size = ftell(in);
    fseek(in, 0, SEEK_SET);
    unsigned char *data = malloc(size);
    if (fread(data, 1, size, in) != (size_t) size) return 1;
    fclose(in);
    vvdecParams params;
    vvdec_params_default(&params);
    params.threads = 1;
    vvdecDecoder *dec = vvdec_decoder_open(&params);
    vvdecAccessUnit *au = vvdec_accessUnit_alloc();
    vvdec_accessUnit_alloc_payload(au, (int) size + 8);
    long pos = 0;
    while (pos + 3 <= size) {
        long start = -1;
        for (long i = pos; i + 3 <= size; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) { start = i + 3; break; }
        }
        if (start < 0) break;
        long end = size;
        for (long i = start; i + 3 <= size; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && i + 3 < size && data[i + 3] == 1))) {
                end = i; break;
            }
        }
        au->payload[0] = 0; au->payload[1] = 0; au->payload[2] = 0; au->payload[3] = 1;
        memcpy(au->payload + 4, data + start, end - start);
        au->payloadUsedSize = (int) (end - start + 4);
        vvdecFrame *frame = NULL;
        int ret = vvdec_decode(dec, au, &frame);
        if (ret != VVDEC_OK && ret != VVDEC_TRY_AGAIN) { fprintf(stderr, "decode error %d\n", ret); return 1; }
        if (frame) { dump(dec, frame); vvdec_frame_unref(dec, frame); }
        pos = end;
    }
    for (;;) {
        vvdecFrame *frame = NULL;
        int ret = vvdec_flush(dec, &frame);
        if (frame) { dump(dec, frame); vvdec_frame_unref(dec, frame); }
        if (ret == VVDEC_EOF || (ret != VVDEC_OK && !frame)) break;
    }
    vvdec_accessUnit_free(au);
    vvdec_decoder_close(dec);
    return 0;
}
