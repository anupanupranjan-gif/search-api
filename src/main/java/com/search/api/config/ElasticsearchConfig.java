package com.search.api.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

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
    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Bean
    public ElasticsearchClient elasticsearchClient() throws Exception {
        log.info("Connecting to Elasticsearch at {}://{}:{}", scheme, host, port);

        var credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password)
        );

        var restClientBuilder = RestClient.builder(new HttpHost(host, port, scheme))
                .setRequestConfigCallback(requestConfigBuilder ->
                        requestConfigBuilder
                                .setConnectTimeout(5000)
                                .setSocketTimeout(60000));

        if ("https".equalsIgnoreCase(scheme)) {
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chains, authType) -> true)
                    .build();
            SSLIOSessionStrategy sslStrategy = new SSLIOSessionStrategy(
                    sslContext, NoopHostnameVerifier.INSTANCE);
            restClientBuilder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder
                            .setDefaultCredentialsProvider(credentialsProvider)
                            .setSSLStrategy(sslStrategy));
        } else {
            restClientBuilder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        var restClient = restClientBuilder.build();

        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        log.info("Elasticsearch client initialized");
        return new ElasticsearchClient(transport);
    }
}
