package com.ap0stole.sheetsmith.domain.exception;

public enum ErrorCode {
    FILE_INVALID(400),
    FILE_TOO_LARGE(413),
    FILE_NOT_FOUND(400),
    LLM_FAILURE(502),
    PROCESSING_ERROR(500),
    JOB_NOT_FOUND(404),
    SESSION_NOT_FOUND(404),
    VALIDATION_ERROR(400),
    PATH_TRAVERSAL(400),
    PATH_ENDPOINT_DISABLED(403),
    PATH_ENDPOINT_MISCONFIGURED(500),
    OLLAMA_UNREACHABLE(502);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
