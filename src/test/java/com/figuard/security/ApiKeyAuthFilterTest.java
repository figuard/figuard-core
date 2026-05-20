package com.figuard.security;

import com.figuard.domain.entity.ApiKey;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock ApiKeyRepository apiKeyRepository;
    @Mock FilterChain filterChain;

    @InjectMocks ApiKeyAuthFilter filter;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void missingHeader_returns401() throws Exception {
        var request  = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    void invalidKey_returns401() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "bad_key");
        var response = new MockHttpServletResponse();

        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    void validKey_proceedsAndSetsTenantContext() throws Exception {
        String rawKey = "fg_live_testkey123";
        String keyHash = ApiKeyAuthFilter.sha256(rawKey);

        Tenant tenant = new Tenant();
        ApiKey apiKey = new ApiKey();
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix("fg_live_t");
        apiKey.setActive(true);
        apiKey.setTenant(tenant);

        when(apiKeyRepository.findByKeyHash(keyHash)).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(any())).thenReturn(apiKey);

        var request  = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, rawKey);
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(filterChain).doFilter(request, response);
        // TenantContext cleared in finally — should be null after filter completes
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void inactiveKey_returns401() throws Exception {
        String rawKey = "fg_live_inactive";
        String keyHash = ApiKeyAuthFilter.sha256(rawKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyHash(keyHash);
        apiKey.setActive(false);

        when(apiKeyRepository.findByKeyHash(keyHash)).thenReturn(Optional.of(apiKey));

        var request  = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, rawKey);
        var response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }
}
