package com.example.ssafesta.booth;

/** No such booth (spec 004 contracts: 404). */
public class BoothNotFoundException extends RuntimeException {

    public BoothNotFoundException(Long boothId) {
        super("존재하지 않는 부스입니다 — boothId=" + boothId);
    }
}
