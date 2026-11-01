package com.example.simplescheduleapp.sse.presentation;

import com.example.simplescheduleapp.common.auth.BearerTokenExtractor;
import com.example.simplescheduleapp.common.auth.TokenService;
import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.sse.application.SseConnectionService;
import com.example.simplescheduleapp.sse.application.SseEmitterRegistry;
import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static com.example.simplescheduleapp.config.ThreadPoolConfig.SSE_HEARTBEAT_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 같은 회원이 다시 연결한 뒤, <b>밀려난 옛 연결의 정리가 새 연결을 지우면 안 된다.</b>
 *
 * <p>레지스트리와 접속 표시는 회원 하나에 한 칸뿐이다. 재연결하면 그 칸은 새 연결 차지가 되는데,
 * 옛 연결의 완료 콜백이나 늦게 실패한 하트비트가 회원 ID만 보고 정리하면 새 연결의 하트비트가 멈추고
 * 알림 대상에서도 빠진다. 클라이언트에는 열린 채 침묵하는 스트림으로 보인다. 2026-10-13 웹에서 관측된
 * 45초 주기 끊김의 후보 경로 중 하나다.
 *
 * <p>옛 연결이 새 연결을 건드릴 수 있는 길은 넷이고, 각각 테스트 하나씩 둔다. 옛 요청의 완료 콜백,
 * 옛 연결에 남은 하트비트의 쓰기 실패, 옛 emitter로 보내던 알림의 쓰기 실패, 그리고 하트비트를
 * 스케줄하는 사이에 밀려난 연결이다.
 *
 * <p>컨트롤러, 서비스, 레지스트리를 실제 빈으로 띄우고 MockMvc의 비동기 요청을 쓴다. 그래야 컨테이너가
 * 비동기 요청을 끝낼 때 Spring이 emitter 콜백을 부르는 실제 배선을 지난다. 하트비트 스케줄러만 목이라
 * 하트비트는 테스트가 직접 실행한다. 이 배선이 정말 콜백을 부르는지는 마지막 양성 대조가 보증한다.
 * 그것이 없으면 "아무것도 지우지 않았다"는 단언이 콜백이 안 불려서 통과할 수 있다. 레지스트리는
 * 실제 빈을 감싼 스파이라, 알림 스레드가 옛 emitter를 꺼내 둔 상황만 그 테스트에서 흉내 낸다.
 *
 * <p>증명하지 않는 것: 실제 Tomcat이 클라이언트 이탈을 언제 알아채는지. 그건 운영 로그로 확인한다.
 */
@DisplayName("SSE 재연결 뒤 옛 연결의 정리 은(는)")
@WebMvcTest(SseController.class)
@Import({SseConnectionService.class, SseEmitterRegistry.class})
class SseSupersededConnectionTest {

    private static final Long MEMBER_ID = 12L;
    private static final String BEARER = "Bearer token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private SseEmitterRegistry sseEmitterRegistry;

    @Autowired
    private SseConnectionService sseConnectionService;

    @MockitoBean(name = SSE_HEARTBEAT_SCHEDULER)
    private TaskScheduler taskScheduler;

    @MockitoBean
    private SseClientPresence sseClientPresence;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private BearerTokenExtractor bearerTokenExtractor;

    private final ScheduledFuture<?> firstHeartbeat = mock(ScheduledFuture.class);
    private final ScheduledFuture<?> secondHeartbeat = mock(ScheduledFuture.class);
    private final ArgumentCaptor<Runnable> heartbeatTasks = ArgumentCaptor.forClass(Runnable.class);

    @BeforeEach
    void setUp() {
        given(bearerTokenExtractor.extract(BEARER)).willReturn("token");
        given(tokenService.extractMemberId("token")).willReturn(MEMBER_ID);
        doReturn(firstHeartbeat, secondHeartbeat)
                .when(taskScheduler)
                .scheduleAtFixedRate(heartbeatTasks.capture(), any(Instant.class), any(Duration.class));
    }

    private MvcResult subscribe() throws Exception {
        return mockMvc.perform(get("/sse-stream")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @DisplayName("옛 연결의 요청이 끝나도(클라이언트 이탈) 새 연결의 하트비트, 등록, 접속 표시를 지우지 않는다")
    @Test
    void 옛_연결의_완료_콜백은_새_연결을_지우지_않는다() throws Exception {
        MvcResult first = subscribe();
        subscribe();
        SseEmitter current = sseEmitterRegistry.get(MEMBER_ID);

        // 컨테이너가 옛 연결의 끊김을 알아채 그 비동기 요청을 끝낸다
        first.getRequest().getAsyncContext().complete();

        assertThat(sseEmitterRegistry.get(MEMBER_ID)).isSameAs(current);
        verify(secondHeartbeat, never()).cancel(anyBoolean());
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
    }

    @DisplayName("옛 연결에 남은 하트비트가 쓰기에 실패해도 새 연결을 지우지 않는다")
    @Test
    void 옛_연결의_하트비트_실패는_새_연결을_지우지_않는다() throws Exception {
        subscribe();
        SseEmitter old = sseEmitterRegistry.get(MEMBER_ID);
        subscribe();
        SseEmitter current = sseEmitterRegistry.get(MEMBER_ID);
        Runnable oldHeartbeat = heartbeatTasks.getAllValues().get(0);

        // 두 연결이 겹쳐 취소되지 못한 옛 하트비트가, 이미 끝난 옛 연결에 쓰다 실패하는 상황
        old.complete();
        oldHeartbeat.run();

        // 하트비트 쓰기가 정말 실패해 catch 경로를 탔다는 증거다. 쓰기가 성공했다면 접속 표시를 갱신했다.
        verify(sseClientPresence, never()).refreshConnection(MEMBER_ID);
        assertThat(sseEmitterRegistry.get(MEMBER_ID)).isSameAs(current);
        verify(secondHeartbeat, never()).cancel(anyBoolean());
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
    }

    @DisplayName("옛 emitter로 보내던 알림이 쓰기에 실패해도 새 연결의 등록을 지우지 않는다")
    @Test
    void 옛_emitter로의_알림_실패는_새_연결을_지우지_않는다() throws Exception {
        subscribe();
        SseEmitter current = sseEmitterRegistry.get(MEMBER_ID);

        // 알림 스레드가 재연결 전에 꺼내 둔 옛 emitter. 그사이 클라이언트가 떠나 쓰기가 실패한다
        SseEmitter stale = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(stale).send(any(SseEmitter.SseEventBuilder.class));
        doReturn(stale).when(sseEmitterRegistry).get(MEMBER_ID);

        // 전송 실패 예외가 난다는 것이 catch 경로를 탔다는 증거다
        assertThatThrownBy(() -> sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문"))
                .isInstanceOf(ApplicationException.class);

        doCallRealMethod().when(sseEmitterRegistry).get(MEMBER_ID);
        verify(sseEmitterRegistry, never()).delete(MEMBER_ID);
        assertThat(sseEmitterRegistry.get(MEMBER_ID)).isSameAs(current);
        verify(firstHeartbeat, never()).cancel(anyBoolean());
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
    }

    @DisplayName("하트비트를 스케줄하는 사이에 밀려난 연결은 그 하트비트를 붙이자마자 멈춘다")
    @Test
    void 스케줄_도중_밀려난_연결의_하트비트는_바로_멈춘다() throws Exception {
        doAnswer(invocation -> {
                    // 첫 연결이 하트비트를 스케줄하는 바로 그 사이에 같은 회원이 다시 연결한다
                    sseConnectionService.connect(MEMBER_ID);
                    return firstHeartbeat;
                })
                .doReturn(secondHeartbeat)
                .when(taskScheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));

        subscribe();

        verify(firstHeartbeat).cancel(false);
        verify(secondHeartbeat, never()).cancel(anyBoolean());
    }

    @DisplayName("양성 대조: 현재 연결의 요청이 끝나면 그 회원의 자원을 모두 정리한다")
    @Test
    void 현재_연결의_요청이_끝나면_자원을_모두_정리한다() throws Exception {
        subscribe();
        MvcResult second = subscribe();

        second.getRequest().getAsyncContext().complete();

        assertThatThrownBy(() -> sseEmitterRegistry.get(MEMBER_ID))
                .isInstanceOf(ApplicationException.class);
        verify(secondHeartbeat).cancel(false);
        verify(sseClientPresence, times(1)).unsubscribeClient(MEMBER_ID);
    }
}
