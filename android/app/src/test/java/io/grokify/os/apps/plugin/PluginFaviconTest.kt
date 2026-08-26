package io.grokify.os.apps.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PluginFaviconTest {
    @Test
    fun everyBuiltinHasAFavicon() {
        BuiltinPluginCatalog.all.forEach { app ->
            assertNotNull("${app.id} is missing a 1:1 favicon", PluginFavicon.drawableRes(app.id))
        }
    }

    @Test
    fun parseIconRecognizesNewKeys() {
        assertEquals(PluginIconKey.Assistant, RemotePluginCatalog.parseIcon("grok_assistant"))
        assertEquals(PluginIconKey.Avatar, RemotePluginCatalog.parseIcon("companion"))
        assertEquals(PluginIconKey.CexBot, RemotePluginCatalog.parseIcon("cexbot"))
        assertEquals(PluginIconKey.Forum, RemotePluginCatalog.parseIcon("discord"))
        assertEquals(PluginIconKey.Lyre, RemotePluginCatalog.parseIcon("lyre"))
    }
}
