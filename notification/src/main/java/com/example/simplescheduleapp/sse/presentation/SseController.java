package com.example.simplescheduleapp.sse.presentation;

import com.example.simplescheduleapp.common.auth.Auth;
import com.example.simplescheduleapp.sse.application.SseConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RequiredArgsConstructor
@RestController
public class SseController {

    private final SseConnectionService sseConnectionService;

    /**
     * 알림 스트림 구독. 구독자는 <b>토큰으로만</b> 정해진다 (ADR-0005).
     *
     * <p>이전 계약 {@code GET /sse-stream/{memberId}}는 경로 변수에 남의 식별자를 넣어 <b>타인의 알림을
     * 구독</b>할 수 있었다. "네이티브 EventSource는 Authorization 헤더를 못 보낸다"가 그 폴백의 근거였으나
     * 실제 클라이언트는 그렇지 않다. 웹은 {@code EventSourcePolyfill}로 {@code Authorization} 헤더를 싣고,
     * 안드로이드는 OkHttp {@code EventSources}에 인증 인터셉터가 달린 클라이언트를 그대로 넘긴다.
     * 그래서 식별자를 받는 경로 변수를 계약에서 없앴다.
     */
    @GetMapping(value = "/sse-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connectSse(@Auth Long memberId) {
        log.info("SSE Connection 요청 수신: {}", memberId);
        SseEmitter connectedEmitter = sseConnectionService.connect(memberId);
        log.info("SSE Connection 요청 완료: {}", memberId);
        return connectedEmitter;
    }
}
