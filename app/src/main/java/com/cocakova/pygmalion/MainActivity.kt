package com.cocakova.pygmalion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cocakova.pygmalion.ui.PygmalionRoot
import com.cocakova.pygmalion.ui.theme.PygmalionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PygmalionTheme { PygmalionRoot() } }
    }
}
