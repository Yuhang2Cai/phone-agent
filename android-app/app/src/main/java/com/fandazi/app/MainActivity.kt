package com.fandazi.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.fandazi.app.feature.auth.SessionState

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null && applicationSession().state.value is SessionState.Authenticated) {
            val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
            navController.navigate(R.id.homeFragment, null, navOptions {
                popUpTo(R.id.authFragment) { inclusive = true }
            })
        }
    }

    fun applicationSession() = (application as FandaziApplication).sessionRepository
}
