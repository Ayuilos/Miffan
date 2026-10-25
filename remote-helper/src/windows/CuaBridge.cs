using System;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Principal;
using System.Security.AccessControl;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using System.Collections.Generic;
using System.Web.Script.Serialization;

// No daemon ACL/permission changes. Every MCP child inherits this Limited interactive token.
public static class MiffanCuaBridge {
    public static readonly Encoding Utf8 = new UTF8Encoding(false, true);
    [StructLayout(LayoutKind.Sequential)] struct SA { public int length; public IntPtr descriptor; public int inherit; }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern SafePipeHandle CreateNamedPipe(string name, uint mode, uint pipeMode, uint instances, uint output, uint input, uint timeout, ref SA security);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] static extern bool WaitNamedPipe(string name, uint timeout);
    [DllImport("kernel32.dll")] static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool GetTokenInformation(IntPtr token, int kind, IntPtr buffer, int size, out int needed);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);
    [DllImport("user32.dll", SetLastError=true)] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool GetUserObjectInformation(IntPtr obj, int index, StringBuilder info, int length, out int needed);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int number);
    static Stream Standard(int number, FileAccess access) {
        // Windows OpenSSH inherits OVERLAPPED pipe handles. ConsoleStream's synchronous
        // ReadFile can handle the first buffered batch then hang on the next pending read.
        try { return new FileStream(new SafeFileHandle(GetStdHandle(number),false),access,65536,true); }
        catch (ArgumentException) { return number==-10 ? Console.OpenStandardInput() : Console.OpenStandardOutput(); }
    }
    static long lastActivity;
    static int active;
    static readonly System.Collections.Concurrent.ConcurrentDictionary<NamedPipeServerStream,byte> clients = new System.Collections.Concurrent.ConcurrentDictionary<NamedPipeServerStream,byte>();
    static readonly System.Collections.Concurrent.ConcurrentDictionary<int,Process> children = new System.Collections.Concurrent.ConcurrentDictionary<int,Process>();
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
    [DllImport("advapi32.dll")] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    static int TokenIntegrity(IntPtr token) {
        int size; GetTokenInformation(token,25,IntPtr.Zero,0,out size);
        if(size<=0) throw new IOException("Cannot check integrity");
        IntPtr memory=Marshal.AllocHGlobal(size);
        try {
            if(!GetTokenInformation(token,25,memory,size,out size)) throw new IOException("Cannot check integrity");
            IntPtr sid=Marshal.ReadIntPtr(memory);
            return Marshal.ReadInt32(GetSidSubAuthority(sid,(uint)(Marshal.ReadByte(GetSidSubAuthorityCount(sid))-1)));
        } finally {Marshal.FreeHGlobal(memory);}
    }
    public static int Integrity() {using(var identity=WindowsIdentity.GetCurrent()) return TokenIntegrity(identity.Token);}
    public static int ProcessIntegrity(int pid) {
        IntPtr process=OpenProcess(0x1000,false,pid),token=IntPtr.Zero;
        if(process==IntPtr.Zero) return -1;
        try {if(!OpenProcessToken(process,8,out token)) return -1;return TokenIntegrity(token);}
        catch {return -1;}
        finally {if(token!=IntPtr.Zero) CloseHandle(token);CloseHandle(process);}
    }
    public static bool DesktopAvailable(int session) {
        if (session == 0 || WTSGetActiveConsoleSessionId() != session) return false;
        IntPtr desktop = OpenInputDesktop(0, false, 1);
        if (desktop == IntPtr.Zero) return false;
        try { var name = new StringBuilder(128); int needed; return GetUserObjectInformation(desktop, 2, name, 256, out needed) && name.ToString() == "Default"; }
        finally { CloseDesktop(desktop); }
    }
    public static bool Available(string name) { return WaitNamedPipe(@"\\.\pipe\" + name, 1); }
    static NamedPipeServerStream Create(string name, string sid, bool first) {
        // Protected DACL: precisely this user, no Everyone/Administrators/System grant.
        var security = new PipeSecurity(); security.SetSecurityDescriptorSddlForm("O:"+sid+"D:P(A;;GA;;;"+sid+")");
        byte[] descriptor = security.GetSecurityDescriptorBinaryForm();
        IntPtr memory = Marshal.AllocHGlobal(descriptor.Length);
        try {
            Marshal.Copy(descriptor, 0, memory, descriptor.Length);
            var attributes = new SA { length=Marshal.SizeOf(typeof(SA)), descriptor=memory, inherit=0 };
            // DUPLEX | OVERLAPPED | FIRST_PIPE_INSTANCE, byte mode | REJECT_REMOTE_CLIENTS.
            var handle = CreateNamedPipe(@"\\.\pipe\"+name, 3u|0x40000000u|(first?0x00080000u:0u), 0x8u, 8, 65536, 65536, 1000, ref attributes);
            if (handle.IsInvalid) { handle.Dispose(); throw new IOException("Bridge pipe unavailable"); }
            return new NamedPipeServerStream(PipeDirection.InOut, true, false, handle);
        } finally { Marshal.FreeHGlobal(memory); }
    }
    static void Touch() { Interlocked.Exchange(ref lastActivity, Environment.TickCount & 0xffffffffL); }
    static long Age(long then) { return ((Environment.TickCount & 0xffffffffL)-then)&0xffffffffL; }
    public static string ReadLine(Stream stream, int limit) {
        using (var data = new MemoryStream()) {
            for (int i=0; i<limit; i++) { int b=stream.ReadByte(); if(b<0) throw new EndOfStreamException(); if(b==10) return Utf8.GetString(data.ToArray()); data.WriteByte((byte)b); }
            throw new IOException("Frame too long");
        }
    }
    public static void WriteLine(Stream stream, string line) { byte[] data=Utf8.GetBytes(line+"\n"); stream.Write(data,0,data.Length); stream.Flush(); }
    public static string Ticket(string root, string id) {
        Guid value; if (!Guid.TryParseExact(id,"N",out value)) throw new IOException("Invalid ticket");
        return Path.Combine(root,id+".ticket");
    }
    public static bool Consume(string root, string id, string token) {
        string file=Ticket(root,id), consumed=file+".used-"+Guid.NewGuid().ToString("N");
        try {
            if ((File.GetAttributes(file)&FileAttributes.ReparsePoint)!=0 || (DateTime.UtcNow-File.GetLastWriteTimeUtc(file)).TotalSeconds>30) return false;
            File.Move(file,consumed); // Atomic claim. Even failed authentication consumes the ticket.
            string expected=File.ReadAllText(consumed,Utf8);
            if (expected.Length!=64 || token.Length!=64) return false;
            int difference=0; for(int i=0;i<64;i++) difference |= expected[i]^token[i];
            return difference==0;
        } catch { return false; }
        finally { if (File.Exists(consumed)) File.Delete(consumed); }
    }
    static void Pump(Stream input, Stream output) {
        byte[] data=new byte[65536]; int n;
        while((n=input.ReadAsync(data,0,data.Length).GetAwaiter().GetResult())>0) { output.WriteAsync(data,0,n).GetAwaiter().GetResult(); output.FlushAsync().GetAwaiter().GetResult(); Touch(); }
    }
    static void Serve(NamedPipeServerStream pipe, string root, string exe, int session) {
        Process child=null;
        try {
            var auth=Task.Run(()=>ReadLine(pipe,128));
            if (!auth.Wait(5000)) return;
            string[] parts=auth.Result.Split(' ');
            if(parts.Length!=2 || !DesktopAvailable(session) || !Consume(root,parts[0],parts[1])) return;
            var start=new ProcessStartInfo(exe,"mcp --socket \\\\.\\pipe\\cua-driver") {
                UseShellExecute=false, CreateNoWindow=true, RedirectStandardInput=true, RedirectStandardOutput=true, RedirectStandardError=true,
                StandardOutputEncoding=Utf8, StandardErrorEncoding=Utf8
            };
            child=Process.Start(start); children[child.Id]=child; child.StandardInput.NewLine="\n";
            // BaseStreams bypass CP936; explicit UTF-8 on the text wrappers too.
            WriteLine(pipe,"OK "+parts[1]); Touch();
            var stderr=Task.Run(()=> { byte[] bytes=new byte[4096]; while(child.StandardError.BaseStream.Read(bytes,0,bytes.Length)>0) {} });
            var incoming=Task.Run(()=>Pump(pipe,child.StandardInput.BaseStream));
            var outgoing=Task.Run(()=>Pump(child.StandardOutput.BaseStream,pipe));
            Task.WaitAny(incoming,outgoing);
        } catch { /* Never log user JSON or exception text. EOF informs the client; no replay. */ }
        finally {
            pipe.Dispose(); byte removed; clients.TryRemove(pipe,out removed);
            if(child!=null) { try { child.StandardInput.Close(); if(!child.WaitForExit(1000)) child.Kill(); } catch {} Process removedChild; children.TryRemove(child.Id,out removedChild); child.Dispose(); }
            Interlocked.Decrement(ref active); Touch();
        }
    }
    public static void Run(string name, string root, string exe, string sid, int session, int idleSeconds) {
        if(WindowsIdentity.GetCurrent().User.Value!=sid || Process.GetCurrentProcess().SessionId!=session || Integrity()!=8192 || !DesktopAvailable(session)) throw new IOException("Bridge requires a Limited unlocked console session");
        Touch(); NamedPipeServerStream listener=Create(name,sid,true);
        try {
            while(true) {
                var waiting=listener.WaitForConnectionAsync();
                while(!waiting.Wait(250)) {
                    if(!DesktopAvailable(session) || (Age(Interlocked.Read(ref lastActivity))>idleSeconds*1000L)) return;
                }
                waiting.GetAwaiter().GetResult();
                var connected=listener;
                while (Volatile.Read(ref active)>=6) { Thread.Sleep(100); if(!DesktopAvailable(session) || Age(Interlocked.Read(ref lastActivity))>idleSeconds*1000L) { connected.Dispose(); return; } }
                // Keep at least one instance alive throughout, preventing name takeover.
                listener=Create(name,sid,false);
                clients[connected]=0; Interlocked.Increment(ref active);
                Task.Run(()=>Serve(connected,root,exe,session));
            }
        } finally {
            listener.Dispose();
            foreach(var client in clients.Keys) { try { client.Dispose(); } catch {} }
            // Kill only the MCP children this bridge created, never the shared daemon.
            foreach(var child in children.Values) { try { if(!child.HasExited) child.Kill(); } catch {} }
        }
    }
    public static NamedPipeClientStream Connect(string name, string root) {
        string id=Guid.NewGuid().ToString("N"); byte[] random=new byte[32];
        using(var generator=System.Security.Cryptography.RandomNumberGenerator.Create()) generator.GetBytes(random);
        string token=BitConverter.ToString(random).Replace("-","").ToLowerInvariant();
        string ticket=Ticket(root,id);
        using(var file=new FileStream(ticket,FileMode.CreateNew,FileAccess.Write,FileShare.None)) { byte[] data=Utf8.GetBytes(token); file.Write(data,0,data.Length); }
        var pipe=new NamedPipeClientStream(".",name,PipeDirection.InOut,PipeOptions.Asynchronous,TokenImpersonationLevel.Identification);
        try {
            pipe.Connect(5000); WriteLine(pipe,id+" "+token);
            var acknowledgement=Task.Run(()=>ReadLine(pipe,128));
            if(!acknowledgement.Wait(5000) || acknowledgement.Result!="OK "+token) throw new IOException("Bridge authentication failed");
            return pipe;
        } catch { pipe.Dispose(); throw; }
        finally { if(File.Exists(ticket)) File.Delete(ticket); }
    }
    public static void Stdio(string name,string root) {
        Console.InputEncoding=Utf8; Console.OutputEncoding=Utf8;
        using(var pipe=Connect(name,root)) {
            var incoming=Task.Run(()=> { Pump(Standard(-10,FileAccess.Read),pipe); });
            var outgoing=Task.Run(()=> { Pump(pipe,Standard(-11,FileAccess.Write)); });
            Task.WaitAny(incoming,outgoing);
        }
    }
    static Dictionary<string,object> Rpc(Stream pipe, int id, string method, object parameters) {
        var json=new JavaScriptSerializer { MaxJsonLength=8*1024*1024 };
        WriteLine(pipe,json.Serialize(new {jsonrpc="2.0",id=id,method=method,@params=parameters}));
        for(int i=0;i<32;i++) {
            var read=Task.Run(()=>ReadLine(pipe,8*1024*1024));
            if(!read.Wait(10000)) throw new IOException("MCP timeout");
            var response=json.Deserialize<Dictionary<string,object>>(read.Result);
            if(!response.ContainsKey("id") || Convert.ToInt32(response["id"])!=id) continue;
            if(response.ContainsKey("error")) throw new IOException("MCP error");
            var result=(Dictionary<string,object>)response["result"];
            if(result.ContainsKey("isError") && (bool)result["isError"]) throw new IOException("Clipboard tool refused");
            return result;
        }
        throw new IOException("MCP response missing");
    }
    /** One MCP handshake and tool listing through the bridge, after an install: the driver's version. */
    public static string Ping(string name,string root) {
        using(var pipe=Connect(name,root)) {
            var initialized=Rpc(pipe,1,"initialize",new { protocolVersion="2025-06-18", capabilities=new {}, clientInfo=new {name="miffan-check",version="1.0"} });
            WriteLine(pipe,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            var listed=Rpc(pipe,2,"tools/list",new {});
            var tools=listed.ContainsKey("tools") ? listed["tools"] as System.Collections.ArrayList : null;
            if(tools==null || tools.Count==0) throw new IOException("No tools");
            var server=initialized.ContainsKey("serverInfo") ? initialized["serverInfo"] as Dictionary<string,object> : null;
            return server!=null && server.ContainsKey("version") ? Convert.ToString(server["version"]) : "";
        }
    }
    public static bool Clipboard(string name,string root,string text) {
        using(var session=new ClipSession(name,root)) return session.Write(text);
    }
    /**
     * Serves clipboard writes over this process's stdin/stdout until EOF: one base64 UTF-8 line in,
     * one JSON line out. Reads go through [Standard] because OpenSSH hands over overlapped pipes.
     */
    public static void ClipServe(string name,string root) {
        var input=Standard(-10,FileAccess.Read); var output=Standard(-11,FileAccess.Write);
        using(var session=new ClipSession(name,root)) {
            while(true) {
                string line;
                try { line=ReadLine(input,4*1024*1024); } catch (EndOfStreamException) { return; }
                bool ok=false;
                try {
                    string text=Utf8.GetString(Convert.FromBase64String(line.Trim()));
                    ok=text.IndexOf('\0')<0 && session.Write(text);
                } catch {}
                WriteLine(output,ok ? "{\"success\":true,\"verified\":true}" : "{\"success\":false,\"verified\":false}");
                // A failed write may leave the MCP connection unusable; the client falls back.
                if(!ok) return;
            }
        }
    }
    /** One bridge connection and MCP handshake reused for many clipboard writes (screen-page typing). */
    public sealed class ClipSession : IDisposable {
        readonly NamedPipeClientStream pipe; int next=1;
        public ClipSession(string name,string root) {
            pipe=Connect(name,root);
            Rpc(pipe,next++,"initialize",new { protocolVersion="2025-06-18", capabilities=new {}, clientInfo=new {name="miffan-clip",version="1.0"} });
            WriteLine(pipe,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }
        public bool Write(string text) {
            Rpc(pipe,next++,"tools/call",new {name="clipboard_write",arguments=new {text=text}});
            return Matches(Rpc(pipe,next++,"tools/call",new {name="clipboard_read",arguments=new {include_text=true}}),text);
        }
        public void Dispose() { pipe.Dispose(); }
    }
    static bool Matches(Dictionary<string,object> result,string text) {
        // Verification stays local to the helper; no old clipboard read, text/hash output.
        if(result.ContainsKey("structuredContent")) {
            var structured=result["structuredContent"] as Dictionary<string,object>;
            if(structured!=null && structured.ContainsKey("text")) return (string)structured["text"]==text;
        }
        foreach(var item in (System.Collections.ArrayList)result["content"]) {
            var content=(Dictionary<string,object>)item;
            if(content.ContainsKey("text")) {
                try { var value=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>((string)content["text"]); if(value.ContainsKey("text")) return (string)value["text"]==text; } catch {}
            }
        }
        return false;
    }
}
