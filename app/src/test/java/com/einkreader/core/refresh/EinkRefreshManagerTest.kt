package com.einkreader.core.refresh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.einkreader.core.refresh.EinkRefreshManager.RefreshMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EinkRefreshManager 刷新模式计算单测（E-ink 核心逻辑，此前零覆盖）。
 * 只测纯逻辑部分（模式判定/连续翻页计数/全刷间隔/波形码映射），不测 sysfs/反射 I/O。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EinkRefreshManagerTest {

    private lateinit var mgr: EinkRefreshManager

    @Before
    fun setUp() {
        mgr = EinkRefreshManager(ApplicationProvider.getApplicationContext())
    }

    // ========== 模式判定（companion 静态 determineMode） ==========

    @Test
    fun determineMode_forwardIsMinimal() {
        assertEquals(RefreshMode.DU_MINIMAL, EinkRefreshManager.determineMode(true, false))
    }

    @Test
    fun determineMode_backwardCacheHitIsFast() {
        assertEquals(RefreshMode.A2_FAST, EinkRefreshManager.determineMode(false, true))
    }

    @Test
    fun determineMode_backwardMissIsFull() {
        assertEquals(RefreshMode.GC16_FULL, EinkRefreshManager.determineMode(false, false))
    }

    // ========== 连续翻页计数（每 N 次强制全刷，默认 N=8） ==========

    @Test
    fun consecutiveForwardsForceFullRefreshEveryN() {
        repeat(7) {
            assertEquals(RefreshMode.DU_MINIMAL, mgr.computeRefreshMode(true, false))
        }
        // 第 8 次强制 GC16 全刷（清累积残影）
        assertEquals(RefreshMode.GC16_FULL, mgr.computeRefreshMode(true, false))
        // 计数重置：第 9 次回到普通 DU
        assertEquals(RefreshMode.DU_MINIMAL, mgr.computeRefreshMode(true, false))
    }

    @Test
    fun backwardsResetsConsecutiveCounter() {
        repeat(7) {
            mgr.computeRefreshMode(true, false)
        }
        // 一次后退把连续计数清零（后退且未命中缓存 = 全刷）
        assertEquals(RefreshMode.GC16_FULL, mgr.computeRefreshMode(false, false))
        assertEquals(RefreshMode.A2_FAST, mgr.computeRefreshMode(false, true))
        // 再次前进需累计满 8 次才再触发全刷
        repeat(7) {
            assertEquals(RefreshMode.DU_MINIMAL, mgr.computeRefreshMode(true, false))
        }
        assertEquals(RefreshMode.GC16_FULL, mgr.computeRefreshMode(true, false))
    }

    @Test
    fun refreshIntervalFromPrefsRespected() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
            .edit().putInt("refresh_interval", 2).apply()
        // N=2：第 1 次 DU、第 2 次强制全刷
        assertEquals(RefreshMode.DU_MINIMAL, mgr.computeRefreshMode(true, false))
        assertEquals(RefreshMode.GC16_FULL, mgr.computeRefreshMode(true, false))
        // 清掉配置，恢复默认 8
        ctx.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
            .edit().remove("refresh_interval").apply()
        repeat(7) {
            assertEquals(RefreshMode.DU_MINIMAL, mgr.computeRefreshMode(true, false))
        }
        assertEquals(RefreshMode.GC16_FULL, mgr.computeRefreshMode(true, false))
    }

    // ========== Nook 波形模式码映射 ==========

    @Test
    fun nookModeCodeMapping() {
        assertEquals(1, EinkRefreshManager.getNookModeCode(RefreshMode.GC16_FULL))
        assertEquals(2, EinkRefreshManager.getNookModeCode(RefreshMode.A2_FAST))
        assertEquals(3, EinkRefreshManager.getNookModeCode(RefreshMode.DU_MINIMAL))
        // 未知/兜底模式回退 A2（2）
        assertEquals(2, EinkRefreshManager.getNookModeCode(RefreshMode.INVALIDATE_FALLBACK))
    }

    @Test
    fun refreshModeTags() {
        assertTrue(RefreshMode.GC16_FULL.isFullRefresh())
        assertTrue(RefreshMode.INVALIDATE_FALLBACK.isFallback())
        assertEquals("GC16_FULL", RefreshMode.GC16_FULL.getTag())
    }
}
