package com.figuard.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ReceiptNotFoundException extends ResponseStatusException {

    public ReceiptNotFoundException(String token) {
        super(HttpStatus.NOT_FOUND, "Receipt not found: " + token);
    }
}
