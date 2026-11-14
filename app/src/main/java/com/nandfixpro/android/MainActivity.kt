package com.nandfixpro.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nandfixpro.android.ui.NandApp
import com.nandfixpro.android.ui.theme.NandFixProTheme

class MainActivity : ComponentActivity() {

    private var usbAttachSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbAttachSignal = 1
        }
        setContent {
            NandFixProTheme {
                val appViewModel: AppViewModel = viewModel()
                val settings by appViewModel.settings.collectAsStateWithLifecycle()
                val view = LocalView.current

                LaunchedEffect(settings.keepScreenOn) {
                    view.keepScreenOn = settings.keepScreenOn
                }
                LaunchedEffect(usbAttachSignal) {
                    if (usbAttachSignal > 0) {
                        appViewModel.detectUsb(autoProbe = false)
                        appViewModel.message("Périphérique USB détecté — lancez la détection ou touchez Connecter.")
                    }
                }

                RequestNotificationPermission()
                NandApp(appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbAttachSignal += 1
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalView.current.context
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
