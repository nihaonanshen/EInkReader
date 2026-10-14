package com.einkreader.core

object Constants {
    @JvmField val MIN_INDENT_AVAILABLE_WIDTH_PX = 10
    @JvmField val PAGE_NUMBER_TEXT_SIZE_SP = 14
    // ✅ [P2-3 2026-10-15] Rust 布局 LRU 缓存容量（NativeBridge.layoutTextBinary 结果缓存）。
    // 每条目含整章全部页 LineMetric（含行文本），原值 20 在 i.MX6 单核小内存设备占堆过大；
    // 取 8 = 前后翻页窗口 3×2 + 冗余，命中率损失可忽略（详见 docs/CODE_REVIEW P2-3）
    @JvmField val LAYOUT_CACHE_ENTRIES = 8
}
