package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
/** Frozen input; never resolves a scene again after parsing replaces its rows. */
@Entity @Table(name="storyboard_run_contexts") @Getter @Setter @NoArgsConstructor
public class StoryboardRunContext {
 @Id @Column(name="run_id",length=36) private String runId;
 @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="run_id") private AgentRun run;
 @Column(nullable=false,length=12) private String mode;
 @Column(name="target_shot_count") private Integer targetShotCount; @Column(columnDefinition="text") private String instructions;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="storyboard_id") private StoryboardDraft storyboard;
 @Column(name="target_shot_id",length=36) private String targetShotId; @Column(name="base_storyboard_revision") private Long baseStoryboardRevision;
 @Column(name="target_shot_snapshot",columnDefinition="text") private String targetShotSnapshot;
 @Column(name="previous_shot_snapshot",columnDefinition="text") private String previousShotSnapshot;
 @Column(name="next_shot_snapshot",columnDefinition="text") private String nextShotSnapshot;
 @Column(name="source_script_id",nullable=false) private Long sourceScriptId; @Column(name="source_script_revision",nullable=false) private long sourceScriptRevision;
 @Column(name="source_scene_id",nullable=false,length=128) private String sourceSceneId; @Column(name="source_scene_no",nullable=false,length=32) private String sourceSceneNo;
 @Column(name="source_heading",nullable=false,length=255) private String sourceHeading; @Column(name="source_scene_text",nullable=false,columnDefinition="text") private String sourceSceneText; @Column(name="source_scene_hash",nullable=false,length=64) private String sourceSceneHash;
 @Column(name="result_hash",length=64) private String resultHash; @Column(name="artifact_id",length=36) private String artifactId; @Column(name="artifact_type",length=24) private String artifactType;
}
