package com.rkp.tenk.exception;

public class QueryException extends RuntimeException {

    public QueryException(String message) {
        super(message);
    }

    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
