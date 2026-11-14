package io.github.mangi.eta.ui.screens.forge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「Forge（opencode）」设置页：中转站地址、API Key、模型 ID。
 * 保存时把配置写进 Linux 环境里的 opencode 配置文件，Key 与其它提供商 Key 一样加密存储。
 */
@Composable
internal fun ForgeSettingsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ForgeSettingsStore(context, scope, onSaved) }
    val settings = store.settings
    val credentials = store.credentials

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.forge_settings_title),
                onBack = onBack,
            ) {
                if (!store.loaded) {
                    ForgeLoadingState(store.message)
                    return@CouiScaffoldPage
                }
                ForgeSectionGroup(title = stringResource(R.string.forge_relay_group)) {
                    ForgeFieldColumn {
                        ForgeField(
                            label = stringResource(R.string.forge_base_url),
                            value = settings.baseUrl,
                            onChange = { store.edit(settings.copy(baseUrl = it)) },
                        )
                        ForgeField(
                            label = "API Key",
                            value = credentials.apiKey,
                            secret = true,
                            onChange = { store.editCredentials(credentials.copy(apiKey = it)) },
                        )
                        ForgeField(
                            label = stringResource(R.string.forge_model_id),
                            value = settings.modelId,
                            onChange = { store.edit(settings.copy(modelId = it)) },
                        )
                    }
                }
                ForgeNote(stringResource(R.string.forge_config_note))
                ForgeSaveBlock(store)
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.forge_settings_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (!store.loaded) {
                    item(key = "loading") { ForgeLoadingState(store.message) }
                    return@MiuixScaffoldPage
                }
                item(key = "relay") {
                    Column {
                        ForgeSectionGroup(title = stringResource(R.string.forge_relay_group)) {
                            ForgeFieldColumn {
                                ForgeField(
                                    label = stringResource(R.string.forge_base_url),
                                    value = settings.baseUrl,
                                    onChange = { store.edit(settings.copy(baseUrl = it)) },
                                )
                                ForgeField(
                                    label = "API Key",
                                    value = credentials.apiKey,
                                    secret = true,
                                    onChange = { store.editCredentials(credentials.copy(apiKey = it)) },
                                )
                                ForgeField(
                                    label = stringResource(R.string.forge_model_id),
                                    value = settings.modelId,
                                    onChange = { store.edit(settings.copy(modelId = it)) },
                                )
                            }
                        }
                        ForgeNote(stringResource(R.string.forge_config_note))
                    }
                }
                item(key = "save") {
                    ForgeSaveBlock(store)
                }
            }
        }
    }
}

@Composable
private fun ForgeLoadingState(failure: String?) {
    if (failure != null) {
        ListEmptyState(title = failure, icon = Icons.Rounded.ErrorOutline)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator(size = 24.dp)
        }
    }
}

@Composable
private fun ForgeSectionGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group(title = title, content = content)
        }
        AppearanceUiStyle.MIUIX -> {
            SmallTitle(title)
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ForgeNote(text: String, modifier: Modifier = Modifier) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Footer(text = text, modifier = modifier)
        }
        AppearanceUiStyle.MIUIX -> {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ForgeFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ForgeField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiTextField(
                value = value,
                onValueChange = onChange,
                label = label,
                singleLine = true,
                maxLines = 1,
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = if (secret) {
                    {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(28.dp)
                                .clickable(role = Role.Button) { visible = !visible },
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiIcon(
                                imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = colors.summary,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            TextField(
                value = value,
                onValueChange = onChange,
                label = label,
                singleLine = true,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = if (secret) {
                    {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ForgeSaveBlock(store: ForgeSettingsStore) {
    val uiStyle = LocalUiStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (uiStyle == AppearanceUiStyle.COUI) CouiPageDefaults.horizontalMargin else 12.dp)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = stringResource(if (store.saving) R.string.speech_saving else R.string.speech_save)
        if (uiStyle == AppearanceUiStyle.COUI) {
            CouiButton(
                text = text,
                enabled = store.loaded && !store.saving,
                role = CouiButtonRole.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = store::save,
            )
        } else {
            TextButton(
                text = text,
                enabled = store.loaded && !store.saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = store::save,
            )
        }
        store.message?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
