package com.chat.talkMe.match;

public interface ImagePermissionService {
    void requestImage(String requester);
    void acceptImageRequest(String approver);
    void declineImageRequest(String decliner);
}
