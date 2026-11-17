package com.ZenithLiteAura.app.nezo.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限与系统设置入口：悬浮窗、通知、电池白名单、厂商自启动白名单。
 * 全部返回「是否成功把用户送到对应页面」，不静默失败。
 */
object PermissionHelper {

    const val REQ_NOTIFICATION = 0x9101

    // ── 悬浮窗 ────────────────────────────────────────────────────────────

    fun hasOverlay(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** 打开悬浮窗授权页；失败退化到应用详情页 */
    fun requestOverlay(activity: Activity): Boolean {
        val overlay = runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            )
            true
        }.getOrDefault(false)
        if (overlay) return true

        return runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            )
            true
        }.getOrDefault(false)
    }

    // ── 通知 ──────────────────────────────────────────────────────────────

    fun hasNotification(context: Context): Boolean {
        val enabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
        if (!enabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 13+ 弹系统授权框；更低版本只能去通知设置页 */
    fun requestNotification(activity: Activity): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION,
            )
            true
        } else {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
            true
        }
    }.getOrDefault(false)

    /** 直接进系统的通知设置页（用户拒绝过权限时用） */
    fun openNotificationSettings(activity: Activity): Boolean = runCatching {
        activity.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    // ── 电池白名单 ────────────────────────────────────────────────────────

    fun batteryOptimizationIgnored(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 申请加入电池白名单（部分 ROM 会直接弹系统确认框） */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(activity: Activity): Boolean {
        val direct = runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
            true
        }.getOrDefault(false)
        if (direct) return true

        return runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }.getOrDefault(false)
    }

    // ── 厂商自启动白名单（国产 ROM 必需，尽力而为） ────────────────────────

    fun openAutoStartSettings(context: Context): Boolean {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        val candidates: List<Pair<String, String>> = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )

            brand.contains("huawei") || brand.contains("honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )

            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
            )

            brand.contains("vivo") || brand.contains("iqoo") -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )

            brand.contains("samsung") -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )

            brand.contains("meizu") -> listOf(
                "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
            )

            else -> emptyList()
        }

        for ((pkg, cls) in candidates) {
            val launched = runCatching {
                context.startActivity(
                    Intent().setComponent(ComponentName(pkg, cls))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (launched) return true
        }

        // 兜底：应用详情页，用户可自行找自启动/后台管理
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}