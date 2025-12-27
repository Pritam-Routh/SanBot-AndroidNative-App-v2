package com.tripandevent.sanbotvoice.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Custom view that shows animated circles to indicate voice activity.
 * - Pulsing blue circles when listening
 * - Pulsing green circles when AI is speaking
 */
public class VoiceAnimationView extends View {
    
    private static final int COLOR_LISTENING = 0xFF2196F3;  // Blue
    private static final int COLOR_SPEAKING = 0xFF4CAF50;   // Green
    private static final int COLOR_IDLE = 0xFF9E9E9E;       // Gray
    
    private Paint paint;
    private float animationProgress = 0f;
    private ValueAnimator animator;
    
    private boolean isListening = false;
    private boolean isSpeaking = false;
    
    public VoiceAnimationView(Context context) {
        super(context);
        init();
    }
    
    public VoiceAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public VoiceAnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        
        // Setup animation
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1500);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            animationProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
    }
    
    /**
     * Set listening state
     */
    public void setListening(boolean listening) {
        this.isListening = listening;
        updateAnimation();
        invalidate();
    }
    
    /**
     * Set speaking state
     */
    public void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        updateAnimation();
        invalidate();
    }
    
    private void updateAnimation() {
        if (isListening || isSpeaking) {
            if (!animator.isRunning()) {
                animator.start();
            }
        } else {
            animator.cancel();
            animationProgress = 0f;
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        int maxRadius = Math.min(centerX, centerY);
        
        // Determine color based on state
        int color;
        if (isSpeaking) {
            color = COLOR_SPEAKING;
        } else if (isListening) {
            color = COLOR_LISTENING;
        } else {
            color = COLOR_IDLE;
        }
        
        // Draw concentric circles with animation
        if (isListening || isSpeaking) {
            // Animated pulsing circles
            for (int i = 0; i < 3; i++) {
                float progress = (animationProgress + i * 0.33f) % 1f;
                float radius = maxRadius * 0.3f + (maxRadius * 0.7f * progress);
                int alpha = (int) (255 * (1f - progress) * 0.5f);
                
                paint.setColor(color);
                paint.setAlpha(alpha);
                canvas.drawCircle(centerX, centerY, radius, paint);
            }
            
            // Center circle
            paint.setColor(color);
            paint.setAlpha(255);
            canvas.drawCircle(centerX, centerY, maxRadius * 0.25f, paint);
        } else {
            // Static idle circle
            paint.setColor(color);
            paint.setAlpha(100);
            canvas.drawCircle(centerX, centerY, maxRadius * 0.5f, paint);
            
            paint.setAlpha(255);
            canvas.drawCircle(centerX, centerY, maxRadius * 0.25f, paint);
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }
}
