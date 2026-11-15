/*
 * Decodes the first surface of a DDS file with DirectXTex (microsoft/DirectXTex, MIT), the oracle of the depth,
 * video and legacy formats of Smalt's DDS decoder tests.
 *
 *   smalt-dxtex <file.dds> <output.raw>
 *
 * The output is the surface's texels in row-major order as four little-endian IEEE 754 binary32 values each (red,
 * green, blue, alpha): the values DirectXTex's scanline loader (Internal::LoadScanline, the first step of every
 * conversion) gives each texel, after DirectXTex's conversion of a planar format to the packed format of the same
 * sampling (NV12 and NV11 to YUY2, P010 to Y210, P016 to Y216). The loader is called directly rather than through
 * Convert, which refuses the formats with typeless parts. The program prints the surface's width, height and DXGI
 * format number on standard output.
 */
#include "DirectXTexP.h"

#include <cstdio>
#include <vector>

using namespace DirectX;

int main(int argc, char **argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: smalt-dxtex <file.dds> <output.raw>\n");
        return 2;
    }
    std::FILE *in = std::fopen(argv[1], "rb");
    if (!in) {
        std::fprintf(stderr, "cannot open %s\n", argv[1]);
        return 1;
    }
    std::vector<uint8_t> data;
    uint8_t buffer[65536];
    size_t read;
    while ((read = std::fread(buffer, 1, sizeof(buffer), in)) > 0) {
        data.insert(data.end(), buffer, buffer + read);
    }
    std::fclose(in);
    TexMetadata metadata;
    ScratchImage loaded;
    HRESULT hr = LoadFromDDSMemory(data.data(), data.size(), DDS_FLAGS_NONE, &metadata, loaded);
    if (FAILED(hr)) {
        std::fprintf(stderr, "LoadFromDDSMemory failed: %08lx\n", static_cast<unsigned long>(hr));
        return 1;
    }
    const Image *image = loaded.GetImage(0, 0, 0);
    ScratchImage single;
    if (IsPlanar(image->format)) {
        hr = ConvertToSinglePlane(*image, single);
        if (FAILED(hr)) {
            std::fprintf(stderr, "ConvertToSinglePlane failed: %08lx\n", static_cast<unsigned long>(hr));
            return 1;
        }
        image = single.GetImage(0, 0, 0);
    }
    std::vector<XMVECTOR> row(image->width + 1);
    std::vector<float> texels(image->width * 4);
    std::FILE *file = std::fopen(argv[2], "wb");
    if (!file) {
        std::fprintf(stderr, "cannot create %s\n", argv[2]);
        return 1;
    }
    for (size_t y = 0; y < image->height; y++) {
        if (!Internal::LoadScanline(row.data(), image->width, image->pixels + y * image->rowPitch, image->rowPitch,
                                    image->format)) {
            std::fprintf(stderr, "LoadScanline does not handle format %d\n", static_cast<int>(image->format));
            std::fclose(file);
            return 1;
        }
        for (size_t x = 0; x < image->width; x++) {
            XMFLOAT4 value;
            XMStoreFloat4(&value, row[x]);
            texels[4 * x] = value.x;
            texels[4 * x + 1] = value.y;
            texels[4 * x + 2] = value.z;
            texels[4 * x + 3] = value.w;
        }
        std::fwrite(texels.data(), sizeof(float), texels.size(), file);
    }
    std::fclose(file);
    std::printf("%zu %zu %d\n", image->width, image->height, static_cast<int>(metadata.format));
    return 0;
}
