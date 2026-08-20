package com.sgaikar1.edgedroid.runtime.qnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexagonDetectorTest {

    @Test
    fun `classifies known soc models`() {
        assertEquals(HexagonArch.V73, HexagonDetector.classifySocModel("SM8450"))
        assertEquals(HexagonArch.V73, HexagonDetector.classifySocModel("SM8475"))
        assertEquals(HexagonArch.V75, HexagonDetector.classifySocModel("SM8550"))
        assertEquals(HexagonArch.V75, HexagonDetector.classifySocModel("SM8550-AB"))
        assertEquals(HexagonArch.V77, HexagonDetector.classifySocModel("SM8650"))
        assertEquals(HexagonArch.V79, HexagonDetector.classifySocModel("SM8750"))
        assertEquals(HexagonArch.V79, HexagonDetector.classifySocModel("SM8750-P"))
        assertEquals(HexagonArch.V81, HexagonDetector.classifySocModel("SM8850"))
        assertEquals(HexagonArch.V81, HexagonDetector.classifySocModel("sm8850"))
    }

    @Test
    fun `unknown or non qualcomm models are not classified`() {
        assertNull(HexagonDetector.classifySocModel(null))
        assertNull(HexagonDetector.classifySocModel(""))
        assertNull(HexagonDetector.classifySocModel("tensor-g4"))
        assertNull(HexagonDetector.classifySocModel("MT6983"))
    }

    @Test
    fun `detect resolves arch from soc model first`() {
        val arch = HexagonDetector.detect(
            socModel = "SM8750",
            hardware = "qcom",
            board = "sun",
            device = "sun",
            product = "sun_bsp",
        )
        assertEquals(HexagonArch.V79, arch)
        assertTrue(arch.isHexagon)
        assertTrue(arch.isNpuCapable)
    }

    @Test
    fun `detect falls back to ro soc model property`() {
        val arch = HexagonDetector.detect(
            socModel = "",
            hardware = "qcom",
            board = "",
            device = "",
            product = "",
            systemSocModel = "SM8850",
        )
        assertEquals(HexagonArch.V81, arch)
        assertTrue(arch.isNpuCapable)
    }

    @Test
    fun `detect resolves platform codenames on qualcomm hardware`() {
        assertEquals(
            HexagonArch.V75,
            HexagonDetector.detect("", "qcom", "kalama", "kalama", "kalama"),
        )
        assertEquals(
            HexagonArch.V77,
            HexagonDetector.detect("", "qcom", "pineapple", "pineapple", "pineapple"),
        )
        assertEquals(
            HexagonArch.V79,
            HexagonDetector.detect("", "qcom", "sun", "sun", "sun"),
        )
        assertEquals(
            HexagonArch.V81,
            HexagonDetector.detect("", "qcom", "dibda", "dibda", "dibda"),
        )
    }

    @Test
    fun `platform codenames require qualcomm hardware to avoid false positives`() {
        // "sun" is also a prefix of e.g. Pixel "sunfish" — must NOT classify non-Qualcomm.
        assertEquals(
            HexagonArch.UNKNOWN,
            HexagonDetector.detect("", "google", "sunfish", "sunfish", "sunfish"),
        )
        assertEquals(
            HexagonArch.UNKNOWN,
            HexagonDetector.detect("", "", "sun", "sun", "sun"),
        )
    }

    @Test
    fun `unknown devices report unknown arch and no npu capability`() {
        val arch = HexagonDetector.detect("", "qcom", "unknown_board", "x", "y")
        assertEquals(HexagonArch.UNKNOWN, arch)
        assertFalse(arch.isHexagon)
        assertFalse(arch.isNpuCapable)
    }

    @Test
    fun `only v79 and v81 are npu capable in the bundled aar`() {
        assertFalse(HexagonArch.V73.isNpuCapable)
        assertFalse(HexagonArch.V75.isNpuCapable)
        assertFalse(HexagonArch.V77.isNpuCapable)
        assertTrue(HexagonArch.V79.isNpuCapable)
        assertTrue(HexagonArch.V81.isNpuCapable)
        assertFalse(HexagonArch.UNKNOWN.isNpuCapable)
    }
}