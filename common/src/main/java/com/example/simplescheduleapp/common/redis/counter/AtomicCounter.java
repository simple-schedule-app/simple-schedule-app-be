package com.example.simplescheduleapp.common.redis.counter;

import java.time.Duration;

/**
 * 분산 환경에서 원자적(atomic) 카운터 연산을 제공하는 추상화.
 * <p>
 * 1차 방어선(캐시 계층 사전 필터링)의 핵심 컴포넌트.
 * Redis의 INCR/DECR 명령은 단일 명령으로 원자적 실행이 보장되므로,
 * 다중 스레드/다중 인스턴스 환경에서 race condition 없이 정원 차감/복구를 수행할 수 있다.
 */
public interface AtomicCounter {

    /**
     * 키에 값을 설정한다 (TTL 없음).
     * <p>
     * 주의: TTL이 없으면 Redis 메모리 누수 가능성이 있다.
     * 가능한 경우 {@link #set(String, long, Duration)}을 사용해 TTL을 함께 설정하라.
     */
    void set(String key, long value);

    /**
     * 키에 값을 설정하고 TTL을 함께 적용한다.
     * <p>
     * 특강 정원처럼 시간이 지나면 더 이상 유효하지 않은 데이터에 사용한다.
     * 예: 특강 종료 시간 + 24시간으로 TTL 설정 → 특강 종료 후 자동 정리.
     *
     * @param key   Redis 키
     * @param value 초기값
     * @param ttl   유효 기간 (null/zero 불가)
     */
    void set(String key, long value, Duration ttl);

    /**
     * 키가 <b>없을 때만</b> 값과 TTL을 원자적으로 설정한다 (SET NX EX).
     *
     * <p>복구 경로 전용이다. 살아 있는 카운터는 이미 신청이 반영된 값이므로 덮어쓰면 그만큼 초과
     * 판매가 된다. "없으면 넣고, 있으면 손대지 않는다"가 원자적으로 보장되어야 생성 경로와 겹쳐도 안전하다.
     *
     * @return 이 호출이 키를 새로 만들었으면 true, 이미 있어서 건드리지 않았으면 false
     */
    boolean setIfAbsent(String key, long value, Duration ttl);

    Long decrement(String key);

    Long increment(String key);

    /**
     * 키가 존재하고 값이 양수일 때만 원자적으로 1 감소시킨다 (check-and-decrement).
     * <p>
     * 단순 DECR과 달리 부재 키를 음수로 생성하지 않으므로,
     * 키 만료/미초기화 상태를 정원 초과로 오분류하지 않는다.
     *
     * @return 감소된 값. 키가 없으면 -2, 남은 수량이 0 이하면 -1 (감소 없음)
     */
    Long decrementIfPositive(String key);

    /**
     * 마감 시각을 함께 검사하는 조건부 감소.
     *
     * <p>{@link #decrementIfPositive}와 달리 <b>감소 전에 마감을 먼저 본다.</b> 이 순서가
     * 중요하다 — 마감이 지났고 자리도 없는 경우 "마감됨"이 나가야지 "자리 없음"이 나가면
     * 사용자가 다시 시도할 이유를 갖게 된다.
     *
     * <p>{@code deadlineKey}가 없으면 마감 검사를 건너뛰고 기존 동작 그대로 진행한다.
     * 이 기능 이전에 만들어진 키(마감 키 없음)가 있기 때문이다. 그 키들은 자기 TTL이
     * 만료되면서 자연히 사라진다.
     *
     * @param nowEpochSecond 호출자가 넘기는 현재 시각. 스크립트가 시계를 읽지 않는다
     * @return 감소된 값. 키 없으면 -2, 남은 수량이 0 이하면 -1, <b>마감이 지났으면 -3</b>
     */
    Long decrementIfPositiveBefore(String key, String deadlineKey, long nowEpochSecond);

    /**
     * 키가 존재할 때만 원자적으로 1 증가시킨다.
     * <p>
     * 단순 INCR과 달리 만료·부재 키를 TTL 없는 키로 부활시키지 않는다 (보상 경로용).
     *
     * @return 증가된 값. 키가 없으면 -2
     */
    Long incrementIfExists(String key);
}
