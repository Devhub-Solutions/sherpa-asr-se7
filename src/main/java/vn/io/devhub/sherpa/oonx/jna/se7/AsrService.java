package vn.io.devhub.sherpa.oonx.jna.se7;

import java.io.File;
import java.io.IOException;

/**
 * ASR Service - cung cap public API don gian de nhan dien audio.
 *
 * Cach su dung:
 * <pre>
 *   AsrService service = new AsrService();
 *   String text = service.recognize(new File("audio.wav"));
 *   service.close();
 * </pre>
 *
 * Hoac su dung singleton pattern:
 * <pre>
 *   AsrService service = AsrService.getInstance();
 *   String text = service.recognize(new File("audio.wav"));
 *   // Khong can close - de JVM shutdown hook xu ly
 * </pre>
 *
 * JDK 7 compatible: khong dung lambda, stream, diamond operator.
 */
public class AsrService {

    private static final int DEFAULT_NUM_THREADS = 2;
    private static final String DEFAULT_PROVIDER = "cpu";

    private static volatile AsrService instance;

    private Asr asr;
    private boolean initialized = false;

    /**
     * Tao AsrService voi mac dinh (2 threads, CPU provider).
     * Auto-init khi lan dau goi recognize().
     */
    public AsrService() {
    }

    /**
     * Tao AsrService voi cau hinh tuy chinh.
     *
     * @param numThreads so luong thread (0 = auto detect)
     * @param provider   "cpu" hoac "cuda"
     */
    public AsrService(int numThreads, String provider) {
        init(numThreads, provider);
    }

    /**
     * Lay singleton instance. Thread-safe double-checked locking (JDK 7).
     * Se tu dong init voi cau hinh mac dinh.
     *
     * @return AsrService instance
     */
    public static AsrService getInstance() {
        if (instance == null) {
            synchronized (AsrService.class) {
                if (instance == null) {
                    instance = new AsrService();
                }
            }
        }
        return instance;
    }

    /**
     * Lay singleton instance voi cau hinh tuy chinh.
     *
     * @param numThreads so luong thread
     * @param provider   "cpu" hoac "cuda"
     * @return AsrService instance
     */
    public static AsrService getInstance(int numThreads, String provider) {
        if (instance == null) {
            synchronized (AsrService.class) {
                if (instance == null) {
                    instance = new AsrService(numThreads, provider);
                }
            }
        }
        return instance;
    }

    /**
     * Khoi tao ASR engine. Tu dong goi neu chua init.
     */
    private synchronized void ensureInit() {
        if (!initialized) {
            init(DEFAULT_NUM_THREADS, DEFAULT_PROVIDER);
        }
    }

    /**
     * Khoi tao ASR engine: load native lib + model.
     *
     * @param numThreads so luong thread
     * @param provider   "cpu" hoac "cuda"
     * @throws RuntimeException neu init that bai
     */
    public synchronized void init(int numThreads, String provider) {
        if (initialized) {
            return;
        }

        System.out.println("[AsrService] Initializing ASR engine...");
        long t0 = System.currentTimeMillis();

        try {
            // Step 1: Load native library
            System.out.println("[AsrService] Step 1/2: Loading native library...");
            File nativeDir = NativeLoader.resolve();
            File libPath = NativeLoader.findLib(nativeDir);

            if (libPath == null || !libPath.exists()) {
                throw new RuntimeException(
                    "Native library not found in: " + nativeDir);
            }

            System.out.println("[AsrService] Native lib: "
                + libPath.getAbsolutePath());

            AsrLib lib = AsrLibLoader.load(libPath.getAbsolutePath());
            System.out.println("[AsrService] Native library loaded ("
                + (System.currentTimeMillis() - t0) + " ms)");

            // Step 2: Load model
            System.out.println("[AsrService] Step 2/2: Loading model...");
            long t1 = System.currentTimeMillis();

            File modelDir = ModelLoader.resolve();
            this.asr = new Asr(lib, modelDir, numThreads, provider);

            System.out.println("[AsrService] Model loaded ("
                + (System.currentTimeMillis() - t1) + " ms)");

            this.initialized = true;
            System.out.println("[AsrService] ASR engine ready (total: "
                + (System.currentTimeMillis() - t0) + " ms)");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ASR: " + e.getMessage(), e);
        }
    }

    /**
     * Nhan dien audio file va tra ve text.
     *
     * Ho tro:
     *   - File WAV lon (tu dong chon phuong phap phu hop)
     *   - WAV 8/16/24/32 bit, mono/stereo
     *   - Auto-resample va mixdown sang mono 16kHz
     *
     * @param audioFile file audio (WAV)
     * @return text nhan dien duoc (Vietnamese)
     * @throws RuntimeException neu file khong ton tai hoac nhan dien that bai
     * @throws IOException      neu doc file loi
     */
    public String recognize(File audioFile) throws IOException {
        if (audioFile == null) {
            throw new IllegalArgumentException("audioFile must not be null");
        }
        if (!audioFile.exists()) {
            throw new IllegalArgumentException(
                "Audio file not found: " + audioFile.getAbsolutePath());
        }
        if (!audioFile.isFile()) {
            throw new IllegalArgumentException(
                "Not a regular file: " + audioFile.getAbsolutePath());
        }

        ensureInit();

        String wavPath = audioFile.getAbsolutePath();
        long fileSize = audioFile.length();

        System.out.println("[AsrService] Recognizing: " + wavPath
            + " (" + formatFileSize(fileSize) + ")");

        // Chon phuong phap phu hop
        if (fileSize > 50 * 1024 * 1024) {
            // File > 50MB: dung Java chunked reader
            return recognizeLarge(wavPath);
        } else {
            // File nho/trung binh: thu native WAV reader truoc
            return recognizeWithFallback(wavPath);
        }
    }

    /**
     * Nhan dien audio tu duong dan file.
     *
     * @param wavPath duong dan tuyet doi den file WAV
     * @return text nhan dien duoc
     * @throws RuntimeException neu nhan dien that bai
     * @throws IOException      neu doc file loi
     */
    public String recognize(String wavPath) throws IOException {
        return recognize(new File(wavPath));
    }

    /**
     * Nhan dien tu float array (cho integration voi external audio source).
     * Audio phai la 16kHz mono float.
     *
     * @param samples float array (16kHz mono)
     * @return text nhan dien duoc
     */
    public String recognizeFloat(float[] samples) {
        ensureInit();
        return asr.recognizeFloat(samples);
    }

    /**
     * Release native resources. Goi khi khong con su dung service nua.
     * Neu dung singleton (getInstance()), khong can goi - shutdown hook
     * se tu dong xu ly.
     */
    public synchronized void close() {
        if (asr != null) {
            try {
                asr.close();
                System.out.println("[AsrService] ASR engine closed");
            } catch (Throwable ignore) {
            }
            asr = null;
            initialized = false;
        }
        // Clear singleton reference
        if (instance == this) {
            instance = null;
        }
    }

    /**
     * Kiem tra service da duoc khoi tao chua.
     *
     * @return true neu ASR engine da san sang
     */
    public boolean isInitialized() {
        return initialized;
    }

    // =================================================================
    //  Internal methods
    // =================================================================

    /**
     * Nhan dien voi fallback: thu native WAV reader, neu loi thi chuyen
     * sang Java chunked reader.
     */
    private String recognizeWithFallback(String wavPath) {
        try {
            return asr.recognizeWav(wavPath);
        } catch (Throwable e) {
            System.out.println("[AsrService] Native WAV reader failed: "
                + e.getMessage());
            System.out.println("[AsrService] Falling back to Java reader...");
            try {
                return recognizeLarge(wavPath);
            } catch (IOException ex) {
                throw new RuntimeException(
                    "Recognition failed (both native and Java reader): "
                    + ex.getMessage(), ex);
            }
        }
    }

    /**
     * Nhan dien file lon bang Java WAV reader.
     */
    private String recognizeLarge(String wavPath) throws IOException {
        float[] samples = Asr.readAllSamplesFromFile(wavPath);
        System.out.println("[AsrService] Read " + samples.length
            + " float samples");
        return asr.recognizeFloat(samples);
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        } else {
            return (bytes / (1024 * 1024)) + " MB";
        }
    }
}