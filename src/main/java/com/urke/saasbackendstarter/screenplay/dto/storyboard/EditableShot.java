package com.urke.saasbackendstarter.screenplay.dto.storyboard;
import com.urke.saasbackendstarter.screenplay.domain.*;
public record EditableShot(ShotSize shotSize, CameraMovement cameraMovement, String visualDescription, String dialogue, String sound, Integer durationSeconds, String imagePrompt, String videoPrompt, String sourceQuote) {}
