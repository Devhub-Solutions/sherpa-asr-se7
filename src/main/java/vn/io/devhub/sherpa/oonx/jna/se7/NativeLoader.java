package vn.io.devhub.sherpa.oonx.jna.se7;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Native binary loader cho ASR - JDK 7 compatible.
 * Ho tro 3 che do tim kiem:
 *   1. ClassLoader getResources (JAR / Eclipse with resources on classpath)
 *   2. Filesystem fallback (Eclipse: tim tu working directory / project root)
 *   3. Extract from JAR (fat JAR chay tu command line)
 */
public final class NativeLoader {

    private static final String NATIVE_BASE = "native";

    private static volatile File resolvedDir;

    private NativeLoader() { }

    public static String platformKey() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");

        String os;
        if (osName.contains("Windows")) {
            os = "win32";
        } else if (osName.contains("Mac")) {
            os = "darwin";
        } else {
            os = "linux";
        }

        String arch;
        if ("amd64".equals(osArch) || "x86_64".equals(osArch)) {
            arch = "x86_64";
        } else if ("aarch64".equals(osArch)) {
            arch = "aarch64";
        } else {
            arch = osArch;
        }

        return os + "-" + arch;
    }

    public static synchronized File resolve() throws IOException {
        if (resolvedDir != null) {
            return resolvedDir;
        }

        String platform = platformKey();
        String resDir = NATIVE_BASE + "/" + platform;
        ClassLoader cl = NativeLoader.class.getClassLoader();

        System.out.println("[NativeLoader-ASR-se7] Platform: " + platform);

        // === Strategy 1: ClassLoader getResources (JAR / Eclipse classpath) ===
        resolvedDir = resolveFromClassLoader(cl, resDir, platform);

        // === Strategy 2: Filesystem fallback (Eclipse working directory) ===
        if (resolvedDir == null) {
            resolvedDir = resolveFromFilesystem(resDir);
        }

        // === Strategy 3: Extract from JAR ===
        if (resolvedDir == null) {
            resolvedDir = resolveFromJar(cl, resDir, platform);
        }

        if (resolvedDir == null) {
            throw new IOException(
                "Native resources not found for '" + resDir + "'.\n"
                + "Searched:\n"
                + "  - ClassLoader resources\n"
                + "  - Filesystem (current dir + src/main/resources)\n"
                + "  - JAR entries\n");
        }

        return resolvedDir;
    }

    /**
     * Strategy 1: Tim qua ClassLoader (hoat dong khi resources nam tren classpath).
     */
    private static File resolveFromClassLoader(ClassLoader cl, String resDir, String platform) {
        try {
            Enumeration<URL> resources = cl.getResources(resDir);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    File fsPath;
                    try {
                        fsPath = new File(url.toURI());
                    } catch (URISyntaxException e) {
                        // Fallback: dung getPath() thay vi toURI()
                        fsPath = new File(url.getPath());
                    }
                    if (fsPath.isDirectory() && findLibSafe(fsPath) != null) {
                        System.out.println("[NativeLoader-ASR-se7] Found via ClassLoader: " + fsPath);
                        return fsPath;
                    }
                }
            }
        } catch (IOException ignore) {
        }
        return null;
    }

    /**
     * Strategy 2: Tim tren filesystem.
     * Eclipse chay voi working directory la project root,
     * nen tim o:
     *   ./src/main/resources/native/<platform>/
     *   ./native/<platform>/
     *   ./bin/native/<platform>/
     */
    private static File resolveFromFilesystem(String resDir) {
        String[] candidates = {
            "src/main/resources/" + resDir,
            resDir,
            "bin/" + resDir,
            "target/classes/" + resDir
        };

        for (int i = 0; i < candidates.length; i++) {
            File dir = new File(candidates[i]);
            System.out.println("[NativeLoader-ASR-se7] Checking filesystem: " + candidates[i]
                + " -> " + (dir.isDirectory() ? "EXISTS" : "not found"));
            if (dir.isDirectory() && findLibSafe(dir) != null) {
                System.out.println("[NativeLoader-ASR-se7] Found via filesystem: " + dir.getAbsolutePath());
                return dir;
            }
        }

        // Thu tim tu thu muc chua class file cua chinh class nay
        try {
            String classResource = NativeLoader.class.getName().replace('.', '/') + ".class";
            URL classUrl = NativeLoader.class.getClassLoader().getResource(classResource);
            if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                String classFilePath = classUrl.getPath();
                // classFilePath: /path/to/bin/vn/io/devhub/.../NativeLoaderJdk7.class
                // Can tim: /path/to/bin/native/<platform>/ hoac /path/to/src/main/resources/native/<platform>/
                File classFile = new File(classFilePath);
                File outputDir = classFile.getParentFile();
                // Leo len: NativeLoaderJdk7.class -> se7 -> jna -> oonx -> sherpa -> devhub -> io -> vn -> (output root)
                for (int depth = 0; depth < 10; depth++) {
                    if (outputDir == null) break;
                    File nativeDir = new File(outputDir, resDir);
                    if (nativeDir.isDirectory() && findLibSafe(nativeDir) != null) {
                        System.out.println("[NativeLoader-ASR-se7] Found via class location: " + nativeDir);
                        return nativeDir;
                    }
                    outputDir = outputDir.getParentFile();
                }

                // Thu cung cap output root, tim src/main/resources
                // bin/ -> project root -> src/main/resources/native/...
                File binDir = classFile.getParentFile();
                for (int depth = 0; depth < 10; depth++) {
                    if (binDir == null) break;
                    File srcRes = new File(binDir, "src/main/resources/" + resDir);
                    if (srcRes.isDirectory() && findLibSafe(srcRes) != null) {
                        System.out.println("[NativeLoader-ASR-se7] Found via src/main/resources: " + srcRes);
                        return srcRes;
                    }
                    binDir = binDir.getParentFile();
                }
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    /**
     * Strategy 3: Extract tu JAR (fat JAR chay tu command line).
     */
    private static File resolveFromJar(ClassLoader cl, String resDir, String platform) throws IOException {
        File temp = createTempDir("sherpa-asr-se7-" + platform);

        boolean extracted = false;
        Enumeration<URL> resources = cl.getResources(resDir);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            if ("jar".equals(url.getProtocol())) {
                extractFromJar(url, temp);
                extracted = true;
            }
        }

        if (extracted && findLibSafe(temp) != null) {
            System.out.println("[NativeLoader-ASR-se7] Extracted from JAR to: " + temp);
            deleteRecursivelyOnShutdown(temp);
            return temp;
        }

        return null;
    }

    public static File findLib(File dir) throws IOException {
        File f = findLibSafe(dir);
        if (f != null) return f;
        throw new IOException("Native lib not found in " + dir);
    }

    static File findLibSafe(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.contains("sherpa_onnx_jna")
                    && (n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) {
                    return f;
                }
            }
        }
        return null;
    }

    // =================================================================

    private static File createTempDir(String prefix) throws IOException {
        File tmp = File.createTempFile(prefix, "");
        tmp.delete();
        tmp.mkdirs();
        return tmp;
    }

    private static void copyStream(InputStream is, File dest) throws IOException {
        FileOutputStream fos = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        } finally {
            fos.close();
        }
    }

    private static void extractFromJar(URL jarUrl, File targetDir) throws IOException {
        JarURLConnection jarConn = (JarURLConnection) jarUrl.openConnection();
        String entryPrefix = jarConn.getEntryName();
        if (!entryPrefix.endsWith("/")) {
            entryPrefix = entryPrefix + "/";
        }

        JarFile jarFile = jarConn.getJarFile();
        try {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(entryPrefix) && !entry.isDirectory()) {
                    String relPath = name.substring(entryPrefix.length());
                    File dest = new File(targetDir, relPath.replace('\\', '/'));
                    dest.getParentFile().mkdirs();
                    InputStream is = jarFile.getInputStream(entry);
                    try {
                        copyStream(is, dest);
                    } finally {
                        is.close();
                    }
                }
            }
        } finally {
            jarFile.close();
        }
    }

    private static void deleteRecursivelyOnShutdown(final File dir) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                deleteRecursive(dir);
            }
        });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        f.delete();
    }
}