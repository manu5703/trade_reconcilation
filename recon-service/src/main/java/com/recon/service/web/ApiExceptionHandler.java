package com.recon.service.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.recon.service.web.ApiException.FieldError;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 problem detail with a message a caller can act on, plus an
 * {@code errors} list naming each bad field. Spring's defaults ("Failed to convert value of type
 * java.lang.String...") are replaced with plain statements of what was expected.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException e) {
        return ResponseEntity.status(e.status()).body(problem(e.status(), e.getMessage(), e.errors()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT,
                "The break was changed by someone else (or by a run) while you were editing it; reload it and try again", List.of()));
    }

    /** Wrong type in a query parameter, e.g. {@code businessDate=yesterday} or {@code status=CLOSED}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "%s must be %s, got '%s'".formatted(e.getName(), describe(e.getRequiredType()), e.getValue());
        return badRequest(message, List.of(new FieldError(e.getName(), message)));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getField() + " " + f.getDefaultMessage()))
                .toList();
        return badRequest(summary(errors), errors);
    }

    /** Constraint annotations on query parameters, e.g. {@code @Max(200) size}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> {
                    String name = r.getMethodParameter().getParameterName();
                    return new FieldError(name, name + " " + err.getDefaultMessage());
                }))
                .toList();
        return badRequest(summary(errors), errors);
    }

    /** Unparseable JSON, or a bad value inside it such as an unknown status. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        if (e.getCause() instanceof InvalidFormatException invalid && !invalid.getPath().isEmpty()) {
            String field = invalid.getPath().getLast().getFieldName();
            String message = "%s must be %s, got '%s'".formatted(field, describe(invalid.getTargetType()), invalid.getValue());
            return badRequest(message, List.of(new FieldError(field, message)));
        }
        return badRequest("Request body is missing or is not valid JSON", List.of());
    }

    private static ResponseEntity<Object> badRequest(String message, List<FieldError> errors) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, message, errors));
    }

    private static ProblemDetail problem(HttpStatus status, String detail, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    private static String summary(List<FieldError> errors) {
        return errors.isEmpty() ? "Request is not valid" : String.join("; ", errors.stream().map(FieldError::message).toList());
    }

    private static String describe(Class<?> type) {
        if (type == null) {
            return "a valid value";
        }
        if (type.isEnum()) {
            return "one of " + String.join(", ", Arrays.stream(type.getEnumConstants()).map(Object::toString).toList());
        }
        if (type == LocalDate.class) {
            return "an ISO date (yyyy-MM-dd)";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "a whole number";
        }
        return "a valid " + type.getSimpleName();
    }
}
