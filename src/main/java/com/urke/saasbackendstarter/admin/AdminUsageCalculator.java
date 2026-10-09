package com.urke.saasbackendstarter.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Uses persisted observations, never the current provider configuration or token-price guesses. */
@Component
@RequiredArgsConstructor
public class AdminUsageCalculator {
    public static final Set<String> EVENT_TYPES = Set.of("run.started", "usage", "provider.request_started", "provider.request_completed", "provider.request_failed");
    private final ObjectMapper json;

    public AdminDtos.Usage calculate(List<AgentEvent> events) {
        String mode = "unknown";
        Map<Long, JsonNode> requests = new LinkedHashMap<>();
        Set<Long> eventSequences = new HashSet<>();
        long genericInput = 0, genericOutput = 0, genericTotal = 0;
        boolean genericObserved = false, genericIncomplete = false, malformed = false;
        for (AgentEvent event : events) {
            if (!eventSequences.add(event.getSequence())) continue;
            JsonNode data;
            try { data = json.readTree(event.getPayload()).path("data"); }
            catch (Exception ex) { malformed = true; continue; }
            if ("run.started".equals(event.getType())) {
                String recordedMode = data.path("mode").asText();
                if (Set.of("live", "mock").contains(recordedMode)) mode = recordedMode;
            } else if (event.getType().startsWith("provider.request_")) {
                if (!data.path("sequence").canConvertToLong() || data.path("sequence").longValue() < 1) { malformed = true; continue; }
                long sequence = data.path("sequence").longValue();
                if (!event.getType().equals("provider.request_started") || !requests.containsKey(sequence)) requests.put(sequence, data);
            } else if ("usage".equals(event.getType())) {
                Long nonCached = count(data, "input"), out = count(data, "output"), all = count(data, "totalTokens");
                if (nonCached == null || out == null || all == null || all < out || all - out < nonCached) {
                    genericIncomplete = true;
                    continue;
                }
                // Pi records total = input + output + cacheRead + cacheWrite;
                // reasoning is already a subset of output. Older Gateway usage
                // events omitted the cache split, but their recorded total minus
                // output still gives the exact inclusive input count. Do not
                // invent separate cache-read/write observations or a price.
                long inclusiveInput = all - out;
                if (data.has("cacheRead") || data.has("cacheWrite")) {
                    Long read = count(data, "cacheRead"), write = count(data, "cacheWrite");
                    long cached = inclusiveInput - nonCached;
                    if (read == null || write == null || read > cached || write != cached - read) {
                        genericIncomplete = true;
                        continue;
                    }
                }
                genericInput += inclusiveInput; genericOutput += out; genericTotal += all;
                genericObserved = true;
            }
        }
        if (!requests.isEmpty()) mode = "live";
        boolean completeGenericUsage = genericObserved && !genericIncomplete;
        if (requests.isEmpty()) return new AdminDtos.Usage(mode, 0, completeGenericUsage ? genericInput : null,
                completeGenericUsage ? genericOutput : null, completeGenericUsage ? genericTotal : null, null,
                "mock".equals(mode) ? "not_applicable" : "unavailable");
        long input = 0, output = 0, total = 0, priced = 0, observed = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean conservative = false;
        for (JsonNode request : requests.values()) {
            JsonNode usage = request.path("actualUsage");
            Long in = count(usage, "input"), out = count(usage, "output"), all = count(usage, "totalTokens");
            if (in != null && out != null && all != null && all > 0) {
                long cachedRead = Optional.ofNullable(count(usage, "cacheRead")).orElse(0L);
                long cachedWrite = Optional.ofNullable(count(usage, "cacheWrite")).orElse(0L);
                if (in + out + cachedRead + cachedWrite == all) {
                    input += in + cachedRead + cachedWrite; output += out; total += all; observed++;
                }
            }
            JsonNode amount = request.path("chargedCostUsd");
            if (amount.isNumber() && amount.decimalValue().signum() >= 0) {
                cost = cost.add(amount.decimalValue()); priced++;
                conservative |= !request.path("actualCostUsd").isNumber();
            }
        }
        String status = priced == 0 ? "unavailable" : priced == requests.size() && observed == requests.size() && !conservative && !malformed ? "estimated" : "partial";
        // Incomplete usage must not appear to be a complete run token count.
        boolean completeUsage = observed == requests.size();
        return new AdminDtos.Usage(mode, requests.size(), completeUsage ? input : null, completeUsage ? output : null,
                completeUsage ? total : null, priced == 0 ? null : cost, status);
    }

    public AdminDtos.UsageSummary summarize(Collection<AdminDtos.Usage> records) {
        long live = 0, mock = 0, unknown = 0, requests = 0, input = 0, output = 0, total = 0, tokenRecords = 0, priced = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean partial = false;
        for (AdminDtos.Usage usage : records) {
            if ("live".equals(usage.mode())) live++; else if ("mock".equals(usage.mode())) mock++; else unknown++;
            requests += usage.providerRequests();
            if (usage.totalTokens() != null) { input += usage.inputTokens(); output += usage.outputTokens(); total += usage.totalTokens(); tokenRecords++; }
            if (usage.estimatedCostUsd() != null) { cost = cost.add(usage.estimatedCostUsd()); priced++; }
            partial |= !Set.of("estimated", "not_applicable").contains(usage.costStatus());
        }
        String status = priced == 0 ? (mock == records.size() && mock > 0 ? "not_applicable" : "unavailable") : partial ? "partial" : "estimated";
        return new AdminDtos.UsageSummary(records.size(), live, mock, unknown, requests,
                tokenRecords == 0 ? null : input, tokenRecords == 0 ? null : output, tokenRecords == 0 ? null : total,
                priced == 0 ? null : cost, status);
    }

    private Long count(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }
}
