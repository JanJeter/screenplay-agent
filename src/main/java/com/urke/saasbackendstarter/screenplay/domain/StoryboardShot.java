package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
@Entity @Table(name="storyboard_shots", uniqueConstraints=@UniqueConstraint(name="uk_storyboard_shot_order",columnNames={"storyboard_id","order_index"}), indexes=@Index(name="idx_storyboard_shots_storyboard",columnList="storyboard_id"))
@Getter @Setter @NoArgsConstructor
public class StoryboardShot {
 @Id @Column(length=36) private String id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="storyboard_id",nullable=false,updatable=false) private StoryboardDraft storyboard;
 @Column(name="order_index",nullable=false) private int orderIndex;
 @Enumerated(EnumType.STRING) @Column(name="shot_size",nullable=false,length=24) private ShotSize shotSize;
 @Enumerated(EnumType.STRING) @Column(name="camera_movement",nullable=false,length=24) private CameraMovement cameraMovement;
 @Column(name="visual_description",nullable=false,columnDefinition="text") private String visualDescription;
 @Column(columnDefinition="text") private String dialogue="";
 @Column(columnDefinition="text") private String sound="";
 @Column(name="duration_seconds",nullable=false) private int durationSeconds;
 @Column(name="image_prompt",nullable=false,columnDefinition="text") private String imagePrompt;
 @Column(name="video_prompt",nullable=false,columnDefinition="text") private String videoPrompt;
 @Column(name="source_quote",nullable=false,columnDefinition="text") private String sourceQuote;
}
