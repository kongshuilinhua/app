package com.example.citruscollect

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.example.citruscollect.ui.AppNav
import com.example.citruscollect.ui.LocalAppContainer
import com.example.citruscollect.ui.theme.CitrusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        val container = (application as CitrusApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                CitrusTheme {
                    AppNav()
                }
            }
        }
    }
}
