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
public class Main {

    static final String VERSION = "1.0.0";

    public static void main(String[] args) {

        AsrService service = null;

        try {
            System.out.println("======================================================");
            System.out.println("  Sherpa-ONNX ASR (Vietnamese) - JDK 7 Compatible");
            System.out.println("  Model: sherpa-onnx-zipformer-vi-int8-2025-04-20");
            System.out.println("======================================================");
            System.out.println();

            // Parse args
            String audioPath = "C:\\Users\\0100644068\\Downloads\\sherpa-asr-se7-source\\src\\main\\resources\\test_wavs\\0.wav";
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

            System.out.println("[main] Audio file: " + audioFile.getAbsolutePath());
            System.out.println("[main] File size: " + audioFile.length() + " bytes");
            System.out.println("[main] Threads: " + numThreads + ", Provider: " + provider);
            System.out.println();

            // --- Create service and recognize ---
            System.out.println("[Step 1/2] Initializing ASR Service...");
            long t0 = System.currentTimeMillis();

            service = new AsrService(numThreads, provider);

            System.out.println("[OK] ASR Service initialized ("
                    + (System.currentTimeMillis() - t0) + " ms)");
            System.out.println();

            // --- Recognize ---
            System.out.println("[Step 2/2] Recognizing audio...");
            System.out.println("------------------------------------------------------");

            t0 = System.currentTimeMillis();
            String text = service.recognize(audioFile);
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

            if (service != null) {
                try {
                    service.close();
                } catch (Throwable ignore) {
                }
            }
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