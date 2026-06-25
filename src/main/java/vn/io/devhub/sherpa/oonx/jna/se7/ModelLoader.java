package vn.io.devhub.sherpa.oonx.jna.se7;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/**
 * Model loader - extract model files tu resources/JAR ra temp dir.
 * JDK 7 compatible: khong dung lambda, stream, diamond.
 *
 * Ho tro 3 che do (giong NativeLoaderJdk7):
 *   1. ClassLoader getResource (Eclipse with resources on classpath)
 *   2. Filesystem fallback (Eclipse: tim tu working dir)
 *   3. Extract from JAR (fat JAR)
 *
 * Model: csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20
 */
public final class ModelLoader {

    private static final String MODEL_RESOURCE_DIR = "model";

    private static final String[] MODEL_FILES = {
        "encoder-epoch-12-avg-8.int8.onnx",
        "decoder-epoch-12-avg-8.onnx",
        "joiner-epoch-12-avg-8.int8.onnx",
        "tokens.txt",
        "bpe.model"
    };

    private static volatile File resolvedModelDir;

    private ModelLoader() {
    }

    /**
     * Resolve model directory.
     */
    public static synchronized File resolve() throws IOException {
        if (resolvedModelDir != null && resolvedModelDir.exists()) {
            return resolvedModelDir;
        }

        ClassLoader cl = ModelLoader.class.getClassLoader();

        // === Strategy 1: ClassLoader resource (filesystem URL) ===
        resolvedModelDir = resolveFromClassLoader(cl);

        // === Strategy 2: Filesystem fallback ===
        if (resolvedModelDir == null) {
            resolvedModelDir = resolveFromFilesystem();
        }

        // === Strategy 3: Extract from JAR ===
        if (resolvedModelDir == null) {
            resolvedModelDir = resolveFromJar(cl);
        }

        if (resolvedModelDir == null) {
            throw new IOException(
                "Model files not found.\n"
                + "Searched:\n"
                + "  - ClassLoader resources\n"
                + "  - src/main/resources/model/\n"
                + "  - model/ (current dir)\n"
                + "  - JAR entries\n");
        }

        return resolvedModelDir;
    }

    /**
     * Strategy 1: ClassLoader getResource.
     */
    private static File resolveFromClassLoader(ClassLoader cl) {
        try {
            URL resUrl = cl.getResource(MODEL_RESOURCE_DIR);
            if (resUrl != null && "file".equals(resUrl.getProtocol())) {
                File fsDir;
                try {
                    fsDir = new File(resUrl.toURI());
                } catch (URISyntaxException e) {
                    fsDir = new File(resUrl.getPath());
                }
                if (fsDir.isDirectory() && validateModelDir(fsDir)) {
                    System.out.println("[ModelLoader-se7] Found via ClassLoader: " + fsDir);
                    return fsDir;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * Strategy 2: Filesystem fallback (Eclipse working directory).
     */
    private static File resolveFromFilesystem() {
        String[] candidates = {
            "src/main/resources/model",
            "model",
            "bin/model",
            "target/classes/model"
        };

        for (int i = 0; i < candidates.length; i++) {
            File dir = new File(candidates[i]);
            System.out.println("[ModelLoader-se7] Checking filesystem: " + candidates[i]
                + " -> " + (dir.isDirectory() ? "EXISTS" : "not found"));
            if (dir.isDirectory() && validateModelDir(dir)) {
                System.out.println("[ModelLoader-se7] Found via filesystem: " + dir.getAbsolutePath());
                return dir;
            }
        }

        // Thu tim tu class location (giong NativeLoaderJdk7)
        try {
            String classResource = ModelLoader.class.getName().replace('.', '/') + ".class";
            URL classUrl = ModelLoader.class.getClassLoader().getResource(classResource);
            if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                File classFile = new File(classUrl.getPath());
                File outputDir = classFile.getParentFile();
                for (int depth = 0; depth < 10; depth++) {
                    if (outputDir == null) break;
                    // Try outputDir/model directly
                    File modelDir = new File(outputDir, MODEL_RESOURCE_DIR);
                    if (modelDir.isDirectory() && validateModelDir(modelDir)) {
                        System.out.println("[ModelLoader-se7] Found via class location (output): " + modelDir);
                        return modelDir;
                    }
                    // Try parent/src/main/resources/model
                    File srcModel = new File(outputDir, "src/main/resources/" + MODEL_RESOURCE_DIR);
                    if (srcModel.isDirectory() && validateModelDir(srcModel)) {
                        System.out.println("[ModelLoader-se7] Found via class location (src): " + srcModel);
                        return srcModel;
                    }
                    outputDir = outputDir.getParentFile();
                }
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    /**
     * Strategy 3: Extract from JAR.
     */
    private static File resolveFromJar(ClassLoader cl) throws IOException {
        File tempDir = createTempDir("sherpa-model-vi-");
        boolean found = false;

        // Thu lay resource URL de biet JAR path
        String testRes = MODEL_RESOURCE_DIR + "/" + MODEL_FILES[0];
        URL resUrl = cl.getResource(testRes);

        if (resUrl != null && "jar".equals(resUrl.getProtocol())) {
            // Extract tu JAR
            String jarPath = resUrl.getPath();
            // jarPath: file:/path/to/app.jar!/model/encoder...
            int bangIdx = jarPath.indexOf('!');
            if (bangIdx > 0) {
                String filePath = jarPath.substring(5, bangIdx); // remove "file:"
                JarFile jarFile = new JarFile(filePath);
                try {
                    java.util.Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(MODEL_RESOURCE_DIR + "/") && !entry.isDirectory()) {
                            File dest = new File(tempDir, name.substring(MODEL_RESOURCE_DIR.length() + 1));
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
                if (validateModelDir(tempDir)) {
                    found = true;
                }
            }
        }

        // Fallback: thu getResourceAsStream tung file
        if (!found) {
            for (int i = 0; i < MODEL_FILES.length; i++) {
                String modelFile = MODEL_RESOURCE_DIR + "/" + MODEL_FILES[i];
                InputStream is = cl.getResourceAsStream(modelFile);
                if (is != null) {
                    File dest = new File(tempDir, MODEL_FILES[i]);
                    dest.getParentFile().mkdirs();
                    copyStream(is, dest);
                    is.close();
                    System.out.println("[ModelLoader-se7] Extracted: " + MODEL_FILES[i]
                        + " (" + dest.length() + " bytes)");
                    found = true;
                }
            }
        }

        if (found && validateModelDir(tempDir)) {
            System.out.println("[ModelLoader-se7] Extracted model to: " + tempDir);
            deleteOnShutdown(tempDir);
            return tempDir;
        }

        return null;
    }

    /**
     * Kiem tra thu muc co chua day du model files.
     */
    static boolean validateModelDir(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        for (int i = 0; i < MODEL_FILES.length; i++) {
            if (!new File(dir, MODEL_FILES[i]).isFile()) {
                return false;
            }
        }
        return true;
    }

    public static String getModelPath(String fileName) throws IOException {
        File dir = resolve();
        File f = new File(dir, fileName);
        if (!f.exists()) {
            throw new IOException("Model file not found: " + f.getAbsolutePath());
        }
        return f.getAbsolutePath();
    }

    private static File createTempDir(String prefix) throws IOException {
        File tmp = File.createTempFile(prefix, "");
        tmp.delete();
        if (!tmp.mkdirs()) {
            throw new IOException("Cannot create temp dir: " + tmp);
        }
        return tmp;
    }

    private static void copyStream(InputStream is, File dest) throws IOException {
        OutputStream os = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
        } finally {
            os.close();
        }
    }

    private static void deleteOnShutdown(final File dir) {
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