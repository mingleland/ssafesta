package com.example.ssafesta.internal.ai;

import com.example.ssafesta.ai.AiAgent;
import com.example.ssafesta.ai.AiAgentRepository;
import com.example.ssafesta.booth.BoothLease;
import com.example.ssafesta.booth.BoothLeaseRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "may this agent hold a conversation in this booth right now?" for FastAPI
 * (spec 008 FR-024, C-08, contract {@code spring-booth-access-api.yaml}).
 *
 * <p>Three checks in the order FR-024 lists them, short-circuiting: valid lease → the agent belongs
 * to that booth → the agent is active. FastAPI stores the returned {@code leaseEndsAt} and judges
 * every later question against it without calling back, so this is the one moment the answer is
 * computed.
 *
 * <p>A refusal is {@code 200} with {@code allowed:false}, not a 4xx. Missing booths and missing
 * agents are refusals too — the caller learns it may not proceed, not whether the row exists.
 */
@Service
public class AiBoothAccessService {

    private final BoothLeaseRepository leases;
    private final AiAgentRepository agents;

    AiBoothAccessService(BoothLeaseRepository leases, AiAgentRepository agents) {
        this.leases = leases;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public BoothAccessView evaluate(Long boothId, Long agentId) {
        // One moment for the whole answer. Reading the clock again per field would let serverTime,
        // the validity decision and remainingSeconds describe three different instants, and FastAPI
        // judges every later question against the pair it is handed.
        Instant now = Instant.now();

        BoothLease lease = leases.findValidByBoothId(boothId, now).orElse(null);
        if (lease == null) {
            // An expired lease is never fetched: with several past leases there is no defined way to
            // pick one, and the only value FastAPI keeps is the end time of a successful answer.
            // So "no leaseEndsAt" and BOOTH_LEASE_EXPIRED mean exactly the same thing.
            return BoothAccessView.leaseExpired(boothId, agentId, now);
        }

        AiAgent agent = agents.findById(agentId)
                .filter(found -> boothId.equals(found.getBoothId()))
                .orElse(null);
        if (agent == null) {
            // agentStatus is left out, and its absence is the answer: the id is not in this booth.
            // Saying more would report whether the agent exists somewhere else.
            return BoothAccessView.agentNotInBooth(boothId, agentId, lease, now);
        }
        if (!agent.isActive()) {
            return BoothAccessView.agentInactive(boothId, agentId, lease, now);
        }
        return BoothAccessView.allowed(boothId, agentId, lease, now);
    }

    /**
     * The four shapes of {@code BoothAccessResponse}, built by the factory that matches each branch
     * of the contract's {@code oneOf} so an invalid combination cannot be assembled here.
     *
     * <p>{@code NON_NULL} drops {@code agentStatus} and {@code denialCode} where the branch forbids
     * them. {@code leaseEndsAt} is exempt: it is required in every branch and carries meaning as an
     * explicit {@code null} (T-97 — a missing key and a null are not the same contract).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoothAccessView(boolean allowed, Long boothId, Long agentId, String agentStatus,
                                  @JsonInclude(JsonInclude.Include.ALWAYS) Instant leaseEndsAt,
                                  long remainingSeconds, Instant serverTime, String denialCode) {

        static BoothAccessView allowed(Long boothId, Long agentId, BoothLease lease, Instant now) {
            return new BoothAccessView(true, boothId, agentId, "ACTIVE", lease.getEndsAt(),
                    lease.remainingSecondsAt(now), now, null);
        }

        static BoothAccessView leaseExpired(Long boothId, Long agentId, Instant now) {
            return new BoothAccessView(false, boothId, agentId, null, null, 0, now,
                    "BOOTH_LEASE_EXPIRED");
        }

        static BoothAccessView agentNotInBooth(Long boothId, Long agentId, BoothLease lease,
                                               Instant now) {
            return new BoothAccessView(false, boothId, agentId, null, lease.getEndsAt(),
                    lease.remainingSecondsAt(now), now, "AGENT_NOT_IN_BOOTH");
        }

        static BoothAccessView agentInactive(Long boothId, Long agentId, BoothLease lease,
                                             Instant now) {
            return new BoothAccessView(false, boothId, agentId, "INACTIVE", lease.getEndsAt(),
                    lease.remainingSecondsAt(now), now, "AGENT_INACTIVE");
        }
    }
}
