/* 不可变显示参数帮助宿主按真实视频比例布置 Surface。 */
package io.github.fongmi.adaudio.probe.player;

/** 当前视频轨的不可变显示参数。 */
public final class ProbePlayerVideoSize {
    private static final ProbePlayerVideoSize UNKNOWN =
            new ProbePlayerVideoSize(0, 0, 1f, 0);

    private final int width;
    private final int height;
    private final float pixelWidthHeightRatio;
    private final int rotationDegrees;

    /** 创建显示参数；宽高同时为 0 表示尚无视频尺寸。 */
    public ProbePlayerVideoSize(int width, int height, float pixelWidthHeightRatio,
                                int rotationDegrees) {
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
            throw new IllegalArgumentException("视频宽高必须同时为正数或同时为 0");
        }
        if (Float.isNaN(pixelWidthHeightRatio) || Float.isInfinite(pixelWidthHeightRatio)
                || pixelWidthHeightRatio <= 0f) {
            throw new IllegalArgumentException("像素宽高比必须为有限正数");
        }
        if (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270) {
            throw new IllegalArgumentException("旋转角度必须为 0、90、180 或 270");
        }
        this.width = width;
        this.height = height;
        this.pixelWidthHeightRatio = pixelWidthHeightRatio;
        this.rotationDegrees = rotationDegrees;
    }

    static ProbePlayerVideoSize unknown() {
        return UNKNOWN;
    }

    /** 返回编码视频宽度；未知时为 0。 */
    public int getWidth() {
        return width;
    }

    /** 返回编码视频高度；未知时为 0。 */
    public int getHeight() {
        return height;
    }

    /** 返回单个像素的宽高比。 */
    public float getPixelWidthHeightRatio() {
        return pixelWidthHeightRatio;
    }

    /** 返回尚未应用的顺时针旋转角度。 */
    public int getRotationDegrees() {
        return rotationDegrees;
    }

    /** 返回是否已经得到有效视频尺寸。 */
    public boolean isKnown() {
        return width > 0 && height > 0;
    }
}
