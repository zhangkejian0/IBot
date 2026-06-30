package com.xbot.xbot.face;

import com.xbot.xbot.model.BehaviorState;
import com.xbot.xbot.model.FaceState;

/**
 * Maps temporal behavior state to virtual-pet FSM wire states.
 *
 * <p>Expression-driven emotion mapping was retired in Flutter; only attention
 * states (drowsy / focused) drive the pet — see {@code camera_screen.dart}.
 */
public final class EmotionMapper {
    private EmotionMapper() {}

    public static FaceState fromBehavior(BehaviorState behavior) {
        if (behavior == null) {
            return FaceState.IDLE;
        }
        switch (behavior) {
            case DROWSY:
                return FaceState.SLEEPY;
            case FOCUSED:
                return FaceState.GAZING;
            default:
                return FaceState.IDLE;
        }
    }
}
