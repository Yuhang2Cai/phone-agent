// 活动原生容器：嵌入 RN Fragment，保留原生五 Tab，并提供不依赖 JS 的初始化失败重试。
package com.fandazi.app.feature.activity

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facebook.react.ReactFragment
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import kotlinx.coroutines.launch

/** 生命周期由正式 NavController 驱动；业务状态与滚动薄状态由 RN 按身份代次保存。 */
class ActivityHostFragment : Fragment(R.layout.fragment_activity_host) {
    private val runtime get() = (requireActivity().application as FandaziApplication).activityRuntime

    /** 只在本根页面实际建立视图时挂载 RN，不在其他四个 Tab 预加载。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 现有宿主采用边到边窗口；活动独立处理顶部系统栏，不把状态文字与页面标题叠在一起。
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            root.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        mount()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.failure.collect { failed -> if (failed) showFailure() }
            }
        }
    }

    /** 同步加载失败与异步 JS 失败均落到原生重试，不展示虚构任务或金币。 */
    private fun mount() {
        try {
            runtime.host
            if (childFragmentManager.findFragmentByTag("activity-react") == null) {
                childFragmentManager.beginTransaction().replace(R.id.activity_react_container,
                    ReactFragment.Builder().setComponentName("FandaziActivity").setFabricEnabled(true).build(), "activity-react").commitNow()
            }
        } catch (_: Exception) { showFailure() }
    }

    /** 原生错误覆盖层可以在 JS 完全不可用时操作；重复错误不叠加按钮或视图。 */
    private fun showFailure() {
        val overlay = view?.findViewById<FrameLayout>(R.id.activity_native_error) ?: return
        overlay.removeAllViews()
        overlay.visibility = View.VISIBLE
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32); setBackgroundColor(android.graphics.Color.rgb(255, 249, 236))
            addView(TextView(context).apply { text = "活动暂时无法加载"; textSize = 20f })
            addView(Button(context).apply {
                text = "重新加载"
                setOnClickListener {
                    overlay.visibility = View.GONE
                    try { runtime.retry(); mount() } catch (_: Exception) { showFailure() }
                }
            })
        }
        overlay.addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    /** 可见时允许 JS 刷新；宿主初始握手也会读取同一焦点。 */
    override fun onResume() { super.onResume(); runtime.setFocused(true) }
    /** 离开 Tab 或后台立即停止 JS 定时刷新，未完成写入仍按原键核查。 */
    override fun onPause() { runtime.setFocused(false); super.onPause() }
    /** RN 先处理内页返回；未消费的系统返回交由 MainActivity 恢复首页。 */
    fun handleBack(): Boolean = (childFragmentManager.findFragmentByTag("activity-react") as? ReactFragment)?.onBackPressed() ?: false
}
