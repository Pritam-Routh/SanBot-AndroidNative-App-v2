package com.tripandevent.sanbotvoice.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tripandevent.sanbotvoice.heygen.HeyGenSessionManager;
import com.tripandevent.sanbotvoice.heygen.HeyGenVideoManager;
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarSessionManager;
import com.tripandevent.sanbotvoice.orchestration.OrchestratedSessionManager;

import io.livekit.android.renderer.SurfaceViewRenderer;

/**
 * AvatarViewController
 *
 * Manages the HeyGen avatar UI state and animations.
 * Handles showing/hiding the avatar container, loading states, and error overlays.
 */
public class AvatarViewController {
    private static final int ANIMATION_DURATION = 300;

    // UI Components
    private final FrameLayout avatarContainer;
    private final SurfaceViewRenderer avatarVideoView;
    private final ProgressBar avatarLoading;
    private final LinearLayout avatarErrorOverlay;
    private final ImageButton closeButton;

    // State
    private boolean isVisible = false;
    private boolean isVideoReady = false;

    public AvatarViewController(
            @NonNull FrameLayout avatarContainer,
            @NonNull SurfaceViewRenderer avatarVideoView,
            @NonNull ProgressBar avatarLoading,
            @Nullable LinearLayout avatarErrorOverlay,
            @Nullable ImageButton closeButton) {
        this.avatarContainer = avatarContainer;
        this.avatarVideoView = avatarVideoView;
        this.avatarLoading = avatarLoading;
        this.avatarErrorOverlay = avatarErrorOverlay;
        this.closeButton = closeButton;

        // Initialize as hidden
        avatarContainer.setVisibility(View.GONE);
        avatarContainer.setAlpha(0f);
    }

    /**
     * Show the avatar container with loading state
     */
    public void showLoading() {
        isVisible = true;
        isVideoReady = false;

        avatarLoading.setVisibility(View.VISIBLE);
        avatarVideoView.setVisibility(View.INVISIBLE);
        if (avatarErrorOverlay != null) {
            avatarErrorOverlay.setVisibility(View.GONE);
        }
        if (closeButton != null) {
            closeButton.setVisibility(View.GONE);
        }

        // Animate in
        avatarContainer.setVisibility(View.VISIBLE);
        avatarContainer.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION)
                .setListener(null)
                .start();
    }

    /**
     * Show the avatar video (called when video track is ready)
     */
    public void showVideo() {
        isVideoReady = true;

        android.util.Log.i("AvatarView", "*** showVideo called ***");
        android.util.Log.d("AvatarView", "isVisible=" + isVisible + ", container.visibility=" + avatarContainer.getVisibility());
        android.util.Log.d("AvatarView", "container dimensions: " + avatarContainer.getWidth() + "x" + avatarContainer.getHeight());
        android.util.Log.d("AvatarView", "videoView dimensions: " + avatarVideoView.getWidth() + "x" + avatarVideoView.getHeight());

        avatarLoading.setVisibility(View.GONE);
        avatarVideoView.setVisibility(View.VISIBLE);
        if (avatarErrorOverlay != null) {
            avatarErrorOverlay.setVisibility(View.GONE);
        }
        if (closeButton != null) {
            closeButton.setVisibility(View.VISIBLE);
        }

        // Always ensure container is visible and alpha is 1
        avatarContainer.setVisibility(View.VISIBLE);
        avatarContainer.setAlpha(1f);

        if (!isVisible) {
            avatarContainer.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setListener(null)
                    .start();
        }
        isVisible = true;

        android.util.Log.i("AvatarView", "showVideo complete - container visible, alpha=1");
    }

    /**
     * Show error state (avatar unavailable)
     */
    public void showError() {
        isVideoReady = false;

        avatarLoading.setVisibility(View.GONE);
        avatarVideoView.setVisibility(View.INVISIBLE);
        if (avatarErrorOverlay != null) {
            avatarErrorOverlay.setVisibility(View.VISIBLE);
        }
        if (closeButton != null) {
            closeButton.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Hide the avatar container
     */
    public void hide() {
        android.util.Log.w("AvatarView", "*** hide() called *** isVisible=" + isVisible);
        // Log stack trace to see who's calling hide
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(8, stackTrace.length); i++) {
            android.util.Log.w("AvatarView", "  at " + stackTrace[i].getClassName() + "." +
                    stackTrace[i].getMethodName() + "(" + stackTrace[i].getFileName() + ":" +
                    stackTrace[i].getLineNumber() + ")");
        }

        if (!isVisible) return;

        avatarContainer.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        avatarContainer.setVisibility(View.GONE);
                        isVisible = false;
                        isVideoReady = false;
                        android.util.Log.w("AvatarView", "hide animation complete - container GONE");
                    }
                })
                .start();
    }

    /**
     * Immediately hide without animation
     */
    public void hideImmediate() {
        avatarContainer.setVisibility(View.GONE);
        avatarContainer.setAlpha(0f);
        isVisible = false;
        isVideoReady = false;
    }

    /**
     * Bind the HeyGen video manager to the renderer
     */
    public void bindVideoManager(@NonNull HeyGenVideoManager videoManager) {
        videoManager.setVideoRenderer(avatarVideoView);
    }

    /**
     * Bind the LiveAvatar session manager to the renderer
     * LiveAvatar uses the same SurfaceViewRenderer as HeyGen for video display
     */
    public void bindLiveAvatarManager(@NonNull LiveAvatarSessionManager sessionManager) {
        sessionManager.setVideoRenderer(avatarVideoView);
    }

    /**
     * Bind the Orchestrated session manager to the renderer
     * In orchestrated mode, HeyGen video comes through the single LiveKit room
     */
    public void bindOrchestratedManager(@NonNull OrchestratedSessionManager sessionManager) {
        sessionManager.setVideoRenderer(avatarVideoView);
    }

    /**
     * Check if avatar is currently visible
     */
    public boolean isVisible() {
        return isVisible;
    }

    /**
     * Check if video is ready and playing
     */
    public boolean isVideoReady() {
        return isVideoReady;
    }

    /**
     * Get the SurfaceViewRenderer for direct access
     */
    public SurfaceViewRenderer getVideoRenderer() {
        return avatarVideoView;
    }

    /**
     * Update UI based on HeyGen session state
     */
    public void updateForSessionState(HeyGenSessionManager.State state) {
        switch (state) {
            case CREATING:
            case CONNECTING:
                showLoading();
                break;
            case ACTIVE:
                // Video track ready callback will show video
                break;
            case STOPPING:
            case IDLE:
                hide();
                break;
            case ERROR:
                showError();
                break;
        }
    }

    /**
     * Update UI based on LiveAvatar session state
     */
    public void updateForLiveAvatarState(LiveAvatarSessionManager.SessionState state) {
        switch (state) {
            case CONNECTING:
                showLoading();
                break;
            case CONNECTED:
                // Stream ready callback will show video
                break;
            case DISCONNECTING:
            case IDLE:
                hide();
                break;
            case ERROR:
                showError();
                break;
        }
    }

    /**
     * Release resources
     */
    public void release() {
        try {
            avatarVideoView.release();
        } catch (Exception e) {
            // Ignore release errors
        }
    }
}
