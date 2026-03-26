package com.picsou.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException account(Long id) {
        return new ResourceNotFoundException("Account not found: " + id);
    }

    public static ResourceNotFoundException goal(Long id) {
        return new ResourceNotFoundException("Goal not found: " + id);
    }

    public static ResourceNotFoundException requisition(String requisitionId) {
        return new ResourceNotFoundException("Requisition not found: " + requisitionId);
    }
}
