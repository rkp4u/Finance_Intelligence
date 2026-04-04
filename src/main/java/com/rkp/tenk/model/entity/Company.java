package com.rkp.tenk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company")
@Getter
@Setter
@NoArgsConstructor
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Lowercased, suffix-stripped name used for deduplication. */
    @Column(name = "canonical_name", nullable = false, unique = true)
    private String canonicalName;

    private String ticker;

    /** SEC CIK number (US companies only). */
    private String cik;

    private String jurisdiction;

    private String sector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Produces a deduplication key from a raw company name.
     * Lowercases and strips common legal suffixes.
     */
    public static String canonicalize(String name) {
        if (name == null) return null;
        return name.toLowerCase()
                .replaceAll(",\\s*(inc|corp|ltd|co|llc|plc|limited|incorporated|corporation)\\.?$", "")
                .replaceAll("\\s+(inc|corp|ltd|co|llc|plc|limited|incorporated|corporation)\\.?$", "")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
