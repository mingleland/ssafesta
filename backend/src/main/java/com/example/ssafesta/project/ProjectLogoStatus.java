package com.example.ssafesta.project;

/** 로고 업로드의 상태 (GitLab #241). */
public enum ProjectLogoStatus {

    /** grant 는 발급됐고 바이트는 아직 검증되지 않았다. 만료되면 정리 대상이다. */
    PENDING,

    /** 검증을 통과했다. 이 상태만 참조·제공될 수 있다. */
    READY,

    /** 결정적으로 거절됐다. 재시도는 새 업로드이고 이 행은 되살아나지 않는다. */
    FAILED
}
