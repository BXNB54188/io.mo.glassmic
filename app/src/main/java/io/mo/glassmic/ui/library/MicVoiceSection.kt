package io.mo.glassmic.ui.library

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.mo.glassmic.audio.voice.MicVoiceController
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
private const val KEY_VOICE_PARAMS = "voice_params"

/** 参数串：id|pitch|robot|hp|lp|gate|gain|tremHz|tremDepth|drive */
data class VoiceParams(
    val id: String = "off",
    val pitch: Float = 0f,
    val robot: Float = 0f,
    val highpass: Float = 0f,
    val lowpass: Float = 0f,
    val gate: Float = -90f,
    val gain: Float = 1f,
    val tremolo: Float = 0f,
    val tremoloDepth: Float = 0f,
    val drive: Float = 1f
) {
    fun encode(): String =
        "$id|$pitch|$robot|$highpass|$lowpass|$gate|$gain|$tremolo|$tremoloDepth|$drive"

    companion object {
        fun decode(raw: String?): VoiceParams {
            val f = (raw ?: "").split('|')
            if (f.size < 7) return VoiceParams()
            return VoiceParams(
                id = f[0],
                pitch = f[1].toFloatOrNull() ?: 0f,
                robot = f[2].toFloatOrNull() ?: 0f,
                highpass = f[3].toFloatOrNull() ?: 0f,
                lowpass = f[4].toFloatOrNull() ?: 0f,
                gate = f[5].toFloatOrNull() ?: -90f,
                gain = f[6].toFloatOrNull() ?: 1f,
                tremolo = if (f.size > 7) f[7].toFloatOrNull() ?: 0f else 0f,
                tremoloDepth = if (f.size > 8) f[8].toFloatOrNull() ?: 0f else 0f,
                drive = if (f.size > 9) f[9].toFloatOrNull() ?: 1f else 1f
            )
        }

        fun of(preset: VoicePresetAlias): VoiceParams = VoiceParams(
            id = preset.id,
            pitch = preset.pitchSemitones,
            robot = preset.robotHz,
            highpass = preset.highpassHz,
            lowpass = preset.lowpassHz,
            gate = preset.gateDb,
            gain = preset.gain,
            tremolo = preset.tremoloHz,
            tremoloDepth = preset.tremoloDepth,
            drive = preset.drive
        )
    }
}

/** 只是给 of() 用的别名，避免 import 冲突。 */
typealias VoicePresetAlias = io.mo.glassmic.core.audio.voice.VoicePreset

data class MicVoiceUiState(
    val running: Boolean = false,
    val serviceRunning: Boolean = false,
    val params: VoiceParams = VoiceParams()
)

/**
 * 实时麦克风变声。
 *
 * 实际处理发生在目标 App 进程里：Hook 让目标 App 自己读真麦克风，再就地变声。
 * 这里只负责「切换音源状态」和「保存参数」，不自己开麦克风
 * —— 后台采集会被 Android 的并发采集规则喂静音。
 */
@HiltViewModel
class MicVoiceViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val playback: PlaybackController,
    private val micVoice: MicVoiceController,
    runtime: RuntimeStateHolder
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS_MIC_VOICE, Context.MODE_PRIVATE)
    private val params = MutableStateFlow(VoiceParams.decode(prefs.getString(KEY_VOICE_PARAMS, null)))

    val state: StateFlow<MicVoiceUiState> = combine(
        runtime.flow, params, micVoice.status
    ) { rt, p, st ->
        MicVoiceUiState(
            // 音源类型是 LIVE（复用 LiveCaptureSource），实际是否在跑看控制器状态
            running = rt.currentSourceType == SourceType.LIVE &&
                st.phase == MicVoiceController.Phase.ACTIVE,
            serviceRunning = rt.enabled && !rt.safeMode,
            params = p
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MicVoiceUiState())

    private fun save(p: VoiceParams) {
        params.value = p
        prefs.edit().putString(KEY_VOICE_PARAMS, p.encode()).apply()
    }

    fun choosePreset(preset: VoicePresetAlias) = save(VoiceParams.of(preset))

    fun setPitch(v: Float) = save(params.value.copy(id = "custom", pitch = v))
    fun setRobot(v: Float) = save(params.value.copy(id = "custom", robot = v))
    fun setHighpass(v: Float) = save(params.value.copy(id = "custom", highpass = v))
    fun setLowpass(v: Float) = save(params.value.copy(id = "custom", lowpass = v))
    fun setGate(v: Float) = save(params.value.copy(id = "custom", gate = v))
    fun setGain(v: Float) = save(params.value.copy(id = "custom", gain = v))
    fun setTremolo(v: Float) = save(params.value.copy(id = "custom", tremolo = v))
    fun setTremoloDepth(v: Float) = save(params.value.copy(id = "custom", tremoloDepth = v))
    fun setDrive(v: Float) = save(params.value.copy(id = "custom", drive = v))

    /** 一键回到中性：所有效果关闭、音高 0、增益 1。 */
    fun resetNeutral() = save(VoiceParams(id = "neutral"))

    fun start() {
        viewModelScope.launch { playback.setMicVoiceSource() }
    }

    fun stop() {
        viewModelScope.launch {
            playback.setRealMic()
            micVoice.close()
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val t = glass
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = t.ink3, modifier = Modifier.weight(1f))
            Text(display, fontSize = 12.sp, color = t.primaryInk)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(30.dp)
        )
    }
}

@Composable
fun MicVoiceCard(
    onNotice: suspend (String) -> Unit,
    vm: MicVoiceViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val t = glass
    var advanced by remember { mutableStateOf(false) }
    val p = state.params

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
            "在目标 App 进程内实时变声。需要在 LSPosed 里勾选该 App，并在改动后强制停止它。",
            fontSize = 12.sp, color = t.ink3,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceChanger.PRESETS.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { preset ->
                        val active = preset.id == p.id
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { vm.choosePreset(preset) }
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

        Text(
            if (advanced) "收起参数 ▴" else "展开参数 ▾",
            fontSize = 13.sp, color = t.primaryInk,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advanced = !advanced }
                .padding(top = 12.dp, bottom = 4.dp)
        )

        if (advanced) {
            ParamSlider("机器人 (Hz)", p.robot, 0f..200f, String.format("%.0f", p.robot)) { vm.setRobot(it) }
            ParamSlider("颤音 (Hz)", p.tremolo, 0f..12f, String.format("%.1f", p.tremolo)) { vm.setTremolo(it) }
            ParamSlider("颤音深度", p.tremoloDepth, 0f..1f, String.format("%.2f", p.tremoloDepth)) { vm.setTremoloDepth(it) }
            ParamSlider("失真", p.drive, 1f..6f, String.format("%.2f", p.drive)) { vm.setDrive(it) }
            ParamSlider("高通 (Hz)", p.highpass, 0f..2000f, String.format("%.0f", p.highpass)) { vm.setHighpass(it) }
            ParamSlider("低通 (Hz)", p.lowpass, 0f..12000f, String.format("%.0f", p.lowpass)) { vm.setLowpass(it) }
            ParamSlider("噪声门 (dB)", p.gate, -90f..-20f, String.format("%.0f", p.gate)) { vm.setGate(it) }
            ParamSlider("增益（1.00 = 不变）", p.gain, 0.5f..2.5f, String.format("%.2f", p.gain)) { vm.setGain(it) }
            ParamSlider("音高 半音（0 = 不变）", p.pitch, -12f..12f, String.format("%.0f", p.pitch), steps = 23) { vm.setPitch(it) }
            Text(
                "重置为中性",
                fontSize = 13.sp, color = t.primaryInk,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.resetNeutral() }
                    .padding(top = 6.dp, bottom = 2.dp)
            )
            Text(
                "0 = 关闭该级；音高 0 = 不变；增益 1.00 = 不变；失真 1.00 = 关闭。",
                fontSize = 11.sp, color = t.ink3,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
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
