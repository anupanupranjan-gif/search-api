package com.search.api.config;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import com.search.api.embedding.SentenceEmbeddingTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public ZooModel<String[], float[][]> embeddingModel() throws Exception {
        log.info("Loading all-MiniLM-L6-v2 embedding model...");

        Criteria<String[], float[][]> criteria = Criteria.builder()
                .setTypes(String[].class, float[][].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")
                .optEngine("PyTorch")
                .optTranslator(new SentenceEmbeddingTranslator())
                .optProgress(new ProgressBar())
                .build();

        ZooModel<String[], float[][]> model = criteria.loadModel();
        log.info("Embedding model loaded");
        return model;
    }

    @Bean
    public Predictor<String[], float[][]> embeddingPredictor(ZooModel<String[], float[][]> embeddingModel) {
        return embeddingModel.newPredictor();
    }
}
