package com.ap0stole.sheetsmith.domain.dto;

import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import lombok.Getter;

@Getter
public class ErrorResponse {

    private final String code;
    private final String message;
    private final String field;

    public ErrorResponse(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ErrorResponse(ErrorCode code, String message, String field) {
        this.code = code.name();
        this.message = message;
        this.field = field;
    }
}
