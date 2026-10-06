package com.urke.saasbackendstarter.screenplay.domain;

import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.domain.User;
import jakarta.persistence.*;
import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="storyboard_drafts", indexes={@Index(name="idx_storyboard_drafts_project_created",columnList="project_id,created_at"),@Index(name="idx_storyboard_drafts_org",columnList="organization_id")})
@Getter @Setter @NoArgsConstructor
public class StoryboardDraft {
 @Id @Column(length=36) private String id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="organization_id",nullable=false,updatable=false) private Organization organization;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="project_id",nullable=false,updatable=false) private ScreenplayProject project;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by",nullable=false,updatable=false) private User createdBy;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="source_run_id",nullable=false,updatable=false) private AgentRun sourceRun;
 @Column(name="source_script_id",nullable=false) private Long sourceScriptId;
 @Column(name="source_script_revision",nullable=false) private long sourceScriptRevision;
 @Column(name="source_scene_id",nullable=false,length=128) private String sourceSceneId;
 @Column(name="source_scene_no",nullable=false,length=32) private String sourceSceneNo;
 @Column(name="source_heading",nullable=false,length=255) private String sourceHeading;
 @Column(name="source_scene_text",nullable=false,columnDefinition="text") private String sourceSceneText;
 @Column(name="source_scene_hash",nullable=false,length=64) private String sourceSceneHash;
 @Column(nullable=false) private long revision=1;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @PrePersist void create(){ Instant now=Instant.now(); if(createdAt==null)createdAt=now; if(updatedAt==null)updatedAt=now; }
 @PreUpdate void update(){updatedAt=Instant.now();}
}
