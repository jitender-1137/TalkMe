package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.MusicTrackResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.service.MusicService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/music")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class MusicController {

    private final MusicService musicService;

    @GetMapping("/search")
    public ResponseEntity<ResponseDto<List<MusicTrackResponse>>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "24") int limit) {
        return ResponseEntity.ok(SuccessResponseDto.success(musicService.search(query, limit)));
    }
}
