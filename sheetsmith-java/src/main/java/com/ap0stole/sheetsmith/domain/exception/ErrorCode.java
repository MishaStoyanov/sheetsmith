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
    OLLAMA_UNREACHABLE(502),
    // 401 means "try again with a fresh token"; the browser's silent refresh keys off it.
    UNAUTHORIZED(401),
    // 403 is the opposite: known, and still not allowed. A retry cannot fix it.
    FORBIDDEN(403),
    USER_NOT_FOUND(404),
    USERNAME_TAKEN(409),
    PRICE_NOT_FOUND(404),
    // 409, not 400: the request is well formed and the refusal is about the state of the data,
    // which is exactly what a conflict means — and the message carries the number to confirm.
    PRICE_IN_USE(409);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
