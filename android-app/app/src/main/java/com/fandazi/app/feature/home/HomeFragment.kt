package com.fandazi.app.feature.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentHomeBinding
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.SessionError
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.agent.openAgentAssistant
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var binding: FragmentHomeBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentHomeBinding.bind(view)
        binding = viewBinding
        val sessions = (requireActivity() as MainActivity).applicationSession()
        viewBinding.logoutButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { sessions.logout() }
        }
        viewBinding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.HOME) }
        viewBinding.searchPageButton.setOnClickListener { view.findNavController().navigate(R.id.searchFragment) }
        viewBinding.merchantPageButton.setOnClickListener { view.findNavController().navigate(R.id.merchantFragment) }
        viewBinding.ordersPageButton.setOnClickListener { view.findNavController().navigate(R.id.ordersFragment) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessions.state.collect { state ->
                    when (state) {
                        is SessionState.Authenticated -> viewBinding.welcomeText.text = getString(R.string.welcome_user, state.user.username)
                        SessionState.Guest -> viewBinding.welcomeText.setText(R.string.welcome_guest)
                        SessionState.Unauthenticated -> viewBinding.welcomeText.setText(R.string.welcome_signed_out)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessions.error.collect { error ->
                    val message = when (error) {
                        SessionError.StorageReadFailed -> getString(R.string.session_read_failed)
                        SessionError.StorageClearFailed -> getString(R.string.logout_failed_retry)
                        SessionError.StorageUnavailable,
                        SessionError.StorageWriteFailed -> getString(R.string.session_storage_unavailable)
                        null -> null
                    }
                    message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
