package com.neo.chat.exception;

import com.neo.chat.dto.response.ResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Central {@code @ControllerAdvice} that translates exceptions thrown by controllers
 * (and the framework) into a consistent {@link com.neo.chat.dto.response.ResponseDto}
 * error body with an HTTP status and localized message. Client-disconnect exceptions
 * (broken pipe / connection reset) are recognized and logged quietly instead of as errors.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * Resolve {@code code} against the configured {@code MessageSource} for the current
     * locale, returning {@code defaultMessage} if it is missing or resolution fails.
     *
     * @param code           the message code to resolve
     * @param defaultMessage fallback message when the code has no entry
     * @return the localized message, or {@code defaultMessage}
     */
    private String getLocalizedMessage(String code, String defaultMessage) {
        try {
            return messageSource.getMessage(code, null, defaultMessage, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return defaultMessage;
        }
    }

    /**
     * Catches every {@link ServiceException} (and subclass) and returns its carried status,
     * localized message code and optional errors payload as a {@code ResponseDto} error.
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ResponseDto<Void>> handleServiceException(ServiceException ex) {
        // A ServiceException carries an HTTP status. A 4xx is a CLIENT error (bad id, wrong
        // password, validation) — expected, attacker-triggerable at will, and must NOT flood the
        // ERROR log (which pages ops). Log 4xx at WARN and reserve ERROR for genuine 5xx faults.
        if (ex.getStatus() >= 500) {
            log.error("ServiceException occurred: [Code: {}] {}", ex.getMessageCode(), ex.getMessage());
        } else {
            log.warn("ServiceException [Code: {}] {} (status {})",
                    ex.getMessageCode(), ex.getMessage(), ex.getStatus());
        }
        String localizedMessage = getLocalizedMessage(ex.getMessageCode(), ex.getMessage());
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, ex.getMessageCode(), ex.getErrors());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    /**
     * Catches multipart uploads exceeding the global size cap and returns HTTP 413 with
     * message code {@code TM_493}. A file over the global multipart cap (30MB) is rejected
     * during parsing — before the controller runs — so surface a clean 413 instead of a 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseDto<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded multipart limit: {}", ex.getMessage());
        ResponseDto<Void> response = ResponseDto.error(
                "File is too large. Images can be up to 2 MB and videos up to 30 MB.", "TM_493", null);
        // 413 — CONTENT_TOO_LARGE is the RFC 9110 name; PAYLOAD_TOO_LARGE is deprecated in Spring 7.
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(response);
    }

    /**
     * Catches bean-validation failures on {@code @Valid} request bodies and returns HTTP 400
     * with code {@code VE_101} and a field→message map of the violations in the errors payload.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        log.warn("Validation error occurred"); // client 400 — not an ERROR-level event
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        String localizedMessage = getLocalizedMessage("VE_101", "Validation Failed");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "VE_101", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Catches Spring Security {@link AccessDeniedException} and returns HTTP 403 with
     * message code {@code TM_005}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDto<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.error("AccessDeniedException: {}", ex.getMessage());
        String localizedMessage = getLocalizedMessage("TM_005", "Access Denied");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_005");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Catches {@link IllegalArgumentException} and returns HTTP 400. Uses code {@code TM_071}
     * generally, or {@code TM_INVALID_UUID} ("Invalid ID format provided") when the message
     * indicates a bad UUID string.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException occurred: {}", ex.getMessage());
        String messageCode = "TM_071";
        // SECURITY: never echo the raw exception message to the client — internal messages
        // (e.g. BCrypt's "rawPassword cannot be null") would leak. Return a generic 400.
        String defaultMsg = "Invalid request.";
        if (ex.getMessage() != null && ex.getMessage().contains("Invalid UUID string")) {
            messageCode = "TM_INVALID_UUID";
            defaultMsg = "Invalid ID format provided";
        }
        String localizedMessage = getLocalizedMessage(messageCode, defaultMsg);
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, messageCode);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Catches a DB uniqueness/constraint violation (e.g. a username/email inserted concurrently)
     * and returns a generic 409 instead of a 500 that would leak the SQL/constraint name.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ResponseDto<Void>> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("DataIntegrityViolationException: {}", ex.getMostSpecificCause().getMessage());
        ResponseDto<Void> response = ResponseDto.error(
                getLocalizedMessage("TM_048", "This value is already in use."), "TM_048");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Catches unmatched static/resource paths and returns HTTP 404 with code {@code TM_004}.
     * These are almost always vulnerability scanners probing for leaked files, so they are
     * logged quietly at DEBUG rather than as errors.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseDto<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        // Almost always automated vulnerability scanners probing for leaked files
        // (/.env, /.git, /wp-login, …). These are harmless 404s — log at DEBUG so they
        // don't spam production logs (and don't drive noise into the throwable-logging
        // path). Bump to `debug` visibility only when investigating.
        log.debug("Resource not found: {}", ex.getMessage());
        String localizedMessage = getLocalizedMessage("TM_004", "Resource Not Found");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_004");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles an async request aborted by the client. Returns nobody (void) and logs at
     * INFO — the socket is already gone, so there is nothing to write.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
        log.info("Async request aborted by client: {}", ex.getMessage());
    }

    /**
     * Handles a connection aborted by the client mid-response. Returns nobody (void) and
     * logs at INFO rather than treating the dead socket as a server fault.
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException ex) {
        log.info("Client aborted connection: {}", ex.getMessage());
    }

    /**
     * Catches a missing static/classpath resource and returns a quiet HTTP 404 with code
     * {@code TM_004} instead of a full stack trace per request.
     */
    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ResponseDto<Void>> handleFileNotFoundException(FileNotFoundException ex) {
        // Missing static/classpath resource (e.g. '/' -> static/index.html, '/sw.js' when the
        // bundled UI is absent). Spring throws FileNotFoundException instead of a clean 404, so
        // handle it here as a quiet 404 rather than logging a full stack trace per request.
        log.debug("Static resource not found: {}", ex.getMessage());
        String localizedMessage = getLocalizedMessage("TM_004", "Resource Not Found");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_004");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles a generic {@link IOException}. Broken-pipe / connection-reset causes are logged
     * quietly at INFO (client disconnect); any other I/O error is logged at ERROR. Returns nobody.
     */
    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException ex) {
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) {
            log.info("Client aborted connection (Broken pipe/Connection reset): {}", msg);
        } else {
            log.error("IOException occurred", ex);
        }
    }

    /**
     * Thrown by Jackson while writing the response body. When the CLIENT closed the
     * socket mid-write (page reload, navigate away, network drop) the broken pipe is
     * buried in the cause chain and the type isn't a ClientAbortException, so it used
     * to fall through to the catch-all and log a full ERROR stack. The socket is gone —
     * there's nothing to write — so log it quietly and return nobody. A genuine
     * serialization failure (not a client abort) still surfaces as a 500.
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ResponseDto<Void>> handleHttpMessageNotWritable(HttpMessageNotWritableException ex) {
        if (isClientAbort(ex)) {
            log.debug("Response write aborted by client (broken pipe): {}", ex.getMessage());
            return null; // socket already closed — writing anything else just fails again
        }
        log.error("Failed to serialize response", ex);
        String localizedMessage = getLocalizedMessage("TM_002", "Internal Server Error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDto.error(localizedMessage, "TM_002"));
    }

    /**
     * Catch-all for any unhandled exception. A client-abort cause is logged quietly and
     * returns nobody; anything else is logged at ERROR and returns HTTP 500 with code {@code TM_002}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto<Void>> handleAllExceptions(Exception ex) {
        // Belt-and-suspenders: any exception whose cause chain is a client abort
        // (broken pipe / connection reset) is a dead socket, not a server fault —
        // log quietly without a stack trace and write nothing.
        if (isClientAbort(ex)) {
            log.debug("Request aborted by client (broken pipe/connection reset): {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled Exception occurred", ex);
        String localizedMessage = getLocalizedMessage("TM_002", "Internal Server Error");
        ResponseDto<Void> response = ResponseDto.error(localizedMessage, "TM_002");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * True if anywhere in the cause chain is a client-side disconnect (dead socket).
     */
    private boolean isClientAbort(Throwable ex) {
        Throwable t = ex;
        for (int hops = 0; t != null && hops < 12; t = t.getCause(), hops++) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("Broken pipe") || m.contains("Connection reset"))) {
                return true;
            }
            if (t.getCause() == t) break; // guard against a self-referential cause
        }
        return false;
    }
}
