package vn.io.devhub.sherpa.oonx.jna.se7;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Native library loader cho ASR - JDK 7 compatible.
 *
 * JNA 4.5.2: Khi Native.loadLibrary() nhan duong dan day du (path),
 * no trich xuat ten file va tim kiem theo chuan cua platform.
 * Nhung tren Windows, JNA co the gap loi khi load DLL tu duong dan tuyet doi
 * vi LoadLibraryEx khong tim thay cac DLL phu thuoc (onnxruntime.dll, ...).
 *
 * Cach fix (JNA 4.5.2 compatible):
 *   1. Dung AddDllDirectory de dang ky thu muc chua DLL (Windows only).
 *   2. Dung NativeLibrary.addSearchPath() de JNA biet tim o dau.
 *   3. Dung Native.loadLibrary(name, interface, opts) - name KHONG co extension.
 *
 * Ten library can chinh xac:
 *   - Windows: file = libsherpa_onnx_jna.dll -> name = "libsherpa_onnx_jna"
 *   - Linux:   file = libsherpa_onnx_jna.so -> name = "sherpa_onnx_jna"
 *              (JNA tu dong them "lib" prefix tren Linux)
 */
public final class AsrLibLoaderJdk7 {

    private AsrLibLoaderJdk7() {
    }

    /**
     * Load ASR native library.
     *
     * @param libPath Duong dan tuyet doi den file DLL/SO
     * @return AsrLibJdk7 interface instance
     */
    public static AsrLibJdk7 load(String libPath) {

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

        // === Windows: Dang ky DLL search directory ===
        if (isWindows) {
            registerDllDirectory(libDir);
        }

        // === Xac dinh ten library cho JNA ===
        String fileName = libFile.getName();
        String jnaLibName = extractJnaLibraryName(fileName, isWindows, isLinux);

        System.out.println("[JNA-se7] File: " + libFile.getAbsolutePath());
        System.out.println("[JNA-se7] Dir:  " + libDir.getAbsolutePath());
        System.out.println("[JNA-se7] JNA library name: " + jnaLibName);
        System.out.println("[JNA-se7] Platform: "
                + (isWindows ? "Windows" : (isLinux ? "Linux" : "Other")));

        Map opts = new HashMap();
        opts.put(Library.OPTION_STRING_ENCODING, "UTF-8");

        // === Dang ky search path cho JNA ===
        // NativeLibrary.addSearchPath(name, path) la API chinh thuc cua JNA 4.x
        // JNA se tim thu vien trong thu muc nay truoc khi tim o he thong
        System.out.println("[JNA-se7] Registering search path: "
                + jnaLibName + " -> " + libDir.getAbsolutePath());
        NativeLibrary.addSearchPath(jnaLibName, libDir.getAbsolutePath());

        // === Load library qua JNA ===
        System.out.println("[JNA-se7] Loading via Native.loadLibrary("
                + jnaLibName + ", ...)");
        try {
            AsrLibJdk7 result = (AsrLibJdk7)
                    Native.loadLibrary(jnaLibName, AsrLibJdk7.class, opts);
            System.out.println("[JNA-se7] Library loaded successfully");
            return result;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JNA-se7] ERROR loading library: " + e.getMessage());
            System.err.println("[JNA-se7] File exists: " + libFile.exists()
                    + ", can read: " + libFile.canRead()
                    + ", size: " + libFile.length() + " bytes");

            // In ra thong tin debug
            System.err.println("[JNA-se7] JNA search paths for '"
                    + jnaLibName + "':");
            try {
                java.lang.reflect.Field searchPaths =
                        NativeLibrary.class.getDeclaredField("searchPaths");
                searchPaths.setAccessible(true);
                java.util.Map sp = (java.util.Map) searchPaths.get(null);
                java.util.Iterator it = sp.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry entry = (java.util.Map.Entry) it.next();
                    System.err.println("[JNA-se7]   "
                            + entry.getKey() + " = " + entry.getValue());
                }
            } catch (Exception ignore) {
                System.err.println("[JNA-se7]   (could not read search paths)");
            }

            throw e;
        }
    }

    /**
     * Trich xuat ten library phu hop voi JNA 4.5.2.
     *
     * JNA 4.x xu ly ten library khac nhau tren Windows va Linux:
     *   - Windows: JNA tim <name>.dll (KHONG them prefix)
     *   - Linux:   JNA tim lib<name>.so (TU DONG them "lib" prefix)
     *
     * @param fileName  Ten file (vd: libsherpa_onnx_jna.dll)
     * @param isWindows
     * @param isLinux
     * @return Ten library cho JNA
     */
    static String extractJnaLibraryName(String fileName,
                                         boolean isWindows,
                                         boolean isLinux) {
        // Cat extension (.dll, .so, .dylib)
        String name = fileName;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) {
            name = name.substring(0, dotIdx);
        }

        // Tren Linux: JNA tu them "lib" prefix, nen phai cat "lib" di
        // vd: libsherpa_onnx_jna.so -> sherpa_onnx_jna (JNA se tim libsherpa_onnx_jna.so)
        if (isLinux && name.startsWith("lib")) {
            name = name.substring(3);
        }

        // Tren Windows: JNA KHONG them prefix, giu nguyen ten
        // vd: libsherpa_onnx_jna.dll -> libsherpa_onnx_jna (JNA se tim libsherpa_onnx_jna.dll)

        return name;
    }

    /**
     * Dang ky thu muc chua DLL voi Windows (SetDefaultDllDirectories + AddDllDirectory).
     * Dieu nay cho phep DLL phu thuoc (onnxruntime.dll, vcruntime140.dll, ...)
     * duoc tim thay khi load libsherpa_onnx_jna.dll.
     */
    public static void registerDllDirectory(File dir) {
        try {
            AsrLibJdk7.Kernel32Ext k32 =
                    (AsrLibJdk7.Kernel32Ext)
                            Native.loadLibrary(
                                    "kernel32",
                                    AsrLibJdk7.Kernel32Ext.class
                            );

            // LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000
            // Gioi han DLL search toi: app dir, system dir, user dir,
            // va cac dir duoc them qua AddDllDirectory
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
            System.out.println(
                    "[JNA-se7] DLL dependencies may not be found. "
                            + "Make sure onnxruntime.dll is in the same directory."
            );
        }
    }
}