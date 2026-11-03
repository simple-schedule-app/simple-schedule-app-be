package com.example.simplescheduleapp.sse.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이 인스턴스에 붙어 있는 살아 있는 SSE 커넥션 등록부. 회원 하나가 탭이나 기기마다 커넥션을 따로 가질 수 있다.
 *
 * <p><b>왜 infrastructure가 아닌가.</b> {@link SseConnection}이 들고 있는 emitter는 진행 중인 HTTP 응답
 * 그 자체라 프로세스 밖으로 옮길 수 없다. 외부 시스템을 부르는 어댑터가 아니라 유스케이스가 들고 있는
 * 인메모리 상태다. 프로세스 경계를 넘는 부분(누가 어디에 붙어 있는가, 알림 브로드캐스트)은 각각
 * {@code SseClientPresence}와 {@code SseMessagePublisher} 포트가 맡는다.
 *
 * <p>회원별 목록은 바꿀 때마다 새 불변 목록으로 갈아 끼운다. 갈아 끼우기는 {@link ConcurrentHashMap#compute}
 * 안에서 하므로 같은 회원의 추가와 제거가 서로를 덮어쓰지 않고, 읽는 쪽은 락 없이 그 시점의 목록을 받는다.
 */
@Component
public class SseConnectionRegistry {

    /**
     * 회원 한 명이 동시에 가질 수 있는 커넥션 수. 넘으면 가장 오래된 커넥션부터 밀어낸다.
     * 탭 여러 개와 기기 몇 대에, 막 끊겨 아직 정리되지 않은 커넥션까지 담을 여유를 둔 값이다.
     */
    public static final int MAX_CONNECTIONS_PER_MEMBER = 10;

    private final Map<Long, List<SseConnection>> connections = new ConcurrentHashMap<>();

    /** 커넥션을 더한다. 상한을 넘으면 가장 오래된 커넥션부터 빼서 돌려준다. 닫는 일은 호출한 쪽이 한다. */
    public List<SseConnection> add(Long memberId, SseConnection connection) {
        List<SseConnection> evicted = new ArrayList<>();
        connections.compute(memberId, (id, current) -> {
            List<SseConnection> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.add(connection);
            while (next.size() > MAX_CONNECTIONS_PER_MEMBER) {
                evicted.add(next.remove(0));
            }
            return List.copyOf(next);
        });
        return evicted;
    }

    /**
     * 커넥션을 빼고 그 회원에게 남은 커넥션 수를 돌려준다. 0이면 마지막 커넥션이었다는 뜻이다.
     * 등록돼 있지 않은 커넥션(이미 밀려났거나 두 번째 해제)이면 아무것도 하지 않고 빈 값을 돌려준다.
     */
    public OptionalInt remove(Long memberId, SseConnection connection) {
        AtomicInteger remaining = new AtomicInteger(-1);
        connections.computeIfPresent(memberId, (id, current) -> {
            if (!current.contains(connection)) {
                return current;
            }
            List<SseConnection> next = new ArrayList<>(current);
            next.remove(connection);
            remaining.set(next.size());
            return next.isEmpty() ? null : List.copyOf(next);
        });
        return remaining.get() < 0 ? OptionalInt.empty() : OptionalInt.of(remaining.get());
    }

    /** 그 회원의 커넥션 목록. 그 시점의 불변 스냅샷이고, 없으면 빈 목록이다. */
    public List<SseConnection> connectionsOf(Long memberId) {
        return connections.getOrDefault(memberId, List.of());
    }
}
