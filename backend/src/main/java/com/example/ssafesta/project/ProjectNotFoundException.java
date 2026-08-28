package com.example.ssafesta.project;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/**
 * No project with that id (spec 009 C-07).
 *
 * <p>A dedicated code rather than the generic {@code NOT_FOUND}: the frontend branches on it, and
 * this repository already names the domain in every other one — {@code BOOTH_NOT_FOUND},
 * {@code GAME_NOT_FOUND}, {@code WALLET_NOT_FOUND}, {@code CONFIG_NOT_FOUND}.
 */
public class ProjectNotFoundException extends ApiException {

    public ProjectNotFoundException(Long projectId) {
        // The id stays out of the message, like BoothNotFoundException: it is developer text, and
        // the client already knows which project it asked for. requestId ties it to the log.
        super(ErrorCode.PROJECT_NOT_FOUND);
    }
}
