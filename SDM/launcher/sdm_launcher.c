/*
 * SDM.exe - tiny native launcher.
 *
 *  - finds a Python 3 interpreter (the one SDM last used, then the usual places)
 *  - runs sdm.py inside this process by loading that Python's runtime (python3XY.dll), so Task Manager
 *    shows "SDM - S Download Manager" with SDM's icon instead of "Python"
 *  - where that is not possible (Store / venv / embeddable / 32-bit Python, or the py launcher before SDM
 *    has recorded the interpreter) it starts "pythonw sdm.py <args>" with no console window instead
 *    (sdm.py itself installs missing packages and opens the window, or just
 *     focuses the running instance)
 *  - if no Python is found, offers to run install.bat
 *
 * Build (any OS):  python -m ziglang cc -target x86_64-windows-gnu -O2 -municode -Wl,--subsystem,windows -s
 *                  sdm_launcher.c sdm.rc -o SDM.exe
 */
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <wchar.h>

#define BIG 4096

static wchar_t g_dir[MAX_PATH];

static BOOL file_exists(const wchar_t *p) {
    DWORD a = GetFileAttributesW(p);
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

static void trim(wchar_t *s) {
    size_t n = wcslen(s);
    while (n && (s[n - 1] == L'\r' || s[n - 1] == L'\n' || s[n - 1] == L' ' || s[n - 1] == L'\t' || s[n - 1] == L'"'))
        s[--n] = 0;
    size_t i = 0;
    while (s[i] == L' ' || s[i] == L'\t' || s[i] == L'"' || s[i] == 0xFEFF) i++;
    if (i) memmove(s, s + i, (wcslen(s + i) + 1) * sizeof(wchar_t));
}

/* python_path.txt is written by SDM / the installer: the interpreter that has SDM's packages */
static BOOL from_saved(wchar_t *out) {
    wchar_t f[MAX_PATH];
    _snwprintf(f, MAX_PATH, L"%ls\\python_path.txt", g_dir);
    HANDLE h = CreateFileW(f, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    char buf[2048];
    DWORD rd = 0;
    BOOL ok = ReadFile(h, buf, sizeof(buf) - 1, &rd, NULL);
    CloseHandle(h);
    if (!ok || rd == 0) return FALSE;
    buf[rd] = 0;
    if (!MultiByteToWideChar(CP_UTF8, 0, buf, -1, out, MAX_PATH)) return FALSE;
    trim(out);
    return file_exists(out);
}

/* newest %base%\Python3*\pythonw.exe */
static BOOL scan_dir(const wchar_t *base, wchar_t *out) {
    wchar_t pat[MAX_PATH], best[MAX_PATH] = L"";
    int best_minor = -1;
    _snwprintf(pat, MAX_PATH, L"%ls\\Python3*", base);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        int minor = _wtoi(fd.cFileName + 7);          /* "Python312" -> 12 */
        wchar_t cand[MAX_PATH];
        _snwprintf(cand, MAX_PATH, L"%ls\\%ls\\pythonw.exe", base, fd.cFileName);
        if (minor >= 9 && minor > best_minor && file_exists(cand)) {
            best_minor = minor;
            wcscpy(best, cand);
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    if (best_minor < 0) return FALSE;
    wcscpy(out, best);
    return TRUE;
}

static BOOL from_common(wchar_t *out) {
    wchar_t base[MAX_PATH], p[MAX_PATH];
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH)) {
        _snwprintf(p, MAX_PATH, L"%ls\\Programs\\Python", base);
        if (scan_dir(p, out)) return TRUE;
    }
    if (GetEnvironmentVariableW(L"ProgramFiles", base, MAX_PATH) && scan_dir(base, out)) return TRUE;
    return FALSE;
}

static BOOL in_path(const wchar_t *exe, wchar_t *out) {
    DWORD n = SearchPathW(NULL, exe, NULL, MAX_PATH, out, NULL);
    if (n == 0 || n >= MAX_PATH) return FALSE;
    /* skip the Microsoft Store placeholder that only opens the Store */
    if (wcsstr(out, L"\\WindowsApps\\") && !wcsstr(out, L"PythonSoftwareFoundation")) {
        WIN32_FILE_ATTRIBUTE_DATA a;
        if (GetFileAttributesExW(out, GetFileExInfoStandard, &a) && a.nFileSizeLow == 0 && a.nFileSizeHigh == 0)
            return FALSE;
    }
    return TRUE;
}

/* everything after argv[0] on our own command line */
static const wchar_t *passthrough_args(void) {
    const wchar_t *c = GetCommandLineW();
    if (*c == L'"') {
        c++;
        while (*c && *c != L'"') c++;
        if (*c) c++;
    } else {
        while (*c && *c != L' ' && *c != L'\t') c++;
    }
    while (*c == L' ' || *c == L'\t') c++;
    return c;
}

/* ---------------------------------------------------------------- hosting Python in this process */
typedef int (*PyMainFn)(int, wchar_t **);
typedef void *(WINAPI *AddDllDirFn)(PCWSTR);

/* the interpreter's own runtime DLL, e.g. python313.dll (not the stable-ABI python3.dll,
   not free-threaded "t" or debug "_d" builds). Returns its minor version, or -1. */
static int find_python_dll(const wchar_t *dir, wchar_t *out) {
    wchar_t pat[MAX_PATH];
    _snwprintf(pat, MAX_PATH, L"%ls\\python3*.dll", dir);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return -1;
    int best = -1;
    do {
        const wchar_t *d = fd.cFileName + 7;            /* after "python3" */
        int minor = 0, k = 0;
        while (d[k] >= L'0' && d[k] <= L'9' && k < 3) { minor = minor * 10 + (d[k] - L'0'); k++; }
        if (k == 0 || _wcsicmp(d + k, L".dll") != 0) continue;
        if (minor >= 9 && minor > best) {
            best = minor;
            _snwprintf(out, MAX_PATH, L"%ls\\%ls", dir, fd.cFileName);
            out[MAX_PATH - 1] = 0;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return best;
}

static BOOL dir_exists(const wchar_t *p) {
    DWORD a = GetFileAttributesW(p);
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

/* A windowless program may inherit broken standard handles (e.g. started by another program that closed
   them). Python refuses to start on those, so hand it "no handle" instead - like a normal pythonw start. */
static void drop_invalid_std_handles(void) {
    const DWORD ids[3] = { STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE };
    for (int i = 0; i < 3; i++) {
        HANDLE h = GetStdHandle(ids[i]);
        if (h == NULL || h == INVALID_HANDLE_VALUE) { SetStdHandle(ids[i], NULL); continue; }
        SetLastError(NO_ERROR);
        if (GetFileType(h) == FILE_TYPE_UNKNOWN && GetLastError() != NO_ERROR) SetStdHandle(ids[i], NULL);
    }
}

static void clear_host_env(void) {
    SetEnvironmentVariableW(L"PYTHONHOME", NULL);
    SetEnvironmentVariableW(L"SDM_PYTHONW", NULL);
}

/* Returns sdm.py's exit code, or -1 when this Python can't be hosted (caller then starts pythonw). */
static int host_python(const wchar_t *pyw, const wchar_t *script) {
    wchar_t dir[MAX_PATH], p[MAX_PATH], dll[MAX_PATH];
    lstrcpynW(dir, pyw, MAX_PATH);
    wchar_t *s = wcsrchr(dir, L'\\');
    if (!s) return -1;
    *s = 0;
    _snwprintf(p, MAX_PATH, L"%ls\\Lib\\os.py", dir);       /* a normal install with its standard library */
    /* Python 3.11+ finds its standard library from its own DLL's folder; older versions keep the
       classic pythonw start */
    if (!file_exists(p) || find_python_dll(dir, dll) < 11) return -1;

    /* DLLs Python and its packages expect next to python.exe (vcruntime, python3.dll for abi3 wheels) */
    AddDllDirFn add_dir = (AddDllDirFn)(void *)GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "AddDllDirectory");
    if (add_dir) {
        add_dir(dir);
        _snwprintf(p, MAX_PATH, L"%ls\\DLLs", dir);
        if (dir_exists(p)) add_dir(p);
    }
    drop_invalid_std_handles();                              /* before the C runtime in Python's DLL starts */
    SetEnvironmentVariableW(L"PYTHONHOME", dir);             /* (its C runtime copies the environment on load) */
    SetEnvironmentVariableW(L"SDM_PYTHONW", pyw);            /* sdm.py: the real interpreter, for child processes */
    HMODULE h = LoadLibraryExW(dll, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!h) { clear_host_env(); return -1; }                 /* e.g. a 32-bit Python: start pythonw instead */
    PyMainFn py_main = (PyMainFn)(void *)GetProcAddress(h, "Py_Main");
    if (!py_main) { FreeLibrary(h); clear_host_env(); return -1; }
    _snwprintf(p, MAX_PATH, L"%ls\\python3.dll", dir);
    if (file_exists(p)) LoadLibraryExW(p, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);

    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv || argc < 1) { clear_host_env(); return -1; }
    wchar_t **av = (wchar_t **)HeapAlloc(GetProcessHeap(), 0, (size_t)(argc + 2) * sizeof(wchar_t *));
    if (!av) { clear_host_env(); return -1; }
    av[0] = argv[0];
    av[1] = (wchar_t *)script;                               /* python <script> <our args...> */
    for (int i = 1; i < argc; i++) av[i + 1] = argv[i];
    av[argc + 1] = NULL;

    SetCurrentDirectoryW(g_dir);
    return py_main(argc + 1, av);
}

static DWORD g_err = 0;

static int run(const wchar_t *cmd) {
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    wchar_t *buf = (wchar_t *)HeapAlloc(GetProcessHeap(), 0, BIG * 2 * sizeof(wchar_t));
    if (!buf) return 0;
    lstrcpynW(buf, cmd, BIG * 2);
    BOOL ok = CreateProcessW(NULL, buf, NULL, NULL, FALSE, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT,
                             NULL, g_dir, &si, &pi);
    g_err = ok ? 0 : GetLastError();
    HeapFree(GetProcessHeap(), 0, buf);
    if (!ok) return 0;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 1;
}

int WINAPI wWinMain(HINSTANCE hi, HINSTANCE hp, PWSTR cl, int show) {
    (void)hi; (void)hp; (void)cl; (void)show;
    DWORD n = GetModuleFileNameW(NULL, g_dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return 1;
    wchar_t *slash = wcsrchr(g_dir, L'\\');
    if (slash) *slash = 0;

    wchar_t script[MAX_PATH];
    _snwprintf(script, MAX_PATH, L"%ls\\sdm.py", g_dir);
    if (!file_exists(script)) {
        MessageBoxW(NULL, L"sdm.py was not found next to SDM.exe.\n\nKeep SDM.exe inside the SDM folder.",
                    L"SDM", MB_ICONERROR);
        return 1;
    }

    wchar_t py[MAX_PATH] = L"";
    const wchar_t *pyargs = L"";
    /* order: interpreter SDM last ran with -> py launcher (what install.bat used)
       -> pythonw on PATH -> standard install folders */
    if (!from_saved(py)) {
        if (in_path(L"pyw.exe", py)) {
            pyargs = L" -3";
        } else if (!in_path(L"pythonw.exe", py) && !from_common(py)) {
            int r = MessageBoxW(NULL,
                L"SDM needs Python 3, which was not found on this PC.\n\nRun the one-time setup now?",
                L"SDM", MB_ICONQUESTION | MB_YESNO);
            if (r == IDYES) {
                wchar_t bat[MAX_PATH];
                _snwprintf(bat, MAX_PATH, L"%ls\\install.bat", g_dir);
                ShellExecuteW(NULL, L"open", bat, NULL, g_dir, SW_SHOWNORMAL);
            }
            return 1;
        }
    }

    if (!*pyargs) {                    /* a real interpreter path (not the py launcher): run SDM in this process */
        int rc = host_python(py, script);
        if (rc >= 0) return rc;
    }

    wchar_t *cmd = (wchar_t *)HeapAlloc(GetProcessHeap(), 0, BIG * 2 * sizeof(wchar_t));
    if (!cmd) return 1;
    _snwprintf(cmd, BIG * 2, L"\"%ls\"%ls \"%ls\" %ls", py, pyargs, script, passthrough_args());
    cmd[BIG * 2 - 1] = 0;
    if (!run(cmd)) {
        wchar_t msg[1024];
        _snwprintf(msg, 1024, L"Could not start Python:\n%ls\n\nError %lu. Run install.bat to repair.", py, g_err);
        MessageBoxW(NULL, msg, L"SDM", MB_ICONERROR);
        HeapFree(GetProcessHeap(), 0, cmd);
        return 1;
    }
    HeapFree(GetProcessHeap(), 0, cmd);
    return 0;
}
