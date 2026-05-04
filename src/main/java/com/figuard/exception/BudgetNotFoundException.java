package com.figuard.exception;

import java.util.UUID;

public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException(UUID id) {
        super("Budget not found: " + id);
    }
}
