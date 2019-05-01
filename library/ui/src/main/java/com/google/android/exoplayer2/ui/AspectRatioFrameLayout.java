/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.IntDef;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link FrameLayout} that resizes itself to match a specified aspect ratio.
 */
public final class AspectRatioFrameLayout extends FrameLayout {

  /** Listener to be notified about changes of the aspect ratios of this view. */
  public interface AspectRatioListener {

    /**
     * Called when either the target aspect ratio or the view aspect ratio is updated.
     *
     * @param targetAspectRatio The aspect ratio that has been set in {@link #setAspectRatio(float)}
     * @param naturalAspectRatio The natural aspect ratio of this view (before its width and height
     *     are modified to satisfy the target aspect ratio).
     * @param aspectRatioMismatch Whether the target and natural aspect ratios differ enough for
     *     changing the resize mode to have an effect.
     */
    void onAspectRatioUpdated(
        float targetAspectRatio, float naturalAspectRatio, boolean aspectRatioMismatch);
  }

  // LINT.IfChange
  /**
   * Resize modes for {@link AspectRatioFrameLayout}. One of {@link #RESIZE_MODE_FIT}, {@link
   * #RESIZE_MODE_FIXED_WIDTH}, {@link #RESIZE_MODE_FIXED_HEIGHT}, {@link #RESIZE_MODE_FILL} or
   * {@link #RESIZE_MODE_ZOOM}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    RESIZE_MODE_FIT,
    RESIZE_MODE_FIXED_WIDTH,
    RESIZE_MODE_FIXED_HEIGHT,
    RESIZE_MODE_FILL,
    RESIZE_MODE_ZOOM,
    RESIZE_MODE_ADDITIONAL_ZOOM
  })
  public @interface ResizeMode {}

  /**
   * Either the width or height is decreased to obtain the desired aspect ratio.
   */
  public static final int RESIZE_MODE_FIT = 0;
  /**
   * The width is fixed and the height is increased or decreased to obtain the desired aspect ratio.
   */
  public static final int RESIZE_MODE_FIXED_WIDTH = 1;
  /**
   * The height is fixed and the width is increased or decreased to obtain the desired aspect ratio.
   */
  public static final int RESIZE_MODE_FIXED_HEIGHT = 2;
  /**
   * The specified aspect ratio is ignored.
   */
  public static final int RESIZE_MODE_FILL = 3;
  /**
   * Either the width or height is increased to obtain the desired aspect ratio.
   */
  public static final int RESIZE_MODE_ZOOM = 4;
  /**
   * Based on MODE_ZOOM, increase eht width or height for a bigger percentages. 放大视频到指定限度。
   */
  public static final int RESIZE_MODE_ADDITIONAL_ZOOM = 5;
  // LINT.ThenChange(../../../../../../res/values/attrs.xml)

  /**
   * The {@link FrameLayout} will not resize itself if the fractional difference between its natural
   * aspect ratio and the requested aspect ratio falls below this threshold.
   *
   * <p>This tolerance allows the view to occupy the whole of the screen when the requested aspect
   * ratio is very close, but not exactly equal to, the aspect ratio of the screen. This may reduce
   * the number of view layers that need to be composited by the underlying system, which can help
   * to reduce power consumption.
   */
  private static final float MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f;

  private final AspectRatioUpdateDispatcher aspectRatioUpdateDispatcher;

  private AspectRatioListener aspectRatioListener;

  private float videoAspectRatio;
  private @ResizeMode int resizeMode;

  /**
    * 裁掉的部分（比如字幕）占视频高度的百分比
    */
  private double cropRatio = 0.2;
  /**
   * 是否拉伸到精确的cropRatio比例
   */
  private boolean keepExact = true;

  public AspectRatioFrameLayout(Context context) {
    this(context, null);
  }

  public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
    resizeMode = RESIZE_MODE_FIT;
    if (attrs != null) {
      TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
          R.styleable.AspectRatioFrameLayout, 0, 0);
      try {
        resizeMode = a.getInt(R.styleable.AspectRatioFrameLayout_resize_mode, RESIZE_MODE_FIT);
      } finally {
        a.recycle();
      }
    }
    aspectRatioUpdateDispatcher = new AspectRatioUpdateDispatcher();
  }

  /**
   * Sets the aspect ratio that this view should satisfy.
   *
   * @param widthHeightRatio The width to height ratio.
   */
  public void setAspectRatio(float widthHeightRatio) {
    if (this.videoAspectRatio != widthHeightRatio) {
      this.videoAspectRatio = widthHeightRatio;
      requestLayout();
    }
  }

  public void setCropRatio(double ratio){
    cropRatio = ratio >= 0 && ratio < 1 ? ratio : 0.2;
  }

  /**
   * Sets the {@link AspectRatioListener}.
   *
   * @param listener The listener to be notified about aspect ratios changes.
   */
  public void setAspectRatioListener(AspectRatioListener listener) {
    this.aspectRatioListener = listener;
  }

  /** Returns the {@link ResizeMode}. */
  public @ResizeMode int getResizeMode() {
    return resizeMode;
  }

  /**
   * Sets the {@link ResizeMode}
   *
   * @param resizeMode The {@link ResizeMode}.
   */
  public void setResizeMode(@ResizeMode int resizeMode) {
    if (this.resizeMode != resizeMode) {
      this.resizeMode = resizeMode;
      requestLayout();
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    if (videoAspectRatio <= 0) {
      // Aspect ratio not set.
      return;
    }

    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    float viewAspectRatio = (float) width / height;
    // aspectDeformation < 0 代表视频的宽高比 < FrameLayout宽高比
    float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
//    if (Math.abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
//      // We're within the allowed tolerance.
//      aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, false);
//      return;
//    }

    // 这里改变的是frameLayout的width和height，而PlayerView是match_parent的，会随着frameLayout的尺寸而变化(*这里的理解可能有误，并不是PlayerView随着FrameLayout变化，而是可播放区域在变)
    switch (resizeMode) {
      case RESIZE_MODE_FIXED_WIDTH:
        height = (int) (width / videoAspectRatio);
        break;
      case RESIZE_MODE_FIXED_HEIGHT:
        // 视频的height随着view的height走，宽度重新计算，可能increase或者decrease；
        // viewWidth/viewHeight = videoWidth/videoHeight =>viewWidth/videoWidth = viewHeight/videoHeight；也就是viewWidth在
        width = (int) (height * videoAspectRatio);
//        height =  height * 2;
        break;
      case RESIZE_MODE_ZOOM://frameLayout的宽或高增加
        // 视频的宽高比 > FrameLayout宽高比。比如2:1超宽屏。为了保持比例，width需要增加，大于屏幕宽度。
        if (aspectDeformation > 0) {
          width = (int) (height * videoAspectRatio);
        } else {
          height = (int) (width / videoAspectRatio);
        }
        break;
      case RESIZE_MODE_FIT: //frameLayout的宽或高减小; 注意此时PlayerView会保持初始尺寸(但是内容的部分会fit FrameLayout)
        if (aspectDeformation > 0) {
          height = (int) (width / videoAspectRatio);
        } else {
          width = (int) (height * videoAspectRatio);
        }
      case RESIZE_MODE_ADDITIONAL_ZOOM: //裁掉底边，默认裁掉高度的1/4。首先肯定是基于ZOOM，
          // 视频的宽高比 >= FrameLayout宽高比。比如2:1超宽屏。为了保持比例，height先增加，然后width增加，大于屏幕宽度。
          if (aspectDeformation >= 0) {
              height = (int)(height * (1 + cropRatio));
              width = (int) (height * videoAspectRatio);
          } else {
              // 比如4:3的视频，width会随着frameLayout增加，那么高度也需要增加以便保持比例（否则就扁了）；
              // 对于4:3(16:12)的视频，扩大到16:9的时候，底部默认有(12-9)/12 = 0.25看不见，已经超出了0.2的默认条件（要问一下产品，此时是否要改为缩小宽度，取消满屏）
              // 对于16:10的视频，扩大到16:10的时候，底部有1/12看不见，不足0.2，需要继续扩大高度
              int newHeight = (int) (width / videoAspectRatio);
              if ((newHeight - height)/height < cropRatio){
                  if(keepExact){
                    height = (int)(height *(1+ cropRatio));
                    width = (int) (height * videoAspectRatio);
                  }else {
                    height = (int) (width / videoAspectRatio);
                  }
              }else {
//                if (keepExact){
//                  width =
//                }
                  height = newHeight;
              }
          }
        break;
      case RESIZE_MODE_FILL://什么也不做，那么可播放区域跟随FrameLayout的Width和Height
      default:
        // Ignore target aspect ratio
        break;
    }
    aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, true);
    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
  }

  /** Dispatches updates to {@link AspectRatioListener}. */
  private final class AspectRatioUpdateDispatcher implements Runnable {

    private float targetAspectRatio;
    private float naturalAspectRatio;
    private boolean aspectRatioMismatch;
    private boolean isScheduled;

    public void scheduleUpdate(
        float targetAspectRatio, float naturalAspectRatio, boolean aspectRatioMismatch) {
      this.targetAspectRatio = targetAspectRatio;
      this.naturalAspectRatio = naturalAspectRatio;
      this.aspectRatioMismatch = aspectRatioMismatch;

      if (!isScheduled) {
        isScheduled = true;
        post(this);
      }
    }

    @Override
    public void run() {
      isScheduled = false;
      if (aspectRatioListener == null) {
        return;
      }
      aspectRatioListener.onAspectRatioUpdated(
          targetAspectRatio, naturalAspectRatio, aspectRatioMismatch);
    }
  }
}
