package com.finetune.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.finetune.desktop.ui.FineTuneDesktopApp
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FineTune Desktop Client",
    ) {
        window.minimumSize = Dimension(1080, 760)
        window.setSize(1280, 840)
        FineTuneDesktopApp()
    }
}
