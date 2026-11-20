package io.github.skinight.focuspenpointer.hook

import io.github.skinight.focuspenpointer.config.ConfigContract

/**
 * Single runtime catalog of the actions a gesture can be mapped to. Configuration keeps
 * storing the string [id]s from [ConfigContract]; the runtime resolves them here once
 * instead of repeating `when (id)` tables for capability checks, execution and logging.
 */
internal enum class GestureAction(
    val id: String,
    val requirement: Requirement,
    /** Diagnostic label written to the runtime event log. */
    val label: String,
) {
    SYSTEM(ConfigContract.ACTION_SYSTEM, Requirement.NONE, "交给系统"),
    CONSUME(ConfigContract.ACTION_CONSUME, Requirement.NONE, "仅消费"),
    ENABLE_LASER(
        ConfigContract.TRIPLE_ACTION_ENABLE_LASER,
        Requirement.NONE,
        "开启虚拟激光或手写笔鼠标",
    ),
    SCROLL_UP(ConfigContract.ACTION_SCROLL_UP, Requirement.POINTER, "滚轮向上"),
    SCROLL_DOWN(ConfigContract.ACTION_SCROLL_DOWN, Requirement.POINTER, "滚轮向下"),
    VOLUME_UP(ConfigContract.ACTION_VOLUME_UP, Requirement.KEY_INJECTION, "音量增加"),
    VOLUME_DOWN(ConfigContract.ACTION_VOLUME_DOWN, Requirement.KEY_INJECTION, "音量降低"),
    BACK(ConfigContract.ACTION_BACK, Requirement.KEY_INJECTION, "返回"),
    HOME(ConfigContract.ACTION_HOME, Requirement.KEY_INJECTION, "桌面"),
    RECENTS(ConfigContract.ACTION_RECENTS, Requirement.KEY_INJECTION, "最近任务"),
    ;

    enum class Requirement {
        NONE,

        /** Needs a pointer position (the wheel scrolls where the pointer is). */
        POINTER,
        KEY_INJECTION,
    }

    companion object {
        private val byId = entries.associateBy(GestureAction::id)

        fun fromId(id: String): GestureAction? = byId[id]

        fun requiresPointer(id: String): Boolean =
            fromId(id)?.requirement == Requirement.POINTER

        /**
         * Unknown ids count as needing key injection. This preserves the historical
         * behavior: such a gesture is only taken over when injection is available, and its
         * execution then fails, leaving the event with the system.
         */
        fun requiresKeyInjection(id: String): Boolean =
            fromId(id)?.let { it.requirement == Requirement.KEY_INJECTION } ?: true

        fun label(id: String): String = fromId(id)?.label ?: "未知动作"
    }
}
