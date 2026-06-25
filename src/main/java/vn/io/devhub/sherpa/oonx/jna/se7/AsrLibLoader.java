package vn.io.devhub.sherpa.oonx.jna.se7;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Native library loader cho ASR - JDK 7 compatible.
 *
 * JNA 4.5.2 bug: khi chay tu JAR, Native.loadLibrary() luon check
 * resource path trong JAR truoc, du da truyen absolute path.
 *
 * Fix: Dung System.load() de preload TAT CA .so/.dll files trong thu muc
 * (theo thu tu dependency), roi tao JNA proxy.
 */
public final class AsrLibLoader {

    private AsrLibLoader() {
    }

    /**
     * Load ASR native library.
     *
     * @param libPath Duong dan tuyet doi den file DLL/SO
     * @return AsrLib interface instance
     */
    public static AsrLib load(String libPath) {

        if (libPath == null || !(new File(libPath)).exists()) {
            throw new IllegalArgumentException(
                    "Native lib not found: " + libPath);
        }

        File libFile = new File(libPath).getAbsoluteFile();
        File libDir = libFile.getParentFile();

        boolean isWindows =
                System.getProperty("os.name", "")
                        .toLowerCase(Locale.ENGLISH)
                        .contains("win");

        boolean isLinux =
                System.getProperty("os.name", "")
                        .toLowerCase(Locale.ENGLISH)
                        .contains("linux");

        System.out.println("[JNA-se7] File: " + libFile.getAbsolutePath());
        System.out.println("[JNA-se7] Dir:  " + libDir.getAbsolutePath());
        System.out.println("[JNA-se7] Platform: "
                + (isWindows ? "Windows" : (isLinux ? "Linux" : "Other")));

        Map opts = new HashMap();
        opts.put(Library.OPTION_STRING_ENCODING, "UTF-8");

        // === Windows: Dang ky DLL search directory ===
        if (isWindows) {
            registerDllDirectory(libDir);
        }

        // === Strategy 1: System.load() cho TAT CA libs + JNA proxy ===
        System.out.println("[JNA-se7] Strategy 1: Preload all natives via System.load()");
        try {
            preloadAllNativeLibs(libDir, libFile.getName(), isWindows, isLinux);

            // Sau khi preload, JNA cache se tim thay library
            String jnaLibName = extractJnaLibraryName(
                    libFile.getName(), isWindows, isLinux);
            System.out.println("[JNA-se7] Creating JNA proxy for: " + jnaLibName);

            AsrLib result = (AsrLib)
                    Native.loadLibrary(jnaLibName, AsrLib.class, opts);
            System.out.println("[JNA-se7] Library loaded successfully");
            return result;
        } catch (Throwable e1) {
            System.out.println("[JNA-se7] Strategy 1 failed: " + e1.getMessage());
        }

        // === Strategy 2: Search path + name ===
        System.out.println("[JNA-se7] Strategy 2: Search path + name");
        String jnaLibName = extractJnaLibraryName(
                libFile.getName(), isWindows, isLinux);
        NativeLibrary.addSearchPath(jnaLibName, libDir.getAbsolutePath());
        try {
            AsrLib result = (AsrLib)
                    Native.loadLibrary(jnaLibName, AsrLib.class, opts);
            System.out.println("[JNA-se7] Library loaded via search path");
            return result;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JNA-se7] ALL STRATEGIES FAILED");
            System.err.println("[JNA-se7] Last error: " + e.getMessage());
            System.err.println("[JNA-se7] File exists: " + libFile.exists()
                    + ", can read: " + libFile.canRead()
                    + ", size: " + libFile.length() + " bytes");
            throw e;
        }
    }

    /**
     * Preload TAT CA native libraries trong thu muc theo thu tu dependency.
     * tren Linux: load onnxruntime -> sherpa-onnx-c-api -> sherpa-onnx-cxx-api
     *             -> sherpa_onnx_jna
     * tren Windows: AddDllDirectory da xu ly, chi can load thu muc
     */
    static void preloadAllNativeLibs(File libDir, String mainLibName,
                                     boolean isWindows, boolean isLinux)
            throws Throwable {
        File[] files = libDir.listFiles();
        if (files == null || files.length == 0) {
            throw new RuntimeException("No files in: " + libDir);
        }

        // Collect all native library files
        ArrayList<File> libs = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (isLinux && n.endsWith(".so")) {
                libs.add(f);
            } else if (isWindows && n.endsWith(".dll")) {
                libs.add(f);
            }
        }

        // Sort by dependency order:
        // 1. onnxruntime (no deps)
        // 2. sherpa-onnx-c-api (depends on onnxruntime)
        // 3. sherpa-onnx-cxx-api (depends on sherpa-onnx-c-api, onnxruntime)
        // 4. libsherpa_onnx_jna (depends on sherpa-onnx-c-api, onnxruntime)
        // 5. Everything else (system DLLs on Windows)
        final String[] ORDER_LINUX = {
            "libonnxruntime.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so",
            "libsherpa_onnx_jna.so"
        };
        final String[] ORDER_WIN = {
            "onnxruntime.dll",
            "onnxruntime_providers_shared.dll",
            "sherpa-onnx-c-api.dll",
            "sherpa-onnx-cxx-api.dll",
            "libsherpa_onnx_jna.dll"
        };

        final String[] order = isLinux ? ORDER_LINUX : ORDER_WIN;

        // Sort libs: ordered first, then the rest
        final ArrayList<File> sorted = new ArrayList();
        final boolean[] loaded = new boolean[libs.size()];

        // First pass: load in dependency order
        for (int o = 0; o < order.length; o++) {
            String target = order[o].toLowerCase(Locale.ROOT);
            for (int i = 0; i < libs.size(); i++) {
                if (loaded[i]) continue;
                if (libs.get(i).getName().toLowerCase(Locale.ROOT).equals(target)) {
                    sorted.add(libs.get(i));
                    loaded[i] = true;
                    break;
                }
            }
        }

        // Second pass: add remaining libs
        for (int i = 0; i < libs.size(); i++) {
            if (!loaded[i]) {
                sorted.add(libs.get(i));
            }
        }

        // Load each library
        for (int i = 0; i < sorted.size(); i++) {
            File lib = sorted.get(i);
            try {
                System.load(lib.getAbsolutePath());
                System.out.println("[JNA-se7]   Loaded: " + lib.getName());
            } catch (UnsatisfiedLinkError e) {
                // Some system DLLs on Windows may fail, that's OK
                // if it's the main lib, rethrow
                if (lib.getName().equalsIgnoreCase(mainLibName)) {
                    throw e;
                }
                System.out.println("[JNA-se7]   Skipped: " + lib.getName()
                    + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * Trich xuat ten library phu hop voi JNA 4.5.2.
     */
    static String extractJnaLibraryName(String fileName,
                                         boolean isWindows,
                                         boolean isLinux) {
        String name = fileName;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) {
            name = name.substring(0, dotIdx);
        }
        if (isLinux && name.startsWith("lib")) {
            name = name.substring(3);
        }
        return name;
    }

    /**
     * Dang ky thu muc chua DLL voi Windows.
     */
    public static void registerDllDirectory(File dir) {
        try {
            AsrLib.Kernel32Ext k32 =
                    (AsrLib.Kernel32Ext)
                            Native.loadLibrary(
                                    "kernel32",
                                    AsrLib.Kernel32Ext.class
                            );

            k32.SetDefaultDllDirectories(0x00001000);

            Pointer cookie =
                    k32.AddDllDirectory(
                            new WString(dir.getAbsolutePath())
                    );

            System.out.println(
                    "[JNA-se7] Windows: SetDefaultDllDirectories + AddDllDirectory("
                            + dir.getAbsolutePath() + ") -> "
                            + (cookie != null)
            );

        } catch (Throwable e) {
            System.out.println(
                    "[JNA-se7] WARNING: AddDllDirectory failed: "
                            + e.getMessage()
            );
        }
    }
}