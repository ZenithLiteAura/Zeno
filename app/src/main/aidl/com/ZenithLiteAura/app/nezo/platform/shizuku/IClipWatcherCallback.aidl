// 剪巢 · Shizuku 剪贴板监听回调（应用进程侧实现，被 shell 进程里的 ShizukuClipService 调用）
package com.ZenithLiteAura.app.nezo.platform.shizuku;

interface IClipWatcherCallback {
    /** 剪贴板文本变化（shell 进程读到后回传；可能来自 Binder 线程） */
    void onClipChanged(String text);
}