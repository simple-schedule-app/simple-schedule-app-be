package com.example.simplescheduleapp.sse.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSE 커넥션 등록부 은(는)")
class SseConnectionRegistryTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long OTHER_MEMBER_ID = 99L;

    private final SseConnectionRegistry registry = new SseConnectionRegistry();

    private static SseConnection newConnection() {
        return new SseConnection(new SseEmitter());
    }

    @DisplayName("한 회원의 커넥션 여럿을 들어온 순서대로 함께 담는다")
    @Test
    void 커넥션_여럿을_함께_담는다() {
        SseConnection first = newConnection();
        SseConnection second = newConnection();

        registry.add(MEMBER_ID, first);
        registry.add(MEMBER_ID, second);

        assertThat(registry.connectionsOf(MEMBER_ID)).containsExactly(first, second);
    }

    @DisplayName("커넥션이 없는 회원은 빈 목록이다")
    @Test
    void 커넥션이_없는_회원은_빈_목록이다() {
        assertThat(registry.connectionsOf(MEMBER_ID)).isEmpty();
    }

    @DisplayName("커넥션을 빼면 남은 수를 돌려주고, 마지막이면 0이다")
    @Test
    void 남은_수를_돌려주고_마지막이면_0이다() {
        SseConnection first = newConnection();
        SseConnection second = newConnection();
        registry.add(MEMBER_ID, first);
        registry.add(MEMBER_ID, second);

        assertThat(registry.remove(MEMBER_ID, first)).hasValue(1);
        assertThat(registry.connectionsOf(MEMBER_ID)).containsExactly(second);
        assertThat(registry.remove(MEMBER_ID, second)).hasValue(0);
        assertThat(registry.connectionsOf(MEMBER_ID)).isEmpty();
    }

    @DisplayName("이미 빠진 커넥션을 다시 빼면 아무것도 하지 않고 빈 값이다")
    @Test
    void 이미_빠진_커넥션은_무시한다() {
        SseConnection connection = newConnection();
        registry.add(MEMBER_ID, connection);
        registry.remove(MEMBER_ID, connection);

        assertThat(registry.remove(MEMBER_ID, connection)).isEmpty();
    }

    @DisplayName("상한을 넘으면 가장 오래된 커넥션부터 빼서 돌려준다")
    @Test
    void 상한을_넘으면_가장_오래된_커넥션부터_밀어낸다() {
        List<SseConnection> added = new ArrayList<>();
        for (int i = 0; i < SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER; i++) {
            SseConnection connection = newConnection();
            added.add(connection);
            assertThat(registry.add(MEMBER_ID, connection)).isEmpty();
        }
        SseConnection overflow = newConnection();

        List<SseConnection> evicted = registry.add(MEMBER_ID, overflow);

        assertThat(evicted).containsExactly(added.get(0));
        assertThat(registry.connectionsOf(MEMBER_ID))
                .hasSize(SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER)
                .doesNotContain(added.get(0))
                .contains(overflow);
    }

    @DisplayName("회원끼리는 서로의 커넥션에 영향을 주지 않는다")
    @Test
    void 회원끼리_독립적이다() {
        SseConnection mine = newConnection();
        SseConnection others = newConnection();
        registry.add(MEMBER_ID, mine);
        registry.add(OTHER_MEMBER_ID, others);

        assertThat(registry.remove(MEMBER_ID, mine)).hasValue(0);
        assertThat(registry.connectionsOf(OTHER_MEMBER_ID)).containsExactly(others);
    }
}
