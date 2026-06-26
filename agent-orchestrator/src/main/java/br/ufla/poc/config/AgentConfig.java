package br.ufla.poc.config;

import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
@Slf4j
public class AgentConfig {

    @Value("${agent.ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${agent.ollama.model}")
    private String ollamaModel;

    @Value("${agent.ollama.temperature:0.1}")
    private double temperature;

    @Value("${agent.ollama.top-p:0.9}")
    private double topP;

    @Value("${agent.ollama.seed:42}")
    private int seed;

    @Value("${agent.ollama.timeout-seconds:300}")
    private int timeoutSeconds;

    @Bean
    public OllamaChatModel ollamaChatModel() {
        // LangChain4j 0.32 OllamaClient usa OkHttp com Retrofit.
        // O Retrofit resolve caminhos relativos (ex: /api/chat) contra a baseUrl,
        // e exige que ela termine com '/' para não descartar o path e cair no localhost.
        String url = ollamaBaseUrl.endsWith("/") ? ollamaBaseUrl : ollamaBaseUrl + "/";

        log.info("[CONFIG] OllamaChatModel baseUrl={} model={} temperature={} seed={}",
                 url, ollamaModel, temperature, seed);

        return OllamaChatModel.builder()
            .baseUrl(url)
            .modelName(ollamaModel)
            .temperature(temperature)
            .topP(topP)
            .seed(seed)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .numPredict(2048)
            .numCtx(8192)
            .build();
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }
}
