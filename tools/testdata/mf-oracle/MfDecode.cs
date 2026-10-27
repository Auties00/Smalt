// Decodes the first video stream of a file with Windows Media Foundation and writes every picture
// as planar 4:2:0 (yuv420p) to the output file, cropped to the given size.
// Usage: MfDecode <input> <output> <width> <height>
using System;
using System.IO;
using System.Runtime.InteropServices;

[ComImport, Guid("70ae66f2-c809-4e4f-8915-bdcb406b7993"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFSourceReader {
    void GetStreamSelection();
    [PreserveSig] int SetStreamSelection(uint stream, bool selected);
    void GetNativeMediaType();
    [PreserveSig] int GetCurrentMediaType(uint stream, out IMFMediaType type);
    [PreserveSig] int SetCurrentMediaType(uint stream, IntPtr reserved, IMFMediaType type);
    void SetCurrentPosition();
    [PreserveSig] int ReadSample(uint stream, uint control, out uint actual, out uint flags, out long timestamp,
                                 out IMFSample sample);
}

[ComImport, Guid("44ae0fa8-ea31-4109-8d2e-4cae4997c555"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFMediaType {
    void GetItem(); void GetItemType(); void CompareItem(); void Compare();
    [PreserveSig] int GetUINT32([MarshalAs(UnmanagedType.LPStruct)] Guid key, out uint value);
    [PreserveSig] int GetUINT64([MarshalAs(UnmanagedType.LPStruct)] Guid key, out ulong value);
    void GetDouble(); void GetGUID(); void GetStringLength(); void GetString(); void GetAllocatedString();
    void GetBlobSize(); void GetBlob(); void GetAllocatedBlob(); void GetUnknown(); void SetItem(); void DeleteItem();
    void DeleteAllItems(); void SetUINT32(); void SetUINT64(); void SetDouble();
    [PreserveSig] int SetGUID([MarshalAs(UnmanagedType.LPStruct)] Guid key,
                              [MarshalAs(UnmanagedType.LPStruct)] Guid value);
}

[ComImport, Guid("c40a00f2-b93a-4d80-ae8c-5a1c634f58e4"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFSample {
    void GetItem(); void GetItemType(); void CompareItem(); void Compare(); void GetUINT32(); void GetUINT64();
    void GetDouble(); void GetGUID(); void GetStringLength(); void GetString(); void GetAllocatedString();
    void GetBlobSize(); void GetBlob(); void GetAllocatedBlob(); void GetUnknown(); void SetItem(); void DeleteItem();
    void DeleteAllItems(); void SetUINT32(); void SetUINT64(); void SetDouble(); void SetGUID(); void SetString();
    void SetBlob(); void SetUnknown(); void LockStore(); void UnlockStore(); void GetCount(); void GetItemByIndex();
    void CopyAllItems();
    void GetSampleFlags(); void SetSampleFlags(); void GetSampleTime(); void SetSampleTime(); void GetSampleDuration();
    void SetSampleDuration(); void GetBufferCount(); void GetBufferByIndex();
    [PreserveSig] int ConvertToContiguousBuffer(out IMFMediaBuffer buffer);
}

[ComImport, Guid("045FA593-8799-42b8-BC8D-8968C6453507"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFMediaBuffer {
    [PreserveSig] int Lock(out IntPtr data, out int maxLength, out int currentLength);
    [PreserveSig] int Unlock();
}

static class MfDecode {
    [DllImport("mfplat.dll")] static extern int MFStartup(uint version, uint flags);
    [DllImport("mfplat.dll")] static extern int MFShutdown();
    [DllImport("mfplat.dll")] static extern int MFCreateMediaType(out IMFMediaType type);
    [DllImport("mfreadwrite.dll", CharSet = CharSet.Unicode)]
    static extern int MFCreateSourceReaderFromURL(string url, IntPtr attributes, out IMFSourceReader reader);

    static readonly Guid MajorType = new Guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    static readonly Guid Subtype = new Guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    static readonly Guid FrameSize = new Guid("1652c33d-d6b2-4012-b834-72030849a37d");
    static readonly Guid DefaultStride = new Guid("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");
    static readonly Guid Video = new Guid("73646976-0000-0010-8000-00AA00389B71");
    static readonly Guid Nv12 = new Guid("3231564E-0000-0010-8000-00AA00389B71");
    const uint FirstVideo = 0xFFFFFFFC;
    const uint AllStreams = 0xFFFFFFFE;
    const uint EndOfStream = 0x2;
    const uint MediaTypeChanged = 0x20;

    static void Check(int hr, string what) {
        if (hr < 0) {
            throw new Exception(what + " failed: 0x" + hr.ToString("X8"));
        }
    }

    static int Main(string[] args) {
        if (args.Length != 4) {
            Console.Error.WriteLine("usage: MfDecode <input> <output> <width> <height>");
            return 2;
        }
        var visibleWidth = int.Parse(args[2]);
        var visibleHeight = int.Parse(args[3]);
        Check(MFStartup(0x00020070, 0), "MFStartup");
        IMFSourceReader reader;
        Check(MFCreateSourceReaderFromURL(Path.GetFullPath(args[0]), IntPtr.Zero, out reader),
                "MFCreateSourceReaderFromURL");
        Check(reader.SetStreamSelection(AllStreams, false), "SetStreamSelection");
        Check(reader.SetStreamSelection(FirstVideo, true), "SetStreamSelection");
        IMFMediaType type;
        Check(MFCreateMediaType(out type), "MFCreateMediaType");
        Check(type.SetGUID(MajorType, Video), "SetGUID");
        Check(type.SetGUID(Subtype, Nv12), "SetGUID");
        Check(reader.SetCurrentMediaType(FirstVideo, IntPtr.Zero, type), "SetCurrentMediaType");
        int height = 0, stride = 0;
        Action describe = () => {
            IMFMediaType current;
            Check(reader.GetCurrentMediaType(FirstVideo, out current), "GetCurrentMediaType");
            ulong size;
            Check(current.GetUINT64(FrameSize, out size), "frame size");
            height = (int) (size & 0xFFFFFFFF);
            uint s;
            stride = current.GetUINT32(DefaultStride, out s) >= 0 ? (int) s : (int) (size >> 32);
        };
        describe();
        var chromaWidth = (visibleWidth + 1) / 2;
        var chromaHeight = (visibleHeight + 1) / 2;
        var chroma = new byte[2 * chromaWidth * chromaHeight];
        var frames = 0;
        using (var output = File.Create(args[1])) {
            while (true) {
                uint actual, flags;
                long timestamp;
                IMFSample sample;
                Check(reader.ReadSample(FirstVideo, 0, out actual, out flags, out timestamp, out sample), "ReadSample");
                if ((flags & MediaTypeChanged) != 0) {
                    describe();
                }
                if (sample != null) {
                    IMFMediaBuffer buffer;
                    Check(sample.ConvertToContiguousBuffer(out buffer), "ConvertToContiguousBuffer");
                    IntPtr data;
                    int max, length;
                    Check(buffer.Lock(out data, out max, out length), "Lock");
                    var bytes = new byte[length];
                    Marshal.Copy(data, bytes, 0, length);
                    buffer.Unlock();
                    Marshal.ReleaseComObject(buffer);
                    Marshal.ReleaseComObject(sample);
                    for (var y = 0; y < visibleHeight; y++) {
                        output.Write(bytes, y * stride, visibleWidth);
                    }
                    // NV12 interleaves U and V in the plane after the luma plane.
                    for (var y = 0; y < chromaHeight; y++) {
                        var row = (height + y) * stride;
                        for (var x = 0; x < chromaWidth; x++) {
                            chroma[y * chromaWidth + x] = bytes[row + 2 * x];
                            chroma[chromaWidth * chromaHeight + y * chromaWidth + x] = bytes[row + 2 * x + 1];
                        }
                    }
                    output.Write(chroma, 0, chroma.Length);
                    frames++;
                }
                if ((flags & EndOfStream) != 0) {
                    break;
                }
            }
        }
        Console.Error.WriteLine("decoded " + frames + " pictures");
        MFShutdown();
        return 0;
    }
}
