package cn.bincker.stream.sound

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.bincker.stream.sound.ui.theme.StreamSoundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamSoundTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column {
                            Button({
                                startForegroundService(
                                    Intent(this@MainActivity, AudioService::class.java).apply {
                                        putExtra("cmd", 0)
                                    }
                                )
                            }){
                                Text("Start")
                            }
                            Button({
                                startForegroundService(Intent(this@MainActivity, AudioService::class.java).apply {
                                    putExtra("cmd", 1)
                                })
                            }, modifier = Modifier.padding(top = 10.dp)){
                                Text("Stop")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if(checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK), 0)
        }
    }
}