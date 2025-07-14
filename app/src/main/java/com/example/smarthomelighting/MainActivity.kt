package com.example.smarthomelighting

import android.os.Bundle
import android.view.WindowManager
import android.os.Build
import android.view.View
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.example.smarthomelighting.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // 隐藏标题栏
            supportActionBar?.hide()
            
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            // 设置沉浸式状态栏
            setupImmersiveMode()

            // 安全获取BottomNavigationView
            val navView: BottomNavigationView = binding.navView ?: run {
                Log.e(TAG, "导航视图为空")
                return
            }

            try {
                val navController = findNavController(R.id.nav_host_fragment_activity_main)
                // Passing each menu ID as a set of Ids because each
                // menu should be considered as top level destinations.
                val appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
                    )
                )
                navView.setupWithNavController(navController)
            } catch (e: Exception) {
                Log.e(TAG, "导航控制器设置失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity初始化失败: ${e.message}")
        }
    }
    
    private fun setupImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 更安全稳定的方式设置透明状态栏
                window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 6.0以上可以设置状态栏文字颜色
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or 
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置沉浸式状态栏失败: ${e.message}")
        }
    }
}