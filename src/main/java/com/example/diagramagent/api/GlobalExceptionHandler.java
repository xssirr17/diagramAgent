package com.example.diagramagent.api;

import java.net.URI;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidPathException.class)
    public ProblemDetail handleInvalidPath(InvalidPathException ex) {
        log.warn("Invalid path requested: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Invalid Path");
        pd.setType(URI.create("urn:problem:invalid-path"));
        return pd;
    }

    @ExceptionHandler(PathNotFoundException.class)
    public ProblemDetail handlePathNotFound(PathNotFoundException ex) {
        log.warn("Path not found: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Path Not Found");
        pd.setType(URI.create("urn:problem:path-not-found"));
        return pd;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("Invalid request payload: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request payload or diagram type: " + ex.getMessage());
        pd.setTitle("Invalid Request Body");
        pd.setType(URI.create("urn:problem:invalid-body"));
        return pd;
    }

    @ExceptionHandler(NoJavaSourcesException.class)
    public ProblemDetail handleNoJavaSources(NoJavaSourcesException ex) {
        log.warn("No java sources: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("No Java Sources Found");
        pd.setType(URI.create("urn:problem:no-java-sources"));
        return pd;
    }

    @ExceptionHandler(InsufficientInformationException.class)
    public ProblemDetail handleInsufficientInformation(InsufficientInformationException ex) {
        log.warn("Insufficient information for diagram: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Insufficient Information");
        pd.setType(URI.create("urn:problem:insufficient-information"));
        return pd;
    }

    @ExceptionHandler(ProviderException.class)
    public ProblemDetail handleProviderFailure(ProviderException ex) {
        log.error("LLM provider failure: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        pd.setTitle("LLM Provider Failure");
        pd.setType(URI.create("urn:problem:provider-failure"));
        return pd;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail handleRateLimit(RateLimitExceededException ex) {
        log.warn("Provider rate limit exceeded: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        pd.setTitle("Rate Limit Exceeded");
        pd.setType(URI.create("urn:problem:rate-limit-exceeded"));
        return pd;
    }

    @ExceptionHandler(TimeoutException.class)
    public ProblemDetail handleTimeout(TimeoutException ex) {
        log.error("Operation timed out: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage());
        pd.setTitle("Gateway Timeout");
        pd.setType(URI.create("urn:problem:timeout"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Invalid Request Parameters");
        pd.setType(URI.create("urn:problem:validation-error"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        pd.setType(URI.create("urn:problem:bad-request"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred"
        );
        pd.setTitle("Internal Server Error");
        pd.setType(URI.create("urn:problem:internal-server-error"));
        return pd;
    }
}
