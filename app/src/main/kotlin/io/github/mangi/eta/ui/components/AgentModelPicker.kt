package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天首页顶栏标题：只负责居中显示标题，不再承担模型切换。
 *
 * 3.4.45 起模型选择与思考档位合并进输入栏右下角的合并控件（ModelEffortControl，
 * 仿旧 Sol 的 Codex 式药丸），顶栏按用户定案只留居中标题：有会话标题显示标题，
 * 角色对话显示头像+角色名（点开角色详情），都没有时显示应用名。
 * [state] / [isStreaming] / [onModelSelected] 参数保留只是为了不动壳层调用点，内部不再使用。
 */
@Composable
internal fun AgentTopBarChatHeader(
    conversationTitle: String?,
    character: RoleplayChatUi?,
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    onModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            character != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onOpenCharacter)
                            .padding(2.dp),
                    ) {
                        CharacterAvatar(
                            name = character.characterName,
                            avatarPath = character.avatarPath,
                            size = 30.dp,
                            shape = CircleShape,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = character.characterName,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenCharacter)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            else -> {
                Text(
                    text = conversationTitle?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.body1.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
