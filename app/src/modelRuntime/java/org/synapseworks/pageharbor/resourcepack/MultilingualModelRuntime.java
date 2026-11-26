package org.synapseworks.pageharbor.resourcepack;

import ai.djl.huggingface.tokenizers.jni.TokenizersLibrary;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import android.os.SystemClock;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

public final class MultilingualModelRuntime implements AutoCloseable {
    private static final int VOCAB_SIZE = 128112;
    private static final int FIRST_LANGUAGE_ID = 128004;
    private static final int EOS = 2;
    private static final int UNKNOWN = 3;
    private static final int MAX_OUTPUT_TOKENS = 64;
    private static final long SEGMENT_TIMEOUT_MS = 20_000;

    private final int sourceLanguageId;
    private final int targetLanguageId;
    private final TokenizersLibrary library = TokenizersLibrary.LIB;
    private OrtEnvironment environment;
    private long tokenizer;
    private OrtSession encoder;
    private OrtSession decoder;
    private int[] sourceIds;
    private String[] vocabulary;

    public MultilingualModelRuntime(File root, int sourceLanguageId, int targetLanguageId) throws Exception {
        require(isLanguageId(sourceLanguageId) && isLanguageId(targetLanguageId) && sourceLanguageId != targetLanguageId);
        this.sourceLanguageId = sourceLanguageId;
        this.targetLanguageId = targetLanguageId;
        long start = SystemClock.elapsedRealtime();
        try {
            android.system.Os.setenv("TOKENIZERS_PARALLELISM", "false", true);
            android.system.Os.setenv("RAYON_NUM_THREADS", "2", true);
            System.loadLibrary("djl_tokenizer");
            environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "rme-local-translation");
            environment.setTelemetry(false);
            tokenizer = library.createTokenizerFromString(read(root, "tokenizer-source.json", 16 * 1024 * 1024));
            require(tokenizer != 0);

            JSONArray ids = new JSONArray(read(root, "source-to-model-ids.json", 1024 * 1024));
            require(ids.length() == 128_000);
            sourceIds = new int[ids.length()];
            for (int i = 0; i < ids.length(); i++) {
                sourceIds[i] = ids.getInt(i);
                require(sourceIds[i] >= 0 && sourceIds[i] < VOCAB_SIZE);
            }
            JSONArray pieces = new JSONArray(read(root, "model-vocabulary.json", 2 * 1024 * 1024));
            require(pieces.length() == VOCAB_SIZE);
            vocabulary = new String[VOCAB_SIZE];
            for (int i = 0; i < VOCAB_SIZE; i++) vocabulary[i] = pieces.getString(i);
            require("</s>".equals(vocabulary[EOS]) && "<unk>".equals(vocabulary[UNKNOWN]));

            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.addConfigEntry("mlas.disable_kleidiai", "1");
                options.setIntraOpNumThreads(2);
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                encoder = environment.createSession(new File(root, "encoder_model_quantized.onnx").getPath(), options);
                decoder = environment.createSession(new File(root, "decoder_model_quantized.onnx").getPath(), options);
            }
            require(encoder.getInputNames().equals(Set.of("input_ids", "attention_mask")));
            require(encoder.getOutputNames().contains("last_hidden_state"));
            require(decoder.getInputNames().equals(Set.of("input_ids", "encoder_attention_mask", "encoder_hidden_states")));
            require(decoder.getOutputNames().contains("logits"));
            if (SystemClock.elapsedRealtime() - start > 10_000) throw new IllegalStateException("MODEL_LOAD_LIMIT");
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public boolean fits(String input) throws Exception {
        try {
            encode(input);
            return true;
        } catch (IllegalArgumentException tooLarge) {
            return false;
        }
    }

    public String translate(String input, AtomicBoolean cancelled) throws Exception {
        long started = SystemClock.elapsedRealtime();
        checkWork(cancelled, started);
        long[] ids = encode(input);
        long[] maskValues = new long[ids.length];
        Arrays.fill(maskValues, 1);
        try (OnnxTensor tokens = tensor(ids);
             OnnxTensor mask = tensor(maskValues);
             OrtSession.Result encoded = encoder.run(Map.of("input_ids", tokens, "attention_mask", mask),
                     Set.of("last_hidden_state"))) {
            checkWork(cancelled, started);
            OnnxTensor hidden = (OnnxTensor) encoded.get(0);
            long[] output = new long[MAX_OUTPUT_TOKENS + 2];
            output[0] = EOS;
            output[1] = targetLanguageId;
            StringBuilder translated = new StringBuilder();
            for (int length = 2; length < output.length; length++) {
                checkWork(cancelled, started);
                int next = nextToken(Arrays.copyOf(output, length), hidden, mask);
                checkWork(cancelled, started);
                if (next == EOS) {
                    String result = translated.toString().replace('▁', ' ');
                    if (result.startsWith(" ")) result = result.substring(1);
                    require(!result.isBlank());
                    return result;
                }
                require(next > UNKNOWN && next < FIRST_LANGUAGE_ID);
                output[length] = next;
                translated.append(vocabulary[next]);
            }
            throw new IllegalStateException("MISSING_EOS_OR_OUTPUT_LIMIT");
        }
    }

    private int nextToken(long[] prefix, OnnxTensor hidden, OnnxTensor mask) throws Exception {
        try (OnnxTensor tokens = tensor(prefix);
             OrtSession.Result result = decoder.run(Map.of(
                 "input_ids", tokens,
                 "encoder_hidden_states", hidden,
                 "encoder_attention_mask", mask
             ), Set.of("logits"))) {
            FloatBuffer logits = ((OnnxTensor) result.get(0)).getFloatBuffer();
            require(logits.remaining() == prefix.length * VOCAB_SIZE);
            int offset = (prefix.length - 1) * VOCAB_SIZE;
            float bestScore = -Float.MAX_VALUE;
            int best = -1;
            for (int id = 0; id < VOCAB_SIZE; id++) {
                float score = logits.get(offset + id);
                require(Float.isFinite(score));
                if (score > bestScore) {
                    best = id;
                    bestScore = score;
                }
            }
            require(best >= 0);
            return best;
        }
    }

    private long[] encode(String input) throws Exception {
        if (input.getBytes(StandardCharsets.UTF_8).length > 512) throw new IllegalArgumentException();
        long encoding = library.encode(tokenizer, input, false);
        try {
            long[] ids = library.getTokenIds(encoding);
            if (ids.length == 0 || ids.length + 2 > 96) throw new IllegalArgumentException();
            long[] translated = new long[ids.length + 2];
            translated[0] = sourceLanguageId;
            for (int i = 0; i < ids.length; i++) {
                require(ids[i] >= 0 && ids[i] < sourceIds.length);
                int id = sourceIds[(int) ids[i]];
                if (id <= UNKNOWN || id >= FIRST_LANGUAGE_ID) throw new IllegalArgumentException();
                translated[i + 1] = id;
            }
            translated[translated.length - 1] = EOS;
            return translated;
        } finally {
            library.deleteEncoding(encoding);
        }
    }

    private OnnxTensor tensor(long[] values) throws Exception {
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(values), new long[]{1, values.length});
    }

    private static void checkWork(AtomicBoolean cancelled, long started) {
        if (cancelled.get()) throw new CancellationException();
        if (SystemClock.elapsedRealtime() - started >= SEGMENT_TIMEOUT_MS) {
            throw new IllegalStateException("SEGMENT_TIME_LIMIT");
        }
    }

    private static boolean isLanguageId(int id) {
        return id >= FIRST_LANGUAGE_ID && id < VOCAB_SIZE;
    }

    private static String read(File root, String name, int limit) throws Exception {
        File file = new File(root, name);
        require(file.length() <= limit);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("MULTILINGUAL_MODEL_DECLINED");
    }

    @Override
    public void close() throws Exception {
        try {
            if (decoder != null) decoder.close();
        } finally {
            try {
                if (encoder != null) encoder.close();
            } finally {
                if (tokenizer != 0) library.deleteTokenizer(tokenizer);
                decoder = null;
                encoder = null;
                tokenizer = 0;
                sourceIds = null;
                vocabulary = null;
            }
        }
    }
}
