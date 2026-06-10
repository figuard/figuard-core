package com.figuard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for outbound webhook URLs.
 *
 * The server POSTs to URLs that users register. Without validation an attacker can register
 * {@code http://169.254.169.254/...} (cloud metadata) or {@code http://localhost:8080/internal}
 * and have the server fetch internal resources for them — classic SSRF, and on a cloud host
 * that can mean credential theft.
 *
 * Validation runs at BOTH registration time and send time. DNS can rebind between the two,
 * so checking only at registration is insufficient — the send-time check resolves the host
 * again and rejects it if it now points at a blocked range.
 *
 * For local development and integration tests that POST to localhost, set
 * {@code figuard.security.webhook-ssrf-guard.allow-private=true}.
 */
@Slf4j
@Component
public class WebhookUrlValidator {

    @Value("${figuard.security.webhook-ssrf-guard.enabled:true}")
    private boolean enabled;

    /** Allow loopback/private targets — for local dev and tests only. */
    @Value("${figuard.security.webhook-ssrf-guard.allow-private:false}")
    private boolean allowPrivate;

    /** Throws 422 if the URL is unsafe. Call at registration and again at send time. */
    public void validate(String rawUrl) {
        if (!enabled) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            reject("Webhook URL is malformed.");
            return;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            reject("Webhook URL must use http or https.");
        }
        if (scheme.equals("http") && !allowPrivate) {
            reject("Webhook URL must use https.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            reject("Webhook URL has no host.");
            return;
        }

        if (allowPrivate) {
            return; // dev/test escape hatch — private ranges permitted
        }

        // Resolve every address the host maps to and block private / loopback / link-local.
        // Checking all addresses defeats a host that resolves to both a public and a private IP.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            reject("Webhook URL host does not resolve.");
            return;
        }

        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()    // 169.254.0.0/16 — includes cloud metadata
                || addr.isSiteLocalAddress()    // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {
                reject("Webhook URL resolves to a private, loopback, or link-local address.");
            }
        }
    }

    /**
     * Non-throwing variant for the async send path. DNS can rebind between registration and
     * send, so re-check here; if the URL is now unsafe, the caller should skip the delivery
     * rather than fetch an internal resource.
     */
    public boolean isSafe(String rawUrl) {
        try {
            validate(rawUrl);
            return true;
        } catch (ResponseStatusException e) {
            log.warn("Webhook send blocked by SSRF guard: {}", e.getReason());
            return false;
        }
    }

    private void reject(String message) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
