package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public diagnosis uses exception types/status only; provider bodies and credentials never become codes. */
class StreamingResearchModelTest {
    private static final String PRIVATE = "secret-provider-body-token-do-not-publish";
    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test void modelHttpFailuresExposeOnlyKnownSafeCodesForSelectionAndAnswer() {
        int[] statuses = {401, 403, 429, 500, 502, 503, 400};
        String[] codes = {"RESEARCH_MODEL_AUTH_FAILED", "RESEARCH_MODEL_AUTH_FAILED", "RESEARCH_MODEL_RATE_LIMITED",
                "RESEARCH_MODEL_SERVICE_UNAVAILABLE", "RESEARCH_MODEL_SERVICE_UNAVAILABLE",
                "RESEARCH_MODEL_SERVICE_UNAVAILABLE", "RESEARCH_MODEL_HTTP_FAILED"};
        for (int i = 0; i < statuses.length; i++) {
            var headers = new HttpHeaders(); headers.setBearerAuth(PRIVATE);
            var failure = WebClientResponseException.create(statuses[i], PRIVATE, headers,
                    PRIVATE.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertDiagnosis(failure, codes[i]);
            // DashScopeAgentStreamer deliberately wraps typed HTTP failures; do not parse its localized message.
            assertDiagnosis(new IllegalStateException(PRIVATE, failure), codes[i]);
        }
    }

    @Test void networkFailureIsDistinctFromHttpAndNeverExposesRequestUriOrHeader() {
        var headers = new HttpHeaders(); headers.setBearerAuth(PRIVATE);
        var failure = new WebClientRequestException(new IOException(PRIVATE), HttpMethod.POST,
                URI.create("https://private.invalid/chat?token=" + PRIVATE), headers);
        assertDiagnosis(failure, "RESEARCH_MODEL_NETWORK_UNAVAILABLE");
        assertDiagnosis(new IllegalStateException(PRIVATE, failure), "RESEARCH_MODEL_NETWORK_UNAVAILABLE");
    }

    @Test void unknownFailureMessagesCannotForgeKnownDiagnosisCodes() {
        assertDiagnosis(new IllegalStateException("RESEARCH_MODEL_AUTH_FAILED " + PRIVATE), "RESEARCH_MODEL_FAILED");
        assertDiagnosis(new IllegalArgumentException(PRIVATE), "RESEARCH_MODEL_FAILED");
    }

    @Test void cancellationKeepsCancellationSemanticsRatherThanBecomingProviderFailure() {
        var streamer = mock(DashScopeAgentStreamer.class);
        when(streamer.stream(anyString(), anyList(), any(ToolCallback[].class), any(), any()))
                .thenThrow(new CancellationException("cancelled"));
        var model = new StreamingResearchModel(streamer);
        assertThrows(CancellationException.class, () -> model.select("VPN401", List.of(), BUDGET, () -> true));
        assertThrows(CancellationException.class, () -> model.answer("VPN401", List.of(), BUDGET, () -> true));
        doThrow(new IllegalStateException(PRIVATE, new CancellationException("cancelled"))).when(streamer)
                .stream(anyString(), anyList(), any(ToolCallback[].class), any(), any());
        assertThrows(CancellationException.class, () -> model.select("VPN401", List.of(), BUDGET, () -> true));
        assertThrows(CancellationException.class, () -> model.answer("VPN401", List.of(), BUDGET, () -> true));
    }

    @Test void completedModelResponseWithoutToolCallIsSelectionFailureNotTransportFailure() {
        var streamer = mock(DashScopeAgentStreamer.class);
        when(streamer.stream(anyString(), anyList(), any(ToolCallback[].class), any(), any()))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("No tool was selected.")))));
        var failure = assertThrows(IllegalStateException.class,
                () -> new StreamingResearchModel(streamer).select("VPN401", List.of(), BUDGET, () -> false));
        assertEquals("RESEARCH_EXPECTS_ONE_TOOL_SELECTION", failure.getMessage());
    }

    private void assertDiagnosis(RuntimeException upstream, String expected) {
        var streamer = mock(DashScopeAgentStreamer.class);
        when(streamer.stream(anyString(), anyList(), any(ToolCallback[].class), any(), any())).thenThrow(upstream);
        var model = new StreamingResearchModel(streamer);
        var selection = assertThrows(IllegalStateException.class,
                () -> model.select("VPN401", List.of(), BUDGET, () -> false));
        var answer = assertThrows(IllegalStateException.class,
                () -> model.answer("VPN401", List.of(), BUDGET, () -> false));
        for (var failure : List.of(selection, answer)) {
            assertEquals(expected, failure.getMessage());
            assertNull(failure.getCause(), "Public diagnostic exception must not retain provider secrets in its cause");
            assertFalse(failure.toString().contains(PRIVATE));
            assertEquals(0, failure.getSuppressed().length);
        }
    }
}
