package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.PostResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PostService {
    PostResponse createPost(PostRequest request, User currentUser);
    PostResponse updatePost(String postUuid, PostRequest request, User currentUser);
    Page<PostResponse> getFeed(Pageable pageable, User currentUser);
    Page<PostResponse> getProfileFeed(String userUuid, Pageable pageable, User currentUser);
    void deletePost(String postUuid, User currentUser);
    void likePost(String postUuid, User currentUser);
    void unlikePost(String postUuid, User currentUser);
    PostCommentResponse addComment(String postUuid, PostCommentRequest request, User currentUser);
    PostCommentResponse editComment(String postUuid, String commentUuid, PostCommentRequest request, User currentUser);
    void deleteComment(String postUuid, String commentUuid, User currentUser);
    void bookmarkPost(String postUuid, User currentUser);
    void unbookmarkPost(String postUuid, User currentUser);
    Page<PostCommentResponse> getComments(String postUuid, Pageable pageable);
}
