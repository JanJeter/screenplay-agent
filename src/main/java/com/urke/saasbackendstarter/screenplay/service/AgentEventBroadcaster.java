package com.urke.saasbackendstarter.screenplay.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.annotation.Resource;

/**
 * Fans out Java-persisted events to browsers. Gateway SSE is consumed exactly
 * once by the dispatch worker; browser subscriptions never open another
 * Gateway connection or occupy a long-lived executor thread.
 */
@Service
@RequiredArgsConstructor
public class AgentEventBroadcaster {
    private final AgentEventService events;
    @Resource(name = "agentBrowserReplayExecutor")
    private AsyncTaskExecutor replayExecutor;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId, long after) {
        SseEmitter emitter = new SseEmitter(150_000L);
        Subscription subscription = new Subscription(runId, after, emitter);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> { remove(subscription); emitter.complete(); });
        emitter.onError(error -> remove(subscription));
        replayExecutor.execute(() -> {
            try {
                // A subscription buffers live events until its history is sent. A lock
                // alone is insufficient: a live event can otherwise update lastSent
                // to 3 before history events 1 and 2 acquire that lock.
                subscription.replay(events.after(runId, after));
            } catch (RuntimeException ex) {
                remove(subscription);
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    public void broadcast(String runId, AgentEventService.PersistedEvent event) {
        var current = subscribers.get(runId);
        if (current != null) current.forEach(subscription -> subscription.send(event));
    }

    private void remove(Subscription subscription) {
        var current = subscribers.get(subscription.runId);
        if (current == null) return;
        current.remove(subscription);
        if (current.isEmpty()) subscribers.remove(subscription.runId, current);
    }

    private final class Subscription {
        private final String runId;
        private final SseEmitter emitter;
        private final ReplayState replayState;

        private Subscription(String runId, long after, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
            this.replayState = new ReplayState(after);
        }

        private synchronized void replay(java.util.List<AgentEventService.PersistedEvent> replay) {
            replayState.completeReplay(replay).forEach(this::sendNow);
        }

        private synchronized void send(AgentEventService.PersistedEvent event) {
            replayState.live(event).forEach(this::sendNow);
        }

        private void sendNow(AgentEventService.PersistedEvent event) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type())
                        .data(event.payload(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                remove(this);
                emitter.complete();
            }
        }
    }

    /** Package-visible state machine so the replay/live race is regression-testable. */
    static final class ReplayState {
        private long lastSent;
        private boolean replaying = true;
        private final TreeMap<Long, AgentEventService.PersistedEvent> buffered = new TreeMap<>();

        ReplayState(long after) { this.lastSent = after; }

        List<AgentEventService.PersistedEvent> live(AgentEventService.PersistedEvent event) {
            if (replaying) {
                if (event.sequence() > lastSent) buffered.putIfAbsent(event.sequence(), event);
                return List.of();
            }
            return sendable(List.of(event));
        }

        List<AgentEventService.PersistedEvent> completeReplay(List<AgentEventService.PersistedEvent> history) {
            List<AgentEventService.PersistedEvent> ordered = new ArrayList<>(history);
            ordered.sort(Comparator.comparingLong(AgentEventService.PersistedEvent::sequence));
            ordered.addAll(buffered.values());
            buffered.clear();
            replaying = false;
            return sendable(ordered);
        }

        private List<AgentEventService.PersistedEvent> sendable(List<AgentEventService.PersistedEvent> events) {
            List<AgentEventService.PersistedEvent> result = new ArrayList<>();
            for (AgentEventService.PersistedEvent event : events) {
                if (event.sequence() > lastSent) {
                    lastSent = event.sequence();
                    result.add(event);
                }
            }
            return result;
        }
    }
}
