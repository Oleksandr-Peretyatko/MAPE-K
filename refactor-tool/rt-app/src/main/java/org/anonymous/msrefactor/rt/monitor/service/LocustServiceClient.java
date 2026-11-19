package org.anonymous.msrefactor.rt.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.anonymous.msrefactor.rt.monitor.web.dto.LocustRunResult;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for the standalone locust-service.
 *
 * <p>Replaces the old subprocess + volume-mount approach. All communication
 * with locust is done over HTTP: triggers runs via REST and receives results
 * via a webhook callback posted to rt-app.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocustServiceClient {

    @Value("${rt.monitor.locust.service-url:http://localhost:8100}")
    private String serviceUrl;

    private final RestTemplate restTemplate;

    /**
     * Triggers a new locust run and returns the remote {@code run_id}.
     *
     * @param iterationId UUID of the current loop iteration
     * @param targetHost  base URL of the application under test
     * @param callbackUrl rt-app endpoint that locust-service will POST results to
     * @param scenarios   ordered list of scenario names (null → locust-service defaults)
     * @return the remote {@code run_id} assigned by the locust-service
     */
    public String startRun(String iterationId, String targetHost, String callbackUrl,
                           List<String> scenarios) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("iteration_id", iterationId);
        body.put("target_host", targetHost);
        body.put("callback_url", callbackUrl);
        if (scenarios != null && !scenarios.isEmpty()) {
            body.put("scenarios", scenarios);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    serviceUrl + "/api/runs", request, Map.class);
            if (response == null || !response.containsKey("run_id")) {
                throw new IllegalStateException("locust-service returned no run_id");
            }
            String runId = (String) response.get("run_id");
            log.info("[locust-client] started run={} for iteration={}", runId, iterationId);
            return runId;
        } catch (RestClientException e) {
            throw new IllegalStateException("Failed to start locust run: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the current status and results for a given {@code run_id}.
     *
     * @return {@link LocustRunResult} or {@code null} if not found / unreachable
     */
    public LocustRunResult getRunResult(String runId) {
        try {
            ResponseEntity<LocustRunResult> resp = restTemplate.getForEntity(
                    serviceUrl + "/api/runs/" + runId, LocustRunResult.class);
            return resp.getStatusCode().is2xxSuccessful() ? resp.getBody() : null;
        } catch (RestClientException e) {
            log.warn("[locust-client] could not fetch run {}: {}", runId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the most recent run for a given iteration UUID from the locust-service DB.
     *
     * @return {@link LocustRunResult} or {@code null} if none found
     */
    public LocustRunResult getRunResultByIteration(String iterationId) {
        try {
            ResponseEntity<LocustRunResult> resp = restTemplate.getForEntity(
                    serviceUrl + "/api/runs/by-iteration/" + iterationId,
                    LocustRunResult.class);
            return resp.getStatusCode().is2xxSuccessful() ? resp.getBody() : null;
        } catch (RestClientException e) {
            log.warn("[locust-client] no run for iteration {}: {}", iterationId, e.getMessage());
            return null;
        }
    }
}
