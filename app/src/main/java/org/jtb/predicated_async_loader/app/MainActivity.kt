package org.jtb.predicated_async_loader.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jtb.predicated_async_loader.PredicatedAsyncLoader
import org.jtb.predicated_async_loader.app.ui.theme.PredicatedAsyncLoaderTheme
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
  private lateinit var asyncInflater: PredicatedAsyncLoader<View>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    asyncInflater = PredicatedAsyncLoader()

    setContent {
      PredicatedAsyncLoaderTheme {
        MainScreen(asyncInflater)
      }
    }
  }

  override fun onDestroy() {
    asyncInflater.shutdown()
    super.onDestroy()
  }
}

@Composable
fun MainScreen(asyncInflater: PredicatedAsyncLoader<View>) {
  val context = LocalContext.current
  val textItems = remember { mutableStateListOf<TextItem>() }
  val count = remember { AtomicInteger(1) }
  var helloView by remember { mutableStateOf<View?>(null) }

  LaunchedEffect(Unit) {
    for (i in 0 until 20) {
      // Capture the invocation order
      val c = count.getAndIncrement()

      asyncInflater.load(
          { LayoutInflater.from(context).inflate(R.layout.hello, null, false) },
          { helloView },
          object : PredicatedAsyncLoader.OnLoadCompleteListener<View> {
            override fun onLoadSuccess(loaded: View?) {

              if (helloView == null) {
                // First callback
                helloView = loaded
                textItems.add(
                    TextItem(
                        text = "#$c: Hello, World! ($helloView)",
                        type = TextType.FIRST,
                    )
                )
              } else if (helloView == loaded) {
                // Subsequent callbacks
                textItems.add(
                    TextItem(
                        text = "#$c: Hello, World! ($helloView)",
                        type = TextType.SUBSEQUENT,
                    )
                )
              } else if (loaded != helloView) {
                // Called back with different view
                textItems.add(
                    TextItem(
                        text = "#$c: Hello, World! ($helloView != $loaded)",
                        type = TextType.ERROR,
                    )
                )
              }
            }

            override fun onLoadFailed(ex: Exception) {
              textItems.add(
                  TextItem(
                      text = "#$c: Hello, World! ($ex)",
                      type = TextType.ERROR,
                  )
              )
            }
          }
      )
    }
  }

  Scaffold(
      modifier = Modifier.fillMaxSize()
  ) { innerPadding ->
    LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(16.dp)  // Add padding around the edges of the LazyColumn
    ) {
      items(textItems) { item ->
        Text(
            text = item.text,
            color = item.color,
        )
      }
    }
  }
}

enum class TextType {
  FIRST,
  SUBSEQUENT,
  ERROR,
}
data class TextItem(
  val text: String,
  val type: TextType,
) {
  val color: Color
    get() = when (type) {
      TextType.FIRST -> Color.Green
      TextType.SUBSEQUENT -> Color.Blue
      TextType.ERROR -> Color.Red
    }
}