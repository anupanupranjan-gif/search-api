package com.search.api.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class SentenceEmbeddingTranslator implements Translator<String[], float[][]> {

    private static final Logger log = LoggerFactory.getLogger(SentenceEmbeddingTranslator.class);
    private HuggingFaceTokenizer tokenizer;

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        tokenizer = HuggingFaceTokenizer.newInstance(
                ctx.getModel().getModelPath(),
                Map.of("padding", "true", "truncation", "true", "maxLength", "256")
        );
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String[] inputs) {
        Encoding[] encodings = tokenizer.batchEncode(inputs);
        NDManager manager = ctx.getNDManager();

        long[][] inputIds       = new long[encodings.length][];
        long[][] attentionMasks = new long[encodings.length][];

        for (int i = 0; i < encodings.length; i++) {
            inputIds[i]       = encodings[i].getIds();
            attentionMasks[i] = encodings[i].getAttentionMask();
        }

        return new NDList(
                manager.create(inputIds),
                manager.create(attentionMasks)
        );
    }

    @Override
    public float[][] processOutput(TranslatorContext ctx, NDList list) {
        // Model returns pooler_output as first tensor: [batch, 384] - already pooled
        NDArray embeddings = list.get(0);

        // L2 normalize
        NDArray norm       = embeddings.norm(new int[]{1}, true).clip(1e-12f, Float.MAX_VALUE);
        NDArray normalized = embeddings.div(norm);

        float[] flat    = normalized.toFloatArray();
        int batchSize   = (int) normalized.getShape().get(0);
        int dims        = (int) normalized.getShape().get(1);

        float[][] result = new float[batchSize][dims];
        for (int i = 0; i < batchSize; i++) {
            System.arraycopy(flat, i * dims, result[i], 0, dims);
        }
        return result;
    }

    @Override
    public Batchifier getBatchifier() { return null; }
}
