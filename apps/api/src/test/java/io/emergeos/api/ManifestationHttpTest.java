package io.emergeos.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.CaptureThoughtCommand;
import io.emergeos.core.application.ManifestationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "emerge.prototype.principal-id=http-test-owner")
class ManifestationHttpTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private ManifestationService service;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void capturesAndApprovesWithoutIdentityFieldsInRequestBodies() throws Exception {
    var captureResult =
        mockMvc
            .perform(
                post("/api/v1/manifestations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "content": "让想法成为可验证的行动",
                          "sourceType": "TEXT",
                          "sourceRef": "http-test",
                          "dataClass": "PERSONAL"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.principalId").value("http-test-owner"))
            .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
            .andReturn();

    String capturedJson = captureResult.getResponse().getContentAsString();
    String manifestationId = JsonPath.read(capturedJson, "$.manifestationId");
    String artifactHash = JsonPath.read(capturedJson, "$.artifact.contentHash");

    mockMvc
        .perform(
            post("/api/v1/manifestations/{id}/approve", manifestationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"artifactHash\":\"" + artifactHash + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principalId").value("http-test-owner"))
        .andExpect(jsonPath("$.status").value("COMPLETED_WITH_RECEIPT"))
        .andExpect(jsonPath("$.receipt.artifactHash").value(artifactHash));

    mockMvc
        .perform(get("/api/v1/manifestations/{id}", manifestationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principalId").value("http-test-owner"))
        .andExpect(jsonPath("$.status").value("COMPLETED_WITH_RECEIPT"));
  }

  @Test
  void returnsNotFoundForMissingOrDifferentlyOwnedManifestations() throws Exception {
    var foreign =
        service.capture(
            new CaptureThoughtCommand(
                "another-user",
                "this must remain private",
                "TEXT",
                "http-test",
                DataClass.PERSONAL));

    mockMvc
        .perform(get("/api/v1/manifestations/does-not-exist"))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/api/v1/manifestations/{id}", foreign.manifestationId()))
        .andExpect(status().isNotFound());
  }
}
