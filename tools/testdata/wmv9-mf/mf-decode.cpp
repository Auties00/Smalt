// Decodes the video of a file with Media Foundation (software, Microsoft's own decoders) into raw I420 frames.
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <cstdio>
#include <cstring>
#include <vector>

#define CHECK(x) do { HRESULT hr_ = (x); if (FAILED(hr_)) { fprintf(stderr, "%s failed 0x%08lx\n", #x, (unsigned long) hr_); return 1; } } while (0)

int wmain(int argc, wchar_t **argv) {
    if (argc < 3) { fprintf(stderr, "usage: mfdec in out.yuv [frames]\n"); return 2; }
    int limit = argc > 3 ? _wtoi(argv[3]) : 1 << 30;
    CHECK(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    CHECK(MFStartup(MF_VERSION));
    IMFAttributes *attributes = nullptr;
    CHECK(MFCreateAttributes(&attributes, 2));
    attributes->SetUINT32(MF_READWRITE_DISABLE_CONVERTERS, TRUE);
    IMFSourceReader *reader = nullptr;
    CHECK(MFCreateSourceReaderFromURL(argv[1], attributes, &reader));
    reader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    IMFMediaType *type = nullptr;
    CHECK(MFCreateMediaType(&type));
    type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_I420);
    CHECK(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type));
    IMFMediaType *current = nullptr;
    CHECK(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &current));
    UINT32 width = 0, height = 0;
    MFGetAttributeSize(current, MF_MT_FRAME_SIZE, &width, &height);
    UINT32 stride = MFGetAttributeUINT32(current, MF_MT_DEFAULT_STRIDE, width);
    fprintf(stderr, "%ux%u stride %u\n", width, height, stride);
    FILE *out = _wfopen(argv[2], L"wb");
    int frames = 0;
    std::vector<BYTE> row(width);
    while (frames < limit) {
        DWORD index = 0, flags = 0;
        LONGLONG time = 0;
        IMFSample *sample = nullptr;
        CHECK(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &index, &flags, &time, &sample));
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) break;
        if (!sample) continue;
        IMFMediaBuffer *buffer = nullptr;
        CHECK(sample->ConvertToContiguousBuffer(&buffer));
        BYTE *data = nullptr;
        DWORD length = 0;
        CHECK(buffer->Lock(&data, nullptr, &length));
        // I420 with an aligned height: planes are stride * alignedHeight apart
        UINT32 alignedHeight = (UINT32) (length * 2 / 3 / stride);
        for (UINT32 y = 0; y < height; y++) fwrite(data + y * stride, 1, width, out);
        BYTE *u = data + stride * alignedHeight;
        BYTE *v = u + (stride / 2) * (alignedHeight / 2);
        for (UINT32 y = 0; y < (height + 1) / 2; y++) fwrite(u + y * (stride / 2), 1, (width + 1) / 2, out);
        for (UINT32 y = 0; y < (height + 1) / 2; y++) fwrite(v + y * (stride / 2), 1, (width + 1) / 2, out);
        buffer->Unlock();
        buffer->Release();
        sample->Release();
        frames++;
    }
    fclose(out);
    fprintf(stderr, "%d frames\n", frames);
    return 0;
}
