package com.ZenithLiteAura.app.nezo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分组：SmallTitle + 一张 Card（行自己带 16dp 内边距，和 SwitchPreference 对齐）。 */
@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(text = title)
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(vertical = 4.dp),
        ) {
            content()
        }
    }
}

/** 行首图标：22dp、主色、与标题 14dp 间距（Nexio 设置入口的一致写法）。 */
@Composable
fun SettingsIcon(icon: ImageVector) {
    Row {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
    }
}

/** 开关行：图标 + 标题 + 开关（可选一行 summary）。 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    summary: String? = null,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        startAction = { SettingsIcon(icon) },
        onCheckedChange = onCheckedChange,
    )
}

/** 导航行：图标 + 标题 +（可选当前值）+ 右箭头。 */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        startAction = { SettingsIcon(icon) },
        endActions = {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        },
        onClick = onClick,
    )
}

/** 只读值行：图标 + 标题 + 右侧值（不可点，用于版本号）。 */
@Composable
fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    BasicComponent(
        title = title,
        startAction = { SettingsIcon(icon) },
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        },
    )
}

/** 二级页里的普通行（无图标）：标题 + 右侧值。 */
@Composable
fun SettingsLabelRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        },
        onClick = onClick,
    )
}

/** 二级页里的开关行（无图标）。 */
@Composable
fun SettingsPlainSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/** 单选项：标题 + 选中时右侧对勾。 */
@Composable
fun SettingsOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        onClick = onClick,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = "已选中",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        },
    )
}

/** 动作行：图标 + 标题 + 右侧状态值，**没有箭头**（点击直接执行，例如「检查更新」）。 */
@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        startAction = { SettingsIcon(icon) },
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        },
        onClick = onClick,
    )
}