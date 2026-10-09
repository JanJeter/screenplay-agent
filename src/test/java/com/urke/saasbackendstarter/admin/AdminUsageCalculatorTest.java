package com.urke.saasbackendstarter.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AdminUsageCalculatorTest {
    private final AdminUsageCalculator calculator = new AdminUsageCalculator(new ObjectMapper());
    @Test void completedRequestsAreDeduplicatedAndGenericUsageIsNotCountedTwice() {
        AgentEvent completed = event(3, "provider.request_completed", "{\"sequence\":1,\"chargedCostUsd\":0.00004224,\"actualCostUsd\":0.00004224,\"actualUsage\":{\"input\":60,\"output\":20,\"cacheRead\":40,\"cacheWrite\":0,\"totalTokens\":120}}");
        var result = calculator.calculate(List.of(event(1, "provider.request_started", "{\"sequence\":1,\"reservedCostUsd\":0.03}"),
                event(2, "usage", "{\"input\":60,\"output\":20,\"totalTokens\":120}"), completed, completed));
        assertThat(result.mode()).isEqualTo("live");
        assertThat(result.providerRequests()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(20);
        assertThat(result.totalTokens()).isEqualTo(120);
        assertThat(result.estimatedCostUsd()).isEqualByComparingTo(new BigDecimal("0.00004224"));
        assertThat(result.costStatus()).isEqualTo("estimated");
    }
    @Test void failedUnknownUsageIsConservativePartialAndNeverFabricatedTokenZero() {
        var result = calculator.calculate(List.of(event(1, "provider.request_started", "{\"sequence\":1}"),
                event(2, "provider.request_failed", "{\"sequence\":1,\"chargedCostUsd\":0.03}")));
        assertThat(result.costStatus()).isEqualTo("partial");
        assertThat(result.totalTokens()).isNull();
        assertThat(result.estimatedCostUsd()).isEqualByComparingTo("0.03");
    }
    @Test void oldZeroUsageDoesNotPretendToBeMockOrFree() {
        var result = calculator.calculate(List.of(event(1, "usage", "{\"input\":0,\"output\":0,\"totalTokens\":0}")));
        assertThat(result.mode()).isEqualTo("unknown");
        assertThat(result.costStatus()).isEqualTo("unavailable");
        assertThat(result.estimatedCostUsd()).isNull();
    }
    @Test void historicalUsageIncludesRecordedCachedInputWithoutInventingCostOrMode() {
        var result = calculator.calculate(List.of(event(1, "usage", "{\"input\":39133,\"output\":8849,\"totalTokens\":67436}")));
        assertThat(result.inputTokens()).isEqualTo(58587);
        assertThat(result.outputTokens()).isEqualTo(8849);
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(result.totalTokens());
        assertThat(result.mode()).isEqualTo("unknown");
        assertThat(result.costStatus()).isEqualTo("unavailable");
        assertThat(result.estimatedCostUsd()).isNull();
    }
    @Test void mixedHistoricalAndMeteredSummaryHasConsistentInclusiveTokenTotals() {
        var historical = calculator.calculate(List.of(event(1, "usage", "{\"input\":60,\"output\":20,\"totalTokens\":120}")));
        var metered = calculator.calculate(List.of(event(1, "provider.request_completed", "{\"sequence\":1,\"chargedCostUsd\":0.00004224,\"actualCostUsd\":0.00004224,\"actualUsage\":{\"input\":60,\"output\":20,\"cacheRead\":40,\"cacheWrite\":0,\"totalTokens\":120}}")));
        var summary = calculator.summarize(List.of(historical, metered));
        assertThat(summary.inputTokens()).isEqualTo(200);
        assertThat(summary.outputTokens()).isEqualTo(40);
        assertThat(summary.totalTokens()).isEqualTo(240);
        assertThat(summary.inputTokens() + summary.outputTokens()).isEqualTo(summary.totalTokens());
        assertThat(summary.estimatedCostUsd()).isEqualByComparingTo("0.00004224");
        assertThat(summary.costStatus()).isEqualTo("partial");
    }
    @Test void inconsistentGenericTotalsOrCacheSplitsRemainUnrecorded() {
        for (String invalid : List.of("{\"input\":60,\"output\":20,\"totalTokens\":70}",
                "{\"input\":0,\"output\":20,\"totalTokens\":10}",
                "{\"input\":60,\"output\":20,\"totalTokens\":120,\"cacheRead\":30,\"cacheWrite\":0}")) {
            var result = calculator.calculate(List.of(event(1, "usage", "{\"input\":1,\"output\":1,\"totalTokens\":2}"), event(2, "usage", invalid)));
            assertThat(result.inputTokens()).isNull();
            assertThat(result.outputTokens()).isNull();
            assertThat(result.totalTokens()).isNull();
            assertThat(result.estimatedCostUsd()).isNull();
        }
    }
    @Test void explicitCacheReadAndWriteAreIncludedExactlyOnce() {
        var result = calculator.calculate(List.of(event(1, "usage", "{\"input\":60,\"output\":20,\"totalTokens\":130,\"cacheRead\":40,\"cacheWrite\":10}")));
        assertThat(result.inputTokens()).isEqualTo(110);
        assertThat(result.inputTokens() + result.outputTokens()).isEqualTo(130);
    }
    @Test void explicitMockIsDifferentFromMissingMetering() {
        var result = calculator.calculate(List.of(event(1, "run.started", "{\"mode\":\"mock\"}")));
        assertThat(result.mode()).isEqualTo("mock");
        assertThat(result.costStatus()).isEqualTo("not_applicable");
        assertThat(result.estimatedCostUsd()).isNull();
        assertThat(calculator.calculate(List.of()).costStatus()).isEqualTo("unavailable");
    }
    @Test void outstandingProviderReservationIsNotAnObservedCharge() {
        var result = calculator.calculate(List.of(event(1, "provider.request_started", "{\"sequence\":1,\"reservedCostUsd\":0.5}")));
        assertThat(result.providerRequests()).isEqualTo(1);
        assertThat(result.estimatedCostUsd()).isNull();
        assertThat(result.costStatus()).isEqualTo("unavailable");
    }
    @Test void summaryFlagsMissingRecordsAndDoesNotInventZeroCost() {
        var unknown = calculator.calculate(List.of());
        var mock = calculator.calculate(List.of(event(1, "run.started", "{\"mode\":\"mock\"}")));
        var live = calculator.calculate(List.of(event(1, "provider.request_failed", "{\"sequence\":1,\"chargedCostUsd\":0.02}")));
        var summary = calculator.summarize(List.of(unknown, mock, live));
        assertThat(summary.totalRuns()).isEqualTo(3);
        assertThat(summary.liveRuns()).isEqualTo(1);
        assertThat(summary.mockRuns()).isEqualTo(1);
        assertThat(summary.unknownRuns()).isEqualTo(1);
        assertThat(summary.costStatus()).isEqualTo("partial");
        assertThat(summary.estimatedCostUsd()).isEqualByComparingTo("0.02");
        assertThat(calculator.summarize(List.of(unknown)).estimatedCostUsd()).isNull();
    }
    private AgentEvent event(long sequence, String type, String data) {
        AgentEvent event = new AgentEvent(); event.setSequence(sequence); event.setType(type); event.setPayload("{\"data\":" + data + "}"); return event;
    }
}
