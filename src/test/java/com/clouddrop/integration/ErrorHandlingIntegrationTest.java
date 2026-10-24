package com.clouddrop.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Every failure must come back as the same JSON shape, with the right status and no internal details. */
class ErrorHandlingIntegrationTest extends IntegrationTestBase {

    @Test
    void errorBody_hasConsistentShape() throws Exception {
        String token = newUserToken("alice@example.com");

        mockMvc.perform(get("/api/files/9999").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("File not found"))
                .andExpect(jsonPath("$.path").value("/api/files/9999"));
    }

    @Test
    void unauthenticated401_usesTheSameJsonShape_andChallengeHeader() throws Exception {
        mockMvc.perform(get("/api/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/files"));
    }

    @Test
    void forbidden403_usesTheSameJsonShape() throws Exception {
        String aliceToken = newUserToken("alice@example.com");
        String bobToken = newUserToken("bob@example.com");
        long id = upload(aliceToken, "a.txt", "text/plain", "x".getBytes());

        mockMvc.perform(get("/api/files/" + id).header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void malformedJson_returns400_withoutLeakingParserDetails() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"));
    }

    @Test
    void missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericId_returns400() throws Exception {
        String token = newUserToken("alice@example.com");

        mockMvc.perform(get("/api/files/abc").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));
    }

    @Test
    void unknownUrlAndWrongMethod_areNot500s() throws Exception {
        String token = newUserToken("alice@example.com");

        mockMvc.perform(get("/api/nope").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(put("/api/files/1").header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unknownUrl_withoutToken_isDeniedByDefault() throws Exception {
        // default-deny: an unauthenticated caller learns nothing about which URLs exist
        mockMvc.perform(get("/api/nope")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
