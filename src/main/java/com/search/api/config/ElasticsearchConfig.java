package com.search.api.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Value("${elasticsearch.password:changeme}")
    private String password;

    @Value("${elasticsearch.scheme:http}")
    private String scheme;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        log.info("Connecting to Elasticsearch at {}://{}:{}", scheme, host, port);

        var credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password)
        );

        var restClient = RestClient.builder(new HttpHost(host, port, scheme))
                .setHttpClientConfigCallback(httpClientBuilder ->
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
                .setRequestConfigCallback(cfg -> cfg
                        .setConnectTimeout(5000)
                        .setSocketTimeout(30000))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(mapper));

        log.info("Elasticsearch client initialized");
        return new ElasticsearchClient(transport);
    }
}
