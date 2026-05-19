package com.figuard.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI figuardOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FiGuard API")
                .version("v1")
                .description("""
                    Pre-flight spend authorization for AI agents.

                    FiGuard checks limits and reserves capacity before your agent acts — \
                    money, tokens, API calls, any bounded resource. \
                    If it doesn't get a yes, nothing moves.

                    **Authentication:** pass your API key in the `X-Agent-Budget-Key` header. \
                    Click **Authorize** above to set it for all requests in this UI.

                    **Authorization flow:** `POST /budgets` → `POST /authorize` (with `X-Session-Token`) → \
                    `POST /events/{id}/confirm` or `/fail` or `/void`
                    """)
                .contact(new Contact()
                    .name("FiGuard")
                    .url("https://github.com/figuard/figuard-core")))
            .components(new Components()
                .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-Agent-Budget-Key")
                    .description("Tenant API key. Prefix: `ab_live_`. " +
                                 "Create one via `POST /api/v1/api-keys` or seed one with the quickstart script.")))
            .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
