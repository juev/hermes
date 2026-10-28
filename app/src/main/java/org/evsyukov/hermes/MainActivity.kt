package org.evsyukov.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.evsyukov.hermes.data.server.SCHEME
import org.evsyukov.hermes.ui.HermesApp
import org.evsyukov.hermes.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel((application as HermesApplication).container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        askForNotifications()
        setContent { HermesApp(vm) }
    }

    /** Answers that finish in the background arrive as notifications; Android 13 asks the user once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification opens its chat; the return from the sign-in page needs nothing, the loopback got the code. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CHAT)?.let { vm.openChat(it) }
        intent?.data?.takeIf { it.scheme == SCHEME && it.host == "add-server" }?.let { vm.openServerLink(it.toString()) }
    }

    companion object {
        const val EXTRA_CHAT = "chat"
    }
}
