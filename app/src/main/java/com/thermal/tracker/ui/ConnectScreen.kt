package com.thermal.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thermal.tracker.model.CameraConfig

/** 连接设置页：IP / RTSP端口 / 账号 / 密码 / 通道号。 */
@Composable
fun ConnectScreen(
    error: String?,
    pingResult: String?,
    connectionStatus: String,
    onPing: (String) -> Unit,
    onConnect: (CameraConfig) -> Unit,
) {
    var ip by remember { mutableStateOf("192.168.2.104") }
    var port by remember { mutableStateOf("554") }
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("asd37210") }
    var channel by remember { mutableStateOf("101") }
    var transport by remember { mutableStateOf("tcp") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("迷糊热成像 V1.0", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = ip, onValueChange = { ip = it },
            label = { Text("相机 IP") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it },
            label = { Text("RTSP 端口") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("用户名") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = channel, onValueChange = { channel = it },
            label = { Text("通道号（热成像主码流，如 101）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("传输协议: ", style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = transport == "tcp", onClick = { transport = "tcp" })
            Text("TCP", modifier = Modifier.clickable { transport = "tcp" })
            Spacer(Modifier.width(16.dp))
            RadioButton(selected = transport == "udp", onClick = { transport = "udp" })
            Text("UDP", modifier = Modifier.clickable { transport = "udp" })
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (pingResult != null) {
            Spacer(Modifier.height(8.dp))
            Text(pingResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        
        Text("状态: $connectionStatus", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onPing(ip.trim()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ping 测试")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onConnect(
                    CameraConfig(
                        ip = ip.trim(),
                        rtspPort = port.toIntOrNull() ?: 554,
                        username = user.trim(),
                        password = pass,
                        channel = channel.toIntOrNull() ?: 101,
                        rtspTransport = transport
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("连接")
        }
    }
}
