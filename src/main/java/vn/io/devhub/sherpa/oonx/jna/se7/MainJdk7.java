package vn.io.devhub.sherpa.oonx.jna.se7;

import java.io.File;

/**
 * Entry point - JDK 7 compatible.
 *
 * SU DUNG:
 *   java -jar sherpa-asr-se7-all.jar <file_audio.wav>
 *   java -jar sherpa-asr-se7-all.jar <file_audio.wav> --threads 4 --provider cpu
 *
 * Model Vietnamese (sherpa-onnx-zipformer-vi-int8) da dc embed trong JAR.
 * Nguoi dung chi can truyen file audio la lay duoc text.
 *
 * Ho tro:
 *   - File WAV lon (doc chunked, buffer 8MB)
 *   - WAV 8/16/24/32 bit, mono/stereo
 */
public class MainJdk7 {

    static final String VERSION = "1.0.0";

    public static void main(String[] args) {

        AsrJdk7 asr = null;

        try {
            System.out.println("======================================================");
            System.out.println("  Sherpa-ONNX ASR (Vietnamese) - JDK 7 Compatible");
            System.out.println("  Model: sherpa-onnx-zipformer-vi-int8-2025-04-20");
            System.out.println("======================================================");
            System.out.println();

            // Parse args
            String audioPath = null;
            int numThreads = 2;
            String provider = "cpu";

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("--threads".equals(a) && i + 1 < args.length) {
                    numThreads = Integer.parseInt(args[++i]);
                } else if ("--provider".equals(a) && i + 1 < args.length) {
                    provider = args[++i];
                } else if (!a.startsWith("--") && audioPath == null) {
                    audioPath = a;
                }
            }

            if (audioPath == null) {
                printUsage();
                System.exit(1);
            }

            File audioFile = new File(audioPath);
            if (!audioFile.exists()) {
                System.err.println("ERROR: Audio file not found: " + audioPath);
                System.exit(1);
            }

            System.out.println("[main-se7] Audio file: " + audioFile.getAbsolutePath());
            System.out.println("[main-se7] File size: " + audioFile.length() + " bytes");
            System.out.println("[main-se7] Threads: " + numThreads + ", Provider: " + provider);
            System.out.println();

            // --- Step 1: Extract & load native library ---
            System.out.println("[Step 1/3] Loading native library...");
            long t0 = System.currentTimeMillis();

            File nativeDir = NativeLoaderJdk7.resolve();
            File libPath = NativeLoaderJdk7.findLib(nativeDir);

            if (libPath == null || !libPath.exists()) {
                throw new RuntimeException("Native library not found in: " + nativeDir);
            }

            System.out.println("[main-se7] Native lib: " + libPath.getAbsolutePath());

            AsrLibJdk7 lib = AsrLibLoaderJdk7.load(libPath.getAbsolutePath());
            System.out.println("[OK] Native library loaded ("
                    + (System.currentTimeMillis() - t0) + " ms)");
            System.out.println();

            // --- Step 2: Extract & load model ---
            System.out.println("[Step 2/3] Loading model (embedded)...");
            t0 = System.currentTimeMillis();

            File modelDir = ModelLoaderJdk7.resolve();

            asr = new AsrJdk7(lib, modelDir, numThreads, provider);

            System.out.println("[OK] Model loaded ("
                    + (System.currentTimeMillis() - t0) + " ms)");
            System.out.println();

            // --- Step 3: Recognize ---
            System.out.println("[Step 3/3] Recognizing audio...");
            System.out.println("------------------------------------------------------");

            t0 = System.currentTimeMillis();
            String text;

            // File > 50MB hoac > 5 phut -> dung chunked reader
            long fileSize = audioFile.length();
            if (fileSize > 50 * 1024 * 1024) {
                System.out.println("[main-se7] Large file detected ("
                        + (fileSize / (1024 * 1024)) + " MB), using chunked reader");
                text = recognizeLargeFile(asr, audioPath);
            } else {
                // Thu native WAV reader truoc
                try {
                    text = asr.recognizeWav(audioPath);
                } catch (Throwable e) {
                    System.out.println("[main-se7] Native WAV reader failed: "
                            + e.getMessage());
                    System.out.println("[main-se7] Falling back to Java chunked reader...");
                    text = recognizeLargeFile(asr, audioPath);
                }
            }

            long dt = System.currentTimeMillis() - t0;

            System.out.println("------------------------------------------------------");
            System.out.println("[RESULT] (" + dt + " ms)");
            System.out.println();
            System.out.println(text);
            System.out.println();
            System.out.println("======================================================");

        } catch (Throwable e) {

            System.err.println();
            System.err.println("============== ERROR ==============");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.err.println("===================================");

            System.exit(1);

        } finally {

            if (asr != null) {
                try {
                    asr.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Doc WAV file bang Java reader (ho tro file lon, buffer 8MB),
     * chuyen sang float[] va gui sang native lib.
     */
    static String recognizeLargeFile(AsrJdk7 asr, String wavPath) {
        try {
            float[] samples = AsrJdk7.readAllSamplesFromFile(wavPath);
            System.out.println("[main-se7] Read " + samples.length + " float samples");
            return asr.recognizeFloat(samples);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to read/process audio: " + e.getMessage(), e);
        }
    }

    static void printUsage() {
        System.err.println("Sherpa-ONNX ASR (Vietnamese) v" + VERSION);
        System.err.println();
        System.err.println("Usage:");
        System.err.println("  java -jar sherpa-asr-se7-all.jar <audio.wav>");
        System.err.println("  java -jar sherpa-asr-se7-all.jar <audio.wav> --threads 4 --provider cpu");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --threads N     So luong thread (default: 2)");
        System.err.println("  --provider P    Provider: cpu, cuda (default: cpu)");
        System.err.println();
        System.err.println("Model (embedded): sherpa-onnx-zipformer-vi-int8-2025-04-20");
        System.err.println("Supports: WAV 8/16/24/32-bit, mono/stereo, large files");
    }
}