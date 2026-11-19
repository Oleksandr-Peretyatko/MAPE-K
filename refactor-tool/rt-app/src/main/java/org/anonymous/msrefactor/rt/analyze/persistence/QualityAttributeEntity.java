package org.anonymous.msrefactor.rt.analyze.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "quality_attribute")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityAttributeEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "name", length = 64, nullable = false, unique = true)
    private String name;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** True for the 7 pre-seeded entries — cannot be hard-deleted, only deactivated. */
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
