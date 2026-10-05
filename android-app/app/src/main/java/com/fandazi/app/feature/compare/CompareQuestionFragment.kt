// UI16I可编辑比较输入：复用唯一Activity聊天模型和草稿；引用经Spring核对，不自动发送。
package com.fandazi.app.feature.compare
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.fandazi.app.*
import com.fandazi.app.feature.agent.*
import kotlinx.coroutines.launch
/** 进入只加载公开名字；发送必须由用户点击，不使用设计示例作为默认问题。 */
class CompareQuestionFragment:Fragment(R.layout.fragment_compare_question) {
    private val app get()=requireActivity().application as FandaziApplication
    private val selection:CompareSelectionViewModel by activityViewModels()
    private val facts:DealCompareViewModel by viewModels{DealCompareViewModel.Factory(HttpDealCompareRepository(app.groupBuyingRepository,app.catalogRepository))}
    private lateinit var agent:AgentViewModel
    /** 用户发送成功交给聊天模型后，不再用离页前EditText复活已经清除的草稿。 */
    private var sent=false
    /** 仅在内存比较创建时认证版本，账号/令牌改变后的离页回调不得保存旧私人输入。 */
    private var inputCredential:String?=null
    /** 绑定同账号现有草稿，发送前再次检查选择/读取状态/用户输入。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        view.compareInsets();inputCredential=(app.sessionRepository.state.value as? com.fandazi.app.feature.auth.SessionState.Authenticated)?.token;agent=ViewModelProvider(requireActivity(),AgentViewModelFactory(app.agentRepository))[AgentViewModel::class.java]
        view.findViewById<View>(R.id.compare_back).setOnClickListener{saveDraft();findNavController().navigateUp()}
        view.findViewById<View>(R.id.compare_question_return).setOnClickListener{saveDraft();findNavController().navigateUp()}
        view.findViewById<EditText>(R.id.compare_question_input).setText(agent.uiState.value.composerText)
        view.findViewById<View>(R.id.compare_question_send).setOnClickListener{
            val input=view.findViewById<EditText>(R.id.compare_question_input).text.toString()
            val state=facts.state.value
            if(!sameCredential() || input.isBlank() || !selection.selection.value.canCompare || state.columns.any{it.loading || it.error!=null} || agent.uiState.value.isSending)return@setOnClickListener
            // 仅用户点击才建立比较上下文并发送；不改写问题，不把Android事实作为可信模型输入。
            agent.setComparison(selection.selection.value.ids);agent.updateComposer(input);agent.sendText(input);sent=true
            findNavController().navigate(R.id.agentChatFragment)
        }
        viewLifecycleOwner.lifecycleScope.launch{viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED){facts.state.collect{state->
            view.findViewById<TextView>(R.id.compare_question_status).text=if(state.columns.any{it.error!=null})"套餐读取失败，请返回对比重试。"else "已带入用户选择的${state.ids.size}个套餐；不会自动发送。"
            view.findViewById<TextView>(R.id.compare_question_names).text=state.columns.joinToString("\n"){it.facts?.deal?.title ?: if(it.loading)"正在读取套餐"else "读取失败"}
            view.findViewById<TextView>(R.id.compare_question_send).compareEnabled(state.ids.size in 2..3 && state.columns.size==state.ids.size && state.columns.all{!it.loading && it.error==null})
        }}}
        facts.initialize(selection.selection.value.ids)
    }
    /** 系统返回/旋转也保存编辑正文至同账号薄草稿，不覆盖为预设文字。 */
    private fun saveDraft(){if(sent || !sameCredential())return;view?.findViewById<EditText>(R.id.compare_question_input)?.let{agent.updateComposer(it.text.toString())}}
    /** 返回聊天已发送后重新读取空草稿，不能显示或再次保存上一轮已发送正文。 */
    override fun onResume(){super.onResume();if(sent){view?.findViewById<EditText>(R.id.compare_question_input)?.setText(agent.uiState.value.composerText);sent=false}}
    /** 只接受同一已认证令牌，游客或新身份绝不接收旧EditText正文。 */
    private fun sameCredential():Boolean=inputCredential!=null && (app.sessionRepository.state.value as? com.fandazi.app.feature.auth.SessionState.Authenticated)?.token==inputCredential
    override fun onPause(){saveDraft();super.onPause()}
    /** 页面离开取消名字读取，聊天业务请求由同一Activity模型管理。 */
    override fun onDestroyView(){facts.cancelReads();super.onDestroyView()}
}
