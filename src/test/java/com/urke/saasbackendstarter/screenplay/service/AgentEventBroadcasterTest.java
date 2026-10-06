package com.urke.saasbackendstarter.screenplay.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventBroadcasterTest {
    @Test
    void buffers_live_events_until_history_has_been_replayed_in_sequence() {
        var state = new AgentEventBroadcaster.ReplayState(0);
        var event3 = event(3);

        // This is the deterministic race from the review: broadcast wins the
        // executor race, but must not advance the browser cursor yet.
        assertThat(state.live(event3)).isEmpty();

        assertThat(state.completeReplay(List.of(event(1), event(2))))
                .extracting(AgentEventService.PersistedEvent::sequence)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void reconnect_cursor_skips_prior_events_but_keeps_a_new_buffered_event() {
        var state = new AgentEventBroadcaster.ReplayState(4);
        assertThat(state.live(event(6))).isEmpty();

        assertThat(state.completeReplay(List.of(event(3), event(4), event(5))))
                .extracting(AgentEventService.PersistedEvent::sequence)
                .containsExactly(5L, 6L);
    }

    private AgentEventService.PersistedEvent event(long sequence) {
        return new AgentEventService.PersistedEvent(sequence, "status", JsonNodeFactory.instance.objectNode());
    }
}
