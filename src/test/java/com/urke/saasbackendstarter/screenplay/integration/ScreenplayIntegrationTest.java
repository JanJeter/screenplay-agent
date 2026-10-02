package com.urke.saasbackendstarter.screenplay.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.domain.Role;
import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.repository.OrganizationRepository;
import com.urke.saasbackendstarter.repository.RoleRepository;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.screenplay.agent.AgentClient;
import com.urke.saasbackendstarter.screenplay.agent.ParsedScene;
import com.urke.saasbackendstarter.screenplay.agent.RuleBasedAgentClient;
import com.urke.saasbackendstarter.screenplay.agent.ScriptAnalysisResult;
import com.urke.saasbackendstarter.screenplay.parser.RuleBasedScriptParser;
import com.urke.saasbackendstarter.security.CustomUserDetails;
import com.urke.saasbackendstarter.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises actual PostgreSQL transactions and JWT filters; deliberately has no test transaction. */
@SpringBootTest(properties = {
        // The disposable container removes the schema; avoid shutdown DDL after it has stopped.
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ScreenplayIntegrationTest {
    private static final String BASE = "/api/v1/screenplay";
    private static final String SAMPLE = """
            TITLE: 雨夜试镜

            INT. 旧摄影棚 - NIGHT
            雨声中，林夏打开收音机。

            林夏
            你听到了吗？

            EXT. 摄影棚后巷 - NIGHT
            脚步声从远处传来，周远举起手电筒。

            周远
            我在这里。
            """;

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean AgentClient agent;

    private final AgentClient realAgent = new RuleBasedAgentClient(new RuleBasedScriptParser());
    private Organization ownerOrganization;
    private Organization otherOrganization;
    private User owner;
    private RequestPostProcessor ownerAuth;
    private RequestPostProcessor colleagueAuth;
    private RequestPostProcessor outsiderAuth;
    private RequestPostProcessor outsiderAdminAuth;

    @BeforeEach
    void arrangeTenants() {
        String suffix = UUID.randomUUID().toString();
        ownerOrganization = organization("owner-" + suffix);
        otherOrganization = organization("other-" + suffix);
        owner = user("owner-" + suffix, ownerOrganization, false);
        ownerAuth = bearer(owner);
        colleagueAuth = bearer(user("colleague-" + suffix, ownerOrganization, false));
        outsiderAuth = bearer(user("other-" + suffix, otherOrganization, false));
        outsiderAdminAuth = bearer(user("admin-" + suffix, otherOrganization, true));
        doAnswer(invocation -> realAgent.analyzeScript(invocation.getArgument(0))).when(agent).analyzeScript(anyString());
    }

    @Test
    void createsProjectUsingAuthenticatedOrganizationAndCreatorAndListsOnlyItsTenant() throws Exception {
        JsonNode created = response(mvc.perform(post(BASE + "/projects").with(ownerAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "  雨夜试镜  ",
                        "organizationId", otherOrganization.getId(), "createdBy", -1))))
                .andExpect(status().isCreated()).andReturn());

        assertThat(created.path("name").asText()).isEqualTo("雨夜试镜");
        assertThat(created.path("organizationId").asLong()).isEqualTo(ownerOrganization.getId());
        assertThat(created.path("createdBy").asLong()).isEqualTo(owner.getId());
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.path("createdAt").asText()).isNotBlank();
        long projectId = created.path("id").asLong();

        assertThat(read(get(BASE + "/projects"), ownerAuth)).hasSize(1);
        assertThat(read(get(BASE + "/projects"), colleagueAuth)).hasSize(1);
        assertThat(read(get(BASE + "/projects"), outsiderAuth)).isEmpty();
        assertThat(read(get(BASE + "/projects"), outsiderAdminAuth)).isEmpty();
        assertThat(read(get(BASE + "/projects/" + projectId), colleagueAuth).path("id").asLong()).isEqualTo(projectId);
        mvc.perform(get(BASE + "/projects")).andExpect(status().is4xxClientError());
        mvc.perform(post(BASE + "/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"unauthenticated\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    void deniesEveryTenantSpecificReadWriteAndExportToOtherUsersAndAdmins() throws Exception {
        long projectId = createProject();
        long scriptId = createScript(projectId, SAMPLE);
        analyze(scriptId).andExpect(status().isOk());
        clearInvocations(agent);

        for (RequestPostProcessor other : List.of(outsiderAuth, outsiderAdminAuth)) {
            mvc.perform(get(BASE + "/projects/" + projectId).with(other)).andExpect(status().isNotFound());
            mvc.perform(get(BASE + "/projects/" + projectId + "/scripts").with(other)).andExpect(status().isNotFound());
            mvc.perform(post(BASE + "/projects/" + projectId + "/scripts").with(other)
                    .contentType(MediaType.APPLICATION_JSON).content(scriptJson("Draft", "Stolen write")))
                    .andExpect(status().isNotFound());
            for (String suffix : List.of("", "/scenes", "/elements", "/export/markdown")) {
                mvc.perform(get(BASE + "/scripts/" + scriptId + suffix).with(other)).andExpect(status().isNotFound());
            }
            mvc.perform(post(BASE + "/scripts/" + scriptId + "/analyze").with(other)).andExpect(status().isNotFound());
        }
        verifyNoInteractions(agent);
        assertThat(read(get(BASE + "/projects/" + projectId + "/scripts"), ownerAuth)).hasSize(1);
        assertThat(read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth)).hasSize(2);
    }

    @Test
    void preservesRawWhitespaceNormalizesLineEndingsAndExposesVersionHistory() throws Exception {
        long projectId = createProject();
        String source = "  INT. STUDIO - DAY\r\n\r\nALICE\rHello.  \r\n";
        long scriptId = createScript(projectId, source);
        JsonNode script = read(get(BASE + "/scripts/" + scriptId), ownerAuth);
        assertThat(script.path("versionName").asText()).isEqualTo("Draft 1");
        assertThat(script.path("rawText").asText()).isEqualTo(source.replace("\r\n", "\n").replace('\r', '\n'));
        assertThat(script.path("status").asText()).isEqualTo("UPLOADED");
        assertThat(script.path("originalFilename").asText()).isEqualTo("sample-screenplay.txt");
        JsonNode history = read(get(BASE + "/projects/" + projectId + "/scripts"), colleagueAuth);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("id").asLong()).isEqualTo(scriptId);
        assertThat(history.get(0).has("rawText")).isFalse();
        verifyNoInteractions(agent);
    }

    @Test
    void rejectsInvalidMetadataAndBlankOrOversizedScriptsBeforeParsing() throws Exception {
        long projectId = createProject();
        for (String text : List.of("", " \r\n\t ", "x".repeat(500_001))) {
            mvc.perform(post(BASE + "/projects/" + projectId + "/scripts").with(ownerAuth)
                    .contentType(MediaType.APPLICATION_JSON).content(scriptJson("Draft", text)))
                    .andExpect(status().isBadRequest());
        }
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("versionName", "Draft"),
                Map.of("versionName", " ", "rawText", SAMPLE),
                Map.of("versionName", "x".repeat(81), "rawText", SAMPLE),
                Map.of("versionName", "Draft", "rawText", SAMPLE, "originalFilename", "x".repeat(256)))) {
            mvc.perform(post(BASE + "/projects/" + projectId + "/scripts").with(ownerAuth)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        for (String name : List.of("   ", "x".repeat(121))) {
            mvc.perform(post(BASE + "/projects").with(ownerAuth).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name)))).andExpect(status().isBadRequest());
        }
        assertThat(read(get(BASE + "/projects/" + projectId + "/scripts"), ownerAuth)).isEmpty();
        verifyNoInteractions(agent);
    }

    @Test
    void analyzesRealSampleAndReplacesResultsWithoutDuplicates() throws Exception {
        long scriptId = createScript(createProject(), SAMPLE);
        JsonNode first = response(analyze(scriptId).andExpect(status().isOk()).andReturn());
        assertThat(first.path("sceneCount").asInt()).isEqualTo(2);
        assertThat(first.path("status").asText()).isEqualTo("ANALYZED");
        JsonNode oldScenes = read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth);
        JsonNode oldElements = read(get(BASE + "/scripts/" + scriptId + "/elements"), ownerAuth);
        assertThat(oldScenes).hasSize(2);
        assertThat(oldScenes.get(0).path("location").asText()).isEqualTo("旧摄影棚");
        assertThat(oldScenes.get(1).path("location").asText()).isEqualTo("摄影棚后巷");
        assertThat(elementKeys(oldElements)).contains("CHARACTER:林夏", "CHARACTER:周远",
                "PROP:收音机", "SOUND:雨声", "RISK:夜戏");

        analyze(scriptId).andExpect(status().isOk());
        JsonNode scenes = read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth);
        JsonNode elements = read(get(BASE + "/scripts/" + scriptId + "/elements"), ownerAuth);
        assertThat(scenes).hasSize(2);
        assertThat(scenes.get(0).path("id").asLong()).isNotEqualTo(oldScenes.get(0).path("id").asLong());
        assertThat(elementKeys(elements)).containsExactlyElementsOf(elementKeys(oldElements));
        assertThat(read(get(BASE + "/scripts/" + scriptId), ownerAuth).path("status").asText()).isEqualTo("ANALYZED");
        mvc.perform(get(BASE + "/scripts/" + scriptId + "/export/markdown").with(ownerAuth))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("旧摄影棚")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PROP：收音机")));
    }

    @ParameterizedTest(name = "preserve committed results when provider failure = {0}")
    @ValueSource(booleans = {true, false})
    void rollsBackFailedReplacementAndCommitsFailureStatusSeparately(boolean providerFailure) throws Exception {
        long scriptId = createScript(createProject(), SAMPLE);
        analyze(scriptId).andExpect(status().isOk());
        JsonNode oldScenes = read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth);
        JsonNode oldElements = read(get(BASE + "/scripts/" + scriptId + "/elements"), ownerAuth);

        if (providerFailure) {
            doThrow(new IllegalStateException("simulated provider failure")).when(agent).analyzeScript(anyString());
        } else {
            // Two scenes with the same sort order violate the real database unique constraint after deletion.
            ParsedScene scene = realAgent.analyzeScript(SAMPLE).scenes().getFirst();
            doReturn(new ScriptAnalysisResult(List.of(scene, scene))).when(agent).analyzeScript(anyString());
        }
        analyze(scriptId).andExpect(status().isInternalServerError());
        assertThat(read(get(BASE + "/scripts/" + scriptId), ownerAuth).path("status").asText()).isEqualTo("ANALYZE_FAILED");
        assertThat(read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth)).isEqualTo(oldScenes);
        assertThat(read(get(BASE + "/scripts/" + scriptId + "/elements"), ownerAuth)).isEqualTo(oldElements);
        String markdown = mvc.perform(get(BASE + "/scripts/" + scriptId + "/export/markdown").with(ownerAuth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(markdown).contains("ANALYZE_FAILED", "旧摄影棚", "摄影棚后巷", "收音机");

        doAnswer(invocation -> realAgent.analyzeScript(invocation.getArgument(0))).when(agent).analyzeScript(anyString());
        analyze(scriptId).andExpect(status().isOk());
        assertThat(read(get(BASE + "/scripts/" + scriptId), ownerAuth).path("status").asText()).isEqualTo("ANALYZED");
    }

    @Test
    void rejectsConcurrentAnalysisWith409AndReleasesDatabaseLockAfterCompletion() throws Exception {
        long scriptId = createScript(createProject(), SAMPLE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test analysis was not released");
            return realAgent.analyzeScript(invocation.getArgument(0));
        }).when(agent).analyzeScript(anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> analyze(scriptId).andReturn().getResponse().getStatus());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                analyze(scriptId).andExpect(status().isConflict());
                verify(agent, times(1)).analyzeScript(anyString());
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        analyze(scriptId).andExpect(status().isOk());
        assertThat(read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth)).hasSize(2);
    }

    @Test
    void acceptsMaximumScriptLengthAndTruncatesOnlyBoundedParsedColumns() throws Exception {
        String heading = "INT. " + "摄影棚".repeat(100) + " - NIGHT";
        String raw = heading + "\n" + "x".repeat(500_000 - heading.length() - 1);
        long scriptId = createScript(createProject(), raw);
        analyze(scriptId).andExpect(status().isOk());
        JsonNode scene = read(get(BASE + "/scripts/" + scriptId + "/scenes"), ownerAuth).get(0);
        assertThat(scene.path("heading").asText()).hasSize(255);
        assertThat(scene.path("location").asText()).hasSize(120);
        assertThat(scene.path("rawText").asText()).isEqualTo(raw);
        assertThat(read(get(BASE + "/scripts/" + scriptId), ownerAuth).path("rawText").asText()).hasSize(500_000);
    }

    @Test
    void exposesAllTenOperationsAndMarkdownMediaTypeInSwagger() throws Exception {
        JsonNode paths = response(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()).path("paths");
        Map<String, List<String>> expected = Map.of(
                "/projects", List.of("get", "post"),
                "/projects/{projectId}", List.of("get"),
                "/projects/{projectId}/scripts", List.of("get", "post"),
                "/scripts/{scriptId}", List.of("get"),
                "/scripts/{scriptId}/analyze", List.of("post"),
                "/scripts/{scriptId}/scenes", List.of("get"),
                "/scripts/{scriptId}/elements", List.of("get"),
                "/scripts/{scriptId}/export/markdown", List.of("get"));
        for (var path : expected.entrySet()) {
            for (String method : path.getValue()) {
                JsonNode operation = paths.path(BASE + path.getKey()).path(method);
                assertThat(operation.isMissingNode()).as(method + " " + path.getKey()).isFalse();
                assertThat(operation.path("security").toString()).contains("bearerAuth");
            }
        }
        assertThat(paths.path(BASE + "/scripts/{scriptId}/export/markdown").path("get")
                .path("responses").path("200").path("content").has("text/markdown")).isTrue();
    }

    private Organization organization(String slug) {
        return organizations.save(Organization.builder().name(slug).slug(slug).build());
    }

    private User user(String name, Organization organization, boolean admin) {
        Set<Role> userRoles = admin ? Set.of(roles.save(Role.builder().name("ADMIN")
                .organization(organization).permissions(Set.of()).build())) : Set.of();
        return users.save(User.builder().email(name + "@test.local").fullName("Integration User")
                .password("unused-test-password").organization(organization).roles(userRoles).build());
    }

    private RequestPostProcessor bearer(User user) {
        String token = tokens.generateToken(new CustomUserDetails(user));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private long createProject() throws Exception {
        return response(mvc.perform(post(BASE + "/projects").with(ownerAuth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"雨夜试镜\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long createScript(long projectId, String source) throws Exception {
        return response(mvc.perform(post(BASE + "/projects/" + projectId + "/scripts").with(ownerAuth)
                .contentType(MediaType.APPLICATION_JSON).content(scriptJson("  Draft 1  ", source)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private String scriptJson(String versionName, String source) throws Exception {
        return json.writeValueAsString(Map.of("versionName", versionName,
                "originalFilename", "sample-screenplay.txt", "rawText", source));
    }

    private org.springframework.test.web.servlet.ResultActions analyze(long scriptId) throws Exception {
        return mvc.perform(post(BASE + "/scripts/" + scriptId + "/analyze").with(ownerAuth));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, RequestPostProcessor auth) throws Exception {
        return response(mvc.perform(request.with(auth)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode response(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> elementKeys(JsonNode elements) {
        List<String> keys = new ArrayList<>();
        elements.forEach(element -> keys.add(element.path("type").asText() + ":" + element.path("name").asText()));
        return keys;
    }
}
