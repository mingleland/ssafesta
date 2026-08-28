package com.example.ssafesta.project;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * The booth already has a project (spec 009 C-01 — one per booth).
 *
 * <p>Thrown from two places on purpose. The pre-check reads the row and refuses politely; the
 * {@code catch} around {@code saveAndFlush} translates the unique-index violation that only a
 * concurrent request can produce. Both paths have to answer the same way, or the loser of a race
 * would get a 500 where a sequential caller gets a 409 (BE/research.md R-02).
 */
public class ProjectAlreadyExistsException extends ApiException {

    public ProjectAlreadyExistsException(Long boothId) {
        super(ErrorCode.PROJECT_ALREADY_EXISTS);
    }
}
