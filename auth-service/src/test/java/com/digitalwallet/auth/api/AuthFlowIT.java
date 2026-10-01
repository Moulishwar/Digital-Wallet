package com.digitalwallet.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalwallet.auth.AbstractAuthIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthFlowIT extends AbstractAuthIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode register(String handle, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handle":"%s","email":"%s","password":"%s","fullName":"Test User"}
                                """.formatted(handle, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("registration succeeds even though wallet-service is unreachable")
    void registrationSurvivesWalletServiceBeingDown() throws Exception {
        JsonNode body = register("alice_r", "alice@example.com");

        assertThat(body.get("handle").asText()).isEqualTo("alice_r");
        assertThat(body.get("walletProvisioned").asBoolean())
                .as("wallet-service is down in this test, and registration must still succeed")
                .isFalse();
    }

    @Test
    @DisplayName("handle and email are stored folded, so case does not create a second account")
    void identifiersAreNormalized() throws Exception {
        JsonNode body = register("MixedCase", "Alice@Example.COM");

        assertThat(body.get("handle").asText()).isEqualTo("mixedcase");
        assertThat(body.get("email").asText()).isEqualTo("alice@example.com");

        // The same address in different case must not be able to register again.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handle":"other","email":"ALICE@example.com","password":"%s","fullName":"X"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a duplicate handle is reported plainly, a duplicate email is not")
    void enumerationExposureDiffersByIdentifier() throws Exception {
        register("taken", "first@example.com");

        MvcResult handleClash = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handle":"taken","email":"second@example.com","password":"%s","fullName":"X"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(handleClash.getResponse().getContentAsString())
                .as("handles are public identifiers, so saying one is taken reveals nothing new")
                .contains("handle is already taken");

        MvcResult emailClash = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handle":"fresh","email":"first@example.com","password":"%s","fullName":"X"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(emailClash.getResponse().getContentAsString())
                .as("confirming an email is registered would let a caller enumerate accounts")
                .doesNotContain("already")
                .contains("could not be completed");
    }

    @Test
    @DisplayName("a short password is rejected before it can be hashed")
    void weakPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handle":"shorty","email":"s@example.com","password":"short","fullName":"X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("login returns a bearer token and a refresh token")
    void loginIssuesTokens() throws Exception {
        register("bob_l", "bob@example.com");
        JsonNode tokens = login("bob@example.com");

        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(tokens.get("accessToken").asText()).contains(".");
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a wrong password and an unknown address fail identically")
    void loginFailuresAreIndistinguishable() throws Exception {
        register("carol", "carol@example.com");

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"carol@example.com","password":"not-the-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownUser = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"not-the-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(readDetail(wrongPassword))
                .as("if these differed, a caller could discover which addresses are registered")
                .isEqualTo(readDetail(unknownUser))
                .isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("the issued token authenticates against this service's own endpoints")
    void accessTokenWorks() throws Exception {
        register("dave", "dave@example.com");
        String accessToken = login("dave@example.com").get("accessToken").asText();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("dave"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("an unauthenticated request gets a Problem Detail, not an empty 401")
    void unauthenticatedRequestsGetProblemDetail() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.type").value("https://digitalwallet.example/errors/unauthorized"));
    }

    @Test
    @DisplayName("refreshing rotates the token — the old one stops working")
    void refreshRotatesTheToken() throws Exception {
        register("erin", "erin@example.com");
        String firstRefresh = login("erin@example.com").get("refreshToken").asText();

        JsonNode refreshed = refresh(firstRefresh);
        String secondRefresh = refreshed.get("refreshToken").asText();

        assertThat(secondRefresh).isNotEqualTo(firstRefresh);
        assertThat(refreshed.get("accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("replaying a spent refresh token revokes every session for that user")
    void refreshTokenReuseBurnsTheChain() throws Exception {
        register("frank", "frank@example.com");
        String firstRefresh = login("frank@example.com").get("refreshToken").asText();

        String secondRefresh = refresh(firstRefresh).get("refreshToken").asText();

        // Replaying the spent token: either the client is broken or someone stole it. There is no
        // way to tell which, so everything is revoked.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(firstRefresh)))
                .andExpect(status().isUnauthorized());

        // The legitimate token is now dead too — that is the point, not a side effect.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(secondRefresh)))
                .andExpect(status().isUnauthorized());

        Integer live = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL", Integer.class);
        assertThat(live).isZero();
    }

    @Test
    @DisplayName("logout revokes the session and is safe to repeat")
    void logoutRevokesAndIsIdempotent() throws Exception {
        register("grace", "grace@example.com");
        String refreshToken = login("grace@example.com").get("refreshToken").asText();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isNoContent());
        }

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logging out an unknown token still returns 204, revealing nothing")
    void logoutOfUnknownTokenIsSilent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"not-a-real-token"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("handle lookup returns the display name and nothing contactable")
    void handleLookupExposesOnlyWhatASenderNeeds() throws Exception {
        register("henry", "henry@example.com");
        String accessToken = login("henry@example.com").get("accessToken").asText();

        MvcResult result = mockMvc.perform(get("/api/users/lookup")
                        .param("handle", "henry")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("henry"))
                .andExpect(jsonPath("$.fullName").value("Test User"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("a lookup by handle must not double as an email harvester")
                .doesNotContain("henry@example.com");
    }

    @Test
    @DisplayName("handle lookup requires authentication")
    void handleLookupIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/users/lookup").param("handle", "henry"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the JWKS endpoint publishes the public key and never the private one")
    void jwksExposesOnlyPublicKeyMaterial() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value("wallet-signing-key"))
                .andExpect(jsonPath("$.keys[0].n").exists())
                .andExpect(jsonPath("$.keys[0].e").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // "d" is the RSA private exponent; p, q, dp, dq and qi are the CRT parameters. Any of them
        // appearing here would hand out the ability to mint tokens.
        assertThat(objectMapper.readTree(body).get("keys").get(0).has("d"))
                .as("the private exponent must never be published")
                .isFalse();
        assertThat(body).doesNotContain("\"p\":").doesNotContain("\"q\":");
    }

    private String readDetail(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("detail").asText();
    }
}
