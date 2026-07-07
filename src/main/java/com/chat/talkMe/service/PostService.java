package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.PostCommentRequest;
import com.chat.talkMe.dto.request.PostRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.PostCommentResponse;
import com.chat.talkMe.dto.response.PostResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PostService {
    PostResponse createPost(PostRequest request, User currentUser);
    PostResponse votePoll(String postUuid, String optionUuid, User currentUser);
    PostResponse getPost(String postUuid, User currentUser);
    PostResponse getPostByShortCode(String shortCode, User currentUser);
    PostResponse updatePost(String postUuid, PostRequest request, User currentUser);
    Page<PostResponse> getFeed(Pageable pageable, User currentUser);
    Page<PostResponse> getProfileFeed(String userUuid, Pageable pageable, User currentUser);
    void deletePost(String postUuid, User currentUser);
    void likePost(String postUuid, User currentUser);
    void unlikePost(String postUuid, User currentUser);
    Page<AuthUserResponse> getPostLikes(String postUuid, Pageable pageable, User currentUser);
    PostCommentResponse addComment(String postUuid, PostCommentRequest request, User currentUser);
    PostCommentResponse editComment(String postUuid, String commentUuid, PostCommentRequest request, User currentUser);
    void deleteComment(String postUuid, String commentUuid, User currentUser);
    void likeComment(String postUuid, String commentUuid, User currentUser);
    void unlikeComment(String postUuid, String commentUuid, User currentUser);
    void bookmarkPost(String postUuid, User currentUser);
    void unbookmarkPost(String postUuid, User currentUser);
    Page<PostCommentResponse> getComments(String postUuid, Pageable pageable, User currentUser);
    Page<PostCommentResponse> getReplies(String postUuid, String commentUuid, Pageable pageable, User currentUser);
}
