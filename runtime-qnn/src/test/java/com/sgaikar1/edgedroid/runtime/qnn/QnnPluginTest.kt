package com.sgaikar1.edgedroid.runtime.qnn

import android.content.Context
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class QnnPluginTest {

    private fun plugin(): QnnPlugin {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        return QnnPlugin(context)
    }

    @Test
    fun `plugin exposes the qnn id`() {
        assertEquals("qnn", plugin().id)
    }

    @Test
    fun `plugin supports GGUF and QNN formats`() {
        assertEquals(setOf(ModelFormat.GGUF, ModelFormat.QNN), plugin().supportedFormats)
    }

    @Test
    fun `plugin declares streaming and vision capabilities`() {
        assertEquals(setOf(Capability.STREAMING, Capability.VISION), plugin().capabilities)
    }

    @Test
    fun `plugin ships arm64-v8a native libraries only`() {
        assertEquals(setOf("arm64-v8a"), plugin().supportedAbis)
    }

    @Test
    fun `plugin reports a version`() {
        assertTrue(plugin().version.isNotBlank())
    }

    @Test
    fun `npuSupported mirrors the detected arch`() {
        val plugin = plugin()
        // JVM unit tests read stub Build fields, so arch is UNKNOWN on this host.
        assertEquals(HexagonArch.UNKNOWN, plugin.hexagonArch)
        assertFalse(plugin.npuSupported)
    }
}