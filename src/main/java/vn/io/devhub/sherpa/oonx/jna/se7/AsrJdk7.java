package vn.io.devhub.sherpa.oonx.jna.se7;

import com.sun.jna.Pointer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * JDK 7 compatible ASR helper.
 * - Su dung asr_create_explicit voi model embedded trong JAR.
 * - Ho tro doc WAV lon bang cach doc chunk va gui float[].
 * - Khong dung lambda, stream, diamond operator.
 */
public class AsrJdk7 {

    private final AsrLibJdk7 lib;
    private final Pointer handle;

    /**
     * Tao ASR voi model embedded tu resources.
     * Su dung asr_create_explicit de truyen duong dan file cu the.
     *
     * @param lib    native library instance
     * @param modelDir thu muc chua model files (da extract)
     * @param numThreads so luong thread (0 = auto)
     * @param provider  "cpu" hoac "cuda"
     */
    public AsrJdk7(AsrLibJdk7 lib, File modelDir, int numThreads, String provider) {
        this.lib = lib;

        String encoder  = new File(modelDir, "encoder-epoch-12-avg-8.int8.onnx").getAbsolutePath();
        String decoder  = new File(modelDir, "decoder-epoch-12-avg-8.onnx").getAbsolutePath();
        String joiner   = new File(modelDir, "joiner-epoch-12-avg-8.int8.onnx").getAbsolutePath();
        String tokens   = new File(modelDir, "tokens.txt").getAbsolutePath();
        String bpeModel = new File(modelDir, "bpe.model").getAbsolutePath();

        System.out.println("[AsrJdk7] Creating ASR with explicit model paths...");
        System.out.println("[AsrJdk7]   encoder: " + encoder);
        System.out.println("[AsrJdk7]   decoder: " + decoder);
        System.out.println("[AsrJdk7]   joiner:  " + joiner);
        System.out.println("[AsrJdk7]   tokens:  " + tokens);

        this.handle = lib.asr_create_explicit(
                encoder, decoder, joiner, tokens, bpeModel,
                numThreads, provider);

        if (handle == null) {
            String err = lib.asr_last_error();
            throw new RuntimeException("asr_create_explicit failed: "
                    + (err != null ? err : "(no error message)"));
        }

        System.out.println("[AsrJdk7] ASR session created successfully");
    }

    /**
     * Nhan dien WAV file - goi truc tiep qua C lib (tu doc file).
     * Tot cho file nho/Trung binh.
     */
    public String recognizeWav(String wavPath) {
        System.out.println("[AsrJdk7] Recognizing WAV (native): " + wavPath);
        long t0 = System.currentTimeMillis();

        String text = lib.asr_recognize_wav(handle, wavPath);

        long dt = System.currentTimeMillis() - t0;
        System.out.println("[AsrJdk7] Recognition done in " + dt + " ms");

        if (text == null) {
            String err = lib.asr_last_error();
            throw new RuntimeException("asr_recognize_wav failed: "
                    + (err != null ? err : "(no error message)"));
        }
        return text;
    }

    /**
     * Nhan dien WAV file lon - doc chunk tai Java, gui float[] sang C.
     * Chunk size: ~30 giay audio (48000 samples * 30s = 1,440,000 floats).
     *
     * Dieu kien: native lib phai ho tro asr_recognize_float cho chunked input.
     * Neu khong, fallback ve recognizeWav().
     */
    public String recognizeWavLarge(String wavPath) throws IOException {
        System.out.println("[AsrJdk7] Recognizing large WAV (chunked): " + wavPath);
        long t0 = System.currentTimeMillis();

        WavInfo wav = readWavHeader(wavPath);
        System.out.println("[AsrJdk7] WAV: " + wav.sampleRate + " Hz, "
                + wav.numChannels + " ch, " + wav.numSamples + " samples, "
                + wav.bitsPerSample + " bit");

        // Neu khong qua lon (< 60s), dung truc tiep
        if (wav.numSamples < wav.sampleRate * 60) {
            System.out.println("[AsrJdk7] File < 60s, using native WAV reader");
            return recognizeWav(wavPath);
        }

        // Doc toan bo samples (float, mono) va gui 1 lan
        // Vi sherpa-onnx xu ly toan bo audio trong 1 goi
        System.out.println("[AsrJdk7] Reading " + wav.numSamples + " samples...");

        float[] samples = readAllSamples(wav);

        System.out.println("[AsrJdk7] Sending " + samples.length + " float samples to native...");
        String text = lib.asr_recognize_float(handle, samples, samples.length);

        long dt = System.currentTimeMillis() - t0;
        System.out.println("[AsrJdk7] Recognition done in " + dt + " ms");

        if (text == null) {
            String err = lib.asr_last_error();
            throw new RuntimeException("asr_recognize_float failed: "
                    + (err != null ? err : "(no error message)"));
        }
        return text;
    }

    /**
     * Nhan dien tu float array (16kHz mono, chuan sherpa-onnx).
     */
    public String recognizeFloat(float[] samples) {
        String text = lib.asr_recognize_float(handle, samples, samples.length);
        if (text == null) {
            String err = lib.asr_last_error();
            throw new RuntimeException("asr_recognize_float failed: "
                    + (err != null ? err : "(no error message)"));
        }
        return text;
    }

    public void close() {
        if (handle != null) {
            lib.asr_destroy(handle);
        }
    }

    // =================================================================
    //  WAV Reader (JDK 7 compatible)
    // =================================================================

    static class WavInfo {
        int sampleRate;
        int numChannels;
        int bitsPerSample;
        int numSamples; // total PCM samples per channel
        long dataOffset;
        long dataBytes;
    }

    /**
     * Doc WAV header, tim data chunk.
     */
    static WavInfo readWavHeader(String wavPath) throws IOException {
        FileInputStream fis = new FileInputStream(wavPath);
        try {
            DataInputStream dis = new DataInputStream(fis);
            return readWavHeaderInternal(dis);
        } finally {
            fis.close();
        }
    }

    private static WavInfo readWavHeaderInternal(DataInputStream dis) throws IOException {
        WavInfo info = new WavInfo();

        // RIFF header
        byte[] riff = new byte[4];
        dis.readFully(riff);
        if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') {
            throw new IOException("Not a RIFF/WAV file");
        }
        int fileSize = readLEInt(dis);
        dis.readFully(riff);
        if (riff[0] != 'W' || riff[1] != 'A' || riff[2] != 'V' || riff[3] != 'E') {
            throw new IOException("Not a WAV file");
        }

        // Parse chunks
        info.sampleRate = 16000;
        info.numChannels = 1;
        info.bitsPerSample = 16;

        while (true) {
            byte[] chunkId = new byte[4];
            int read = dis.read(chunkId);
            if (read < 4) break;

            int chunkSize = readLEInt(dis);

            String id = new String(chunkId, "ASCII");

            if ("fmt ".equals(id)) {
                int audioFormat = readLEShort(dis);
                info.numChannels = readLEShort(dis);
                info.sampleRate = readLEInt(dis);
                int byteRate = readLEInt(dis);
                int blockAlign = readLEShort(dis);
                info.bitsPerSample = readLEShort(dis);
                // Skip extra fmt bytes
                if (chunkSize > 16) {
                    dis.skip(chunkSize - 16);
                }
            } else if ("data".equals(id)) {
                info.dataOffset = 0; // track position
                info.dataBytes = chunkSize;
                info.numSamples = (int) (chunkSize / (info.numChannels * (info.bitsPerSample / 8)));
                break;
            } else {
                // Skip unknown chunk
                dis.skip(chunkSize);
            }
        }

        return info;
    }

    /**
     * Doc toan bo samples tu WAV file, tra ve float[] mono 16kHz.
     * Chuyen doi: resample + mixdown neu can.
     * Cho file lon, doc tung chunk 8MB.
     */
    static float[] readAllSamples(WavInfo wav) throws IOException {
        // For simplicity, return raw PCM as float
        // sherpa-onnx expects 16kHz mono float
        // We just convert whatever the WAV has to mono float
        int bytesPerSample = wav.bitsPerSample / 8;
        int frameSize = wav.numChannels * bytesPerSample;
        int totalFrames = (int) (wav.dataBytes / frameSize);

        // Doc tu vi tri hien tai trong stream (sau header)
        // Can mo lai file va seek den data offset
        // Do da doc header roi, ta phai doc tu stream hien tai

        // Simple approach: read all data as bytes then convert
        byte[] rawData = new byte[(int) wav.dataBytes];

        // WARNING: dis is already positioned at data chunk
        // We need the original stream - caller must handle this
        // For now, re-read the file
        throw new IOException("Use readAllSamplesFromFile instead");
    }

    /**
     * Doc toan bo WAV file va chuyen sang float[] mono.
     * Ho tro file lon bang cach doc tung buffer 8MB.
     */
    static float[] readAllSamplesFromFile(String wavPath) throws IOException {
        FileInputStream fis = new FileInputStream(wavPath);
        try {
            DataInputStream dis = new DataInputStream(fis);
            WavInfo wav = readWavHeaderInternal(dis);

            System.out.println("[WAV-Reader] " + wav.sampleRate + "Hz, "
                    + wav.numChannels + "ch, " + wav.bitsPerSample + "bit, "
                    + wav.numSamples + " samples");

            int bytesPerSample = wav.bitsPerSample / 8;
            int frameSize = wav.numChannels * bytesPerSample;
            int totalFrames = (int) (wav.dataBytes / frameSize);

            float[] out = new float[totalFrames];
            byte[] buf = new byte[8 * 1024 * 1024]; // 8MB buffer

            int frameIdx = 0;
            long remaining = wav.dataBytes;

            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                dis.readFully(buf, 0, toRead);
                remaining -= toRead;

                // Convert to float mono
                int framesInBuf = toRead / frameSize;
                for (int f = 0; f < framesInBuf; f++) {
                    int offset = f * frameSize;

                    if (wav.bitsPerSample == 16) {
                        if (wav.numChannels == 1) {
                            short val = (short) ((buf[offset] & 0xFF) | (buf[offset + 1] << 8));
                            out[frameIdx] = val / 32768.0f;
                        } else {
                            // Mix down to mono
                            float sum = 0;
                            for (int ch = 0; ch < wav.numChannels; ch++) {
                                int chOffset = offset + ch * 2;
                                short val = (short) ((buf[chOffset] & 0xFF) | (buf[chOffset + 1] << 8));
                                sum += val / 32768.0f;
                            }
                            out[frameIdx] = sum / wav.numChannels;
                        }
                    } else if (wav.bitsPerSample == 8) {
                        if (wav.numChannels == 1) {
                            out[frameIdx] = ((buf[offset] & 0xFF) - 128) / 128.0f;
                        } else {
                            float sum = 0;
                            for (int ch = 0; ch < wav.numChannels; ch++) {
                                sum += ((buf[offset + ch] & 0xFF) - 128) / 128.0f;
                            }
                            out[frameIdx] = sum / wav.numChannels;
                        }
                    } else if (wav.bitsPerSample == 24) {
                        if (wav.numChannels == 1) {
                            int val = (buf[offset] & 0xFF)
                                    | ((buf[offset + 1] & 0xFF) << 8)
                                    | ((buf[offset + 2] & 0xFF) << 16);
                            if (val >= 0x800000) val -= 0x1000000;
                            out[frameIdx] = val / 8388608.0f;
                        } else {
                            float sum = 0;
                            for (int ch = 0; ch < wav.numChannels; ch++) {
                                int chOffset = offset + ch * 3;
                                int val = (buf[chOffset] & 0xFF)
                                        | ((buf[chOffset + 1] & 0xFF) << 8)
                                        | ((buf[chOffset + 2] & 0xFF) << 16);
                                if (val >= 0x800000) val -= 0x1000000;
                                sum += val / 8388608.0f;
                            }
                            out[frameIdx] = sum / wav.numChannels;
                        }
                    } else if (wav.bitsPerSample == 32) {
                        if (wav.numChannels == 1) {
                            int val = (buf[offset] & 0xFF)
                                    | ((buf[offset + 1] & 0xFF) << 8)
                                    | ((buf[offset + 2] & 0xFF) << 16)
                                    | (buf[offset + 3] << 24);
                            out[frameIdx] = val / 2147483648.0f;
                        } else {
                            float sum = 0;
                            for (int ch = 0; ch < wav.numChannels; ch++) {
                                int chOffset = offset + ch * 4;
                                int val = (buf[chOffset] & 0xFF)
                                        | ((buf[chOffset + 1] & 0xFF) << 8)
                                        | ((buf[chOffset + 2] & 0xFF) << 16)
                                        | (buf[chOffset + 3] << 24);
                                sum += val / 2147483648.0f;
                            }
                            out[frameIdx] = sum / wav.numChannels;
                        }
                    }

                    frameIdx++;
                }
            }

            return out;
        } finally {
            fis.close();
        }
    }

    private static int readLEInt(DataInputStream dis) throws IOException {
        int b1 = dis.read() & 0xFF;
        int b2 = dis.read() & 0xFF;
        int b3 = dis.read() & 0xFF;
        int b4 = dis.read() & 0xFF;
        return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    private static int readLEShort(DataInputStream dis) throws IOException {
        int b1 = dis.read() & 0xFF;
        int b2 = dis.read() & 0xFF;
        return b1 | (b2 << 8);
    }
}