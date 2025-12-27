package com.tripandevent.sanbotvoice.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Audio waveform visualizer
 */
public class AudioWaveformView extends View {
    
    private static final int NUM_BARS = 32;
    private static final int BAR_SPACING_DP = 3;
    private static final int MIN_BAR_HEIGHT_DP = 4;
    private static final int CORNER_RADIUS_DP = 2;
    
    private Paint barPaint;
    private RectF barRect;
    
    private float[] barHeights;
    private float[] targetHeights;
    private float audioLevel = 0f;
    
    private int barSpacing;
    private int minBarHeight;
    private int cornerRadius;
    private int barWidth;
    
    private boolean isListening = false;
    private boolean isSpeaking = false;
    
    private int listeningColor = Color.parseColor("#2196F3");
    private int speakingColor = Color.parseColor("#4CAF50");
    private int idleColor = Color.parseColor("#9E9E9E");
    
    private ValueAnimator waveAnimator;
    private float wavePhase = 0f;
    private Random random;
    
    public AudioWaveformView(Context context) {
        super(context);
        init(context);
    }
    
    public AudioWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public AudioWaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        
        barSpacing = (int) (BAR_SPACING_DP * density);
        minBarHeight = (int) (MIN_BAR_HEIGHT_DP * density);
        cornerRadius = (int) (CORNER_RADIUS_DP * density);
        
        barHeights = new float[NUM_BARS];
        targetHeights = new float[NUM_BARS];
        random = new Random();
        
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);
        barRect = new RectF();
        
        for (int i = 0; i < NUM_BARS; i++) {
            barHeights[i] = minBarHeight;
            targetHeights[i] = minBarHeight;
        }
        
        startWaveAnimation();
    }
    
    private void startWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        waveAnimator.setDuration(2000);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(animation -> {
            wavePhase = (float) animation.getAnimatedValue();
            updateBarHeights();
            invalidate();
        });
        waveAnimator.start();
    }
    
    private void updateBarHeights() {
        float maxHeight = getHeight() * 0.8f;
        
        for (int i = 0; i < NUM_BARS; i++) {
            if (isSpeaking || isListening) {
                float wave = (float) Math.sin(wavePhase + i * 0.3f);
                float noise = random.nextFloat() * 0.2f;
                float baseHeight = audioLevel * maxHeight;
                float variation = wave * 0.3f + noise;
                targetHeights[i] = Math.max(minBarHeight, baseHeight * (0.5f + 0.5f * (1 + variation)));
            } else {
                float wave = (float) Math.sin(wavePhase + i * 0.2f);
                targetHeights[i] = minBarHeight + (minBarHeight * wave * 0.5f);
            }
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f;
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;
        
        barWidth = (width - (NUM_BARS - 1) * barSpacing) / NUM_BARS;
        
        int currentColor = isSpeaking ? speakingColor : (isListening ? listeningColor : idleColor);
        
        LinearGradient gradient = new LinearGradient(
            0, centerY - height / 2f, 0, centerY + height / 2f,
            currentColor, adjustAlpha(currentColor, 0.3f), Shader.TileMode.CLAMP
        );
        barPaint.setShader(gradient);
        
        for (int i = 0; i < NUM_BARS; i++) {
            float x = i * (barWidth + barSpacing);
            float barHeight = barHeights[i];
            
            barRect.set(x, centerY - barHeight / 2, x + barWidth, centerY);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
            
            barRect.set(x, centerY, x + barWidth, centerY + barHeight / 2);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
        }
    }
    
    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
    
    public void setAudioLevel(float level) {
        this.audioLevel = Math.max(0f, Math.min(1f, level));
    }
    
    public void setListening(boolean listening) {
        this.isListening = listening;
        if (listening) this.isSpeaking = false;
    }
    
    public void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        if (speaking) this.isListening = false;
    }
    
    public void setIdle() {
        this.isListening = false;
        this.isSpeaking = false;
        this.audioLevel = 0f;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (waveAnimator != null) waveAnimator.cancel();
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (waveAnimator != null && !waveAnimator.isRunning()) waveAnimator.start();
    }
}