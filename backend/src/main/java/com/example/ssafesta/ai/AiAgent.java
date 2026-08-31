package com.example.ssafesta.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A booth's AI docent (spec 007 US1, data-model "AI Agent — Spring 소유").
 *
 * <p>The table has existed since V1 with every column this needs; what was missing was any read or
 * write path. V15 adds the unique index that turns "one agent per booth" (C-13) from an intention
 * into an invariant.
 *
 * <p>Spring stores and validates the vocabulary; FastAPI is what interprets it into a prompt
 * (C-12). Nothing here knows what {@code MEDIUM} means in tokens, and it should not — that mapping
 * belongs to the AI part and would go stale here.
 */
@Entity
@Table(name = "ai_agents")
public class AiAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Fixed at creation: moving an agent between booths would move it across owners (C-04). */
    @Column(name = "booth_id", nullable = false, updatable = false)
    private Long boothId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "role_code", nullable = false, length = 50)
    private String role;

    @Column(name = "tone_code", nullable = false, length = 30)
    private String tone;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "response_length", nullable = false, length = 20)
    private String responseLength;

    @Column(name = "service_price", nullable = false)
    private int servicePrice;

    @Column(name = "handoff_enabled", nullable = false)
    private boolean handoffEnabled;

    /**
     * Stored as {@code null} when empty, never as an empty array.
     *
     * <p>The response renders both as {@code []}, so leaving two storage shapes for one visible
     * value would make an unchanged PATCH look like a change and bump {@code updatedAt}. One
     * representation, decided at the door (see {@link #normalise}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_topics", columnDefinition = "jsonb")
    private List<String> forbiddenTopics;

    /**
     * {@code ACTIVE} on every row this class writes. There is no lifecycle here — no vocabulary
     * defines the other values, and deletion is a delete.
     *
     * <p><b>Read defensively anyway.</b> Other values do reach this column (a test writes
     * {@code DISABLED} through JDBC, and spec 011·106 may define a lifecycle later), so callers ask
     * {@link #isActive()} rather than comparing the stored string. {@code LayoutConfigResolver}
     * counts only {@code ACTIVE} rows for the same reason.
     */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Maintained here, not by the database: the column default only fires on INSERT and there is no
     * update trigger, so a column left to the schema would sit at the creation time forever.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiAgent() {
    }

    public AiAgent(Long boothId, String name, String role, String tone, String systemPrompt,
                   String responseLength, int servicePrice, boolean handoffEnabled,
                   List<String> forbiddenTopics, Instant now) {
        this.boothId = boothId;
        this.name = name;
        this.role = role;
        this.tone = tone;
        this.systemPrompt = systemPrompt;
        this.responseLength = responseLength;
        this.servicePrice = servicePrice;
        this.handoffEnabled = handoffEnabled;
        this.forbiddenTopics = normalise(forbiddenTopics);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Empty and absent are the same thing, and the same thing gets one representation. */
    static List<String> normalise(List<String> topics) {
        return topics == null || topics.isEmpty() ? null : List.copyOf(topics);
    }

    public Long getId() { return id; }

    /**
     * Whether the agent may be used, normalising every non-{@code ACTIVE} stored value to "no".
     *
     * <p>Returned as a boolean rather than the raw string so the vocabulary stays inside this class:
     * spec 008's contract only knows {@code ACTIVE}/{@code INACTIVE}, and handing out
     * {@code DISABLED} would break it.
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public Long getBoothId() { return boothId; }

    public String getName() { return name; }

    public String getRole() { return role; }

    public String getTone() { return tone; }

    public String getSystemPrompt() { return systemPrompt; }

    public String getResponseLength() { return responseLength; }

    public int getServicePrice() { return servicePrice; }

    public boolean isHandoffEnabled() { return handoffEnabled; }

    /** Never {@code null} to the caller — the storage shape does not leak into the contract. */
    public List<String> getForbiddenTopics() {
        return forbiddenTopics == null ? List.of() : forbiddenTopics;
    }

    public Instant getUpdatedAt() { return updatedAt; }

    // ── 수정 ────────────────────────────────────────────────────────────────

    public void changeName(String name) { this.name = name; }

    public void changeRole(String role) { this.role = role; }

    public void changeTone(String tone) { this.tone = tone; }

    public void changeSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public void changeResponseLength(String responseLength) { this.responseLength = responseLength; }

    public void changeServicePrice(int servicePrice) { this.servicePrice = servicePrice; }

    public void changeHandoffEnabled(boolean handoffEnabled) { this.handoffEnabled = handoffEnabled; }

    public void changeForbiddenTopics(List<String> topics) { this.forbiddenTopics = normalise(topics); }

    /** Call only when something actually changed — an unchanged PATCH must not move this. */
    public void touch(Instant now) { this.updatedAt = now; }
}
