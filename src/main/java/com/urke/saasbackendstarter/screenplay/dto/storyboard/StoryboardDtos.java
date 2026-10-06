package com.urke.saasbackendstarter.screenplay.dto.storyboard;
import com.urke.saasbackendstarter.screenplay.domain.StoryboardProposalStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.time.Instant; import java.util.List;

public final class StoryboardDtos { private StoryboardDtos() {}
 public record GenerationRequest(@NotBlank @Size(max=128) String scriptId,@NotBlank @Size(max=128) String sceneId,@NotNull @Min(4) @Max(8) Integer targetShotCount,@Size(max=1000) String instructions,@NotBlank @Size(max=128) String clientRequestId){}
 public record RewriteRequest(@NotBlank @Size(max=1000) String instruction,@NotNull @Min(1) Long expectedRevision,@NotBlank @Size(max=128) String clientRequestId){}
 public record ProposalMutationRequest(@NotNull @Min(1) Long expectedRevision){}
 public record SaveRequest(@NotNull @Min(1) Long expectedRevision,@NotNull @Size(min=4,max=8) List<@Valid SavedShot> shots){}
 public record SavedShot(@NotBlank @Size(max=128) String id, com.urke.saasbackendstarter.screenplay.domain.ShotSize shotSize,com.urke.saasbackendstarter.screenplay.domain.CameraMovement cameraMovement,@NotBlank @Size(max=2000) String visualDescription,@Size(max=1200) String dialogue,@Size(max=1200) String sound,@NotNull @Min(1) @Max(30) Integer durationSeconds,@NotBlank @Size(max=2000) String imagePrompt,@NotBlank @Size(max=2500) String videoPrompt,@NotBlank @Size(max=500) String sourceQuote){}
 public record ResultRequest(@NotNull @Valid ModelResult result){}
 public record ModelResult(String mode,List<@Valid EditableShot> shots,@Valid EditableShot proposalShot){}
 public record SourceSnapshot(String scriptId,long scriptRevision,String sourceSceneId,int sceneNo,String heading,String sceneText,String sceneHash){}
 public record ShotResponse(String id,int orderIndex,com.urke.saasbackendstarter.screenplay.domain.ShotSize shotSize,com.urke.saasbackendstarter.screenplay.domain.CameraMovement cameraMovement,String visualDescription,String dialogue,String sound,int durationSeconds,String imagePrompt,String videoPrompt,String sourceQuote){}
 public record ProposalSummary(String id,String targetShotId,long baseStoryboardRevision,StoryboardProposalStatus status,Instant createdAt){}
 public record ProposalResponse(String id,String storyboardId,String targetShotId,String sourceRunId,long baseStoryboardRevision,StoryboardProposalStatus status,EditableShot candidateShot,Instant createdAt,Instant resolvedAt){}
 public record DetailResponse(String id,String projectId,String createdBy,String sourceRunId,SourceSnapshot sourceSnapshot,long revision,Instant createdAt,Instant updatedAt,List<ShotResponse> shots,List<ProposalSummary> proposalSummaries){}
 public record ListItem(String id,String projectId,String scriptId,long scriptRevision,int sceneNo,String heading,long revision,int shotCount,Instant createdAt,Instant updatedAt){}
 public record ListResponse(List<ListItem> items,int page,int size,long total){}
 public record RunAccepted(String runId,String status){}
 /** Internal Gateway acknowledgement. Keep this shape aligned with Node's validateSavedStoryboardResult. */
 public record ResultResponse(String artifactId,ResultRef resultRef){
  public ResultResponse(String artifactId,String artifactType,String storyboardId){this(artifactId,new ResultRef(artifactType,artifactId,storyboardId));}
 }
 public record ResultRef(String type,String id,String storyboardId){}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record ContextResponse(String mode,Integer targetShotCount,String instructions,String instruction,String storyboardId,String targetShotId,Long baseStoryboardRevision,SourceSnapshot sourceSnapshot,EditableShot targetShot,EditableShot previousShot,EditableShot nextShot){}
}
