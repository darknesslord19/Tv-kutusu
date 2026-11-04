package com.tvkutu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.tvkutu.app.ui.HomeScreen
import com.tvkutu.app.ui.Palette
import com.tvkutu.app.ui.PlayerScreen
import com.tvkutu.app.ui.SetupScreen
import com.tvkutu.app.ui.TvKutuTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvKutuTheme {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                    when (vm.screen) {
                        Screen.Setup -> SetupScreen(vm)
                        Screen.Home -> HomeScreen(vm)
                        Screen.Player -> PlayerScreen(vm)
                    }
                }
            }
        }
    }
}
