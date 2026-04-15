package com.esolutions.massmailer.exception;

import com.esolutions.massmailer.dto.MailDtos.ErrorResponse;
import com.esolutions.massmailer.domain.ports.ErpIntegrationException;
import com.esolutions.massmailer.service.PdfAttachmentResolver.PdfResolutionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CampaignNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CampaignNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ErpIntegrationException.class)
    public ResponseEntity<ErrorResponse> handleErpError(ErpIntegrationException ex, HttpServletRequest req) {
        log.error("ERP integration error [{}] on {}: {}",
                ex.getErpSource(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(502, "ERP Integration Error", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(PdfResolutionException.class)
    public ResponseEntity<ErrorResponse> handlePdfError(PdfResolutionException ex, HttpServletRequest req) {
        log.warn("PDF resolution error on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "PDF Error", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "Validation Failed", errors, req.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException ex) {
        // silently return 404 for static resources like favicon.ico
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        int statusCode = ex.getStatusCode().value();
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(statusCode, ex.getStatusCode().toString(),
                        ex.getReason() != null ? ex.getReason() : ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        // suppress noisy favicon 404s
        if (req.getRequestURI().contains("favicon")) {
            return ResponseEntity.notFound().build();
        }
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error",
                        "An unexpected error occurred", req.getRequestURI()));
    }
}
