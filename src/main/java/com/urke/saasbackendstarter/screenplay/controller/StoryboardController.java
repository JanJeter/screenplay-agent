package com.urke.saasbackendstarter.screenplay.controller;

import com.urke.saasbackendstarter.screenplay.dto.storyboard.StoryboardDtos.*;
import com.urke.saasbackendstarter.screenplay.service.StoryboardService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Browser API. IDs and source ownership are exclusively derived server-side. */
@RestController @RequestMapping("/api/v1/screenplay") @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth")
public class StoryboardController {
 private final StoryboardService storyboards;
 @PostMapping("/projects/{projectId}/storyboards/generations") public ResponseEntity<RunAccepted> generate(@PathVariable Long projectId,@Valid @RequestBody GenerationRequest body){return ResponseEntity.accepted().body(storyboards.generate(projectId,body));}
 @GetMapping("/projects/{projectId}/storyboards") public ListResponse list(@PathVariable Long projectId,@RequestParam(required=false) String scriptId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return storyboards.list(projectId,scriptId,page,size);}
 @GetMapping("/storyboards/{storyboardId}") public DetailResponse detail(@PathVariable String storyboardId){return storyboards.detail(storyboardId);}
 @GetMapping(value="/storyboards/{storyboardId}/export",produces="text/markdown;charset=UTF-8") public String export(@PathVariable String storyboardId,@RequestParam(defaultValue="markdown") String format){return storyboards.exportMarkdown(storyboardId,format);}
 @PutMapping("/storyboards/{storyboardId}") public DetailResponse save(@PathVariable String storyboardId,@Valid @RequestBody SaveRequest body){return storyboards.save(storyboardId,body);}
 @PostMapping("/storyboards/{storyboardId}/shots/{shotId}/regenerations") public ResponseEntity<RunAccepted> rewrite(@PathVariable String storyboardId,@PathVariable String shotId,@Valid @RequestBody RewriteRequest body){return ResponseEntity.accepted().body(storyboards.rewrite(storyboardId,shotId,body));}
 @GetMapping("/storyboards/{storyboardId}/shot-proposals/{proposalId}") public ProposalResponse proposal(@PathVariable String storyboardId,@PathVariable String proposalId){return storyboards.proposal(storyboardId,proposalId);}
 @PostMapping("/storyboards/{storyboardId}/shot-proposals/{proposalId}/accept") public DetailResponse accept(@PathVariable String storyboardId,@PathVariable String proposalId,@Valid @RequestBody ProposalMutationRequest body){return storyboards.acceptProposal(storyboardId,proposalId,body);}
 @PostMapping("/storyboards/{storyboardId}/shot-proposals/{proposalId}/reject") public ProposalResponse reject(@PathVariable String storyboardId,@PathVariable String proposalId){return storyboards.rejectProposal(storyboardId,proposalId);}
}
