package com.figuard.domain.enums;

public enum DelegatedTokenStatus {
    ACTIVE,   // token is usable
    REVOKED   // token was explicitly revoked — any authorize attempt returns INVALID_SESSION_TOKEN
}
