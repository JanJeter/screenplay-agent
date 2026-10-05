package com.urke.saasbackendstarter.screenplay.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.service.AgentRunService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Typed, fixed-origin client for the Node Gateway; browser credentials never reach it. */
@Component
public class AgentGatewayClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final URI baseUri;
    private final String gatewayToken;

    public AgentGatewayClient(ObjectMapper json,
                              @Value("${agent.gateway.url:http://127.0.0.1:3001}") String baseUrl,
                              @Value("${agent.gateway.token:}") String gatewayToken) {
        this.json = json;
        URI parsed = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme())) {
            throw new IllegalStateException("agent.gateway.url must use HTTP(S)");
        }
        this.baseUri = parsed;
        this.gatewayToken = gatewayToken;
    }

    public String createSession(AgentRunService.GatewayDispatch dispatch) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("projectId", Long.toString(dispatch.projectId()));
        payload.put("screenplayId", Long.toString(dispatch.screenplayId()));
        payload.put("applicationSessionId", dispatch.applicationSessionId());
        payload.put("applicationRunId", dispatch.applicationRunId());
        payload.put("executionToken", dispatch.executionToken());
        payload.put("taskType", dispatch.taskType());
        return post("agent/sessions", dispatch.userId(), payload, HttpStatus.CREATED).path("sessionId").asText();
    }

    public String submit(String gatewaySessionId, String message, String requestId, Long userId) {
        Map<String, String> payload = Map.of("sessionId", gatewaySessionId, "message", message, "clientRequestId", requestId);
        return post("agent/chat", userId, payload, HttpStatus.ACCEPTED).path("runId").asText();
    }

    public void cancel(String gatewayRunId, Long userId) {
        post("agent/runs/" + gatewayRunId + "/cancel", userId, Map.of(), HttpStatus.ACCEPTED);
    }

    /** Blocks until the Gateway closes its SSE response and emits parsed public events. */
    public void streamEvents(String gatewayRunId, Long userId, long after, Consumer<GatewayEvent> consumer) throws IOException, InterruptedException {
        HttpRequest request = request("agent/runs/" + gatewayRunId + "/events?after=" + Math.max(0, after), userId)
                .timeout(Duration.ofSeconds(135)).header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.util.stream.Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) throw gatewayFailure(response.statusCode());
        try (var lines = response.body()) {
            String id = null, type = null, data = null;
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                String line = iterator.next();
                if (line.startsWith("id: ")) id = line.substring(4);
                else if (line.startsWith("event: ")) type = line.substring(7);
                else if (line.startsWith("data: ")) data = line.substring(6);
                else if (line.isEmpty() && type != null && data != null) {
                    consumer.accept(new GatewayEvent(id, type, json.readTree(data)));
                    id = null; type = null; data = null;
                }
            }
        }
    }

    private JsonNode post(String path, Long userId, Object payload, HttpStatus expected) {
        try {
            HttpRequest request = request(path, userId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected.value()) throw gatewayFailure(response.statusCode());
            return json.readTree(response.body());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent gateway unavailable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent gateway request interrupted", ex);
        }
    }

    private HttpRequest.Builder request(String path, Long userId) {
        if (gatewayToken == null || gatewayToken.length() < 16) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent gateway is not configured");
        }
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + gatewayToken)
                .header("X-Agent-User", Long.toString(userId));
    }

    private ResponseStatusException gatewayFailure(int status) {
        return new ResponseStatusException(status == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
                "Agent gateway returned HTTP " + status);
    }

    public record GatewayEvent(String id, String type, JsonNode data) { }
}
