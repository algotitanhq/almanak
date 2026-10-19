package app.almanak

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.almanak.ui.AlmanakRoot
import app.almanak.ui.DisplayConfig

/**
 * Screensaver: the same ambient UI, hosted in a DreamService. A Dream is not a LifecycleOwner, so it provides
 * the lifecycle, ViewModel store and saved state Compose expects. STARTED only while dreaming, so the refresh
 * loops (collected with WhileSubscribed) run exactly while the screensaver is visible.
 */
class AlmanakDream : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any key wakes the TV
        isFullscreen = true
        isScreenBright = true
        val graph = (application as AlmanakApp).graph
        val vm = ViewModelProvider(this, AmbientViewModel.factory(graph))[AmbientViewModel::class.java]
        window.decorView.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeViewModelStoreOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(
            ComposeView(this).apply {
                setContent {
                    val settings by graph.settings.settings.collectAsStateWithLifecycle()
                    AlmanakRoot(vm, DisplayConfig.from(settings))
                }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        moveTo(Lifecycle.State.RESUMED)
    }

    override fun onDreamingStopped() {
        moveTo(Lifecycle.State.CREATED)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        moveTo(Lifecycle.State.DESTROYED)
        viewModelStore.clear()
        super.onDetachedFromWindow()
    }

    /**
     * Order-independent transitions: on Android 11, DreamService.detach() calls onDetachedFromWindow *before*
     * onDreamingStopped, so a stop can arrive after destroy. DESTROYED is terminal; everything else steps
     * through the intermediate events automatically.
     */
    private fun moveTo(state: Lifecycle.State) {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) lifecycleRegistry.currentState = state
    }
}
