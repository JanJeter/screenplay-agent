package com.urke.saasbackendstarter.controller;

import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.dto.organization.OrganizationSummary;
import com.urke.saasbackendstarter.mapper.OrganizationMapper;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import com.urke.saasbackendstarter.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrganizationController.class)
@Import(com.urke.saasbackendstarter.security.SecurityConfig.class)
class OrganizationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean OrganizationService organizationService;
    @MockBean OrganizationMapper mapper;
    @MockBean CurrentUserProvider current;
    @MockBean com.urke.saasbackendstarter.security.JwtTokenProvider jwt;
    @MockBean org.springframework.security.core.userdetails.UserDetailsService details;

    @Test @WithMockUser(roles = "USER") void creationIsDisabledForFixedWorkspace() throws Exception {
        mvc.perform(post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("当前工作台使用固定团队工作区，不支持此操作"));
        verifyNoInteractions(organizationService);
    }
    @Test @WithMockUser(roles = "ADMIN") void adminCannotDeleteAnyWorkspace() throws Exception {
        mvc.perform(delete("/api/v1/organizations/999")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前工作台使用固定团队工作区，不支持此操作"));
        verifyNoInteractions(organizationService);
    }
    @Test @WithMockUser(roles = "USER") void ordinaryUserCannotDeleteWorkspace() throws Exception {
        mvc.perform(delete("/api/v1/organizations/1")).andExpect(status().isForbidden());
        verifyNoInteractions(organizationService);
    }
    @Test @WithMockUser(roles = "ADMIN") void adminListingContainsOnlyCurrentWorkspace() throws Exception {
        own();
        mvc.perform(get("/api/v1/organizations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.totalElements").value(1));
        verifyNoInteractions(organizationService);
    }
    @Test @WithMockUser(roles = "USER") void nameFilterIsCaseInsensitiveAndCannotSearchOtherWorkspaces() throws Exception {
        own();
        mvc.perform(get("/api/v1/organizations").param("name","team")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/organizations").param("name","Other")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        verifyNoInteractions(organizationService);
    }
    @Test @WithMockUser(roles = "ADMIN") void laterPageIsEmptyWithoutFakingTotal() throws Exception {
        own();
        mvc.perform(get("/api/v1/organizations").param("page","1").param("size","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0)).andExpect(jsonPath("$.totalElements").value(1));
    }
    @Test @WithMockUser(roles = "ADMIN") void invalidPageSizeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/organizations").param("size","1000")).andExpect(status().isBadRequest());
    }
    private void own() {
        Organization org = Organization.builder().id(7L).name("Team").slug("team").build();
        when(current.getCurrentOrganization()).thenReturn(org);
        when(mapper.toSummary(org)).thenReturn(OrganizationSummary.builder().id(7L).name("Team").slug("team").build());
    }
}
