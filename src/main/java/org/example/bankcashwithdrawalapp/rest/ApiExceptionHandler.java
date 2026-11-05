package org.example.bankcashwithdrawalapp.rest;

import org.example.bankcashwithdrawalapp.exception.AccountNotFoundException;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.exception.IdempotencyKeyReuseException;
import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidWithdrawalException.class)
    public ResponseEntity<ApiError> handleInvalidWithdrawal(InvalidWithdrawalException exception) {
        logClientError("INVALID_WITHDRAWAL");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_WITHDRAWAL", exception.getMessage()));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        String message = exception instanceof MissingServletRequestParameterException missingParameter
                ? "Required parameter '%s' is missing".formatted(missingParameter.getParameterName())
                : exception instanceof MissingRequestHeaderException missingHeader
                ? "Required header '%s' is missing".formatted(missingHeader.getHeaderName())
                : "A request parameter has an invalid value";
        logClientError("INVALID_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", message));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException exception) {
        logClientError("ACCOUNT_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("ACCOUNT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException exception) {
        logClientError("INSUFFICIENT_FUNDS");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("INSUFFICIENT_FUNDS", exception.getMessage()));
    }

    @ExceptionHandler(ConcurrentWithdrawalException.class)
    public ResponseEntity<ApiError> handleConcurrentWithdrawal(ConcurrentWithdrawalException exception) {
        logClientError("CONCURRENT_WITHDRAWAL");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONCURRENT_WITHDRAWAL", exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ApiError> handleIdempotencyKeyReuse(IdempotencyKeyReuseException exception) {
        logClientError("IDEMPOTENCY_KEY_REUSED");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_KEY_REUSED", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedError(Exception exception) {
        LOGGER.error("Unhandled request failure requestId={}", MDC.get("requestId"), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private void logClientError(String code) {
        LOGGER.warn("Request rejected requestId={} code={}", MDC.get("requestId"), code);
    }

    public record ApiError(String code, String message) {
    }
}
