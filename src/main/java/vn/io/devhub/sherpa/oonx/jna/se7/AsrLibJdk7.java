package vn.io.devhub.sherpa.oonx.jna.se7;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

public interface AsrLibJdk7 extends Library {

    public interface Kernel32Ext extends Library {
        boolean SetDefaultDllDirectories(int directoryFlags);
        Pointer AddDllDirectory(WString lpPathName);
    }

    Pointer asr_create(String modelDir,
                       String tokensPath,
                       int numThreads,
                       String provider);

    Pointer asr_create_explicit(String encoder,
                                String decoder,
                                String joiner,
                                String tokens,
                                String paraformer,
                                int numThreads,
                                String provider);

    void asr_destroy(Pointer handle);

    String asr_recognize_wav(Pointer handle, String wavPath);

    String asr_recognize_float(Pointer handle,
                               float[] samples,
                               int numSamples);

    String asr_recognize_int16(Pointer handle,
                               short[] samples,
                               int numSamples);

    String asr_last_error();
}