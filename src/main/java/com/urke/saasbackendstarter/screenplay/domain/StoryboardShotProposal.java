package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter; import java.time.Instant;
@Entity @Table(name="storyboard_shot_proposals",indexes={@Index(name="idx_storyboard_proposals_storyboard",columnList="storyboard_id"),@Index(name="idx_storyboard_proposals_run",columnList="source_run_id")}) @Getter @Setter @NoArgsConstructor
public class StoryboardShotProposal {
 @Id @Column(length=36) private String id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="storyboard_id",nullable=false,updatable=false) private StoryboardDraft storyboard;
 @Column(name="target_shot_id",nullable=false,length=36) private String targetShotId;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="source_run_id",nullable=false,updatable=false) private AgentRun sourceRun;
 @Column(name="base_storyboard_revision",nullable=false) private long baseStoryboardRevision;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private StoryboardProposalStatus status=StoryboardProposalStatus.PENDING;
 @Enumerated(EnumType.STRING) @Column(name="shot_size",nullable=false,length=24) private ShotSize shotSize;
 @Enumerated(EnumType.STRING) @Column(name="camera_movement",nullable=false,length=24) private CameraMovement cameraMovement;
 @Column(name="visual_description",nullable=false,columnDefinition="text") private String visualDescription;
 @Column(columnDefinition="text") private String dialogue=""; @Column(columnDefinition="text") private String sound="";
 @Column(name="duration_seconds",nullable=false) private int durationSeconds;
 @Column(name="image_prompt",nullable=false,columnDefinition="text") private String imagePrompt;
 @Column(name="video_prompt",nullable=false,columnDefinition="text") private String videoPrompt;
 @Column(name="source_quote",nullable=false,columnDefinition="text") private String sourceQuote;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt; @Column(name="resolved_at") private Instant resolvedAt;
 @PrePersist void create(){if(createdAt==null)createdAt=Instant.now();}
}
