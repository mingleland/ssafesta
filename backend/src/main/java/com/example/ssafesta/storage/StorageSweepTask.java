package com.example.ssafesta.storage;

/**
 * 객체 저장소 정리 작업 하나. {@link ObjectDeleteQueue} 의 sweep 이 자기 일을 하기 전에 불러 준다.
 *
 * <p><b>스케줄 실행 지점을 늘리지 않기 위한 이음새다.</b> 도메인마다 {@code @Scheduled} 를 붙이면
 * 같은 성질의 배치가 여럿 돌고, 하나가 멈춘 것을 아무도 모른다. 반대로 도메인 정리 코드를 큐 클래스
 * 안에 적으면 저장소 코드가 프로젝트·게임 스키마를 알게 된다 — 그쪽 방향의 의존을 만들지 않으려고
 * 검증기를 공용으로 뺐는데 삭제 쪽에서 되돌릴 이유가 없다.
 *
 * <p>구현은 자기 트랜잭션을 열고, 지울 객체의 좌표를 {@link ObjectDeleteQueue#enqueue} 로 넘긴다.
 * 실제 저장소 호출은 큐가 트랜잭션 밖에서 한다.
 */
public interface StorageSweepTask {

    /** 한 번 훑는다. 예외를 던져도 큐의 sweep 은 계속한다 — 한 도메인의 실패가 다른 쪽을 멈추지 않는다. */
    void sweep();
}
