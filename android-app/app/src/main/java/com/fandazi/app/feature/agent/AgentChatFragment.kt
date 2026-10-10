// 正式聊天页：固定头/输入/导航，消息区四类RecyclerView；展示按帧合并，业务事件仍逐条处理。
package com.fandazi.app.feature.agent
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.PopupMenu
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.navigation.TopLevelNavigator
import com.fandazi.app.feature.auth.SessionState
import kotlinx.coroutines.launch

/** 唯一聊天页；卡片导航和确认始终来自显式点击，投影及流式更新不触发业务写入。 */
class AgentChatFragment:Fragment(R.layout.fragment_agent_chat) {
    private lateinit var viewModel:AgentViewModel
    private var list:RecyclerView?=null
    private var adapter:AgentMessageAdapter?=null
    private var manager:LinearLayoutManager?=null
    private var voiceInput:AgentVoiceInputController?=null
    private val voicePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){voiceInput?.onPermissionResult(it)}
    private var pending:AgentUiState?=null
    private var rendered:AgentUiState?=null
    private var frameQueued=false
    private var owner:String?=null
    private var ownerInitialized=false
    private var viewEpoch=0L
    private var submitEpoch=0L
    private var dragEpoch=0L
    private var following=true
    private val sendFocus=AgentSendFocus()
    private var restoreKey:String?=null
    private var restoreOffset=0
    /** 普通文本与业务薄SavedState桥接，页面离开不依赖Fragment级语音模型继续存在。 */
    private var draftWatcher:TextWatcher?=null
    private var draftInput:EditText?=null
    private val authenticated get()=(requireActivity() as MainActivity).applicationSession().state.value is SessionState.Authenticated
    /** 一帧仅投影最新普通状态；终态/错误/资格变化走同步renderNow，业务事件没有被此合并丢弃。 */
    private val frame=Choreographer.FrameCallback {
        frameQueued=false;val state=pending;pending=null
        if(state!=null && list!=null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))renderNow(state)
    }
    /** 控件创建一次；状态和认证订阅仅STARTED阶段有效，离开后取消待渲染帧。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        viewEpoch++;ownerInitialized=false;rendered=null;following=true;sendFocus.cancel()
        viewModel=ViewModelProvider(requireActivity(),AgentViewModelFactory((requireActivity().application as FandaziApplication).agentRepository, requireActivity().application))[AgentViewModel::class.java]
        // 只有用户比较发送后带入上下文，明确展示数量及清除入口；不会自动再次发送。
        fun renderComparison(){val ids=viewModel.comparisonSelection();view.findViewById<View>(R.id.agent_compare_context).visibility=if(ids.isEmpty())View.GONE else View.VISIBLE;view.findViewById<android.widget.TextView>(R.id.agent_compare_context_text).text="正在比较${ids.size}个已选套餐；清除后恢复普通聊天。"}
        renderComparison();view.findViewById<View>(R.id.agent_compare_clear).setOnClickListener{viewModel.clearComparison();renderComparison()}
        restoreKey=viewModel.uiState.value.scrollAnchorKey;restoreOffset=viewModel.uiState.value.scrollAnchorOffset
        list=view.findViewById(R.id.agent_chat_scroll);manager=LinearLayoutManager(requireContext());list!!.layoutManager=manager
        // 关闭变化动画避免token交叉闪烁，保留回收和差量比较。
        list!!.itemAnimator=null;list!!.setItemViewCacheSize(4);resetAdapter()
        list!!.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrollStateChanged(recyclerView:RecyclerView,newState:Int) {
                if(newState==RecyclerView.SCROLL_STATE_DRAGGING){dragEpoch++;restoreKey=null;sendFocus.cancel()}
                following=!recyclerView.canScrollVertically(1)
                if(newState==RecyclerView.SCROLL_STATE_IDLE)saveAnchor()
            }
            override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int){
                following=!recyclerView.canScrollVertically(1)
                // scrollToPositionWithOffset不会经历DRAGGING→IDLE，实际布局后立即保存，避免Activity保存树仍带发送前旧锚点。
                if(recyclerView.scrollState==RecyclerView.SCROLL_STATE_IDLE)saveAnchor()
            }
        })
        // 键盘缩放只让原底部用户跟随，不能强迫上翻用户返回底部。
        list!!.addOnLayoutChangeListener{_,_,top,_,bottom,_,oldTop,_,oldBottom->if(bottom-top!=oldBottom-oldTop && following && restoreKey==null && list?.scrollState!=RecyclerView.SCROLL_STATE_DRAGGING)scrollToBottom()}
        view.findViewById<View>(R.id.agent_chat_close).setOnClickListener{anchor->PopupMenu(requireContext(),anchor).apply{
            // 明确返回首页也复用保存栈，避免菜单/引导回调绕过Tab导航另建首页。
            menu.add("返回首页");setOnMenuItemClickListener{saveAnchor();TopLevelNavigator.select(findNavController(),R.id.homeFragment);true};show()
        }}
        // 保留Task14独立草稿绑定；本任务不触发录音或ASR测试，发送仍委托原业务入口。
        val app=requireActivity().application as FandaziApplication
        val voiceModel=ViewModelProvider(this,AgentVoiceInputViewModelFactory(app))[AgentVoiceInputViewModel::class.java]
        val savedDraft=viewModel.uiState.value.composerText
        voiceInput=AgentVoiceInputController(this,view,voiceModel,app.sessionRepository,requestPermission={voicePermission.launch(it)},
            isSending={viewModel.uiState.value.isSending || viewModel.uiState.value.isRecoveringHistory},sendText={sendFocus.request();viewModel.sendText(it)})
        // 账号绑定完成后回填普通文字草稿，控制器仍管理录音/识别/合并；这里只保存当前输入正文。
        if(authenticated)voiceModel.updateDraft(savedDraft)
        draftInput=view.findViewById(R.id.agent_chat_input)
        draftWatcher=object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(authenticated)viewModel.updateComposer(s?.toString().orEmpty())}
        }
        draftInput?.addTextChangedListener(draftWatcher)
        if(authenticated)viewModel.openAssistant()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    launch{viewModel.uiState.collect{queue(it)}}
                    launch{(requireActivity() as MainActivity).applicationSession().state.collect{queue(viewModel.uiState.value,force=true)}}
                } finally{cancelFrame();saveAnchor()}
            }
        }
    }
    /** 账号变化先换Adapter释放旧私有行及回调；普通token排一帧，关键状态立即刷新。 */
    private fun queue(state:AgentUiState,force:Boolean=false) {
        val session=(requireActivity() as MainActivity).applicationSession().state.value
        val nextOwner=(session as? SessionState.Authenticated)?.user?.username
        val changed=ownerInitialized && owner!=nextOwner
        if(!ownerInitialized || changed) {
            ownerInitialized=true;owner=nextOwner
            if(changed){cancelFrame();viewEpoch++;submitEpoch++;dragEpoch++;restoreKey=null;sendFocus.cancel();rendered=null;following=true;resetAdapter()
                // 认证通知和业务状态通知可能先后到达；同步清原owner，禁止用新账号资格显示旧账号行。
                viewModel.bindAuthenticatedOwner(nextOwner, com.fandazi.app.feature.auth.authenticationIdentity(session))
            }
        }
        val current=if(changed)viewModel.uiState.value else state
        voiceInput?.refreshComposer();val before=rendered
        val immediate=force || changed || !authenticated || before==null || current.error!=before.error || current.isSending!=before.isSending ||
            current.isRecoveringHistory!=before.isRecoveringHistory || current.pendingConfirmationCard!=before.pendingConfirmationCard || current.pendingAfterSaleCards!=before.pendingAfterSaleCards ||
            current.events.lastOrNull()?.type in setOf("final","error","completed","waiting_confirmation","waiting_input")
        if(immediate){cancelFrame();renderNow(current)}else{pending=current;if(!frameQueued){frameQueued=true;Choreographer.getInstance().postFrameCallback(frame)}}
    }
    /** 主线程冻结数据、捕获底部/首行锚点；异步Diff后仅同代且没有新拖动才定位。 */
    private fun renderNow(state:AgentUiState) {
        // 账号切换/显式清除后即时隐藏旧比较提示，不能只在第一次创建时决定可见性。
        val ids=viewModel.comparisonSelection()
        view?.findViewById<View>(R.id.agent_compare_context)?.visibility=if(ids.isEmpty())View.GONE else View.VISIBLE
        view?.findViewById<android.widget.TextView>(R.id.agent_compare_context_text)?.text="正在比较${ids.size}个已选套餐；清除后恢复普通聊天。"
        val recycler=list ?: return;val currentAdapter=adapter ?: return;val layout=manager ?: return
        // 首次恢复定位已经提交但尚未布局时，canScrollVertically仍是空列表值；沿用用户/恢复意图，防止下一StateFlow把上翻恢复抢到底部。
        val started=SystemClock.elapsedRealtimeNanos();val anchor=captureAnchor();val wasBottom=following
        val rows=AgentRenderRows.project(state,authenticated,viewModel::isCurrentReplyCard)
        val lastUser=state.messages.lastOrNull{it.fromUser}?.displayKey
        val userAdded=lastUser!=rendered?.messages?.lastOrNull{it.fromUser}?.displayKey
        // 发送定位持续到有效差量提交，下一帧或终态取代首提交时仍使用同一用户展示键。
        val focusTarget=sendFocus.targetFor(lastUser,userAdded)
        val restore=restoreKey;val offset=restoreOffset;val generation=++submitEpoch;val viewGeneration=viewEpoch;val dragGeneration=dragEpoch
        rendered=state
        currentAdapter.submitRows(rows){
            if(list!==recycler || adapter!==currentAdapter || generation!=submitEpoch || viewGeneration!=viewEpoch || dragGeneration!=dragEpoch)return@submitRows
            val restoredIndex=restore?.let{key->rows.indexOfFirst{it.key==key}} ?: -1
            // 加载提示/空引导阶段不消费恢复锚点，等待首批真实消息。
            when {
                focusTarget!=null && focusTarget==lastUser->{sendFocus.committed(focusTarget);restoreKey=null;following=true;scrollToBottom()}
                restoredIndex>=0->{restoreKey=null;following=false;layout.scrollToPositionWithOffset(restoredIndex,offset)}
                restore!=null && rows.none{it is AgentRenderRow.Message && it.key.startsWith("message:")}->Unit
                restore!=null->{restoreKey=null;following=wasBottom;if(wasBottom)scrollToBottom()}
                wasBottom->{following=true;scrollToBottom()}
                anchor!=null->rows.indexOfFirst{it.key==anchor.first}.takeIf{it>=0}?.let{layout.scrollToPositionWithOffset(it,anchor.second)}
            }
        }
        currentAdapter.metrics.frame(SystemClock.elapsedRealtimeNanos()-started)
    }
    /** 四类Adapter委托原导航/重试/快捷操作；半屏仍使用兼容Renderer，不被本任务改造。 */
    private fun resetAdapter() {
        val recycler=list ?: return
        for(i in 0 until recycler.childCount)adapter?.release(recycler.getChildViewHolder(recycler.getChildAt(i)))
        recycler.adapter=null;recycler.recycledViewPool.clear()
        adapter=AgentMessageAdapter(::navigate,{viewModel.retry()},
            {(requireActivity() as MainActivity).openProtectedDestination(R.id.agentChatFragment)},
            {TopLevelNavigator.select(findNavController(),R.id.homeFragment)},
            {if(voiceInput?.canSendQuickQuery()==true){sendFocus.request();viewModel.sendText(it)}})
        recycler.adapter=adapter
    }
    /** 显式点击使用原公开ID；确认/取消/选券实时再次验证原摘要版本。 */
    private fun navigate(request:AgentNavigationRequest,card:AgentCardPayload) {
        if(request !is AgentNavigationRequest.AgentReply)viewModel.rememberViewedCard(card)
        saveAnchor()
        when(request) {
            is AgentNavigationRequest.Store->findNavController().navigate(R.id.storeFragment,Bundle().apply{putString("storePublicId",request.storePublicId)})
            is AgentNavigationRequest.Deal->findNavController().navigate(R.id.dealDetailFragment,Bundle().apply{putString("storePublicId",request.storePublicId);putString("dealPublicId",request.dealPublicId)})
            is AgentNavigationRequest.Order->findNavController().navigate(R.id.groupOrderDetailFragment,Bundle().apply{putString("orderId",request.orderPublicId)})
            is AgentNavigationRequest.Voucher->findNavController().navigate(R.id.voucherDetailFragment,Bundle().apply{putString("voucherId",request.voucherPublicId)})
            is AgentNavigationRequest.AgentReply->{if(viewModel.replyToCard(request))sendFocus.request()}
        }
    }
    /** 捕获稳定行身份及相对内容起点的像素偏移，不保存列表绝对位置。 */
    private fun captureAnchor():Pair<String,Int>? {
        val recycler=list ?: return null;val layout=manager ?: return null;val data=adapter ?: return null
        val position=layout.findFirstVisibleItemPosition();val row=data.currentList.getOrNull(position) ?: return null
        val child=layout.findViewByPosition(position) ?: return null
        return row.key to (layout.getDecoratedTop(child)-recycler.paddingTop)
    }
    /** 真实消息行出现且恢复完成后才回写薄锚点；退出或游客不保存私人行。 */
    private fun saveAnchor(){
        if(!authenticated || restoreKey!=null)return
        // null锚点表示继续跟随底部；离开时在底部却保存旧首行，会把之后新增的历史留在屏幕下方。
        if(following){viewModel.updateScrollAnchor(null,0);return}
        captureAnchor()?.takeIf{it.first.startsWith("message:")}?.let{viewModel.updateScrollAnchor(it.first,it.second)}
    }
    /** 不排队无代际保护的旧滚动任务，当前布局只定位当前Adapter底部。 */
    private fun scrollToBottom(){val count=adapter?.itemCount ?: 0;if(count>0)manager?.scrollToPosition(count-1)}
    /** 取消展示帧，不干预业务事件或SSE游标。 */
    private fun cancelFrame(){if(frameQueued)Choreographer.getInstance().removeFrameCallback(frame);frameQueued=false;pending=null}
    /** 销毁清理附着Holder、回收池、帧和语音View引用，沿用唯一聊天页原SSE关闭规则。 */
    override fun onDestroyView() {
        saveAnchor();cancelFrame();sendFocus.cancel();viewEpoch++;submitEpoch++
        list?.let{r->for(i in 0 until r.childCount)adapter?.release(r.getChildViewHolder(r.getChildAt(i)));r.clearOnScrollListeners();r.adapter=null;r.layoutManager=null;r.recycledViewPool.clear()}
        adapter=null;manager=null;list=null;rendered=null;ownerInitialized=false
        draftWatcher?.let{draftInput?.removeTextChangedListener(it)};draftWatcher=null;draftInput=null
        voiceInput?.close();voiceInput=null;viewModel.closeAssistant();super.onDestroyView()
    }
}
