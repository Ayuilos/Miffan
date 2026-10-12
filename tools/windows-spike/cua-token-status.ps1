# Read-only process integrity/session and foreground identity checks.
$ErrorActionPreference='Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SpikeToken {
  [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
  [DllImport("advapi32.dll",SetLastError=true)] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
  [DllImport("advapi32.dll",SetLastError=true)] static extern bool GetTokenInformation(IntPtr token,int cls,IntPtr data,uint len,out uint needed);
  [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
  [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid,uint index);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
  public static int Integrity(int pid) {
    IntPtr process=OpenProcess(0x1000,false,pid); if(process==IntPtr.Zero) return -Marshal.GetLastWin32Error();
    IntPtr token; if(!OpenProcessToken(process,8,out token)){int e=Marshal.GetLastWin32Error();CloseHandle(process);return -e;}
    CloseHandle(process); uint size;GetTokenInformation(token,25,IntPtr.Zero,0,out size);
    IntPtr buffer=Marshal.AllocHGlobal((int)size);
    try { if(!GetTokenInformation(token,25,buffer,size,out size)) return -Marshal.GetLastWin32Error();
      IntPtr sid=Marshal.ReadIntPtr(buffer);byte count=Marshal.ReadByte(GetSidSubAuthorityCount(sid));
      return Marshal.ReadInt32(GetSidSubAuthority(sid,(uint)(count-1)));
    } finally {Marshal.FreeHGlobal(buffer);CloseHandle(token);}
  }
}
'@
$foreground=[SpikeToken]::GetForegroundWindow();$foregroundPid=[uint32]0
[void][SpikeToken]::GetWindowThreadProcessId($foreground,[ref]$foregroundPid)
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
[pscustomobject]@{Time=(Get-Date -Format o);ForegroundHwnd=$foreground.ToInt64();ForegroundPid=$foregroundPid;
    SshIntegrityRid=[SpikeToken]::Integrity($PID);
    Task=@{User=$task.Principal.UserId;LogonType="$($task.Principal.LogonType)";RunLevel="$($task.Principal.RunLevel)"};
    Driver=@(Get-CimInstance Win32_Process | Where-Object Name -eq 'cua-driver.exe' | ForEach-Object {
      [pscustomobject]@{Pid=$_.ProcessId;Session=$_.SessionId;CommandLine=$_.CommandLine;IntegrityRid=[SpikeToken]::Integrity($_.ProcessId)}
    })}|ConvertTo-Json -Depth 4
