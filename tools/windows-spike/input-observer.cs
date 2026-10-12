// Experiment-only, windowless Session 1 observer. No input/activation API.
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
public static class SpikeInputObserver {
 [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hwnd);
 [DllImport("user32.dll")] static extern uint GetDpiForWindow(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
 [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(POINT point);
 [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT point);
 [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr hwnd,uint flags);
 [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr hwnd,int attr,out uint value,int size);
 struct RECT {public int Left,Top,Right,Bottom;}
 struct POINT {public int X,Y;}
 static string Bool(bool b) {return b?"true":"false";}
 static string Snapshot(string nonce) {
  var hwnd=new IntPtr(788388);uint owner;GetWindowThreadProcessId(hwnd,out owner);
  uint cloak;int hr=DwmGetWindowAttribute(hwnd,14,out cloak,4);RECT rect;GetWindowRect(hwnd,out rect);
  long fg=GetForegroundWindow().ToInt64();bool exists=IsWindow(hwnd),visible=IsWindowVisible(hwnd),iconic=IsIconic(hwnd);
  var point=new POINT {X=600,Y=500};long root=GetAncestor(WindowFromPoint(point),2).ToInt64();
  POINT cursor;GetCursorPos(out cursor);long cursorRoot=GetAncestor(WindowFromPoint(cursor),2).ToInt64();
  int session=System.Diagnostics.Process.GetCurrentProcess().SessionId;
  bool active=exists&&visible&&!iconic&&hr==0&&cloak==0&&owner==24040&&fg==788388&&session==1&&root==788388;
  return "{\"RequestId\":\""+nonce+"\",\"Active\":"+Bool(active)+",\"Exists\":"+Bool(exists)+",\"Visible\":"+Bool(visible)+",\"Minimized\":"+Bool(iconic)+",\"Cloaked\":"+cloak+",\"CloakQueryHresult\":"+hr+",\"ForegroundHwnd\":"+fg+",\"PointRootHwnd\":"+root+",\"CursorX\":"+cursor.X+",\"CursorY\":"+cursor.Y+",\"CursorRootHwnd\":"+cursorRoot+",\"WindowId\":788388,\"TargetPid\":"+owner+",\"Session\":"+session+",\"Dpi\":"+GetDpiForWindow(hwnd)+",\"Left\":"+rect.Left+",\"Top\":"+rect.Top+",\"Right\":"+rect.Right+",\"Bottom\":"+rect.Bottom+",\"ScreenWidth\":"+GetSystemMetrics(0)+",\"ScreenHeight\":"+GetSystemMetrics(1)+",\"MonitorCount\":"+GetSystemMetrics(80)+"}";
 }
 public static void Main() {
  SetThreadDpiAwarenessContext(new IntPtr(-4));
  string dir=Path.Combine(Path.GetTempPath(),"miffan-windows-spike"),request=Path.Combine(dir,"input-observer-request.txt"),output=Path.Combine(dir,"input-observer-result.json");
  var deadline=DateTime.UtcNow.AddMinutes(10);string last=null;
  while(DateTime.UtcNow<deadline&&!File.Exists(Path.Combine(dir,"input-observer-stop"))) {
   try {
    string nonce=File.Exists(request)?File.ReadAllText(request).Trim():null;
    Guid parsed;if(nonce!=last&&Guid.TryParse(nonce,out parsed)) {
     string result=Snapshot(parsed.ToString());string tmp=output+".tmp";File.WriteAllText(tmp,result,new System.Text.UTF8Encoding(false));
     if(File.Exists(output))File.Delete(output);File.Move(tmp,output);last=nonce;
    }
   } catch(IOException) {}
   Thread.Sleep(50);
  }
 }
}
