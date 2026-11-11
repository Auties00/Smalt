# DDS DirectXTex fixtures

Self-generated. Each `<name>.dds` is a one-surface DDS file of seeded pseudo-random texels in a depth-stencil, 1-bit,
video (YUV) or legacy palette format, written by `tools/testdata/dxtex-oracle/make-fixtures.py`; `<name>.f32` is
DirectXTex's decode of it: four little-endian binary32 values (red, green, blue, alpha) per texel in row-major order,
the values DirectXTex's scanline loader gives each texel after its conversion of a planar format to the packed format
of the same sampling. They were written by `smalt-dxtex` (`tools/testdata/dxtex-oracle/smalt-dxtex.cpp`), built from
DirectXTex at commit `c0cb97764379ebd20dbd2016c58965db85f5b307` and DirectXMath at commit
`d400b65211034e3e7997b3d5e8d7fdf45b0c3d2e` (both MIT, github.com/microsoft) with MSYS2's g++ 15.2 (UCRT64,
`-O2 -std=c++17`), on Windows x64:

    g++ -O2 -std=c++17 -I<DirectXTex>/DirectXTex -I<DirectXMath>/Inc -o smalt-dxtex.exe smalt-dxtex.cpp \
        <DirectXTex>/DirectXTex/{DirectXTexDDS,DirectXTexConvert,DirectXTexImage,DirectXTexUtil,DirectXTexMipmaps,
        DirectXTexCompress,BC,BC4BC5,BC6HBC7,DirectXTexMisc,DirectXTexWIC}.cpp -lole32 -lwindowscodecs -luuid -static
    python tools/testdata/dxtex-oracle/make-fixtures.py smalt-dxtex.exe

`expected.tsv` records each fixture's DXGI format number as DirectXTex reports it, its size and the SHA-256 of both
files. DirectXTex has no decode of the typeless depth formats (`R24G8_TYPELESS`, `R32G8X24_TYPELESS`), of the
palette formats of a DX10 header (`P8`, `A8P8`, `AI44`, `IA44`, which carry no palette), of the Windows 10 planar
formats (`P208`, `V208`, `V408`) or of the legacy `DDPF_YUV` mask formats, so those are tested against the formats
they share their texels with and against their definitions.
