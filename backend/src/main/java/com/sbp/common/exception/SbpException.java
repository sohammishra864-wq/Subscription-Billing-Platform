package com.sbp.common.exception;

public abstract class SbpException extends RuntimeException {
    protected SbpException(String message) { super(message); }
}
