package com.urke.saasbackendstarter.screenplay.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.screenplay.agent.AgentClient;
import com.urke.saasbackendstarter.screenplay.agent.RuleBasedAgentClient;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.dto.storyboard.EditableShot;
import com.urke.saasbackendstarter.screenplay.dto.storyboard.StoryboardDtos.*;
import com.urke.saasbackendstarter.screenplay.parser.RuleBasedScriptParser;
import com.urke.saasbackendstarter.screenplay.repository.*;
import com.urke.saasbackendstarter.screenplay.service.*;
import com.urke.saasbackendstarter.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets; import java.util.*; import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*; import static org.mockito.ArgumentMatchers.anyString; import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL coverage for SB-03/04 invariants; Gateway is deliberately not treated as a model-quality test. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create","spring.jpa.show-sql=false"}) @AutoConfigureMockMvc @Testcontainers
class StoryboardIntegrationTest {
 private static final String BASE="/api/v1/screenplay";
 private static final String SCRIPT="""
   INT. 客厅 - 夜
   林夏把收音机放在桌上。\n""";
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired OrganizationRepository organizations; @Autowired UserRepository users; @Autowired JwtTokenProvider tokens; @Autowired AgentRunRepository runs; @Autowired AgentRunOutboxRepository outbox; @Autowired StoryboardDraftRepository drafts; @Autowired StoryboardService storyboards;
 @MockitoBean AgentClient agent; @MockitoBean AgentGatewayDispatchService dispatcher;
 Organization ownerOrg, otherOrg; User owner, other; RequestPostProcessor ownerAuth, otherAuth;
 @BeforeEach void setUp(){String s=UUID.randomUUID().toString();ownerOrg=organizations.save(Organization.builder().name("o"+s).slug("o"+s).build());otherOrg=organizations.save(Organization.builder().name("x"+s).slug("x"+s).build());owner=users.save(User.builder().email("o"+s+"@t").fullName("o").password("x").organization(ownerOrg).build());other=users.save(User.builder().email("x"+s+"@t").fullName("x").password("x").organization(otherOrg).build());ownerAuth=bearer(owner);otherAuth=bearer(other);RuleBasedAgentClient real=new RuleBasedAgentClient(new RuleBasedScriptParser());doAnswer(i->real.analyzeScript(i.getArgument(0))).when(agent).analyzeScript(anyString());}
 @Test void snapshots_survive_reparse_and_cross_tenant_and_idempotency_are_enforced() throws Exception {
  long project=project(); long script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong();
  String request="{\"scriptId\":\""+script+"\",\"sceneId\":\""+scene+"\",\"targetShotCount\":4,\"instructions\":\"保持道具连续\",\"clientRequestId\":\"storyboard-it-1\"}";
  String runId=accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(request)).path("runId").asText();
  assertThat(accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(request)).path("runId").asText()).isEqualTo(runId);
  AgentRun run=runs.findById(runId).orElseThrow(); run.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(run);
  var result=new ModelResult("generate",List.of(shot(),shot(),shot(),shot()),null);
  String storyboardId=storyboards.saveResult(runId,claims(run,project,script,"storyboard:create"),result).artifactId();
  mvc.perform(get(BASE+"/storyboards/"+storyboardId).with(otherAuth)).andExpect(status().isNotFound());
  JsonNode before=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String snapshot=before.path("sourceSnapshot").path("sceneText").asText();
  analyze(script);
  JsonNode after=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); assertThat(after.path("sourceSnapshot").path("sceneText").asText()).isEqualTo(snapshot); assertThat(after.path("shots")).hasSize(4); assertThat(read(get(BASE+"/scripts/"+script),ownerAuth).path("rawText").asText()).isEqualTo(SCRIPT);
 }
 @Test void invalid_result_is_rejected_without_a_persisted_storyboard() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong();
  long draftsBefore=drafts.count();
  String req="{\"scriptId\":\""+script+"\",\"sceneId\":\""+scene+"\",\"targetShotCount\":4,\"instructions\":\"\",\"clientRequestId\":\"storyboard-it-invalid\"}";
  String runId=accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(req)).path("runId").asText(); AgentRun run=runs.findById(runId).orElseThrow();run.setStatus(AgentRunStatus.RUNNING);runs.saveAndFlush(run);
  assertThatThrownBy(()->storyboards.saveResult(runId,claims(run,project,script,"storyboard:create"),new ModelResult("generate",List.of(shot(),shot(),shot()),null))).isInstanceOf(StoryboardApiException.class);
  assertThat(drafts.count()).isEqualTo(draftsBefore);
 }
 @Test void shot_proposal_is_frozen_compared_and_applied_only_to_its_target() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong();
  String generation="{\"scriptId\":\""+script+"\",\"sceneId\":\""+scene+"\",\"targetShotCount\":4,\"instructions\":\"\",\"clientRequestId\":\"storyboard-proposal-generate\"}";
  String generationRun=accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(generation)).path("runId").asText(); AgentRun initial=runs.findById(generationRun).orElseThrow(); initial.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(initial);
  String storyboardId=storyboards.saveResult(generationRun,claims(initial,project,script,"storyboard:create"),new ModelResult("generate",List.of(shot(),shot(),shot(),shot()),null)).artifactId(); JsonNode before=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); JsonNode targetBefore=before.path("shots").get(1); String targetId=targetBefore.path("id").asText();
  analyze(script); String rewrite="{\"instruction\":\"改成更克制的静态构图\",\"expectedRevision\":1,\"clientRequestId\":\"storyboard-proposal-rewrite\"}";
  String rewriteRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+targetId+"/regenerations").contentType(MediaType.APPLICATION_JSON).content(rewrite)).path("runId").asText(); AgentRun rewriting=runs.findById(rewriteRun).orElseThrow(); rewriting.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(rewriting);
  ContextResponse frozen=storyboards.context(rewriteRun,claims(rewriting,project,script,"storyboard:read")); assertThat(frozen.targetShot().visualDescription()).isEqualTo(targetBefore.path("visualDescription").asText()); assertThat(storyboards.context(rewriteRun,claims(rewriting,project,script,"storyboard:read")).targetShot()).isEqualTo(frozen.targetShot());
  String proposalId=storyboards.saveResult(rewriteRun,claims(rewriting,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot())).artifactId(); JsonNode withCandidate=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); assertThat(withCandidate.path("shots")).isEqualTo(before.path("shots")); JsonNode proposal=read(get(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+proposalId),ownerAuth); assertThat(proposal.path("status").asText()).isEqualTo("PENDING");
  JsonNode acceptedProposal=response(mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+proposalId+"/accept").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}")).andExpect(status().isOk()).andReturn()).path("shots");
  assertThat(acceptedProposal.get(1).path("id").asText()).isEqualTo(targetId); assertThat(acceptedProposal.get(1).path("orderIndex").asInt()).isEqualTo(targetBefore.path("orderIndex").asInt()); assertThat(acceptedProposal.get(1).path("visualDescription").asText()).isEqualTo("林夏把收音机放在桌上后，克制地停在原地。");
  ContextResponse afterDraftRevisionChanged=storyboards.context(rewriteRun,claims(rewriting,project,script,"storyboard:read")); assertThat(afterDraftRevisionChanged).isEqualTo(frozen); assertThat(afterDraftRevisionChanged.baseStoryboardRevision()).isEqualTo(1);
  for(int i=0;i<before.path("shots").size();i++)if(i!=1)assertThat(acceptedProposal.get(i)).isEqualTo(before.path("shots").get(i));
  String markdown=mvc.perform(get(BASE+"/storyboards/"+storyboardId+"/export").param("format","markdown").with(ownerAuth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8); assertThat(markdown).contains("# 分镜表","来源剧本版本","镜头 ID","林夏把收音机放在桌上后，克制地停在原地。","cinematic \\| close<br>shot \\*");
  mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+proposalId+"/accept").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":2}")).andExpect(status().isConflict()); assertThat(read(get(BASE+"/storyboards/"+storyboardId),ownerAuth).path("shots")).isEqualTo(acceptedProposal);

  JsonNode current=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String secondTarget=current.path("shots").get(2).path("id").asText(); String secondRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+secondTarget+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"保持原意\",\"expectedRevision\":2,\"clientRequestId\":\"storyboard-proposal-stale\"}")).path("runId").asText(); AgentRun staleRun=runs.findById(secondRun).orElseThrow();staleRun.setStatus(AgentRunStatus.RUNNING);runs.saveAndFlush(staleRun); String staleProposal=storyboards.saveResult(secondRun,claims(staleRun,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot())).artifactId();
  mvc.perform(put(BASE+"/storyboards/"+storyboardId).with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content(savePayload(current,2))).andExpect(status().isOk()); mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+staleProposal+"/accept").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3}")).andExpect(status().isConflict());

  JsonNode afterStale=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String rejectTarget=afterStale.path("shots").get(3).path("id").asText(); String rejectRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+rejectTarget+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"仅调整节奏\",\"expectedRevision\":3,\"clientRequestId\":\"storyboard-proposal-reject\"}")).path("runId").asText(); AgentRun rejecting=runs.findById(rejectRun).orElseThrow();rejecting.setStatus(AgentRunStatus.RUNNING);runs.saveAndFlush(rejecting); String rejectedProposal=storyboards.saveResult(rejectRun,claims(rejecting,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot())).artifactId(); JsonNode beforeReject=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+rejectedProposal+"/reject").with(ownerAuth)).andExpect(status().isOk()); mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+rejectedProposal+"/reject").with(ownerAuth)).andExpect(status().isOk()); assertThat(read(get(BASE+"/storyboards/"+storyboardId),ownerAuth).path("shots")).isEqualTo(beforeReject.path("shots"));

  String cancelledRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+rejectTarget+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"失败和取消不应覆盖\",\"expectedRevision\":3,\"clientRequestId\":\"storyboard-proposal-cancelled\"}")).path("runId").asText(); AgentRun cancelled=runs.findById(cancelledRun).orElseThrow();cancelled.setStatus(AgentRunStatus.CANCELLED);runs.saveAndFlush(cancelled); assertThatThrownBy(()->storyboards.saveResult(cancelledRun,claims(cancelled,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot()))).isInstanceOf(StoryboardApiException.class); assertThat(read(get(BASE+"/storyboards/"+storyboardId),ownerAuth).path("shots")).isEqualTo(beforeReject.path("shots"));
  String failedRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+rejectTarget+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"失败不应覆盖\",\"expectedRevision\":3,\"clientRequestId\":\"storyboard-proposal-failed\"}")).path("runId").asText(); AgentRun failed=runs.findById(failedRun).orElseThrow();failed.setStatus(AgentRunStatus.FAILED);runs.saveAndFlush(failed); assertThatThrownBy(()->storyboards.saveResult(failedRun,claims(failed,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot()))).isInstanceOf(StoryboardApiException.class); assertThat(read(get(BASE+"/storyboards/"+storyboardId),ownerAuth).path("shots")).isEqualTo(beforeReject.path("shots"));
 }
 @Test void rewrite_context_keeps_its_frozen_base_revision_after_the_draft_advances() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong(); String storyboardId=materialize(project,script,scene,"rewrite-context-freeze",List.of(shot(),shot(),shot(),shot())); JsonNode before=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String targetId=before.path("shots").get(1).path("id").asText();
  String runId=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+targetId+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"冻结上下文\",\"expectedRevision\":1,\"clientRequestId\":\"rewrite-context-freeze\"}")).path("runId").asText(); AgentRun run=runs.findById(runId).orElseThrow(); run.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(run);
  ContextResponse frozen=storyboards.context(runId,claims(run,project,script,"storyboard:read")); assertThat(frozen.baseStoryboardRevision()).isEqualTo(1L);
  mvc.perform(put(BASE+"/storyboards/"+storyboardId).with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content(savePayload(before,1))).andExpect(status().isOk());
  ContextResponse reread=storyboards.context(runId,claims(run,project,script,"storyboard:read")); assertThat(reread).isEqualTo(frozen); assertThat(reread.baseStoryboardRevision()).isEqualTo(1L);
  assertThatThrownBy(()->storyboards.saveResult(runId,claims(run,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot()))).isInstanceOf(StoryboardApiException.class);
  assertThat(read(get(BASE+"/storyboards/"+storyboardId),ownerAuth).path("revision").asLong()).isEqualTo(2);
 }
 @Test void concurrent_saves_use_one_locked_revision_and_never_silently_overwrite() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong(); String storyboardId=materialize(project,script,scene,"concurrent-save",List.of(shot("one"),shot("two"),shot("three"),shot("four")));
  JsonNode before=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth);
  List<Integer> statuses=race(
    ()->putStatus(storyboardId,saveWithVisual(before,1,"并发保存 A")),
    ()->putStatus(storyboardId,saveWithVisual(before,1,"并发保存 B")));
  assertThat(statuses).containsExactlyInAnyOrder(200,409);
  JsonNode after=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); assertThat(after.path("revision").asLong()).isEqualTo(2); assertThat(after.path("shots").get(0).path("visualDescription").asText()).isIn("并发保存 A","并发保存 B");
 }
 @Test void save_and_accept_race_share_the_same_draft_lock() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong(); String storyboardId=materialize(project,script,scene,"save-accept",List.of(shot(),shot(),shot(),shot())); JsonNode before=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String shotId=before.path("shots").get(1).path("id").asText();
  String rewriteRun=accepted(post(BASE+"/storyboards/"+storyboardId+"/shots/"+shotId+"/regenerations").contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"调整\",\"expectedRevision\":1,\"clientRequestId\":\"race-proposal\"}")).path("runId").asText(); AgentRun rewriting=runs.findById(rewriteRun).orElseThrow(); rewriting.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(rewriting); String proposalId=storyboards.saveResult(rewriteRun,claims(rewriting,project,script,"shot-proposal:create"),new ModelResult("rewrite",null,proposalShot())).artifactId();
  List<Integer> statuses=race(
    ()->putStatus(storyboardId,saveWithVisual(before,1,"保存与采纳竞态")),
    ()->mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shot-proposals/"+proposalId+"/accept").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}")).andReturn().getResponse().getStatus());
  assertThat(statuses).containsExactlyInAnyOrder(200,409); JsonNode after=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); assertThat(after.path("revision").asLong()).isEqualTo(2);
  String visual=after.path("shots").get(1).path("visualDescription").asText(); assertThat(visual).isIn("林夏把收音机放在桌上后，克制地停在原地。",before.path("shots").get(1).path("visualDescription").asText());
 }
 @Test void reorder_persists_temporary_indices_before_final_order_and_keeps_ids_and_content() throws Exception {
  long project=project(), script=script(project); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong(); String storyboardId=materialize(project,script,scene,"reorder",List.of(shot("A"),shot("B"),shot("C"),shot("D"))); JsonNode current=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth);
  for (List<Integer> order:List.of(List.of(1,0,2,3),List.of(1,2,0,3),List.of(3,2,1,0))) { String payload=saveInOrder(current,current.path("revision").asLong(),order); JsonNode saved=response(mvc.perform(put(BASE+"/storyboards/"+storyboardId).with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andReturn()); assertThat(saved.path("revision").asLong()).isEqualTo(current.path("revision").asLong()+1); current=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); assertThat(current.path("shots")).isEqualTo(saved.path("shots")); for(int i=0;i<4;i++)assertThat(current.path("shots").get(i).path("orderIndex").asInt()).isEqualTo(i); }
  assertThat(current.path("shots").get(0).path("visualDescription").asText()).isEqualTo("D"); assertThat(current.path("shots").get(3).path("visualDescription").asText()).isEqualTo("A");
 }
 @Test void chinese_context_capacity_accepts_long_generate_and_rewrite_and_rejects_oversized_legacy_snapshot_before_run() throws Exception {
  long project=project(), script=script(project,"INT. 客厅 - 夜\n"+"字".repeat(7900)); analyze(script); long scene=read(get(BASE+"/scripts/"+script+"/scenes"),ownerAuth).get(0).path("id").asLong();
  String generation="{\"scriptId\":\""+script+"\",\"sceneId\":\""+scene+"\",\"targetShotCount\":4,\"instructions\":\""+"中".repeat(1000)+"\",\"clientRequestId\":\"long-chinese-generate\"}"; String runId=accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(generation)).path("runId").asText(); assertThat(runs.findById(runId)).isPresent();
  AgentRun run=runs.findById(runId).orElseThrow(); run.setStatus(AgentRunStatus.RUNNING); runs.saveAndFlush(run); String quote="字".repeat(500); String storyboardId=storyboards.saveResult(runId,claims(run,project,script,"storyboard:create"),new ModelResult("generate",List.of(longShot(quote),longShot(quote),longShot(quote),longShot(quote)),null)).artifactId(); JsonNode detail=read(get(BASE+"/storyboards/"+storyboardId),ownerAuth); String targetId=detail.path("shots").get(1).path("id").asText();
  mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shots/"+targetId+"/regenerations").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\""+"改".repeat(1000)+"\",\"expectedRevision\":1,\"clientRequestId\":\"long-chinese-rewrite\"}")).andExpect(status().isAccepted());
  long runsBefore=runs.count(), outboxBefore=outbox.count(); StoryboardDraft draft=drafts.findById(storyboardId).orElseThrow(); draft.setSourceSceneText("字".repeat(100000)); drafts.saveAndFlush(draft);
  mvc.perform(post(BASE+"/storyboards/"+storyboardId+"/shots/"+targetId+"/regenerations").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"instruction\":\"超限\",\"expectedRevision\":1,\"clientRequestId\":\"oversized-context\"}")).andExpect(status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("STORYBOARD_CONTEXT_TOO_LARGE")); assertThat(runs.count()).isEqualTo(runsBefore); assertThat(outbox.count()).isEqualTo(outboxBefore);
 }
 private EditableShot shot(){return new EditableShot(ShotSize.MEDIUM,CameraMovement.STATIC,"林夏和桌上的收音机","","环境安静",3,"cinematic living room","static medium shot","林夏把收音机放在桌上。");}
 private EditableShot shot(String visual){return new EditableShot(ShotSize.MEDIUM,CameraMovement.STATIC,visual,"","环境安静",3,"cinematic living room","static medium shot","林夏把收音机放在桌上。");}
 private EditableShot longShot(String quote){String z="镜";return new EditableShot(ShotSize.MEDIUM,CameraMovement.STATIC,z.repeat(2000),z.repeat(1200),z.repeat(1200),30,z.repeat(2000),z.repeat(2500),quote);}
 private EditableShot proposalShot(){return new EditableShot(ShotSize.CLOSE_UP,CameraMovement.STATIC,"林夏把收音机放在桌上后，克制地停在原地。","","环境安静",4,"cinematic | close\nshot *","static close shot","林夏把收音机放在桌上。");}
 private String savePayload(JsonNode detail,long revision) throws Exception {var body=json.createObjectNode();body.put("expectedRevision",revision);var saved=body.putArray("shots");for(JsonNode shot:detail.path("shots")){var item=saved.addObject();item.put("id",shot.path("id").asText());item.put("shotSize",shot.path("shotSize").asText());item.put("cameraMovement",shot.path("cameraMovement").asText());item.put("visualDescription",shot.path("visualDescription").asText());item.put("dialogue",shot.path("dialogue").asText());item.put("sound",shot.path("sound").asText());item.put("durationSeconds",shot.path("durationSeconds").asInt());item.put("imagePrompt",shot.path("imagePrompt").asText());item.put("videoPrompt",shot.path("videoPrompt").asText());item.put("sourceQuote",shot.path("sourceQuote").asText());}return json.writeValueAsString(body);}
 private AgentExecutionClaims claims(AgentRun r,long project,long script,String scope){return new AgentExecutionClaims(ownerOrg.getId(),owner.getId(),project,script,r.getId(),r.getSession().getId(),Set.of(scope));}
 private long project() throws Exception{return response(mvc.perform(post(BASE+"/projects").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"storyboard\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private long script(long project) throws Exception{return script(project,SCRIPT);}
 private long script(long project,String rawText) throws Exception{return response(mvc.perform(post(BASE+"/projects/"+project+"/scripts").with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("versionName","Draft","rawText",rawText)))).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private String materialize(long project,long script,long scene,String key,List<EditableShot> values) throws Exception {String req="{\"scriptId\":\""+script+"\",\"sceneId\":\""+scene+"\",\"targetShotCount\":4,\"instructions\":\"\",\"clientRequestId\":\""+key+"\"}";String runId=accepted(post(BASE+"/projects/"+project+"/storyboards/generations").contentType(MediaType.APPLICATION_JSON).content(req)).path("runId").asText();AgentRun run=runs.findById(runId).orElseThrow();run.setStatus(AgentRunStatus.RUNNING);runs.saveAndFlush(run);return storyboards.saveResult(runId,claims(run,project,script,"storyboard:create"),new ModelResult("generate",values,null)).artifactId();}
 private int putStatus(String storyboardId,String payload) throws Exception{return mvc.perform(put(BASE+"/storyboards/"+storyboardId).with(ownerAuth).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse().getStatus();}
 private String saveWithVisual(JsonNode detail,long revision,String visual) throws Exception {var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(savePayload(detail,revision));((com.fasterxml.jackson.databind.node.ObjectNode)body.withArray("shots").get(0)).put("visualDescription",visual);return json.writeValueAsString(body);}
 private String saveInOrder(JsonNode detail,long revision,List<Integer> order) throws Exception {var body=json.createObjectNode();body.put("expectedRevision",revision);var saved=body.putArray("shots");for(int i:order){JsonNode shot=detail.path("shots").get(i);var item=saved.addObject();item.put("id",shot.path("id").asText());item.put("shotSize",shot.path("shotSize").asText());item.put("cameraMovement",shot.path("cameraMovement").asText());item.put("visualDescription",shot.path("visualDescription").asText());item.put("dialogue",shot.path("dialogue").asText());item.put("sound",shot.path("sound").asText());item.put("durationSeconds",shot.path("durationSeconds").asInt());item.put("imagePrompt",shot.path("imagePrompt").asText());item.put("videoPrompt",shot.path("videoPrompt").asText());item.put("sourceQuote",shot.path("sourceQuote").asText());}return json.writeValueAsString(body);}
 private List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);try{List<Future<Integer>> futures=List.of(executor.submit(()->{ready.countDown();start.await(10,TimeUnit.SECONDS);return first.call();}),executor.submit(()->{ready.countDown();start.await(10,TimeUnit.SECONDS);return second.call();}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));}finally{executor.shutdownNow();}}
 private void analyze(long script) throws Exception{mvc.perform(post(BASE+"/scripts/"+script+"/analyze").with(ownerAuth)).andExpect(status().isOk());}
 private RequestPostProcessor bearer(User u){String token=tokens.generateToken(new CustomUserDetails(u));return r-> {r.addHeader("Authorization","Bearer "+token);return r;};}
 private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,RequestPostProcessor auth) throws Exception{return json.readTree(mvc.perform(b.with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));}
 private JsonNode accepted(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception{return response(mvc.perform(b.with(ownerAuth)).andExpect(status().isAccepted()).andReturn());}
 private JsonNode response(org.springframework.test.web.servlet.MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));}
}
