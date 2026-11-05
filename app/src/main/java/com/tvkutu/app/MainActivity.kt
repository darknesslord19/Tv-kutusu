package com.tvkutu.app

import android.os.Bundle
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import com.tvkutu.app.data.Repository
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
import com.tvkutu.app.ui.QrSetupScreen
import com.tvkutu.app.ui.SetupScreen
import com.tvkutu.app.ui.TvKutuTheme

class MainActivity : ComponentActivity() {

    /**
     * Logo yükleyici: tarayıcı kimliğiyle istek atar (bazı logo sunucuları varsayılan
     * okhttp kimliğini reddeder), SVG logoları da çizer, logoları diskte önbelleğe alır.
     */
    private fun setupImageLoader() {
        val client = vm.repo.http.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", Repository.IMAGE_USER_AGENT)
                        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .build()
                )
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(client)
                .components { add(SvgDecoder.Factory()) }
                .respectCacheHeaders(false)
                .crossfade(true)
                .build()
        )
    }

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupImageLoader()
        setContent {
            TvKutuTheme {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                    when (vm.screen) {
                        Screen.Setup -> SetupScreen(vm)
                        Screen.QrSetup -> QrSetupScreen(vm)
                        Screen.Home -> HomeScreen(vm)
                        Screen.Player -> PlayerScreen(vm)
                    }
                }
            }
        }
    }
}
