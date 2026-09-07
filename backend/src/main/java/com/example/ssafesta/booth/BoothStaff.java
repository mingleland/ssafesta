package com.example.ssafesta.booth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A member who may work on someone else's booth (spec 005 FR-012).
 *
 * <p>spec 005 only <b>reads</b> this table. Inviting, accepting and assigning roles belong to
 * spec 011; building any of that here would mean building it twice, and 011's role vocabulary does
 * not exist yet. Membership is therefore "a row exists" — when 011 decides how rows appear, this
 * code keeps working unchanged (research R-07).
 */
@Entity
@Table(name = "booth_staffs")
@IdClass(BoothStaff.Key.class)
public class BoothStaff {

    @Id
    @Column(name = "booth_id")
    private Long boothId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    protected BoothStaff() {
    }

    public BoothStaff(Long boothId, Long userId, String role) {
        this.boothId = boothId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public Long getBoothId() { return boothId; }
    public Long getUserId() { return userId; }
    public String getRole() { return role; }

    /** Composite key mirroring {@code PRIMARY KEY(booth_id, user_id)}. */
    public static class Key implements Serializable {

        private Long boothId;
        private Long userId;

        public Key() {
        }

        public Key(Long boothId, Long userId) {
            this.boothId = boothId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(boothId, key.boothId) && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(boothId, userId);
        }
    }
}
