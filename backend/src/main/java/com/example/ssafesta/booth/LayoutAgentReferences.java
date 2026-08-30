package com.example.ssafesta.booth;

import org.springframework.stereotype.Component;

/**
 * "Is this AI agent placed in the booth's layout?" — asked by agent deletion (spec 007 C-14).
 *
 * <p>Lives in {@code booth} rather than {@code ai} because the answer is inside the layout JSON, and
 * knowledge of that shape should not spread. The caller gets a boolean.
 */
@Component
public class LayoutAgentReferences {

    private final BoothLayoutDraftRepository drafts;
    private final BoothLayoutPublishedVersionRepository published;
    private final BoothRepository booths;

    LayoutAgentReferences(BoothLayoutDraftRepository drafts,
                          BoothLayoutPublishedVersionRepository published,
                          BoothRepository booths) {
        this.drafts = drafts;
        this.published = published;
        this.booths = booths;
    }

    /**
     * Whether the draft or the <b>currently public</b> version places this agent.
     *
     * <p>Two different reasons, and only the second is a hard invariant. The published one protects
     * visitors from a booth that points at nothing (A-3). The draft one only protects work in
     * progress — a draft may already reference an agent id that does not exist, because
     * {@link LayoutValidator} deliberately allows that while editing. So deleting an agent and then
     * re-adding its old id to a draft is still possible; publish is what refuses it.
     *
     * <p>"Currently public" is the pointer on the booth, not the highest version number. After a
     * re-lease the newest row can be an old tenant's, and blocking deletion on a version no visitor
     * can reach would be wrong.
     */
    public boolean referencedByLayouts(Long boothId, Long agentId) {
        return drafts.findById(boothId)
                .map(draft -> placesAgent(draft.getLayoutJson(), agentId))
                .orElse(false)
                || currentlyPublished(boothId, agentId);
    }

    private boolean currentlyPublished(Long boothId, Long agentId) {
        return booths.findById(boothId)
                .map(Booth::getPublishedLayoutVersion)
                .flatMap(version -> published.findByBoothIdAndVersionNo(boothId, version))
                .map(snapshot -> placesAgent(snapshot.getLayoutJson(), agentId))
                .orElse(false);
    }

    private boolean placesAgent(String layoutJson, Long agentId) {
        return LayoutJson.parse(layoutJson).document().objects().stream()
                .filter(object -> LayoutObjectType.AI_AGENT.name().equals(object.type()))
                .anyMatch(object -> object.configId() != null
                        && agentId.equals(object.configId().longValue()));
    }
}
