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
                    // The app is in front: a permanent connection gets its service back and a dead
                    // socket comes back at once; a working one stays, with its
                    // calls in flight.
                    container.turns.appInFront()
                    container.connection.value?.let { c ->
                        c.socket.checkAlive()
                        container.scope.launch { container.activity.initialize(c.session) }
                    }
                }
            },
        )
    }
}
