package com.urke.saasbackendstarter.screenplay.controller;

import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.dto.storyboard.StoryboardDtos.*;
import com.urke.saasbackendstarter.screenplay.service.StoryboardService;
import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.http.MediaType; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;

/** Capability-only endpoints consumed by the gateway profiles; browser auth is intentionally unsupported here. */
@RestController @RequestMapping("/internal/agent/runs") @RequiredArgsConstructor
public class StoryboardAgentInternalController {
 private final AgentExecutionTokenService tokens; private final StoryboardService storyboards;
 @GetMapping(value="/{runId}/storyboard-context",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> context(@RequestHeader("Authorization") String bearer,@PathVariable String runId){AgentExecutionClaims claims=tokens.verifyBearer(bearer);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(storyboards.contextWire(runId,claims));}
 @PostMapping("/{runId}/storyboard-result") public ResultResponse result(@RequestHeader("Authorization") String bearer,@PathVariable String runId,@Valid @RequestBody ResultRequest body){AgentExecutionClaims claims=tokens.verifyBearer(bearer);return storyboards.saveResult(runId,claims,body.result());}
}
