package spw.cue

import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.KeyBindingManager
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableSpwWorkshopApi::class)
class CuePluginLifecycleTest {
    @Test fun deniedLibraryDoesNotDisableShortcutRegistration() {
        val host = Host(keyGranted = true)
        val plugin = plugin(host)
        SwingUtilities.invokeAndWait {
            plugin.start()
            assertEquals(1, host.keys)
            plugin.stop()
            assertEquals(1, host.closedKeys)
        }
    }

    @Test fun concurrentPermissionDenialDoesNotAbortStartupAndStop() {
        val host = Host(keyGranted = true, denyRegistration = true)
        val plugin = plugin(host)
        SwingUtilities.invokeAndWait {
            plugin.start()
            plugin.stop()
        }
        assertEquals(1, host.keys)
        assertEquals(1, host.toasts)
    }

    @Test fun startingAgainRejectsConfigWorkFromTheStoppedSession() {
        val host = Host(keyGranted = false)
        val plugin = plugin(host)
        SwingUtilities.invokeAndWait(plugin::start)
        assertTrue(host.firstLookup.await(5, TimeUnit.SECONDS))
        try {
            SwingUtilities.invokeAndWait {
                plugin.stop()
                plugin.start()
            }
            assertTrue(host.registered.await(5, TimeUnit.SECONDS))
        } finally {
            host.releaseFirstLookup.countDown()
            assertTrue(host.completedFirstLookup.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait(plugin::stop)
        }
        assertEquals(0, host.keys)
        assertEquals(0, host.first.added)
        assertEquals(1, host.second.added)
        assertEquals(1, host.second.removed)
    }

    private fun plugin(host: Host): CuePlugin {
        WorkshopApi.instance = host
        // The fallback can stay closed in a headless test.
        return CuePlugin(PluginContext("spw.cue.support", "0.3.0-test5", "test.spmod", "1.19.3", Channel.Steam))
    }

    private class Host(private val keyGranted: Boolean, private val denyRegistration: Boolean = false) : WorkshopApi {
        var keys = 0
        var closedKeys = 0
        var toasts = 0
        val firstLookup = CountDownLatch(1)
        val releaseFirstLookup = CountDownLatch(1)
        val completedFirstLookup = CountDownLatch(1)
        val registered = CountDownLatch(1)
        val first = Config()
        val second = Config(registered)
        private val lookups = java.util.concurrent.atomic.AtomicInteger()
        override val ui = object : WorkshopApi.Ui {
            override fun toast(text: String, type: WorkshopApi.Ui.ToastType) { toasts++ }
        }
        override val playback: WorkshopApi.Playback get() = error("Unexpected playback")
        override val library: WorkshopApi.Library get() = error("Unexpected library")
        override val hookRegistrar: HookRegistrar get() = error("Unexpected Hook")
        override val manager = object : WorkshopApi.Manager {
            override val keyBindingManager = object : KeyBindingManager {
                override fun register(
                    actionId: String, title: String, defaultShortcut: ActionShortcut?, hasGlobal: Boolean, handler: Runnable
                ): AutoCloseable {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    keys++
                    if (denyRegistration) throw PluginPermissionDeniedException("spw.cue.support", PluginPermission.KEY_BINDINGS)
                    return AutoCloseable { closedKeys++ }
                }

                override fun unregister(actionId: String) = error("Unexpected unregister")
            }

            override fun createConfigManager(pluginId: String): ConfigManager = createConfigManager()
            override fun createConfigManager(): ConfigManager {
                if (lookups.getAndIncrement() != 0) return second
                firstLookup.countDown()
                try {
                    releaseFirstLookup.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Allow an in-flight host call to return after stop.
                } finally {
                    completedFirstLookup.countDown()
                }
                return first
            }

            override fun isPermissionGranted(permission: PluginPermission): Boolean =
                permission == PluginPermission.KEY_BINDINGS && keyGranted
        }
    }

    private class Config(private val registered: CountDownLatch? = null) : ConfigManager, ConfigHelper {
        var added = 0
        var removed = 0
        override fun getConfig() = this
        override fun getConfig(fileName: String) = this
        override fun addConfigChangeListener(listener: Consumer<ConfigHelper>) =
            addConfigChangeListener("config.json", listener)
        override fun addConfigChangeListener(fileName: String, listener: Consumer<ConfigHelper>) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            added++
            registered?.countDown()
        }
        override fun removeConfigChangeListener(listener: Consumer<ConfigHelper>) { removed++ }
        override fun <T> get(key: String, defaultValue: T) = defaultValue
        override fun set(key: String, value: Any) = error("Unexpected configuration write")
        override fun save() = false
        override fun reload() = true
        override fun getConfigPath(): Path = error("No writable test configuration")
    }
}
