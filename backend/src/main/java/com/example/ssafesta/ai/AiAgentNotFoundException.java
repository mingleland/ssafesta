package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/** No AI agent with that id (spec 007 FR-001). */
public class AiAgentNotFoundException extends ApiException {

    public AiAgentNotFoundException(Long agentId) {
        // The id stays out of the message, like the other NOT_FOUNDs: the client already knows what
        // it asked for, and requestId ties the response to the log.
        super(ErrorCode.AGENT_NOT_FOUND);
    }
}
