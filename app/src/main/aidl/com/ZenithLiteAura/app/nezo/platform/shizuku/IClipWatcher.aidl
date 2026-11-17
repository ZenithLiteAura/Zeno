// 剪巢 · Shizuku UserService 接口
// 注意：本接口的实现类 ShizukuClipService 跑在 Shizuku 的 shell 进程（UID 2000）里，
// 只有它能后台读剪贴板（com.android.shell 持有 READ_CLIPBOARD_IN_BACKGROUND）。
package com.ZenithLiteAura.app.nezo.platform.shizuku;

import com.ZenithLiteAura.app.nezo.platform.shizuku.IClipWatcherCallback;

interface IClipWatcher {
    /** 开始监听：在 shell 进程里注册 mainClipChanged 监听器，并先主动读一次当前剪贴板 */
    void start(IClipWatcherCallback callback);

    /** 停止监听并移除回调；返回是否确实移除过 */
    boolean stop();
}