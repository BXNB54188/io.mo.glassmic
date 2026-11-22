package io.mo.glassmic.ui.library

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.audio.voice.VoiceChanger
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.ui.common.GlassCard
import io.mo.glassmic.ui.common.PrimaryButton
import io.mo.glassmic.ui.common.StatusKind
import io.mo.glassmic.ui.common.StatusPill
import io.mo.glassmic.ui.common.glass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PREFS_MIC_VOICE = "mic_voice"
private const val KEY_VOICE_PRESET = "voice_preset"

data class MicVoiceUiState(
    val running: Boolean = false,
    val serviceRunning: Boolean = false,
    val presetId: String = "off"
)

/**
 * 实时麦克风变声。
 *
 * 实际处理发生在目标 App 进程里：Hook 让目标 App 自己读真麦克风，再就地变声。
 * 这里只负责「切换音源状态」和「保存预设」，不自己开麦克风
 * —— 后台采集会被 Android 的并发采集规则喂静音。
 */
@HiltViewModel
class MicVoiceViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val playback: PlaybackController,
    runtime: RuntimeStateHolder
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS_MIC_VOICE, Context.MODE_PRIVATE)

    private val preset = MutableStateFlow(prefs.getString(KEY_VOICE_PRESET, "off") ?: "off")

    val state: StateFlow<MicVoiceUiState> = combine(runtime.flow, preset) { rt, id ->
        MicVoiceUiState(
            running = rt.currentSourceType == SourceType.MIC_VOICE,
            serviceRunning = rt.enabled && !rt.safeMode,
            presetId = id
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MicVoiceUiState())

    fun choosePreset(id: String) {
        preset.value = id
        prefs.edit().putString(KEY_VOICE_PRESET, id).apply()
    }

    fun start() {
        viewModelScope.launch { playback.setMicVoiceSource() }
    }

    fun stop() {
        viewModelScope.launch { playback.setRealMic() }
    }
}

@Composable
fun MicVoiceCard(
    onNotice: suspend (String) -> Unit,
    vm: MicVoiceViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val t = glass

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        radius = 26.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "麦克风变声",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (state.running) StatusPill("运行中", StatusKind.Ok)
        }
        Text(
            "在目标 App 进程内实时变声：它读到的声音会先被处理再还给它。需要在 LSPosed 里勾选该 App。",
            fontSize = 12.sp, color = t.ink3,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceChanger.PRESETS.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { preset ->
                        val active = preset.id == state.presetId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { vm.choosePreset(preset.id) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                preset.label,
                                fontSize = 13.sp,
                                color = if (active) t.primaryInk else t.ink,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!state.serviceRunning && !state.running) {
            Text(
                "先在主页开启 GlassMic",
                fontSize = 12.sp, color = t.ink3,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.padding(top = 10.dp))
        PrimaryButton(
            text = if (state.running) "停止变声" else "开始变声",
            onClick = { if (state.running) vm.stop() else vm.start() },
            enabled = state.running || state.serviceRunning,
            color = if (state.running) t.err else androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
