package com.tripandevent.sanbotvoice.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Beautiful glowing voice orb with microphone icon.
 * Features:
 * - Animated rainbow gradient ring when active
 * - Pulsing glow effect responding to audio levels
 * - Large touchable microphone button
 * - Smooth state transitions
 */
public class VoiceOrbView extends View {

    // Colors
    private static final int COLOR_CENTER = 0xFFFFFFFF;
    private static final int COLOR_INNER_GLOW = 0xFFE040FB;
    private static final int COLOR_OUTER_GLOW = 0xFFAA00FF;
    private static final int COLOR_RING = 0xFF9C27B0;
    private static final int COLOR_IDLE = 0xFF404040;

    // Rainbow gradient colors for active state
    private static final int[] RAINBOW_COLORS = {
        0xFF00E5FF,  // Cyan
        0xFFE040FB,  // Purple
        0xFFFF4081,  // Pink
        0xFFFF9100,  // Orange
        0xFFFFEB3B,  // Yellow
        0xFF00E5FF   // Cyan (wrap around)
    };

    private Paint glowPaint;
    private Paint ringPaint;
    private Paint orbPaint;
    private Paint micPaint;
    private Paint micFillPaint;

    private float animationProgress = 0f;
    private float pulseProgress = 0f;
    private float audioLevel = 0f;
    private float targetAudioLevel = 0f;

    private ValueAnimator rotationAnimator;
    private ValueAnimator pulseAnimator;

    private boolean isActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isPressed = false;

    private OnOrbClickListener clickListener;

    public interface OnOrbClickListener {
        void onOrbClick();
    }

    public VoiceOrbView(Context context) {
        super(context);
        init();
    }

    public VoiceOrbView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoiceOrbView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Glow paint for outer effects
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);

        // Ring paint for the gradient ring
        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(8f);

        // Orb center paint
        orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        orbPaint.setStyle(Paint.Style.FILL);

        // Microphone icon paint
        micPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        micPaint.setStyle(Paint.Style.STROKE);
        micPaint.setStrokeWidth(6f);
        micPaint.setStrokeCap(Paint.Cap.ROUND);
        micPaint.setColor(COLOR_INNER_GLOW);

        micFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        micFillPaint.setStyle(Paint.Style.FILL);
        micFillPaint.setColor(COLOR_INNER_GLOW);

        setupAnimators();
        setClickable(true);
    }

    private void setupAnimators() {
        // Rotation animator for rainbow gradient
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(3000);
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            animationProgress = (float) animation.getAnimatedValue();
            // Smooth audio level transition
            audioLevel += (targetAudioLevel - audioLevel) * 0.15f;
            invalidate();
        });

        // Pulse animator for breathing effect
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(2000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(animation -> {
            pulseProgress = (float) animation.getAnimatedValue();
        });
    }

    public void setOnOrbClickListener(OnOrbClickListener listener) {
        this.clickListener = listener;
    }

    public void setActive(boolean active) {
        this.isActive = active;
        updateAnimationState();
        invalidate();
    }

    public void setListening(boolean listening) {
        this.isListening = listening;
        this.isActive = listening || isSpeaking;
        updateAnimationState();
        invalidate();
    }

    public void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        this.isActive = speaking || isListening;
        updateAnimationState();
        invalidate();
    }

    public void setAudioLevel(float level) {
        this.targetAudioLevel = Math.max(0f, Math.min(1f, level));
    }

    private void updateAnimationState() {
        if (isActive) {
            if (!rotationAnimator.isRunning()) {
                rotationAnimator.start();
            }
            if (!pulseAnimator.isRunning()) {
                pulseAnimator.start();
            }
        } else {
            rotationAnimator.cancel();
            pulseAnimator.cancel();
            animationProgress = 0f;
            pulseProgress = 0f;
            targetAudioLevel = 0f;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float orbRadius = Math.min(getWidth(), getHeight()) * 0.35f;

        float dx = event.getX() - centerX;
        float dy = event.getY() - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (distance <= orbRadius * 1.2f) {
                    isPressed = true;
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isPressed && distance <= orbRadius * 1.5f) {
                    isPressed = false;
                    invalidate();
                    if (clickListener != null) {
                        clickListener.onOrbClick();
                    }
                    performClick();
                    return true;
                }
                isPressed = false;
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                isPressed = false;
                invalidate();
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float maxRadius = Math.min(getWidth(), getHeight()) / 2f;

        // Calculate sizes
        float orbRadius = maxRadius * 0.35f;
        float ringRadius = maxRadius * 0.45f;
        float glowRadius = maxRadius * 0.85f;

        // Audio level affects glow size
        float audioBoost = 1f + (audioLevel * 0.3f);
        float pulseBoost = 1f + (pulseProgress * 0.1f);
        float pressBoost = isPressed ? 0.95f : 1f;

        if (isActive) {
            // Draw outer glow layers
            drawGlowLayers(canvas, centerX, centerY, glowRadius * audioBoost * pulseBoost);

            // Draw animated rainbow ring
            drawRainbowRing(canvas, centerX, centerY, ringRadius * pressBoost, animationProgress);

            // Draw inner glow
            drawInnerGlow(canvas, centerX, centerY, orbRadius * 1.3f * audioBoost);
        } else {
            // Draw subtle idle glow
            drawIdleGlow(canvas, centerX, centerY, glowRadius * 0.7f);
        }

        // Draw main orb
        drawOrb(canvas, centerX, centerY, orbRadius * pressBoost);

        // Draw microphone icon
        drawMicrophoneIcon(canvas, centerX, centerY, orbRadius * 0.5f);
    }

    private void drawGlowLayers(Canvas canvas, float cx, float cy, float radius) {
        // Multiple glow layers for depth
        int[] glowColors = {0x00E040FB, 0x40E040FB, 0x00E040FB};
        float[] positions = {0f, 0.5f, 1f};

        for (int i = 3; i >= 1; i--) {
            float layerRadius = radius * (0.6f + i * 0.15f);
            int alpha = (int) (30 + audioLevel * 40) / i;

            RadialGradient gradient = new RadialGradient(
                cx, cy, layerRadius,
                new int[]{
                    Color.argb(alpha, 224, 64, 251),
                    Color.argb(alpha / 2, 170, 0, 255),
                    Color.argb(0, 123, 31, 162)
                },
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
            );
            glowPaint.setShader(gradient);
            canvas.drawCircle(cx, cy, layerRadius, glowPaint);
        }
        glowPaint.setShader(null);
    }

    private void drawRainbowRing(Canvas canvas, float cx, float cy, float radius, float rotation) {
        // Create rotating sweep gradient
        canvas.save();
        canvas.rotate(rotation, cx, cy);

        SweepGradient sweepGradient = new SweepGradient(cx, cy, RAINBOW_COLORS, null);
        ringPaint.setShader(sweepGradient);
        ringPaint.setStrokeWidth(12f + audioLevel * 8f);
        ringPaint.setAlpha(255);

        canvas.drawCircle(cx, cy, radius, ringPaint);

        // Add glow to ring
        ringPaint.setStrokeWidth(20f + audioLevel * 12f);
        ringPaint.setAlpha(80);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        canvas.restore();
        ringPaint.setShader(null);
    }

    private void drawInnerGlow(Canvas canvas, float cx, float cy, float radius) {
        RadialGradient gradient = new RadialGradient(
            cx, cy, radius,
            new int[]{
                Color.argb((int)(150 + audioLevel * 100), 224, 64, 251),
                Color.argb(50, 170, 0, 255),
                Color.argb(0, 123, 31, 162)
            },
            new float[]{0f, 0.6f, 1f},
            Shader.TileMode.CLAMP
        );
        glowPaint.setShader(gradient);
        canvas.drawCircle(cx, cy, radius, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawIdleGlow(Canvas canvas, float cx, float cy, float radius) {
        RadialGradient gradient = new RadialGradient(
            cx, cy, radius,
            new int[]{
                Color.argb(40, 224, 64, 251),
                Color.argb(20, 170, 0, 255),
                Color.argb(0, 0, 0, 0)
            },
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        );
        glowPaint.setShader(gradient);
        canvas.drawCircle(cx, cy, radius, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawOrb(Canvas canvas, float cx, float cy, float radius) {
        // Main orb with gradient
        RadialGradient orbGradient = new RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.5f,
            new int[]{
                Color.argb(255, 60, 60, 70),
                Color.argb(255, 30, 30, 40),
                Color.argb(255, 15, 15, 20)
            },
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        );
        orbPaint.setShader(orbGradient);
        canvas.drawCircle(cx, cy, radius, orbPaint);

        // Subtle highlight
        RadialGradient highlightGradient = new RadialGradient(
            cx - radius * 0.2f, cy - radius * 0.3f, radius * 0.6f,
            new int[]{
                Color.argb(30, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            },
            null,
            Shader.TileMode.CLAMP
        );
        orbPaint.setShader(highlightGradient);
        canvas.drawCircle(cx, cy, radius, orbPaint);
        orbPaint.setShader(null);
    }

    private void drawMicrophoneIcon(Canvas canvas, float cx, float cy, float size) {
        int micColor = isActive ? COLOR_INNER_GLOW : COLOR_IDLE;
        micPaint.setColor(micColor);
        micFillPaint.setColor(micColor);

        float micWidth = size * 0.5f;
        float micHeight = size * 0.7f;
        float cornerRadius = micWidth / 2;

        // Microphone body (rounded rectangle)
        float left = cx - micWidth / 2;
        float top = cy - micHeight / 2 - size * 0.1f;
        float right = cx + micWidth / 2;
        float bottom = cy + micHeight / 2 - size * 0.1f;

        // Draw mic body with fill
        canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, micFillPaint);

        // Draw arc (the holder)
        float arcTop = cy - size * 0.15f;
        float arcRadius = micWidth * 0.8f;
        micPaint.setStrokeWidth(4f);
        canvas.drawArc(
            cx - arcRadius, arcTop,
            cx + arcRadius, arcTop + arcRadius * 1.6f,
            0, 180, false, micPaint
        );

        // Draw stand line
        float standTop = arcTop + arcRadius * 1.6f - 4f;
        float standBottom = cy + size * 0.5f;
        canvas.drawLine(cx, standTop, cx, standBottom, micPaint);

        // Draw base line
        float baseWidth = size * 0.4f;
        canvas.drawLine(cx - baseWidth / 2, standBottom, cx + baseWidth / 2, standBottom, micPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }
}
