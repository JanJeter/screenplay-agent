import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Dependency-free Java 17+ example; the backend itself targets Java 21.
 * Run: java -Dfile.encoding=UTF-8 examples/java/AgentGatewayClient.java
 * Required environment: AGENT_GATEWAY_TOKEN (the same value configured in Node).
 * Never ship that service token to the browser.
 */
public final class AgentGatewayClient {
    private final URI base;
    private final String serviceToken;
    private final String userId;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public AgentGatewayClient(URI base, String serviceToken, String userId) {
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.serviceToken = requireNonblank(serviceToken, "AGENT_GATEWAY_TOKEN");
        this.userId = requireNonblank(userId, "trusted user ID");
    }

    public String createSession(String projectId, String screenplayId)
            throws IOException, InterruptedException {
        String response = post("agent/sessions", "{\"projectId\":" + json(projectId)
                + ",\"screenplayId\":" + json(screenplayId) + "}");
        return responseUuid(response, "sessionId");
    }

    public String chat(String sessionId, String message, String clientRequestId)
            throws IOException, InterruptedException {
        // Preserve clientRequestId if retrying this POST after an uncertain network failure.
        String response = post("agent/chat", "{\"sessionId\":" + json(sessionId)
                + ",\"message\":" + json(message)
                + ",\"clientRequestId\":" + json(clientRequestId) + "}");
        return responseUuid(response, "runId");
    }

    public void cancelRun(String runId) throws IOException, InterruptedException {
        post("agent/runs/" + pathUuid(runId) + "/cancel", "{}");
    }

    public String getRun(String runId) throws IOException, InterruptedException {
        return jsonResponse(request("agent/runs/" + pathUuid(runId)).GET().build());
    }

    public EventStream openEvents(String runId, String lastEventId)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = request("agent/runs/" + pathUuid(runId) + "/events")
                .setHeader("Accept", "text/event-stream");
        if (lastEventId != null && !lastEventId.isBlank()) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = http.send(request.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Gateway event stream HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
            response.body().close();
            throw new IOException("Gateway did not return text/event-stream");
        }
        return new EventStream(response.body(), lastEventId);
    }

    private HttpRequest.Builder request(String path) {
        // userId must come from Java's authenticated principal, never an incoming browser header.
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + serviceToken)
                .header("X-Agent-User", userId)
                .header("Accept", "application/json");
    }

    private String post(String path, String body) throws IOException, InterruptedException {
        return jsonResponse(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private String jsonResponse(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Do not log response bodies: they may contain screenplay text or provider details.
                throw new IOException("Gateway HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("Gateway response exceeded 64 KiB");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public record SseEvent(String id, String event, String data) {}

    /** Close this handle on browser disconnect; cancelRun is a separate, explicit operation. */
    public static final class EventStream implements AutoCloseable {
        private static final int MAX_FRAME_CHARS = 1_048_576;
        private final InputStream input;
        private final ScheduledExecutorService deadline;
        private String lastEventId;
        private boolean terminal;

        private EventStream(InputStream input, String lastEventId) {
            this.input = input;
            this.lastEventId = lastEventId;
            // Closing the body unblocks a stalled read. HttpRequest.timeout alone does not
            // provide a complete application deadline after ofInputStream has returned.
            deadline = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-deadline");
                thread.setDaemon(true);
                return thread;
            });
            deadline.schedule(() -> {
                try { input.close(); } catch (IOException ignored) { }
            }, 5, TimeUnit.MINUTES);
        }

        public String lastEventId() { return lastEventId; }

        public void consume(Consumer<SseEvent> consumer) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String eventType = "message";
            String eventId = lastEventId;
            StringBuilder data = new StringBuilder();
            int frameSize = 0;
            String line;
            while ((line = readBoundedLine(reader)) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("SSE read interrupted");
                frameSize += line.length();
                if (frameSize > MAX_FRAME_CHARS) throw new IOException("SSE frame too large");
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        data.setLength(data.length() - 1);
                        SseEvent event = new SseEvent(eventId, eventType, data.toString());
                        consumer.accept(event);
                        lastEventId = eventId; // Advance only after successful delivery.
                        terminal = eventType.equals("run.completed") || eventType.equals("run.failed")
                                || eventType.equals("run.cancelled");
                        if (terminal) return;
                    }
                    eventType = "message";
                    data.setLength(0);
                    frameSize = 0;
                    continue;
                }
                if (line.startsWith(":")) continue; // Heartbeat/comment.
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "id" -> { if (value.indexOf('\0') < 0) eventId = value; }
                    case "event" -> eventType = value;
                    case "data" -> data.append(value).append('\n');
                    default -> { } // retry and extension fields are not needed by this example.
                }
            }
            if (!terminal) throw new IOException("SSE ended before a terminal event; reconnect using lastEventId()");
        }

        private static String readBoundedLine(BufferedReader reader) throws IOException {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n') break;
                if (ch == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') reader.reset();
                    break;
                }
                if (line.length() == MAX_FRAME_CHARS) throw new IOException("SSE line too large");
                line.append((char) ch);
            }
            return ch == -1 && line.isEmpty() ? null : line.toString();
        }

        @Override public void close() throws IOException {
            deadline.shutdownNow();
            input.close();
        }
    }

    private static String pathUuid(String value) { return UUID.fromString(value).toString(); }

    private static String responseUuid(String response, String field) throws IOException {
        // Only the example's two UUID response fields. In Spring, bind a record with Jackson instead.
        var matcher = Pattern.compile("\"" + Pattern.quote(field)
                + "\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(response);
        if (!matcher.find()) throw new IOException("Missing UUID response field: " + field);
        try { return pathUuid(matcher.group(1)); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid UUID response field: " + field, e); }
    }

    private static String requireNonblank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
                    else result.append(ch);
                }
            }
        }
        return result.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        String base = System.getenv().getOrDefault("AGENT_GATEWAY_URL", "http://127.0.0.1:3001");
        String userId = System.getenv().getOrDefault("AGENT_USER_ID", "demo-user");
        AgentGatewayClient client = new AgentGatewayClient(URI.create(base),
                System.getenv("AGENT_GATEWAY_TOKEN"), userId);
        String sessionId = client.createSession(args.length > 0 ? args[0] : "demo-project",
                args.length > 1 ? args[1] : "demo-screenplay");
        String requestId = UUID.randomUUID().toString();
        String runId = client.chat(sessionId, args.length > 2 ? args[2] : "请分析第一个场景，并给出改进建议。", requestId);
        System.out.println("sessionId=" + sessionId + " runId=" + runId);
        try (EventStream stream = client.openEvents(runId, null)) {
            stream.consume(event -> System.out.println(event.event() + " " + event.data()));
            // In Spring MVC, forward each event with SseEmitter.event().id(...).name(...).data(...).
            // On disconnect, stream.close(); on user Stop, also client.cancelRun(runId).
        }
    }
}
