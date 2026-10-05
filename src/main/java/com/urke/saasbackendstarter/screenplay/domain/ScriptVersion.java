package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "script_versions", indexes = {
        @Index(name = "idx_script_versions_project", columnList = "project_id"),
        @Index(name = "idx_script_versions_project_created", columnList = "project_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ScriptVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ScreenplayProject project;

    @NotBlank
    @Size(max = 80)
    @Column(name = "version_name", nullable = false, length = 80)
    private String versionName;

    @Size(max = 255)
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "file_path", columnDefinition = "text")
    private String filePath;

    @NotBlank
    @Size(max = 500_000)
    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    /** Changes only when this immutable version's body is changed. */
    @Column(name = "content_revision", nullable = false)
    private long contentRevision = 1L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScriptStatus status = ScriptStatus.UPLOADED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
