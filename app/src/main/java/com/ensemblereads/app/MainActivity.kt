package com.ensemblereads.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(applicationContext)
        setContent { AppRoot(container) }
    }
}
