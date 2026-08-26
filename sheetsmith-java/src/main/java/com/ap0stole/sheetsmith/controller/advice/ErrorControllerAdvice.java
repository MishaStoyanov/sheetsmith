package com.ap0stole.sheetsmith.controller.advice;

import com.ap0stole.sheetsmith.domain.dto.ErrorResponse;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ErrorControllerAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        log.warn("API error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getErrorCode().getHttpStatus())
                .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), ex.getField()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("File too large: {}", ex.getMessage());
        return ResponseEntity
                .status(413)
                .body(new ErrorResponse(ErrorCode.FILE_TOO_LARGE, "File exceeds the 50MB limit", "file"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String field = fieldError != null ? fieldError.getField() : null;
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Validation failed";
        log.warn("Validation error on field '{}': {}", field, message);
        return ResponseEntity
                .status(400)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR, message, field));
    }

    /**
     * A path nobody serves, and a path served for a different method.
     * <p>
     * Without these two, Spring's own routing exceptions fall through to the catch-all below and a
     * mistyped URL answers <em>500 An unexpected error occurred</em> — which reads as a broken
     * server rather than a wrong address, and sends whoever hit it looking for a fault that is not
     * there. It matters most on an instance running with the chat off, where the message endpoints
     * genuinely are not there and "404" is the honest answer.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        log.debug("No handler: {}", ex.getMessage());
        return ResponseEntity
                .status(404)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR,
                        "No such endpoint on this instance."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleWrongMethod(HttpRequestMethodNotSupportedException ex) {
        log.debug("Wrong method: {}", ex.getMessage());
        String supported = ex.getSupportedHttpMethods() == null ? "" : ex.getSupportedHttpMethods().toString();
        return ResponseEntity
                .status(405)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR,
                        ex.getMethod() + " is not supported here"
                                + (supported.isBlank() ? "." : " — this path accepts " + supported + ".")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(500)
                .body(new ErrorResponse(ErrorCode.PROCESSING_ERROR, "An unexpected error occurred"));
    }
}
