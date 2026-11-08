// Decodes the first audio stream of a file with Windows Media Foundation and writes every sample as
// interleaved 32-bit little-endian float to the output file. With a channel count other than "-", the
// decoder is asked for that many channels; otherwise it keeps the count it chooses. For the Dolby
// Digital (Plus) decoder, an operational mode (CODECAPI_AVDecDDOperationalMode: 0 none, 1 line,
// 2 RF, 3 custom analog dialnorm, 4 custom digital dialnorm) and the dynamic range scale factors
// high and low (0 to 100) may follow; mode 3 with both scales 0 reproduces the stream without
// dialogue normalization or dynamic range control. Prints "<channels> <rate> <channel mask> <frames>"
// to standard output.
// Usage: MfAudioDecode <input> <output> [channels|- [mode high low]]
using System;
using System.IO;
using System.Runtime.InteropServices;

[ComImport, Guid("7b981cf0-560e-4116-9875-b099895f23d7"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFSourceReader {
    void GetStreamSelection();
    [PreserveSig] int SetStreamSelection(uint stream, bool selected);
    [PreserveSig] int GetNativeMediaType(uint stream, uint index, out IMFMediaType type);
    [PreserveSig] int GetCurrentMediaType(uint stream, out IMFMediaType type);
    [PreserveSig] int SetCurrentMediaType(uint stream, IntPtr reserved, IMFMediaType type);
    void SetCurrentPosition();
    [PreserveSig] int ReadSample(uint stream, uint control, out uint actual, out uint flags, out long timestamp,
                                 out IMFSample sample);
    void Flush(); void GetServiceForStream(); void GetPresentationAttribute();
    void SetNativeMediaType(); void AddTransformForStream(); void RemoveAllTransformsForStream();
    [PreserveSig] int GetTransformForStream(uint stream, uint index, out Guid category,
                                            [MarshalAs(UnmanagedType.IUnknown)] out object transform);
}

[ComImport, Guid("2cd2d921-c447-44a7-a13c-4adabfc247e3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFAttributes {
    void GetItem(); void GetItemType(); void CompareItem(); void Compare();
    [PreserveSig] int GetUINT32([MarshalAs(UnmanagedType.LPStruct)] Guid key, out uint value);
    void GetUINT64(); void GetDouble(); void GetGUID(); void GetStringLength(); void GetString(); void GetAllocatedString();
    void GetBlobSize(); void GetBlob(); void GetAllocatedBlob(); void GetUnknown(); void SetItem(); void DeleteItem();
    void DeleteAllItems();
    [PreserveSig] int SetUINT32([MarshalAs(UnmanagedType.LPStruct)] Guid key, uint value);
}

[ComImport, Guid("bf94c121-5b05-4e6f-8000-ba598961414d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFTransform {
    void GetStreamLimits(); void GetStreamCount(); void GetStreamIDs(); void GetInputStreamInfo();
    void GetOutputStreamInfo();
    [PreserveSig] int GetAttributes(out IMFAttributes attributes);
}

[ComImport, Guid("901db4c7-31ce-41a2-85dc-8fa0bf41b8da"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface ICodecAPI {
    void IsSupported(); void IsModifiable(); void GetParameterRange(); void GetParameterValues(); void GetDefaultValue();
    void GetValue();
    [PreserveSig] int SetValue([MarshalAs(UnmanagedType.LPStruct)] Guid api, [In] ref object value);
}

[ComImport, Guid("44ae0fa8-ea31-4109-8d2e-4cae4997c555"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMFMediaType {
    void GetItem(); void GetItemType(); void CompareItem(); void Compare();
    [PreserveSig] int GetUINT32([MarshalAs(UnmanagedType.LPStruct)] Guid key, out uint value);
    [PreserveSig] int GetUINT64([MarshalAs(UnmanagedType.LPStruct)] Guid key, out ulong value);
    void GetDouble(); void GetGUID(); void GetStringLength(); void GetString(); void GetAllocatedString();
    void GetBlobSize(); void GetBlob(); void GetAllocatedBlob(); void GetUnknown(); void SetItem(); void DeleteItem();
    void DeleteAllItems();
    [PreserveSig] int SetUINT32([MarshalAs(UnmanagedType.LPStruct)] Guid key, uint value);
    void SetUINT64(); void SetDouble();
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

static class MfAudioDecode {
    [DllImport("mfplat.dll")] static extern int MFStartup(uint version, uint flags);
    [DllImport("mfplat.dll")] static extern int MFShutdown();
    [DllImport("mfplat.dll")] static extern int MFCreateMediaType(out IMFMediaType type);
    [DllImport("mfreadwrite.dll", CharSet = CharSet.Unicode)]
    static extern int MFCreateSourceReaderFromURL(string url, IntPtr attributes, out IMFSourceReader reader);

    static readonly Guid MajorType = new Guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    static readonly Guid Subtype = new Guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    static readonly Guid Channels = new Guid("37e48bf5-645e-4c5b-89de-ada9e29b696a");
    static readonly Guid Rate = new Guid("5faeeae7-0290-4c31-9e8a-c534f68d9dba");
    static readonly Guid Mask = new Guid("55fb5765-644a-4caf-8479-938983bb1588");
    static readonly Guid Bits = new Guid("f2deb57f-40fa-4764-aa33-ed4f2d1ff669");
    static readonly Guid Audio = new Guid("73647561-0000-0010-8000-00AA00389B71");
    static readonly Guid Float = new Guid("00000003-0000-0010-8000-00AA00389B71");
    static readonly Guid OperationalMode = new Guid("d6d6c6d1-064e-4fdd-a40e-3ecbfcb7ebd0");
    static readonly Guid ScaleHigh = new Guid("50196c21-1f33-4af5-b296-11426d6c8789");
    static readonly Guid ScaleLow = new Guid("044e62e4-11a5-42d5-a3b2-3bb2c7c2d7cf");
    const uint FirstAudio = 0xFFFFFFFD;
    const uint AllStreams = 0xFFFFFFFE;
    const uint EndOfStream = 0x2;
    const uint MediaTypeChanged = 0x20;

    static void Check(int hr, string what) {
        if (hr < 0) {
            throw new Exception(what + " failed: 0x" + hr.ToString("X8"));
        }
    }

    static int Main(string[] args) {
        if (args.Length != 2 && args.Length != 3 && args.Length != 6) {
            Console.Error.WriteLine("usage: MfAudioDecode <input> <output> [channels|- [mode high low]]");
            return 2;
        }
        Check(MFStartup(0x00020070, 0), "MFStartup");
        IMFSourceReader reader;
        Check(MFCreateSourceReaderFromURL(Path.GetFullPath(args[0]), IntPtr.Zero, out reader),
                "MFCreateSourceReaderFromURL");
        Check(reader.SetStreamSelection(AllStreams, false), "SetStreamSelection");
        Check(reader.SetStreamSelection(FirstAudio, true), "SetStreamSelection");
        IMFMediaType type;
        Check(MFCreateMediaType(out type), "MFCreateMediaType");
        Check(type.SetGUID(MajorType, Audio), "SetGUID");
        Check(type.SetGUID(Subtype, Float), "SetGUID");
        Check(type.SetUINT32(Bits, 32), "SetUINT32");
        if (args.Length >= 3 && args[2] != "-") {
            Check(type.SetUINT32(Channels, uint.Parse(args[2])), "SetUINT32");
        }
        var set = reader.SetCurrentMediaType(FirstAudio, IntPtr.Zero, type);
        if (set < 0) {
            IMFMediaType native;
            if (reader.GetNativeMediaType(FirstAudio, 0, out native) >= 0) {
                uint nativeChannels, nativeRate;
                native.GetUINT32(Channels, out nativeChannels);
                native.GetUINT32(Rate, out nativeRate);
                Console.Error.WriteLine("no decoder for the native type: " + nativeChannels + " channels at "
                        + nativeRate + " Hz");
            }
            Check(set, "SetCurrentMediaType");
        }
        if (args.Length == 6) {
            for (uint index = 0; ; index++) {
                Guid category;
                object transform;
                if (reader.GetTransformForStream(FirstAudio, index, out category, out transform) < 0) {
                    break;
                }
                var api = transform as ICodecAPI;
                var mft = transform as IMFTransform;
                Console.Error.WriteLine("transform " + index + " " + category + " codecapi=" + (api != null) + " mft=" + (mft != null));
                if (api != null) {
                    object mode = uint.Parse(args[3]);
                    object high = uint.Parse(args[4]);
                    object low = uint.Parse(args[5]);
                    Check(api.SetValue(OperationalMode, ref mode), "SetValue(AVDecDDOperationalMode)");
                    Check(api.SetValue(ScaleHigh, ref high), "SetValue(AVDecDDDynamicRangeScaleHigh)");
                    Check(api.SetValue(ScaleLow, ref low), "SetValue(AVDecDDDynamicRangeScaleLow)");
                } else if (mft != null) {
                    IMFAttributes attributes;
                    if (mft.GetAttributes(out attributes) >= 0 && attributes != null) {
                        Console.Error.WriteLine(" attrs " + attributes.SetUINT32(OperationalMode, uint.Parse(args[3])).ToString("X8")
                                + " " + attributes.SetUINT32(ScaleHigh, uint.Parse(args[4])).ToString("X8")
                                + " " + attributes.SetUINT32(ScaleLow, uint.Parse(args[5])).ToString("X8"));
                    }
                }
            }
        }
        uint channels = 0, rate = 0, mask = 0;
        Action describe = () => {
            IMFMediaType current;
            Check(reader.GetCurrentMediaType(FirstAudio, out current), "GetCurrentMediaType");
            Check(current.GetUINT32(Channels, out channels), "channels");
            Check(current.GetUINT32(Rate, out rate), "rate");
            if (current.GetUINT32(Mask, out mask) < 0) {
                mask = 0;
            }
        };
        describe();
        long bytesWritten = 0;
        using (var output = File.Create(args[1])) {
            while (true) {
                uint actual, flags;
                long timestamp;
                IMFSample sample;
                Check(reader.ReadSample(FirstAudio, 0, out actual, out flags, out timestamp, out sample), "ReadSample");
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
                    output.Write(bytes, 0, length);
                    bytesWritten += length;
                }
                if ((flags & EndOfStream) != 0) {
                    break;
                }
            }
        }
        Console.WriteLine(channels + " " + rate + " " + mask + " " + (bytesWritten / 4 / Math.Max(1, channels)));
        MFShutdown();
        return 0;
    }
}
