package com.ap0stole.sheetsmith.controller.advice;

import com.ap0stole.sheetsmith.domain.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the API says when a request goes somewhere that is not there.
 * <p>
 * Both cases used to fall through to the catch-all and answer <em>500 An unexpected error
 * occurred</em>, which reads as a broken server rather than a wrong address — and sends whoever hit
 * it hunting for a fault that does not exist. It surfaced while checking an instance running with
 * the chat off, where the message endpoints are genuinely absent and 404 is simply the truth.
 */
class ErrorControllerAdviceTest {

    private final ErrorControllerAdvice advice = new ErrorControllerAdvice();

    @Test
    @DisplayName("a path nobody serves is 404, not a server fault")
    void missingPathIs404() {
        ResponseEntity<ErrorResponse> response =
                advice.handleNotFound(new NoResourceFoundException(HttpMethod.POST, "/api/nope"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("No such endpoint");
    }

    @Test
    @DisplayName("the wrong method is 405, and the answer names what the path does accept")
    void wrongMethodIs405() {
        ResponseEntity<ErrorResponse> response = advice.handleWrongMethod(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .contains("POST")
                .contains("GET");
    }

    @Test
    @DisplayName("a method not supported with nothing to suggest still reads as a sentence")
    void wrongMethodWithoutAlternatives() {
        ResponseEntity<ErrorResponse> response =
                advice.handleWrongMethod(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).endsWith(".");
    }
}
