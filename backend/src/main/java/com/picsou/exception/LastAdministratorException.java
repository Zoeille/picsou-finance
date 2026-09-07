package com.picsou.exception;

public class LastAdministratorException extends RuntimeException {

    public LastAdministratorException() {
        super("Cannot delete the last administrator");
    }
}
