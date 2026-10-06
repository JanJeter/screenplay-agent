package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentGatewayDispatchService {
    private final AgentOutboxService outbox;
    private final AgentRunWorker worker;
    private final AgentGatewayClient gateway;
    private final AgentGatewayEventProjector projector;
    @jakarta.annotation.Resource(name = "agentGatewayStreamExecutor")
    private org.springframework.core.task.AsyncTaskExecutor executor;

    public void dispatchAsync(String runId) { executor.execute(() -> dispatch(runId)); }

    @Scheduled(fixedDelayString = "${agent.outbox.poll-ms:5000}")
    public void dispatchPending() {
        for (String runId : outbox.readyRunIds()) dispatchAsync(runId);
    }

    private void dispatch(String runId) {
        if (!outbox.claim(runId)) return;
        AgentRunService.GatewayDispatch dispatch = worker.start(runId);
        if (dispatch == null) {
            // Another worker can temporarily own this session. Keep a queued
            // run retryable instead of stranding it as QUEUED + FAILED outbox.
            var reference = worker.gatewayReference(runId);
            if (reference.status() == com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus.QUEUED) outbox.retryLater(runId);
            else outbox.failed(runId);
            return;
        }
        try {
            String gatewaySessionId = gateway.createSession(dispatch);
            String gatewayRunId = gateway.submit(gatewaySessionId, dispatch.message(), dispatch.requestId(), dispatch.userId());
            var binding = worker.bindGateway(runId, gatewaySessionId, gatewayRunId);
            if (binding.cancellationRequested()) {
                gateway.cancel(binding.gatewayRunId(), binding.userId());
            }
            outbox.dispatched(runId);
            gateway.streamEvents(gatewayRunId, dispatch.userId(), 0, event -> projector.project(runId, event));
        } catch (RuntimeException ex) {
            worker.failure(runId, "gateway_dispatch_failed");
            outbox.failed(runId);
        } catch (Exception ex) {
            worker.failure(runId, "gateway_stream_failed");
            outbox.failed(runId);
        }
    }

}
