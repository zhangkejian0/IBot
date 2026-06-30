package com.xbot.xbot.core;

/**
 * Debug / display toggles ported from Flutter {@code DisplaySettings}.
 *
 * <p>Object detection is omitted from the native Android build.
 */
public class DisplaySettings {
    public boolean faceEnabled = true;
    public boolean handEnabled = true;
    public boolean identityEnabled = true;
    public boolean poseEnabled = true;
    public boolean debugMode = false;

    // Debug visualization
    public boolean showFaceMesh = true;
    public boolean showFaceBox = true;
    public boolean showHandSkeleton = true;
    public boolean showPoseSkeleton = true;
    public boolean showLandmarkIndices = false;
    public boolean showExpression = true;
    public boolean showGesture = true;
    public boolean showIdentity = true;
    public boolean mirrorFrontCamera = true;
    public boolean useFrontCamera = true;

    // Base control
    public boolean baseControlEnabled = false;
    public String baseControlMode = "manual";

    // Voice assistant
    public boolean voiceEnabled = false;
    public boolean wakeWordEnabled = true;
    public boolean ttsEnabled = true;
    public boolean streamingSttEnabled = true;
    public boolean gazeTriggerEnabled = true;
    public String wakeWord = "你好";
    public String asrApiKey;
    public String llmApiKey;

    // Persona / network logging
    public boolean personaLogEnabled = true;
    public boolean personaLogServerEnabled = true;
    public boolean networkLogEnabled = true;
}
