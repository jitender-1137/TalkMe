package com.chat.talkMe.exception;

import com.chat.talkMe.dto.response.ResponseDto;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit test for {@link GlobalExceptionHandler}. Controller tests can only reach the handlers
 * for exceptions their endpoints actually throw; this exercises EVERY {@code @ExceptionHandler}
 * branch — including the upload / IO / client-abort / writer paths that no REST controller in this
 * app produces — plus the {@code isClientAbort} cause-chain logic.
 *
 * <p>The handler is constructed with a {@link StaticMessageSource}, which returns the supplied
 * default message for any code (so {@code getMessage()} equals the default and {@code getMessageCode()}
 * is the load-bearing assertion).
 */
@DisplayName("GlobalExceptionHandler (unit)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());

    // ── ServiceException (and subclasses) → its own status + code ────────────────

    @Nested
    @DisplayName("ServiceException")
    class Service {

        @Test
        void shouldMapNotFoundToItsStatusAndCode() {
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleServiceException(new NotFoundException("Missing", "TM_101"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().isSuccess()).isFalse();
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_101");
            assertThat(res.getBody().getMessage()).isEqualTo("Missing");
        }

        @Test
        void shouldMapArbitraryStatusException() {
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleServiceException(new ServiceException(422, "Unprocessable", "TM_143"));

            // 422 is UNPROCESSABLE_CONTENT in Spring 7 (renamed from UNPROCESSABLE_ENTITY) — assert by value.
            assertThat(res.getStatusCode().value()).isEqualTo(422);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_143");
        }

        @Test
        void shouldPassThroughErrorsPayload() {
            Map<String, String> errors = Map.of("field", "bad");
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleServiceException(new ServiceException(400, "Bad", "TM_070", errors));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().getErrors()).isEqualTo(errors);
        }
    }

    // ── Max upload size → 413 / TM_493 ───────────────────────────────────────────

    @Test
    void shouldMapMaxUploadSizeToPayloadTooLarge() {
        ResponseEntity<ResponseDto<Void>> res =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(30L * 1024 * 1024));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody().getMessageCode()).isEqualTo("TM_493");
    }

    // ── Bean validation → 400 / VE_101 + field errors ────────────────────────────

    @Test
    void shouldMapValidationErrorsToFieldMap() {
        BindingResult br = new BeanPropertyBindingResult(new Object(), "req");
        br.addError(new FieldError("req", "email", "Email format is invalid"));
        br.addError(new FieldError("req", "name", "Name is required"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(br);

        ResponseEntity<ResponseDto<Void>> res = handler.handleValidationException(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessageCode()).isEqualTo("VE_101");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) res.getBody().getErrors();
        assertThat(errors)
                .containsEntry("email", "Email format is invalid")
                .containsEntry("name", "Name is required");
    }

    // ── Access denied → 403 / TM_005 ─────────────────────────────────────────────

    @Test
    void shouldMapAccessDeniedToForbidden() {
        ResponseEntity<ResponseDto<Void>> res =
                handler.handleAccessDeniedException(new AccessDeniedException("nope"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getMessageCode()).isEqualTo("TM_005");
    }

    // ── IllegalArgumentException → TM_071, or TM_INVALID_UUID for UUID parse ──────

    @Nested
    @DisplayName("IllegalArgumentException")
    class IllegalArg {

        @Test
        void shouldMapGenericToTm071() {
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleIllegalArgumentException(new IllegalArgumentException("something off"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_071");
        }

        @Test
        void shouldMapInvalidUuidToDedicatedCode() {
            ResponseEntity<ResponseDto<Void>> res = handler.handleIllegalArgumentException(
                    new IllegalArgumentException("Invalid UUID string: xyz"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_INVALID_UUID");
        }

        @Test
        void shouldHandleNullMessage() {
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleIllegalArgumentException(new IllegalArgumentException((String) null));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_071");
        }
    }

    // ── Not-found style (resource / file) → 404 / TM_004 ─────────────────────────

    @Test
    void shouldMapNoResourceFoundToNotFound() {
        ResponseEntity<ResponseDto<Void>> res =
                handler.handleNoResourceFoundException(new NoResourceFoundException(HttpMethod.GET, "/.env", "/.env"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getMessageCode()).isEqualTo("TM_004");
    }

    @Test
    void shouldMapFileNotFoundToNotFound() {
        ResponseEntity<ResponseDto<Void>> res =
                handler.handleFileNotFoundException(new FileNotFoundException("index.html"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getMessageCode()).isEqualTo("TM_004");
    }

    // ── Client-abort family (void handlers) — must not throw ─────────────────────

    @Test
    void shouldSwallowAsyncRequestNotUsable() {
        assertThatCode(() -> handler.handleAsyncRequestNotUsableException(
                new AsyncRequestNotUsableException("client gone"))).doesNotThrowAnyException();
    }

    @Test
    void shouldSwallowClientAbort() {
        assertThatCode(() -> handler.handleClientAbortException(
                new ClientAbortException("Broken pipe"))).doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("IOException (void)")
    class IoException {
        @Test
        void shouldSwallowBrokenPipe() {
            assertThatCode(() -> handler.handleIOException(new IOException("Broken pipe")))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldSwallowGenuineIoError() {
            assertThatCode(() -> handler.handleIOException(new IOException("disk failure")))
                    .doesNotThrowAnyException();
        }
    }

    // ── HttpMessageNotWritable: client abort → null; genuine → 500 ───────────────

    @Nested
    @DisplayName("HttpMessageNotWritableException")
    class NotWritable {
        @Test
        void shouldReturnNullWhenCauseIsClientAbort() {
            HttpMessageNotWritableException ex = new HttpMessageNotWritableException(
                    "write failed", new ClientAbortException("Broken pipe"));

            ResponseEntity<ResponseDto<Void>> res = handler.handleHttpMessageNotWritable(ex);

            // Socket already closed — the handler writes nothing.
            assertThat(res).isNull();
        }

        @Test
        void shouldReturn500ForGenuineSerializationFailure() {
            ResponseEntity<ResponseDto<Void>> res = handler.handleHttpMessageNotWritable(
                    new HttpMessageNotWritableException("cannot serialize"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_002");
        }
    }

    // ── Catch-all: generic → 500; client-abort cause chain → null ────────────────

    @Nested
    @DisplayName("catch-all Exception")
    class CatchAll {
        @Test
        void shouldReturn500ForUnhandledException() {
            ResponseEntity<ResponseDto<Void>> res =
                    handler.handleAllExceptions(new RuntimeException("boom"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.getBody().isSuccess()).isFalse();
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_002");
        }

        @Test
        void shouldReturnNullWhenCauseChainIsClientAbort() {
            // A broken-pipe buried two levels deep still counts as a dead socket → write nothing.
            Exception buried = new RuntimeException("wrapper",
                    new IllegalStateException("mid", new IOException("Connection reset")));

            ResponseEntity<ResponseDto<Void>> res = handler.handleAllExceptions(buried);

            assertThat(res).isNull();
        }

        @Test
        void shouldReturn500WhenCauseChainIsUnrelated() {
            Exception buried = new RuntimeException("wrapper", new IllegalStateException("mid"));

            ResponseEntity<ResponseDto<Void>> res = handler.handleAllExceptions(buried);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.getBody().getMessageCode()).isEqualTo("TM_002");
        }
    }
}
