<#
.SYNOPSIS
  Mindustry 服务器控制台接管工具: 附加到服务器进程的控制台, 读取终端/注入命令。
  解决代理(或人)误判"服务器未启动"的视角问题: 所有读取都带视角元信息。

.EXAMPLE
  # 自动定位服务器进程(6567 监听)并读取末尾 15 行(含视角元信息)
  .\console.ps1 find
  .\console.ps1 read -TargetPid 2632 -Tail 15
  .\console.ps1 read -TargetPid 2632 -From 410        # 增量: 只读 410 行之后的内容
  .\console.ps1 send -TargetPid 2632 -Text "status"   # 注入命令(自动回显确认)
#>
param(
    [Parameter(Position = 0)][string]$Command = "find",   # find | read | send
    [int]$TargetPid = 0,
    [int]$Tail = 15,        # read: 读取末尾 N 行(与窗口位置无关, 读缓冲区坐标)
    [int]$From = -1,        # read: 增量模式, 只返回该行之后的内容
    [string]$Text = "",     # send: 注入的命令
    [string]$TextB64 = "",  # send: 注入命令的 base64(UTF-8)形式——避免 bash→powershell 参数编码损坏中文
    [int]$Backspaces = 0,   # send: 先注入 N 个退格(清理输入残留)
    [int]$PipePort = 0,     # send: 本地命令管道端口(默认 6568); 文本含非 ASCII 时自动走管道(UTF-8 无损, 绕过 WriteConsoleInput 中文截断)
    [switch]$CheckIdle     # send: 注入前检查输入缓冲区, 非空则跳过(避免覆盖正在输入的内容)
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
public class TermCon {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint dwProcessId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr tmpl);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr h);
    [StructLayout(LayoutKind.Sequential)] public struct COORD { public short X; public short Y; }
    [StructLayout(LayoutKind.Sequential)] public struct SMALL_RECT { public short Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct CONSOLE_SCREEN_BUFFER_INFO {
        public COORD dwSize; public COORD dwCursorPosition; public ushort wAttributes;
        public SMALL_RECT srWindow; public COORD dwMaximumWindowSize;
    }
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetConsoleScreenBufferInfo(IntPtr hConsoleOutput, out CONSOLE_SCREEN_BUFFER_INFO info);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool ReadConsoleOutputCharacter(IntPtr hConsoleOutput, [Out] char[] lpCharacter, uint nLength, COORD dwReadCoord, out uint lpNumberOfCharsRead);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetConsoleMode(IntPtr h, out uint mode);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetNumberOfConsoleInputEvents(IntPtr h, out uint n);
    [StructLayout(LayoutKind.Explicit)] public struct INPUT_RECORD {
        [FieldOffset(0)] public ushort EventType;
        [FieldOffset(4)] public KEY_EVENT_RECORD KeyEvent;
    }
    [StructLayout(LayoutKind.Sequential)] public struct KEY_EVENT_RECORD {
        public int bKeyDown;
        public ushort wRepeatCount;
        public ushort wVirtualKeyCode;
        public ushort wVirtualScanCode;
        [MarshalAs(UnmanagedType.U2)] public char UnicodeChar;
        public uint dwControlKeyState;
    }
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool WriteConsoleInput(IntPtr h, INPUT_RECORD[] b, uint n, out uint w);
    [DllImport("user32.dll")] public static extern short VkKeyScan(char ch);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);

    public static bool AttachTo(uint pid) {
        FreeConsole(); Thread.Sleep(250);
        if (!AttachConsole(pid)) return false;
        Thread.Sleep(250);
        return true;
    }
    public static void Detach() { FreeConsole(); }
    public static IntPtr OpenOut() { return CreateFile("CONOUT$", 0xC0000000, 0x3, IntPtr.Zero, 3, 0, IntPtr.Zero); }
    public static IntPtr OpenIn() { return CreateFile("CONIN$", 0xC0000000, 0x3, IntPtr.Zero, 3, 0, IntPtr.Zero); }
}
"@

function Get-Info {
    # 返回 @{w; h; cur; winTop; winBottom; bufH; inMode; inEvents}
    $hOut = [TermCon]::OpenOut()
    $info = New-Object TermCon+CONSOLE_SCREEN_BUFFER_INFO
    [TermCon]::GetConsoleScreenBufferInfo($hOut, [ref]$info) | Out-Null
    $hIn = [TermCon]::OpenIn()
    $inMode = 0; [TermCon]::GetConsoleMode($hIn, [ref]$inMode) | Out-Null
    $inEvents = 0; [TermCon]::GetNumberOfConsoleInputEvents($hIn, [ref]$inEvents) | Out-Null
    [TermCon]::CloseHandle($hOut) | Out-Null
    [TermCon]::CloseHandle($hIn) | Out-Null
    return @{
        w = $info.dwSize.X; bufH = $info.dwSize.Y
        curY = $info.dwCursorPosition.Y; curX = $info.dwCursorPosition.X
        winTop = $info.srWindow.Top; winBottom = $info.srWindow.Bottom
        inMode = $inMode; inEvents = $inEvents
    }
}

function Read-Lines([int]$fromRow, [int]$count, [int]$w, [int]$bufH) {
    # 读缓冲区从 fromRow 开始 count 行(与窗口位置无关)
    if ($count -le 0 -or $fromRow -lt 0) { return @() }
    $hOut = [TermCon]::OpenOut()
    $buf = New-Object char[] ($w * $count)
    $read = 0
    $coord = New-Object TermCon+COORD
    $coord.X = 0; $coord.Y = [int16]$fromRow
    [TermCon]::ReadConsoleOutputCharacter($hOut, $buf, $buf.Length, $coord, [ref]$read) | Out-Null
    [TermCon]::CloseHandle($hOut) | Out-Null
    $lines = @()
    for ($y = 0; $y -lt $count; $y++) {
        $line = -join $buf[($y*$w)..($y*$w + $w - 1)]
        $lines += $line.TrimEnd()
    }
    return $lines
}

function Show-ViewMeta($info, [int]$fromRow, [int]$toRow) {
    $atBottom = ($info.winBottom -ge $info.bufH - 2) -or ($info.winBottom -ge $info.curY)
    $cursorLine = $info.curY
    Write-Output ("== VIEW: buffer=" + $info.w + "x" + $info.bufH +
        " | window(visible)=" + $info.winTop + ".." + $info.winBottom +
        " | cursor=(" + $info.curX + "," + $info.curY + ")" +
        " | read range=" + $fromRow + ".." + $toRow +
        " | windowAtBottom=" + $atBottom +
        " | inputMode=0x" + $info.inMode.ToString("X") + " events=" + $info.inEvents +
        " ==")
    Write-Output ("== 解读: 窗口在底部(最新输出)= " + $atBottom + "; 光标行即最后输出行=" + $cursorLine + "; 新输出写在光标行 ==")
}

if ($Command -eq "find") {
    # 自动定位 6567 监听进程 + loader 进程树
    $conn = Get-NetTCPConnection -LocalPort 6567 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { Write-Output "NOT_FOUND: 6567 无监听"; exit 1 }
    $serverPid = $conn.OwningProcess
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid" -ErrorAction SilentlyContinue
    $loader = $null; $cmd = $null
    if ($server) {
        $loader = Get-CimInstance Win32_Process -Filter "ProcessId=$($server.ParentProcessId)" -ErrorAction SilentlyContinue
        if ($loader) { $cmd = Get-CimInstance Win32_Process -Filter "ProcessId=$($loader.ParentProcessId)" -ErrorAction SilentlyContinue }
    }
    Write-Output ("SERVER_PID=" + $serverPid + " (java, " + $server.Name + ")")
    if ($loader) { Write-Output ("LOADER_PID=" + $server.ParentProcessId + " (" + $loader.Name + ")") }
    if ($cmd -and $cmd.CommandLine -match "run\.bat") { Write-Output ("RUNBAT_CMD_PID=" + $cmd.ProcessId) }
    exit 0
}

if ($TargetPid -le 0) { Write-Output "需要 -TargetPid <服务器java PID>(先运行 console.ps1 find)"; exit 1 }

if (-not [TermCon]::AttachTo([uint32]$TargetPid)) {
    Write-Output ("ATTACH_FAILED pid=" + $TargetPid + " err=" + [Runtime.InteropServices.Marshal]::GetLastWin32Error())
    exit 1
}
try {
    if ($Command -eq "read") {
        $info = Get-Info
        $totalRows = $info.bufH
        # 有效内容: 光标前(新输出写到光标行)
        $lastRow = $info.curY
        if ($From -ge 0) {
            # 增量模式: 从 From 到光标
            $count = $lastRow - $From + 1
            if ($count -le 0) { Show-ViewMeta $info $From $lastRow; Write-Output "(无新内容)" }
            else {
                Show-ViewMeta $info $From $lastRow
                Read-Lines $From $count $info.w $info.bufH | ForEach-Object { Write-Output $_ }
            }
        } else {
            $start = [Math]::Max(0, $lastRow - $Tail + 1)
            Show-ViewMeta $info $start $lastRow
            Read-Lines $start $Tail $info.w $info.bufH | ForEach-Object { Write-Output $_ }
        }
    }
    elseif ($Command -eq "send") {
        if ($TextB64) {
            $Text = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($TextB64))
        }
        # 含非 ASCII(如中文)时自动走本地命令管道: WriteConsoleInput 注入非 ASCII 会被 Windows 控制台截断为低字节(实测 U+4E2D->U+002D), 管道 UTF-8 无损
        $hasNonAscii = $false
        foreach ($ch in $Text.ToCharArray()) { if ([int]$ch -gt 127) { $hasNonAscii = $true; break } }
        if ($hasNonAscii) {
            $port = if ($PipePort -gt 0) { $PipePort } else { 6568 }
            try {
                $client = New-Object System.Net.Sockets.TcpClient("127.0.0.1", $port)
                $stream = $client.GetStream()
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text + "`n")
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Flush()
                Start-Sleep -Milliseconds 200
                $client.Close()
                Write-Output ("PIPE-SEND[" + $Text + "] -> 127.0.0.1:" + $port + " (UTF-8, 执行结果见服务器日志)")
            } catch {
                Write-Output ("PIPE-SEND FAILED: " + $_.Exception.Message)
                Write-Output ("  服务器需开启命令管道(console.kts startCmdPipe, config.conf coreMindustry.console.cmdPipePort); 或用 ASCII 命令")
                exit 1
            }
            exit 0
        }
        $hIn = [TermCon]::OpenIn()
        if ($CheckIdle) {
            $n = 0; [TermCon]::GetNumberOfConsoleInputEvents($hIn, [ref]$n) | Out-Null
            if ($n -gt 0) { Write-Output ("SKIP: 输入缓冲区非空(" + $n + " 事件, 可能正在手动输入), 未注入"); exit 0 }
        }
        [TermCon]::CloseHandle($hIn) | Out-Null
        # 用 C# 构造事件(纯 PowerShell 对 Explicit 结构体赋值不可靠)
        $sendType = @"
using System;
using System.Runtime.InteropServices;
public class TermSend {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr tmpl);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr h);
    [StructLayout(LayoutKind.Explicit)] public struct INPUT_RECORD {
        [FieldOffset(0)] public ushort EventType;
        [FieldOffset(4)] public KEY_EVENT_RECORD KeyEvent;
    }
    [StructLayout(LayoutKind.Sequential)] public struct KEY_EVENT_RECORD {
        public int bKeyDown;
        public ushort wRepeatCount;
        public ushort wVirtualKeyCode;
        public ushort wVirtualScanCode;
        [MarshalAs(UnmanagedType.U2)] public char UnicodeChar;
        public uint dwControlKeyState;
    }
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool WriteConsoleInput(IntPtr h, INPUT_RECORD[] b, uint n, out uint w);
    [DllImport("user32.dll")] public static extern short VkKeyScan(char ch);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);
    public static int Send(uint pid, string text, int bs) {
        string full = new string('\b', bs) + text + "\r";
        char[] chars = full.ToCharArray();
        IntPtr hIn = CreateFile("CONIN$", 0xC0000000, 0x3, IntPtr.Zero, 3, 0, IntPtr.Zero);
        INPUT_RECORD[] recs = new INPUT_RECORD[chars.Length * 2];
        int idx = 0;
        foreach (char ch in chars) {
            short vk = VkKeyScan(ch);
            ushort vkCode = (vk < 0) ? (ushort)0 : (ushort)(vk & 0xFF); // 中文等无键码字符 vkCode=0, 依赖 UnicodeChar
            uint scan = MapVirtualKey(vkCode, 0);
            for (int down = 1; down >= 0; down--) {
                INPUT_RECORD r = new INPUT_RECORD();
                r.EventType = 1;
                r.KeyEvent.bKeyDown = down;
                r.KeyEvent.wRepeatCount = 1;
                r.KeyEvent.wVirtualKeyCode = vkCode;
                r.KeyEvent.wVirtualScanCode = (ushort)scan;
                r.KeyEvent.UnicodeChar = ch;
                r.KeyEvent.dwControlKeyState = 0;
                recs[idx++] = r;
            }
        }
        uint w = 0;
        bool ok = WriteConsoleInput(hIn, recs, (uint)idx, out w);
        CloseHandle(hIn);
        return ok ? (int)w : -1;
    }
}
"@
        Add-Type -TypeDefinition $sendType
        $sent = [TermSend]::Send([uint32]$TargetPid, $Text, $Backspaces)
        Write-Output ("SEND[" + $Text + "] events=" + $sent)
        # 回显确认: 读光标所在行(命令应回显在提示符行)
        Start-Sleep -Milliseconds 800
        $info = Get-Info
        $lines = @(Read-Lines $info.curY 1 $info.w $info.bufH)
        if ($lines.Count -gt 0) { Write-Output ("ECHO: " + $lines[0].Trim()) }
    }
    else {
        Write-Output "未知命令: $Command (find|read|send)"
    }
} finally {
    [TermCon]::Detach()
}
