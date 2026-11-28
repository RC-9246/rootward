package io.local.accpanel;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/** Expose the same physical range and units on platforms with and without SeekBar.setMin. */
final class ThresholdSlider extends SeekBar {
    private final int minimum;
    private final String label;
    private final String unit;

    ThresholdSlider(Context context, int minimum, int maximum, String label, String unit) {
        super(context);
        this.minimum = minimum;
        this.label = label;
        this.unit = unit;
        if (Build.VERSION.SDK_INT >= 26) setMin(minimum);
        setUpperBound(maximum);
        setContentDescription(label);
    }

    int value() { return getProgress() + offset(); }
    void setValue(int value) { setProgress(value - offset()); }
    void setUpperBound(int maximum) { setMax(maximum - offset()); }
    private int offset() { return Build.VERSION.SDK_INT >= 26 ? 0 : minimum; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        String reading = value() + unit;
        if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(reading);
        else info.setContentDescription(label + "，" + reading);
        if (Build.VERSION.SDK_INT < 26)
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minimum, getMax() + offset(), value()));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 26 && args != null
                && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                && args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            Bundle adjusted = new Bundle(args);
            adjusted.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                    args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) - offset());
            return super.performAccessibilityAction(action, adjusted);
        }
        return super.performAccessibilityAction(action, args);
    }
}
