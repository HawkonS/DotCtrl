package tech.hawkon.dotctrl.data

import android.content.Context
import tech.hawkon.dotctrl.R

/** Localize validation messages from the platform-independent core at the UI boundary. */
fun Context.errorText(message: String?): String? = when (message) {
    "单点模式不支持滑动" -> getString(R.string.error_single_point_mode_does_not_support_swipes)
    "点击间隔须为 5–60000 毫秒" -> getString(R.string.error_click_interval_must_be_5_60000_ms)
    "次数须为 1–100000" -> getString(R.string.error_count_must_be_1_100000)
    "脚本标识无效" -> getString(R.string.error_invalid_script_id)
    "名称须为 1–60 字符" -> getString(R.string.error_name_must_contain_1_60_characters)
    "屏幕方向无效" -> getString(R.string.error_invalid_screen_orientation)
    "循环次数须为 0–100000（0 为持续运行）" -> getString(R.string.error_loop_count_must_be_0_100000_0_continuous)
    "安全超时须为 1–1440 分钟" -> getString(R.string.error_safety_timeout_must_be_1_1440_minutes)
    "每个脚本最多 100 步" -> getString(R.string.error_a_script_can_contain_up_to_100_steps)
    "动作标识重复" -> getString(R.string.error_duplicate_action_ids)
    "动作标识无效" -> getString(R.string.error_invalid_action_id)
    "坐标须在 0–100% 之间" -> getString(R.string.error_coordinates_must_be_within_0_100)
    "手势时长须为 40–10000 毫秒" -> getString(R.string.error_gesture_duration_must_be_40_10000_ms)
    "长按时长至少 500 毫秒" -> getString(R.string.error_long_presses_must_last_at_least_500_ms)
    "步骤延时须为 0–60000 毫秒" -> getString(R.string.error_step_delay_must_be_0_60000_ms)
    "文件须小于 1 MB" -> getString(R.string.error_the_file_must_be_smaller_than_1_mb)
    "最多保存 500 个脚本" -> getString(R.string.error_you_can_save_up_to_500_scripts)
    "脚本标识重复" -> getString(R.string.error_duplicate_script_ids)
    "位置标识无效" -> getString(R.string.error_invalid_position_id)
    "名称须为 1–24 字符" -> getString(R.string.error_name_must_contain_1_24_characters)
    "坐标须为 0–100%" -> getString(R.string.error_coordinates_must_be_within_0_100)
    "模式标识无效" -> getString(R.string.error_invalid_mode_id)
    "不支持的预设版本" -> getString(R.string.error_unsupported_preset_version)
    "最多保存 100 个位置或模式" -> getString(R.string.error_you_can_save_up_to_100_positions_or_modes)
    "预设标识重复" -> getString(R.string.error_duplicate_preset_ids)
    "不能覆盖内置模式" -> getString(R.string.error_built_in_modes_cannot_be_overwritten)
    "请先添加动作" -> getString(R.string.error_add_an_action_first)
    "系统取消或拒绝了手势，已停止" -> getString(R.string.error_the_system_cancelled_or_rejected_a_gesture_stopped)
    "执行失败" -> getString(R.string.error_execution_failed)
    else -> when {
        message?.startsWith("不支持的脚本文件版本：") == true -> getString(R.string.error_unsupported_script_file_version_1, message.removePrefix("不支持的脚本文件版本："))
        message?.startsWith("不支持的配置文件版本：") == true -> getString(R.string.error_unsupported_configuration_file_version_1, message.removePrefix("不支持的配置文件版本："))
        else -> message
    }
}
