package com.fyt.tireplus;

import android.view.View;
import android.widget.LinearLayout;

/**
 * UI 小工具
 */
class ViewUtil {

    static void addSpacer(LinearLayout parent, int heightPx) {
        View v = new View(parent.getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, heightPx));
        parent.addView(v);
    }
}
