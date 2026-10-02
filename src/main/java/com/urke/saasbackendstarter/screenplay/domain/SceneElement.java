package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "scene_elements", indexes = {
        @Index(name = "idx_scene_elements_scene", columnList = "scene_id"),
        @Index(name = "idx_scene_elements_scene_type", columnList = "scene_id, type")
})
@Getter
@Setter
@NoArgsConstructor
public class SceneElement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scene_id", nullable = false, updatable = false)
    private ScriptScene scene;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ElementType type;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;
}
