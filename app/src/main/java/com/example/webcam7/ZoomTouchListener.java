package com.example.webcam7;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 播放视图的双指缩放手势支持。
 *
 * <p>通过 ScaleGestureDetector + GestureDetector 组合，直接修改目标视图的
 * scaleX/scaleY 与 translationX/translationY，实现以两指中点为焦点的缩放；
 * 放大后可单指拖动平移，双击复位。</p>
 */
public class ZoomTouchListener implements View.OnTouchListener {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;

    private final View target;
    /** true 时消费全部触摸事件（用于 ImageView）；false 时放行给 PlayerView 控制器。 */
    private final boolean consumeTouch;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    public ZoomTouchListener(Context context, View target, boolean consumeTouch) {
        this.target = target;
        this.consumeTouch = consumeTouch;
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        this.scaleDetector.setQuickScaleEnabled(false);
        this.gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        return consumeTouch;
    }

    /** 应用缩放与位移，并把位移限制在可视范围内。 */
    private void applyTransform() {
        if (scale <= MIN_SCALE + 0.001f) {
            scale = MIN_SCALE;
            offsetX = 0f;
            offsetY = 0f;
        } else {
            float maxX = target.getWidth() * (scale - 1f) / 2f;
            float maxY = target.getHeight() * (scale - 1f) / 2f;
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setTranslationX(offsetX);
        target.setTranslationY(offsetY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float targetScale = scale * detector.getScaleFactor();
            targetScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, targetScale));
            float ratio = targetScale / scale;
            float pivotX = target.getWidth() / 2f;
            float pivotY = target.getHeight() / 2f;
            // 保持两指中点下方的画面位置不变
            offsetX = (detector.getFocusX() - pivotX) * (1f - ratio) + offsetX * ratio;
            offsetY = (detector.getFocusY() - pivotY) * (1f - ratio) + offsetY * ratio;
            scale = targetScale;
            applyTransform();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (scale > MIN_SCALE) {
                offsetX -= distanceX;
                offsetY -= distanceY;
                applyTransform();
                return true;
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            scale = MIN_SCALE;
            offsetX = 0f;
            offsetY = 0f;
            applyTransform();
            return true;
        }
    }
}
