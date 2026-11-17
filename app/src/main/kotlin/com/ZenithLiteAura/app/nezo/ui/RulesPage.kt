package com.ZenithLiteAura.app.nezo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipnest.core.CaptureResult
import com.clipnest.core.CaptureRule
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 识别规则页：粘贴一段文本 → preview 出字段表 → 9 条内置规则开关。
 */
@Composable
fun RulesPage(
    rules: List<CaptureRule>,
    actions: UiActions,
    onToast: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CaptureResult?>(null) }
    var failed by remember { mutableStateOf(false) }

    val enabledCount = rules.count { it.enabled }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "try-title") { SmallTitle(text = "粘贴一段分享文本试试") }
        item(key = "try-card") {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    useLabelAsPlaceholder = true,
                    label = "4.89 复制打开抖音，看看【…】…",
                    singleLine = false,
                    minLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = "解析看看",
                        onClick = {
                            val value = text.trim()
                            if (value.isEmpty()) {
                                failed = true
                                onToast("先粘一段分享文本")
                                return@TextButton
                            }
                            val hit = actions.preview(value).firstOrNull { it.matched && it.item != null }
                            result = hit
                            failed = hit == null
                            if (hit == null) onToast("没识别到平台分享文案")
                        },
                    )
                    TextButton(
                        text = "填入示例",
                        onClick = {
                            text = rules.firstOrNull { it.id == "douyin-share" }?.sample
                                ?: rules.firstOrNull()?.sample ?: ""
                            failed = false
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            failed -> "未命中"
                            result != null -> "已命中"
                            else -> "只在本地解析"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failed) cs.error else cs.onSurfaceVariantSummary,
                    )
                }
            }
        }

        val hit = result?.item
        if (hit != null) {
            item(key = "result") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    FieldRow("平台", hit.platformName.ifBlank { hit.platform })
                    FieldRow("作者", hit.author.ifBlank { "—" })
                    FieldRow("类型", hit.workType.ifBlank { "—" })
                    FieldRow("标题", hit.title.ifBlank { "—" })
                    FieldRow("链接", hit.url, mono = true)
                    FieldRow("rate", hit.rate.ifBlank { "—" }, mono = true)
                    FieldRow("规则", result?.rule?.name ?: hit.ruleId)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "收录这条",
                        onClick = {
                            actions.captureText(text.trim())
                            onToast("已提交收录")
                        },
                    )
                }
            }
        }

        item(key = "rules-title") { SmallTitle(text = "内置规则 · $enabledCount/${rules.size} 条生效") }
        item(key = "rules-card") {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
                rules.forEach { rule ->
                    val tag = platformTagColors(rule.platform)
                    SwitchPreference(
                        title = rule.name,
                        summary = rule.description.ifBlank { rule.platformName },
                        checked = rule.enabled,
                        startAction = {
                            Box(
                                Modifier
                                    .padding(start = 2.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(tag.content.copy(alpha = 0.7f)),
                            )
                        },
                        onCheckedChange = { on -> actions.setRuleEnabled(rule.id, on) },
                    )
                }
            }
        }
        item(key = "tip") {
            Text(
                text = "规则全部在本机用正则解析，命中后立刻落库；未命中的普通文本不会被收录。",
                style = MiuixTheme.textStyles.footnote2,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, mono: Boolean = false) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = cs.onSurfaceVariantSummary,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            style = if (mono) MonoStyle.copy(fontSize = MiuixTheme.textStyles.footnote2.fontSize)
            else MiuixTheme.textStyles.body2,
            color = cs.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}