package com.ZenithLiteAura.app.nezo.ui.settings

import com.ZenithLiteAura.app.nezo.data.ClipRepo
import com.clipnest.core.ClipItem
import kotlinx.coroutines.flow.StateFlow

/**
 * 回收站桥：直接读 `ClipRepo.trash` 并调用它的三个动作。
 *
 * 注意：契约目前只有「清空回收站」（`purgeTrash()`），**没有单条彻底删除**；
 * 单条删除先恢复再永久删除（走 `UiActions.delete`），见 [purgeOne]。
 */
object TrashGateway {

    val trashFlow: StateFlow<List<ClipItem>> get() = ClipRepo.trash

    fun restore(id: String): Boolean = ClipRepo.restoreFromTrash(id)

    fun purgeAll(): Boolean = runCatching {
        ClipRepo.purgeTrash()
        true
    }.getOrDefault(false)

    /**
     * 彻底删除单条。
     *
     * TODO(契约): 等 `ClipRepo.purgeTrash(id)` / `purgeItem(id)` 落地后直接调用，去掉「恢复→删除」两步。
     */
    fun purgeOne(id: String, fallbackDelete: (String) -> Unit): Boolean {
        if (!restore(id)) return false
        fallbackDelete(id)
        return true
    }
}