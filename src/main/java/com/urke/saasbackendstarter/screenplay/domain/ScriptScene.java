package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "script_scenes", indexes = {
        @Index(name = "idx_script_scenes_script_version", columnList = "script_version_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_script_scenes_version_sort", columnNames = {"script_version_id", "sort_order"})
})
@Getter
@Setter
@NoArgsConstructor
public class ScriptScene {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "script_version_id", nullable = false, updatable = false)
    private ScriptVersion scriptVersion;

    @Column(name = "scene_no", nullable = false, columnDefinition = "text")
    private String sceneNo;

    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String heading;

    @Enumerated(EnumType.STRING)
    @Column(name = "interior_exterior", nullable = false, length = 20)
    private InteriorExterior interiorExterior = InteriorExterior.UNKNOWN;

    @Size(max = 120)
    @Column(length = 120)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day", nullable = false, length = 20)
    private TimeOfDay timeOfDay = TimeOfDay.UNKNOWN;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "page_estimate", precision = 12, scale = 3)
    private BigDecimal pageEstimate;

    @Min(0)
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
