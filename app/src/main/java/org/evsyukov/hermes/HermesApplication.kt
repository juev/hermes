package org.evsyukov.hermes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

class HermesApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // The app is in front: what happened so far is seen, and the socket comes back at once.
                    container.connection.value?.let { c ->
                        c.socket.kick()
                        container.scope.launch { container.activity.markSeen(c.session) }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.connection.value?.let { c -> container.scope.launch { container.activity.markSeen(c.session) } }
                }
            },
        )
    }
}
