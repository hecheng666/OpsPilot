package com.opspilot.server.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Demo Service 内部接口客户端。
 */
@Component
public class DemoClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${opspilot.demo-base-url}")
    private String baseUrl;

    public String getBaseUrl() {
        return baseUrl;
    }

    public Map<String, Object> getMetrics() {
        return restTemplate.getForObject(baseUrl + "/internal/metrics", Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLogs(long sinceId, int limit) {
        return restTemplate.getForObject(baseUrl + "/internal/logs?sinceId=" + sinceId + "&limit=" + limit, Map.class);
    }

    public List<Map<String, Object>> getChanges() {
        ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                baseUrl + "/internal/changes", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        return resp.getBody();
    }

    public Map<String, Object> getFaultStatus() {
        return restTemplate.getForObject(baseUrl + "/internal/faults/status", Map.class);
    }

    public Map<String, Object> injectFault(Map<String, Object> body) {
        return restTemplate.postForObject(baseUrl + "/internal/faults/inject", body, Map.class);
    }

    public Map<String, Object> recoverFault(Map<String, Object> body) {
        return restTemplate.postForObject(baseUrl + "/internal/faults/recover", body, Map.class);
    }

    public Map<String, Object> executeAction(Map<String, Object> body) {
        return restTemplate.postForObject(baseUrl + "/internal/actions/execute", body, Map.class);
    }

    public boolean healthy() {
        try {
            Map<?, ?> r = restTemplate.getForObject(baseUrl + "/actuator/health", Map.class);
            return r != null && "UP".equals(String.valueOf(r.get("status")));
        } catch (Exception e) {
            return false;
        }
    }
}
