package com.figuard.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

public class BudgetNotFoundException extends ResponseStatusException {

    public BudgetNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Budget not found: " + id);
    }
}
