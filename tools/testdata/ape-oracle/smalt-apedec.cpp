// Decodes a Monkey's Audio file frame by frame with the Monkey's Audio SDK and writes the raw little-endian samples
// of every frame decoded before the first that cannot be read (a truncated file decodes up to its last whole frame).
// Usage: smalt-apedec <input.ape> <output.raw>
// Build: g++ -O2 -I<SDK>/Shared -I<SDK>/MACLib smalt-apedec.cpp libMAC.a -o smalt-apedec
#include "All.h"
#include "MACLib.h"
#include <cstdio>
#include <vector>

int main(int argc, char ** argv)
{
    if (argc != 3)
        return 2;
    int error = 0;
    std::vector<APE::str_utfn> path(argv[1], argv[1] + strlen(argv[1]) + 1);
    APE::IAPEDecompress * decoder = CreateIAPEDecompress(path.data(), &error, true, true, false);
    if (decoder == nullptr || error != 0)
        return 3;
    APE::int64 frameBlocks = decoder->GetInfo(APE::IAPEDecompress::APE_INFO_BLOCKS_PER_FRAME);
    APE::int64 align = decoder->GetInfo(APE::IAPEDecompress::APE_INFO_BLOCK_ALIGN);
    FILE * out = fopen(argv[2], "wb");
    std::vector<unsigned char> buffer(static_cast<size_t>(frameBlocks * align));
    APE::int64 total = 0;
    for (;;)
    {
        APE::int64 got = 0;
        // A frame that cannot be read is reported while the frame before it is returned: keep that one, then stop.
        int result = decoder->GetData(buffer.data(), frameBlocks, &got);
        if (got > 0)
        {
            fwrite(buffer.data(), 1, static_cast<size_t>(got * align), out);
            total += got;
        }
        if (result != 0 || got <= 0)
            break;
    }
    fclose(out);
    printf("%lld\n", static_cast<long long>(total));
    delete decoder;
    return 0;
}
