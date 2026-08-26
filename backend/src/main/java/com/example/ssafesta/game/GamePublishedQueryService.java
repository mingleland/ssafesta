package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a player reads on entering a published game (contracts §Runtime).
 *
 * <p>Reads {@code game_published_versions} and nothing else. That is what keeps drafts invisible —
 * a flag on the draft row could be forgotten, a read path that never touches the table cannot be.
 */
@Service
public class GamePublishedQueryService {

    private final GameRepository games;
    private final GamePublishedVersionRepository published;

    public GamePublishedQueryService(GameRepository games, GamePublishedVersionRepository published) {
        this.games = games;
        this.published = published;
    }

    /**
     * Resolves which version is public, without reading the snapshot.
     *
     * <p>Separated from {@link #find} so a conditional request can be answered before the up-to-2MB
     * {@code project_json} is loaded. Running the gates first also keeps the 304 honest — a game that
     * went private since the client last read it must be refused, not answered "unchanged".
     *
     * @return the version number a caller may read
     */
    @Transactional(readOnly = true)
    public int currentVersion(Long gameId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));
        if (game.isDeleted()) {
            throw new ApiException(ErrorCode.GAME_DELETED);
        }
        if (game.getVisibility() != GameVisibility.PUBLIC) {
            throw new ApiException(ErrorCode.GAME_NOT_PUBLIC);
        }
        Integer pointer = game.getPublishedVersion();
        if (pointer == null) {
            throw new ApiException(ErrorCode.GAME_NOT_PUBLISHED);
        }
        return pointer;
    }

    /** The validator tag for a version number. Published rows never change, so the number is enough. */
    public static String eTagOf(int publishedVersion) {
        return "\"v" + publishedVersion + "\"";
    }

    /**
     * The currently public version.
     *
     * <p>The four refusals are separate codes on purpose — the user-facing sentence differs for each
     * and the editor branches on them (contracts §Runtime).
     *
     * <p>{@code PRIVATE} is refused for <b>everyone, owner included</b>. The contract says a private
     * game cannot be started rather than cannot be started by others, and the editor has its own
     * local preview, so an owner exception would only add a path nothing uses.
     *
     * @param viewerUserId {@code null} for a guest — playing is open to them (FR-023)
     */
    @Transactional(readOnly = true)
    public PublishedView find(Long gameId, Long viewerUserId) {
        Game game = games.findById(gameId)
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));
        if (game.isDeleted()) {
            throw new ApiException(ErrorCode.GAME_DELETED);
        }
        if (game.getVisibility() != GameVisibility.PUBLIC) {
            throw new ApiException(ErrorCode.GAME_NOT_PUBLIC);
        }
        Integer pointer = game.getPublishedVersion();
        if (pointer == null) {
            // Legal state, not a fault: visibility and publishing are independent axes, so a PUBLIC
            // game with nothing published lands here (contracts §공개 설정 변경).
            throw new ApiException(ErrorCode.GAME_NOT_PUBLISHED);
        }

        GamePublishedVersion snapshot = published.findByGameIdAndVersionNo(gameId, pointer)
                // The composite foreign key makes this unreachable. If it ever happens the pointer
                // outlived its row, which is a server fault — reported as one rather than smoothed
                // over with an empty project (T-24).
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_PROJECT_INVALID));

        return new PublishedView(snapshot.getSchemaVersion(), gameId, snapshot.getVersionNo(),
                snapshot.getProjectJson(), snapshot.getPublishedAt());
    }

    public record PublishedView(String schemaVersion, Long gameId, int publishedVersion,
                                String projectJson, Instant publishedAt) {

        /**
         * The validator tag for this snapshot.
         *
         * <p>The version number is enough: published rows never change, so the same number always
         * means the same bytes. Hashing the project would cost a read of up to 2MB to learn what an
         * integer already told us.
         */
        public String eTag() {
            return eTagOf(publishedVersion);
        }
    }
}
