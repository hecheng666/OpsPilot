package com.opspilot.server.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 客户端：embedding（bge-m3）与 chat（qwen2.5，P2 使用）。
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${opspilot.ollama-base-url}")
    private String baseUrl;

    @Value("${opspilot.embed-model}")
    private String embedModel;

    @Value("${opspilot.chat-model}")
    private String chatModel;

    public String getEmbedModel() {
        return embedModel;
    }

    public String getChatModel() {
        return chatModel;
    }

    /** 文本转向量；失败返回 null（调用方降级为纯关键词检索） */
    public List<Double> embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("model", embedModel, "input", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("embed 失败 status={} body={}", resp.statusCode(), resp.body());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode arr = root.path("embeddings").path(0);
            List<Double> vec = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                vec.add(n.asDouble());
            }
            return vec.isEmpty() ? null : vec;
        } catch (Exception e) {
            log.warn("embed 异常: {}", e.getMessage());
            return null;
        }
    }

    /** 判断 embed 模型是否可用 */
    public boolean embedAvailable() {
        return embed("ping") != null;
    }

    /** chat 响应：content + token 统计 */
    public record ChatResp(String content, int promptTokens, int completionTokens) {}

    /** 对话生成；失败返回 null（调用方走降级） */
    public ChatResp chat(String systemPrompt, String userPrompt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", chatModel,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)),
                    "stream", false,
                    "format", "json",
                    "options", Map.of("temperature", 0.2, "num_ctx", 8192, "num_predict", 512)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("chat 失败 status={} body={}", resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("message").path("content").asText();
            if (content.isBlank()) {
                return null;
            }
            return new ChatResp(content,
                    root.path("prompt_eval_count").asInt(0),
                    root.path("eval_count").asInt(0));
        } catch (Exception e) {
            log.warn("chat 异常: {}", e.getMessage());
            return null;
        }
    }

    /** 判断 chat 模型是否已安装（查 /api/tags，不触发推理） */
    public boolean chatAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return false;
            }
            for (JsonNode m : objectMapper.readTree(resp.body()).path("models")) {
                String name = m.path("name").asText();
                if (name.equals(chatModel) || name.startsWith(chatModel + ":")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 余弦相似度 */
    public static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
