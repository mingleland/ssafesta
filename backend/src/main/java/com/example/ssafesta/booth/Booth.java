package com.example.ssafesta.booth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A member's booth and the container its content hangs off (spec 004).
 *
 * <p><b>A booth belongs to its owner, not to a slot</b> (spec 004 C-01). The slot is only a
 * position it occupies while a lease is active. That is what makes it impossible for a later
 * tenant of the same slot to inherit the previous owner's layout, documents or survey responses:
 * they get their own booth, and the old one keeps its content under its original owner
 * (invariant I-5, SC-004).
 *
 * <p>Expiry therefore never deletes a booth — it only detaches the slot (FR-010).
 */
@Entity
@Table(name = "booths")
public class Booth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    /**
     * The slot this booth currently occupies, or {@code null} when not leased.
     *
     * <p>The column is UNIQUE, so a stale value blocks anyone else from taking that slot — the
     * detach in {@link #detachSlot()} is what keeps re-leasing possible (FR-017).
     */
    @Column(name = "current_slot_id")
    private Long currentSlotId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /**
     * The exterior slot's limited presentation (spec 005 FR-018, spec 006 — "제한형 Facade").
     * Not a layout: four fixed fields, not free placement.
     */
    @Column(name = "facade_theme_code", nullable = false, length = 50)
    private String facadeThemeCode = "DEFAULT";

    @Column(name = "facade_primary_color", length = 7)
    private String facadePrimaryColor;

    @Column(name = "facade_sign_text", length = 60)
    private String facadeSignText;

    @Column(name = "facade_logo_url", length = 2048)
    private String facadeLogoUrl;

    /**
     * Which published layout version visitors currently see, or {@code null} when nothing is
     * published (spec 005 R-02, invariant I-3).
     *
     * <p>The nullable pointer is what makes FR-011 expressible: on re-lease it is cleared, so the
     * previous owner's last published layout cannot come back to life on its own.
     */
    @Column(name = "published_layout_version")
    private Integer publishedLayoutVersion;

    @Column(name = "homepage_url", length = 2048)
    private String homepageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BoothStatus status = BoothStatus.INACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Booth() {
    }

    public Booth(Long ownerUserId, String name) {
        this.ownerUserId = ownerUserId;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public Long getCurrentSlotId() { return currentSlotId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BoothStatus getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getFacadeThemeCode() { return facadeThemeCode; }
    public String getFacadePrimaryColor() { return facadePrimaryColor; }
    public String getFacadeSignText() { return facadeSignText; }
    public String getFacadeLogoUrl() { return facadeLogoUrl; }
    public Integer getPublishedLayoutVersion() { return publishedLayoutVersion; }
    public String getHomepageUrl() { return homepageUrl; }

    /**
     * Whether visitors can see anything of this booth at all — the one predicate every visitor gate
     * asks (spec 005 R-02, invariant I-3).
     *
     * <p>Named here rather than repeated as {@code getPublishedLayoutVersion() == null} at each gate:
     * 016 homepage ({@code BoothQueryService.visibleHomepageUrl}) and 009 project exhibition
     * ({@code ProjectService.findPublishedByBooth}) both branch on it, and a third reading of the
     * same column would be a third place to change when "public" is redefined.
     *
     * <p>Expiry is a <b>separate</b> question — a booth can be published and expired at once, and
     * every gate asks the lease first (004 FR-019).
     */
    public boolean isPublished() {
        return publishedLayoutVersion != null;
    }

    /** Points visitors at a newly published version — only ever called from the publish transaction. */
    void publishLayoutVersion(int versionNo) {
        this.publishedLayoutVersion = versionNo;
        this.updatedAt = Instant.now();
    }

    /**
     * Stops serving a published layout while keeping every version row intact (FR-011: preserved,
     * but not automatically republished).
     */
    void clearPublishedLayoutVersion() {
        this.publishedLayoutVersion = null;
        this.updatedAt = Instant.now();
    }

    void changeFacade(String themeCode, String primaryColor, String signText, String logoUrl) {
        this.facadeThemeCode = themeCode;
        this.facadePrimaryColor = primaryColor;
        this.facadeSignText = signText;
        this.facadeLogoUrl = logoUrl;
        this.updatedAt = Instant.now();
    }

    /**
     * The page the booth's laptop opens (spec 016 FR-001, contracts/homepage-api.md §2).
     *
     * <p>{@code null} means unregistered, which is a visitor-facing <i>notice</i> rather than an
     * error (FR-009) — the server never substitutes a placeholder.
     *
     * <p>Stored verbatim. No trim, no case folding, no normalisation: the bytes a client saves are
     * the bytes it reads back (data-model §2). Format is the caller's to check — that decision lives
     * in {@link BoothHomepageService} where the rejection message can say which rule was broken.
     */
    void changeHomepageUrl(String homepageUrl) {
        this.homepageUrl = homepageUrl;
        this.updatedAt = Instant.now();
    }

    void attachSlot(Long slotId) {
        this.currentSlotId = slotId;
        this.status = BoothStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /**
     * Releases the slot while keeping every piece of content intact (spec 004 FR-010).
     *
     * <p>Clearing the published pointer <b>here</b> rather than at the three places that call this
     * is the whole of spec 005 FR-017: a booth that leaves its slot stops serving its published
     * layout, and no future release path can forget to do it. The draft and the version history
     * stay exactly where they are — preserved, but not republished on their own (FR-011).
     */
    void detachSlot() {
        this.currentSlotId = null;
        this.status = BoothStatus.INACTIVE;
        this.publishedLayoutVersion = null;
        this.updatedAt = Instant.now();
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUserId.equals(userId);
    }
}
