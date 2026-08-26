package com.ap0stole.sheetsmith.domain.exception;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String field;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, String message, String field) {
        super(message);
        this.errorCode = errorCode;
        this.field = field;
    }
}
