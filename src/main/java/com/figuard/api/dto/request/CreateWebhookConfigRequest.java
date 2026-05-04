package com.figuard.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Getter @Setter
public class CreateWebhookConfigRequest {

    @NotBlank(message = "url is required")
    @URL(message = "url must be a valid HTTP/HTTPS URL")
    @Size(max = 2000, message = "url must not exceed 2000 characters")
    private String url;

    @NotBlank(message = "secret is required")
    @Size(min = 16, message = "secret must be at least 16 characters")
    private String secret;

    @NotEmpty(message = "events must contain at least one event type")
    private List<String> events;
}
