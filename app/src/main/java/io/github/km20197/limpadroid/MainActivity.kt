package io.github.km20197.limpadroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.km20197.limpadroid.ui.navigation.LimpaDroidNavHost
import io.github.km20197.limpadroid.ui.theme.LimpaDroidTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val container = (application as LimpaDroidApp).container
    setContent {
      LimpaDroidTheme {
        LimpaDroidNavHost(container = container)
      }
    }
  }
}
