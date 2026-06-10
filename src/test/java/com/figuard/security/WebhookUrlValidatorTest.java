package com.figuard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookUrlValidatorTest {

    private WebhookUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookUrlValidator();
        ReflectionTestUtils.setField(validator, "enabled", true);
        ReflectionTestUtils.setField(validator, "allowPrivate", false);
    }

    @Test
    void rejects_loopback() {
        assertThatThrownBy(() -> validator.validate("https://127.0.0.1/hook"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate("https://localhost/hook"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejects_cloud_metadata_endpoint() {
        // 169.254.169.254 is link-local — the AWS/GCP metadata endpoint
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejects_private_ranges() {
        assertThatThrownBy(() -> validator.validate("https://10.0.0.5/hook"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate("https://192.168.1.10/hook"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate("https://172.16.0.1/hook"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejects_non_http_scheme() {
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> validator.validate("gopher://evil/"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejects_plain_http_when_not_allow_private() {
        assertThatThrownBy(() -> validator.validate("http://example.com/hook"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void accepts_public_https() {
        assertThatCode(() -> validator.validate("https://example.com/figuard"))
            .doesNotThrowAnyException();
    }

    @Test
    void isSafe_returns_false_for_private() {
        assertThat(validator.isSafe("https://127.0.0.1/hook")).isFalse();
    }

    @Test
    void isSafe_returns_true_for_public() {
        assertThat(validator.isSafe("https://example.com/figuard")).isTrue();
    }

    @Test
    void allow_private_escape_hatch_permits_localhost() {
        ReflectionTestUtils.setField(validator, "allowPrivate", true);
        assertThatCode(() -> validator.validate("http://localhost:8080/hook"))
            .doesNotThrowAnyException();
    }

    @Test
    void disabled_guard_permits_anything() {
        ReflectionTestUtils.setField(validator, "enabled", false);
        assertThatCode(() -> validator.validate("http://169.254.169.254/"))
            .doesNotThrowAnyException();
    }
}
