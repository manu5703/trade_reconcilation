package com.recon.service.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * An error the API reports on purpose, with the HTTP status it maps to and a message written for
 * the caller. Rendered as RFC 7807 problem details by {@link ApiExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    /** A problem with one request field. */
    public record FieldError(String field, String message) {
    }

    private final HttpStatus status;
    private final List<FieldError> errors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<FieldError> errors) {
        super(message);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static ApiException badRequest(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, List.of(new FieldError(field, message)));
    }

    public HttpStatus status() {
        return status;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
