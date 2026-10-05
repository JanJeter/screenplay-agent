package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.domain.AgentDraft;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraftStatus;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentDraftResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.SaveAgentDraftRequest;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import jakarta.persistence.EntityManager;

@Service
@RequiredArgsConstructor
public class AgentDraftService {
    private final AgentDraftRepository drafts;
    private final EntityManager entityManager;

    @Transactional
    public AgentDraftResponse save(AgentAuthorizationService.AuthorizedAgentScope authorized,
                                   SaveAgentDraftRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        if (!authorized.run().getSession().getId().equals(request.sessionId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Draft session does not match execution context");
        }
        if (!Long.toString(authorized.script().getId()).equals(request.screenplayId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Draft screenplay does not match execution context");
        }
        if (request.sourceVersion() < 0 || request.sourceVersion() != authorized.script().getContentRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Screenplay content revision has changed");
        }
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "draft:" + authorized.run().getSession().getOrganization().getId() + ":" + idempotencyKey)
                .getSingleResult();
        String payloadHash = hash(request.screenplayId() + "\n" + request.sessionId() + "\n"
                + request.sourceVersion() + "\n" + request.content());
        return drafts.findByOrganizationIdAndIdempotencyKey(authorized.run().getSession().getOrganization().getId(), idempotencyKey)
                .map(existing -> {
                    if (!existing.getPayloadHash().equals(payloadHash)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key has a different payload");
                    }
                    return response(existing);
                })
                .orElseGet(() -> {
                    AgentDraft draft = new AgentDraft();
                    draft.setId(UUID.randomUUID().toString());
                    draft.setOrganization(authorized.run().getSession().getOrganization());
                    draft.setRun(authorized.run());
                    draft.setSourceScript(authorized.script());
                    draft.setSourceRevision(request.sourceVersion());
                    draft.setContent(request.content());
                    draft.setStatus(AgentDraftStatus.PENDING_REVIEW);
                    draft.setIdempotencyKey(idempotencyKey);
                    draft.setPayloadHash(payloadHash);
                    return response(drafts.saveAndFlush(draft));
                });
    }

    private AgentDraftResponse response(AgentDraft draft) {
        return new AgentDraftResponse(draft.getId(), "pending_review");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
