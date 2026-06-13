package com.chat.talkMe.exception;

public class FileStorageException extends ServiceException {
    public FileStorageException(String message) {
        super(500, message, "TM_170");
    }
}
