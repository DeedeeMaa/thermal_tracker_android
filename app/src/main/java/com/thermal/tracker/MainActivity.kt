package com.thermal.tracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thermal.tracker.ui.ConnectScreen
import com.thermal.tracker.ui.LiveScreen
import com.thermal.tracker.ui.theme.ThermalTheme
import com.thermal.tracker.viewmodel.LiveViewModel
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV 初始化（Maven 官方包用 initLocal）
        if (!OpenCVLoader.initLocal()) {
            Log.e("ThermalTracker", "OpenCV 初始化失败")
        }

        setContent {
            ThermalApp()
        }
    }
}

@Composable
private fun ThermalApp() {
    val vm: LiveViewModel = viewModel()
    val connected by vm.connected.collectAsState()
    val error by vm.error.collectAsState()
    val pingResult by vm.pingResult.collectAsState()
    val status by vm.connectionStatus.collectAsState()
    var screen by rememberSaveable { mutableStateOf("connect") }

    // 断开连接后自动回到连接页
    LaunchedEffect(connected) {
        if (!connected && screen == "live") screen = "connect"
    }

    ThermalTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                screen == "connect" || !connected -> ConnectScreen(
                    error = error,
                    pingResult = pingResult,
                    connectionStatus = status,
                    onPing = { vm.ping(it) },
                    onConnect = {
                        vm.connect(it)
                        screen = "live"
                    },
                )
                else -> LiveScreen(vm)
            }
        }
    }
}
