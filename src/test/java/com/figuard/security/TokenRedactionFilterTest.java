package com.figuard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Verifies that TokenRedactionFilter physically prevents raw credential headers
 * from being readable by any downstream component.
 */
class TokenRedactionFilterTest {

    TokenRedactionFilter filter = new TokenRedactionFilter();

    // -------------------------------------------------------------------------
    // Header value redaction
    // -------------------------------------------------------------------------

    @Test
    void getHeader_returnsRedacted_forXSessionToken() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("X-Session-Token", "st_real_token_value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Capture the wrapped request that the filter passes downstream
        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        assertThat(wrappedRequest[0].getHeader("X-Session-Token")).isEqualTo("[REDACTED]");
    }

    @Test
    void getHeader_returnsRedacted_forXAgentBudgetKey() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("X-Agent-Budget-Key", "fg_live_realkey123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        assertThat(wrappedRequest[0].getHeader("X-Agent-Budget-Key")).isEqualTo("[REDACTED]");
    }

    @Test
    void getHeader_isCaseInsensitive_forRedactedHeaders() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("x-session-token", "st_lowercase_token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        // Both original and upper-case lookups must be redacted
        assertThat(wrappedRequest[0].getHeader("x-session-token")).isEqualTo("[REDACTED]");
        assertThat(wrappedRequest[0].getHeader("X-Session-Token")).isEqualTo("[REDACTED]");
        assertThat(wrappedRequest[0].getHeader("X-SESSION-TOKEN")).isEqualTo("[REDACTED]");
    }

    @Test
    void getHeader_passesThrough_forOtherHeaders() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("Content-Type", "application/json");
        original.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        assertThat(wrappedRequest[0].getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(wrappedRequest[0].getHeader("Accept")).isEqualTo("application/json");
    }

    // -------------------------------------------------------------------------
    // getHeaders (Enumeration form)
    // -------------------------------------------------------------------------

    @Test
    void getHeaders_returnsRedacted_forXSessionToken() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("X-Session-Token", "st_real_token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        List<String> values = Collections.list(wrappedRequest[0].getHeaders("X-Session-Token"));
        assertThat(values).containsExactly("[REDACTED]");
    }

    // -------------------------------------------------------------------------
    // Header names remain visible
    // -------------------------------------------------------------------------

    @Test
    void getHeaderNames_stillReturnsRedactedHeaderNames() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("X-Session-Token", "st_token");
        original.addHeader("Content-Type", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        final HttpServletRequest[] wrappedRequest = new HttpServletRequest[1];
        FilterChain chain = (req, res) -> wrappedRequest[0] = (HttpServletRequest) req;

        filter.doFilter(original, response, chain);

        List<String> names = Collections.list(wrappedRequest[0].getHeaderNames());
        // Header names are visible (for routing) — only the values are redacted
        assertThat(names).anySatisfy(n -> assertThat(n).isEqualToIgnoringCase("X-Session-Token"));
        assertThat(names).anySatisfy(n -> assertThat(n).isEqualToIgnoringCase("Content-Type"));
    }

    // -------------------------------------------------------------------------
    // Non-HTTP requests fall through unchanged
    // -------------------------------------------------------------------------

    @Test
    void nonHttpRequest_passesThrough_unchanged() throws Exception {
        // ServletRequest (not HttpServletRequest) should not be wrapped
        jakarta.servlet.ServletRequest nonHttp = mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse nonHttpResponse = mock(jakarta.servlet.ServletResponse.class);

        final jakarta.servlet.ServletRequest[] passed = new jakarta.servlet.ServletRequest[1];
        FilterChain chain = (req, res) -> passed[0] = req;

        filter.doFilter(nonHttp, nonHttpResponse, chain);

        assertThat(passed[0]).isSameAs(nonHttp);
    }
}
