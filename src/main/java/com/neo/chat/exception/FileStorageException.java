package com.neo.chat.exception;

import java.io.Serial;

/**
 * Signals a failure while storing or retrieving an uploaded file (I/O or storage-backend
 * error). Maps to HTTP 500 with message code {@code TM_170}.
 */
public class FileStorageException extends ServiceException {
    @Serial
    private static final long serialVersionUID = 1L;

    public FileStorageException(String message) {
        super(500, message, "TM_170");
    }
}
