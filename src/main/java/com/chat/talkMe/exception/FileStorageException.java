package com.chat.talkMe.exception;

public class FileStorageException extends ServiceException {
    private static final long serialVersionUID = 1L;
    public FileStorageException(String message) {
        super(500, message, "TM_170");
    }
}
