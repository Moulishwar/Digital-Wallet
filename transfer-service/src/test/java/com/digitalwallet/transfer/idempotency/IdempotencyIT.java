package com.digitalwallet.transfer.idempotency;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.digitalwallet.transfer.AbstractTransferIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The guarantee that makes this API safe to retry: one key, one outcome.
 *
 * <p>The concurrent case is the one that matters. A client whose request times out will fire the
 * same key again while the first is very possibly still running, so "check whether the key exists,
 * then insert it" is not good enough — two requests can both pass the check. What saves it is the
 * unique constraint on {@code (idempotency_key, user_id)}: the database picks a winner, and the
 * loser adopts the winner's answer instead of doing the work again.
 */
@AutoConfigureMockMvc
class IdempotencyIT extends AbstractTransferIntegrationTest {

    private static final int CONCURRENT_ATTEMPTS = 20;

    @Autowired
    private MockMvc mockMvc;

    private static String sendBody(String handle, long amountMinor, String note) {
        return """
                {"recipientHandle": "%s", "amountMinor": %d, "note": %s}
                """.formatted(handle, amountMinor, note == null ? "null" : "\"" + note + "\"");
    }

    @Test
    @DisplayName("replaying a key returns the original response and does not send again")
    void replayReturnsTheOriginalAnswer() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingSucceeds(45_000L);
        String key = UUID.randomUUID().toString();

        String first = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, "Dinner")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, "Dinner")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Field for field, including the transfer id and both timestamps: the retry receives the
        // original answer, not a fresh one that merely resembles it. Compared as JSON rather than
        // as text because the response is stored in a jsonb column, and PostgreSQL reorders jsonb
        // keys — a difference no correct client can observe.
        assertSameJson(first, second);
        assertTransferCount(1);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/internal/postings")));
    }

    @Test
    @DisplayName("a failure is replayed too — the key records what the request answered, not just success")
    void failuresAreAlsoReplayed() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingRejected(422, "insufficient-funds", "Balance is 1000 but the posting requires 5000");
        String key = UUID.randomUUID().toString();

        String first = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        assertSameJson(first, second);
        assertTransferCount(1);
        // The retry did not re-attempt the posting: the key already knew the answer.
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/internal/postings")));
    }

    @Test
    @DisplayName("reusing a key with a different body is a 409, not someone else's receipt")
    void keyReuseWithADifferentBodyConflicts() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubHandleResolves("bob", UUID.randomUUID());
        stubPostingSucceeds(45_000L);
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 5_000L, null)))
                .andExpect(status().isCreated());

        // A different amount is a different request. Answering it with the first one's result would
        // silently swallow a payment the user believes they made.
        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("alice", 9_999L, null)))
                .andExpect(status().isConflict());

        // And a different recipient likewise.
        mockMvc.perform(post("/api/transfers")
                        .with(userToken(sender))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("bob", 5_000L, null)))
                .andExpect(status().isConflict());

        assertTransferCount(1);
    }

    @Test
    @DisplayName("one user's key does not collide with another's identical key")
    void keysAreScopedPerUser() throws Exception {
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingSucceeds(45_000L);

        for (UUID user : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            mockMvc.perform(post("/api/transfers")
                            .with(userToken(user))
                            .header("Idempotency-Key", "shared-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sendBody("alice", 5_000L, null)))
                    .andExpect(status().isCreated());
        }

        assertTransferCount(2);
    }

    @Test
    @DisplayName("twenty concurrent requests with one key produce one transfer and twenty identical "
            + "responses")
    void concurrentDuplicatesCollapseToOne() throws Exception {
        UUID sender = UUID.randomUUID();
        stubHandleResolves("alice", UUID.randomUUID());
        stubPostingSucceeds(45_000L);
        String key = UUID.randomUUID().toString();

        // Every thread is released at once, so the requests genuinely overlap rather than queueing.
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<String>> attempts = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) {
            attempts.add(() -> {
                startLine.await();
                return mockMvc.perform(post("/api/transfers")
                                .with(userToken(sender))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sendBody("alice", 5_000L, "Dinner")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            startLine.countDown();

            List<String> responses = new ArrayList<>();
            for (Future<String> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(CONCURRENT_ATTEMPTS).doesNotContainNull();
            // All twenty callers were told the same thing, including the same transfer id.
            for (String response : responses) {
                assertSameJson(responses.get(0), response);
            }
        } finally {
            pool.shutdownNow();
        }

        // The database picked exactly one winner, and only that one moved any money.
        assertTransferCount(1);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/internal/postings")));
    }

    private void assertTransferCount(int expected) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM transfer", Integer.class);
        assertThat(count).isEqualTo(expected);
    }

    /**
     * Asserts two responses carry exactly the same fields and values.
     *
     * <p>Strict comparison — no extra or missing fields are tolerated — but insensitive to key
     * order, which JSON does not define as meaningful and which PostgreSQL's jsonb normalisation
     * changes on the way through storage.
     */
    private static void assertSameJson(String expected, String actual) throws Exception {
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.STRICT);
    }
}
