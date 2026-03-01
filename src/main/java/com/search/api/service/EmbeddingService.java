package com.search.api.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2";

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    @PostConstruct
    public void init() {
        try {
            log.info("Loading embedding model: {}", MODEL_NAME);
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + MODEL_NAME)
                    .optTranslator(new SentenceTranslator())
                    .optProgress(new ProgressBar())
                    .build();

            model = criteria.loadModel();
            predictor = model.newPredictor();
            log.info("Embedding model loaded successfully");
        } catch (Exception e) {
            log.warn("Could not load DJL model ({}). Vector search will be disabled.", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }

    public float[] embed(String text) {
        if (predictor == null) return null;
        try {
            return predictor.predict(text);
        } catch (Exception e) {
            log.warn("Embedding failed for query '{}': {}", text, e.getMessage());
            return null;
        }
    }

    public boolean isAvailable() {
        return predictor != null;
    }

    // Sentence embedding translator: mean pooling over token embeddings
    private static class SentenceTranslator implements Translator<String, float[]> {

        private HuggingFaceTokenizer tokenizer;

        @Override
        public void prepare(TranslatorContext ctx) throws Exception {
            tokenizer = HuggingFaceTokenizer.newInstance(
                    "sentence-transformers/all-MiniLM-L6-v2",
                    Map.of("padding", "true", "truncation", "true", "maxLength", "128"));
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
            NDManager manager = ctx.getNDManager();
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            return new NDList(
                    manager.create(inputIds).reshape(1, inputIds.length),
                    manager.create(attentionMask).reshape(1, attentionMask.length));
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // last hidden state: [1, seq_len, hidden_size]
            NDArray tokenEmbeddings = list.get(0);
            // mean pooling
            NDArray meanPooled = tokenEmbeddings.mean(new int[]{1}).squeeze();
            // L2 normalize
            NDArray norm = meanPooled.norm().clip(1e-9f, Float.MAX_VALUE);
            meanPooled = meanPooled.div(norm);
            return meanPooled.toFloatArray();
        }
    }
}
