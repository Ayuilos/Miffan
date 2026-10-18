using System;
using System.Runtime.InteropServices;
public static class MiffanNative {
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", CharSet=CharSet.Unicode)] static extern bool WTSQuerySessionInformation(IntPtr server, uint session, int kind, out IntPtr data, out uint bytes);
    [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr data);
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct WTSLevel {
        public uint SessionId; public int SessionState; public int SessionFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=33)] public string Station;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=21)] public string User;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=18)] public string Domain;
        public long Logon, Connect, Disconnect, LastInput, Current;
        public uint InBytes, OutBytes, InFrames, OutFrames, InCompressed, OutCompressed;
    }
    [StructLayout(LayoutKind.Sequential)] public struct WTSInfo { public uint Level; public WTSLevel Data; }
    public static string QueryString(uint session, int kind) {
        IntPtr data; uint bytes;
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, kind, out data, out bytes)) return null;
        try { return Marshal.PtrToStringUni(data); } finally { WTSFreeMemory(data); }
    }
    public static int LockFlag(uint session) {
        IntPtr data; uint bytes;
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, 25, out data, out bytes)) return -1;
        try {
            if (bytes < Marshal.SizeOf(typeof(WTSInfo))) return -1;
            WTSInfo info = (WTSInfo)Marshal.PtrToStructure(data, typeof(WTSInfo));
            return info.Level == 1 && info.Data.SessionId == session ? info.Data.SessionFlags : -1;
        } finally { WTSFreeMemory(data); }
    }
}
