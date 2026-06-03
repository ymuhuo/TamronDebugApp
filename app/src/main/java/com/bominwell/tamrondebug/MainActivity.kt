package com.bominwell.tamrondebug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bominwell.tamrondebug.ui.TamronDebugScreen
import com.bominwell.tamrondebug.viewmodel.TamronDebugViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val vm: TamronDebugViewModel = viewModel()
                    TamronDebugScreen(vm)
                }
            }
        }
    }
}
