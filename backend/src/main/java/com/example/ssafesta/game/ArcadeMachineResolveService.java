package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which game an arcade machine runs, judged fresh on every request (S15P21A604-602, GitLab #56 안 1).
 *
 * <p>Unity sends only the machine id it placed in the scene; the game behind it is the server's
 * answer. Caching that answer anywhere would let a game that was made private a second ago keep
 * launching, which is why the endpoint is {@code no-store} and this method re-reads both rows.
 *
 * <p><b>An unplayable game is a 200, not an error.</b> The machine is a world fixture standing in
 * front of the player — the world does not break because the game behind it was unpublished, so the
 * response says {@code playable: false} with a reason and the client shows a notice (spec 019
 * FR-020, contracts §unavailableReason 어휘). Only an unknown machine is a {@code 404}: there is
 * nothing to describe, and {@code machineId} is the one field the response cannot leave empty.
 */
@Service
public class ArcadeMachineResolveService {

    private final ArcadeMachineBindingRepository bindings;
    private final GameRepository games;

    public ArcadeMachineResolveService(ArcadeMachineBindingRepository bindings, GameRepository games) {
        this.bindings = bindings;
        this.games = games;
    }

    @Transactional(readOnly = true)
    public ResolvedView resolve(String machineId) {
        ArcadeMachineBinding binding = bindings.findById(machineId)
                .orElseThrow(() -> new ApiException(ErrorCode.MACHINE_NOT_FOUND));
        Game game = games.findById(binding.getGameId())
                // The foreign key makes this unreachable. If it ever happens the binding outlived
                // its game, which is a server fault — reported as one rather than smoothed over
                // with a playable:false the operator would never see (T-24).
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_PROJECT_INVALID));

        ErrorCode blocked = GamePublishedQueryService.blockedReason(game);
        boolean playable = blocked == null;
        return new ResolvedView(machineId, game.getId(),
                // Only a playable game states its version. A private game's version number is not
                // a secret worth guarding, but sending it would invite a client to launch on it.
                playable ? game.getPublishedVersion() : null,
                playable, playable ? null : blocked.name());
    }

    /**
     * The resolution body (contracts §Booth Portal Resolution, arcade 변형).
     *
     * <p>No {@code boothId}: these machines are world fixtures, so there is no lease to judge and
     * {@code BOOTH_LEASE_EXPIRED} cannot occur here (2026-09-10 배치 결정, docs/26).
     */
    public record ResolvedView(String machineId, Long gameId, Integer publishedVersion,
                               boolean playable, String unavailableReason) {
    }
}
