/* 饭搭子 AI：纯本地、可编辑的原型状态 */
const dishes = [
  { id: 'beef-noodle', name: '招牌牛肉面', desc: '现熬牛骨汤 · 牛腱肉 · 可选辣度', price: 25, emoji: '🍜' },
  { id: 'fried-rice', name: '扬州炒饭', desc: '虾仁火腿 · 粒粒分明', price: 22, emoji: '🍛' },
  { id: 'tea', name: '桂花乌龙奶茶', desc: '低糖推荐 · 清香不腻', price: 14, emoji: '🧋' }
];
const statusSteps = ['订单已提交', '商家接单', '骑手取餐', '正在配送', '已送达'];
let currentPage = 'login';
let cart = [];
let agentMode = 'half';
let orderStatus = 0;
let agentEvents = [];
let automationEnabled = true;
let orderCreated = false;
let selectedAddress = '家 · 湖滨路 88 号 2 栋 1602';
let memberName = '张同学';
let addresses = [
  { id: 'home', label: '家', full: '家 · 湖滨路 88 号 2 栋 1602', detail: '杭州市西湖区 湖滨路 88 号 2 栋 1602', isDefault: true },
  { id: 'office', label: '公司', full: '公司 · 文三路 90 号 A 座', detail: '杭州市西湖区 文三路 90 号 A 座', isDefault: false }
];
let agentOrderConfirmed = false;
let agentOrderCancelled = false;
const app = document.querySelector('#app');
const modalRoot = document.querySelector('#modal-root');

function money(value) { return `¥${value.toFixed(2)}`; }
function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
function cartCount() { return cart.reduce((sum, item) => sum + item.quantity, 0); }
function cartTotal() { return cart.reduce((sum, item) => sum + item.price * item.quantity, 0); }
function nav(active) { return `<nav class="bottom-nav"><button class="nav-btn ${active === 'home' ? 'active' : ''}" onclick="navigate('home')"><span>⌂</span>首页</button><button class="nav-btn ${active === 'orders' ? 'active' : ''}" onclick="navigate('orders')"><span>▤</span>订单</button><button class="nav-btn ${active === 'profile' ? 'active' : ''}" onclick="navigate('profile')"><span>☺</span>我的</button></nav>`; }
function aiFab() { return currentPage !== 'login' ? '<button class="ai-fab" aria-label="打开 AI 助手" onclick="openAgent()">✦</button>' : ''; }
function top(title, back = 'home') { return `<div class="topline"><button class="back" aria-label="返回" onclick="navigate('${back}')">‹</button><h2>${title}</h2><span style="width:36px"></span></div>`; }
function cartBar() { return cartCount() ? `<div class="cart-bar"><span>🛍 ${cartCount()} 件 · ${money(cartTotal())}</span><button onclick="navigate('checkout')">去结算</button></div>` : ''; }

function renderLogin() {
  return `<section id="login" class="page login">
    <div class="brand-mark">🍚</div><h1>饭搭子 AI</h1>
    <p class="subtitle">懂你每一口的外卖搭子</p>
    <input class="field" placeholder="手机号" inputmode="tel" value="138 0000 0000">
    <input class="field" placeholder="验证码" value="123456">
    <button class="primary" onclick="navigate('home')">登录并开始点餐</button>
    <button class="link" onclick="navigate('register')">还没有账号？注册</button>
    <div class="card" style="margin-top:30px"><b>✦ AI 点餐更省心</b><p class="muted" style="margin-top:8px">说出“想吃不辣的热汤面”，搭子会帮你搭好订单。</p></div>
  </section>`;
}
function renderRegister() {
  return `<section id="register" class="page login">
    <button class="back" onclick="navigate('login')">‹</button>
    <div class="brand-mark" style="margin-top:20px">✦</div><h1>创建饭搭子账号</h1>
    <p class="subtitle">注册后保存你的口味和地址</p>
    <input id="register-name" class="field" placeholder="昵称" value="新搭子">
    <input class="field" placeholder="手机号" inputmode="tel" value="138 0000 0000">
    <input class="field" placeholder="验证码" value="123456">
    <button class="primary" onclick="completeRegistration()">注册并开始点餐</button>
    <button class="link" onclick="navigate('login')">已有账号？去登录</button>
  </section>`;
}
function renderHome() { return `<section id="home" class="page"><div class="topline"><div><span class="pill">📍 西湖区</span><h1 style="margin-top:8px">中午好，吃点好的</h1></div><button class="icon-btn" onclick="navigate('addresses')">⌖</button></div><button class="search" onclick="navigate('search')">⌕ 搜索商家、菜品或“想吃点热的”</button><div class="hero"><span class="pill">饭搭子推荐</span><h2 style="margin-top:12px">今天吃什么？<br>交给 AI 吧</h2><p>根据你的口味、预算和时间，给你一份刚刚好的午餐。</p></div><div class="chips"><button class="chip active">附近优选</button><button class="chip">一人食</button><button class="chip">20 分钟达</button><button class="chip">低脂轻食</button></div><h2 class="section-title">为你精选</h2>${merchantCard('阿牛牛肉面馆', '月售 2000+ · 22 分钟', '4.9', '🍜')}${merchantCard('小灶川味家常菜', '月售 1000+ · 30 分钟', '4.8', '🍲')}${aiFab()}${nav('home')}</section>`; }
function merchantCard(name, meta, rating, emoji) { return `<button class="card merchant-card" onclick="navigate('merchant')"><div class="cover">${emoji}</div><div><h3>${name}</h3><p class="muted">${meta}</p><div class="rating">★ ${rating} · 满 25 减 3</div></div></button>`; }
function renderSearch() { return `<section id="search" class="page">${top('搜索','home')}<input id="search-input" class="field" value="牛肉面" autofocus><div class="chips"><button class="chip active">综合</button><button class="chip">销量</button><button class="chip">距离</button></div><h2 class="section-title">搜索结果</h2>${merchantCard('阿牛牛肉面馆','距你 1.2km · 22 分钟','4.9','🍜')}<div class="card food-item"><div class="cover">🍜</div><div class="food-copy"><h3>招牌牛肉面</h3><p class="muted">来自阿牛牛肉面馆</p><p class="price">¥25</p></div><button class="add" onclick="openDish('beef-noodle')">+</button></div>${aiFab()}${nav('home')}</section>`; }
function renderMerchant() { return `<section id="merchant" class="page"><div class="menu-head"><button class="back" onclick="navigate('home')">‹</button><span class="pill">品牌优选</span><h2 style="margin-top:10px">阿牛牛肉面馆</h2><p style="font-size:13px;margin-top:7px">★ 4.9 · 月售 2000+ · 22 分钟送达</p></div><div class="chips"><button class="chip active">招牌</button><button class="chip">热销</button><button class="chip">小食饮品</button></div><h2 class="section-title">招牌推荐</h2>${dishes.map(d => `<div class="food-item"><div class="cover">${d.emoji}</div><div class="food-copy"><h3>${d.name}</h3><p class="muted">${d.desc}</p><p class="price">¥${d.price}</p></div><button class="add" aria-label="添加 ${d.name}" onclick="openDish('${d.id}')">+</button></div>`).join('')}${cartBar()}${aiFab()}${nav('home')}</section>`; }
function renderCheckout() { const items = cart.length ? cart.map(itemRow).join('') : '<div class="empty">购物车还是空的，去挑些好吃的吧。</div>'; return `<section id="checkout" class="page">${top('确认订单','merchant')}<button class="card address" style="width:100%;text-align:left" onclick="navigate('addresses')"><b>📍 ${escapeHtml(selectedAddress)}</b><p class="muted" style="margin-top:6px">张同学 138****0000 · 点击切换地址</p></button><div class="card" style="margin-top:12px"><h3>阿牛牛肉面馆</h3>${items}<div class="total"><span>合计</span><span>${money(cartTotal())}</span></div></div><div class="card" style="margin-top:12px"><div class="between"><span>餐具</span><span class="muted">按需提供 ›</span></div><div class="between" style="margin-top:16px"><span>配送方式</span><span class="muted">尽快送达</span></div></div><button class="primary" ${cart.length ? '' : 'disabled'} onclick="confirmOrder()">提交订单 · ${money(cartTotal())}</button>${aiFab()}${nav('home')}</section>`; }
function itemRow(item) { return `<div class="list-row"><div class="cover">${item.emoji}</div><div class="grow"><b>${item.name}</b><p class="muted">${item.options}</p><p class="price">¥${item.price}</p></div><div class="qty"><button onclick="changeQuantity('${item.id}',-1)">−</button><b>${item.quantity}</b><button onclick="changeQuantity('${item.id}',1)">+</button></div></div>`; }
function renderPayment() { return `<section id="payment" class="page">${top('模拟支付','checkout')}<div class="card confirm-card" style="margin-top:44px"><div style="font-size:48px">🧾</div><p class="muted" style="margin-top:12px">饭搭子 AI · 阿牛牛肉面馆</p><h1 style="margin:13px 0">${money(cartTotal())}</h1><p class="muted">本页仅模拟支付，不会扣款</p></div><div class="card" style="margin-top:16px"><div class="list-row"><span style="font-size:25px">💳</span><div class="grow"><b>饭搭子模拟支付</b><p class="muted">安全快捷的原型流程</p></div><span>✓</span></div></div><button class="primary" onclick="submitMockPayment()">支付 ${money(cartTotal())}</button></section>`; }
function renderOrders() { return `<section id="orders" class="page">${top('我的订单','home')}<div class="chips"><button class="chip active">全部</button><button class="chip">进行中</button><button class="chip">已完成</button></div>${orderCreated ? `<button class="card merchant-card" style="margin-top:18px" onclick="navigate('order-detail')"><div class="cover">🍜</div><div class="grow"><div class="between"><h3>阿牛牛肉面馆</h3><span style="color:var(--ai)">${statusSteps[orderStatus]}</span></div><p class="muted">${cartCount()} 件商品 · ${money(cartTotal())}</p><p class="muted" style="margin-top:9px">查看订单详情 ›</p></div></button>` : '<div class="empty">还没有订单，去首页逛逛吧。</div>'}${aiFab()}${nav('orders')}</section>`; }
function renderOrderDetail() { return `<section id="order-detail" class="page">${top('订单详情','orders')}<div class="card" style="background:linear-gradient(135deg,var(--ai),#8584ff);color:white"><span class="pill" style="color:var(--ink)">配送服务中</span><h2 style="margin-top:12px">${statusSteps[orderStatus]}</h2><p style="font-size:13px;margin-top:7px">订单号 FA${String(Date.now()).slice(-6)}</p></div><div class="card" style="margin-top:13px"><h3>订单状态</h3><div class="timeline">${statusSteps.map((step, index) => `<div class="event ${index > orderStatus ? 'pending' : ''}"><b>${step}</b><p class="muted">${index <= orderStatus ? '已更新 · 饭搭子为你持续关注' : '等待下一步'}</p></div>`).join('')}</div>${orderStatus < 4 ? '<button class="secondary" style="width:100%" onclick="advanceOrderStatus()">推进订单状态（演示）</button>' : ''}</div><div class="card" style="margin-top:13px"><h3>配送地址</h3><p class="muted" style="margin-top:8px">${escapeHtml(selectedAddress)}</p></div>${aiFab()}${nav('orders')}</section>`; }
function renderAddresses() {
  const addressCards = addresses.map(address => `<button class="card address" style="width:100%;text-align:left" onclick="selectAddressById('${address.id}')">
    <h3>${escapeHtml(address.label)} ${address.isDefault ? '<span class="pill">默认</span>' : ''}</h3>
    <p class="muted" style="margin-top:8px">${escapeHtml(memberName)} 138****0000<br>${escapeHtml(address.detail)}</p>
  </button>`).join('');
  return `<section id="addresses" class="page">${top('地址管理','profile')}${addressCards}
    <button class="primary" onclick="openAddressForm()">＋ 新增收货地址</button>${aiFab()}${nav('profile')}
  </section>`;
}
function renderProfile() { return `<section id="profile" class="page"><div class="topline"><h1>我的</h1><button class="icon-btn" onclick="toast('消息中心暂无新消息')">♧</button></div><div class="card" style="display:flex;gap:13px;align-items:center"><div class="cover" style="border-radius:50%">🧑🏻</div><div><h2>${escapeHtml(memberName)}</h2><p class="muted">饭搭子 AI 已陪伴 12 天</p></div></div><div class="card settings" style="margin-top:15px"><button class="list-row" style="width:100%;background:transparent;text-align:left" onclick="navigate('addresses')"><span>📍</span><b class="grow">地址管理</b><span>›</span></button><button class="list-row" style="width:100%;background:transparent;text-align:left" onclick="navigate('agent')"><span>✦</span><b class="grow">AI 搭子</b><span>›</span></button><button class="list-row" style="width:100%;background:transparent;text-align:left" onclick="navigate('automation')"><span>⚙</span><b class="grow">自动化设置</b><span>›</span></button></div>${aiFab()}${nav('profile')}</section>`; }
function renderAutomation() { return `<section id="automation" class="page">${top('自动化设置','profile')}<div class="hero" style="background:linear-gradient(135deg,var(--ai-soft),#f7f2ff)"><span class="pill">AI 搭子</span><h2 style="margin-top:12px">让点餐更省一步</h2><p>自动化只会在你的设置范围内运行，每次下单仍由你确认。</p></div><div class="card settings"><div class="list-row"><div class="grow"><b>自动确认偏好</b><p class="muted">符合预算和口味时先为你准备</p></div><button aria-label="切换自动确认" class="switch ${automationEnabled ? 'on' : ''}" onclick="toggleAutomation()"></button></div><div class="list-row"><div class="grow"><b>午餐提醒</b><p class="muted">工作日 11:35</p></div><span>›</span></div><div class="list-row"><div class="grow"><b>每餐预算</b><p class="muted">¥ 15 - 35</p></div><span>›</span></div></div>${aiFab()}${nav('profile')}</section>`; }
function render() {
  const pages = {
    login: renderLogin, register: renderRegister, home: renderHome, search: renderSearch,
    merchant: renderMerchant, checkout: renderCheckout, payment: renderPayment,
    orders: renderOrders, 'order-detail': renderOrderDetail, addresses: renderAddresses,
    profile: renderProfile, automation: renderAutomation, agent: renderProfile
  };
  app.innerHTML = pages[currentPage]();
}
function navigate(page) { currentPage = page === 'agent' ? 'profile' : page; render(); window.scrollTo(0,0); if (page === 'agent') openAgent(); }
function openDish(dishId) { const dish = dishes.find(d => d.id === dishId); modalRoot.innerHTML = `<div class="modal-backdrop"><section class="sheet"><div class="handle"></div><div class="food-item"><div class="cover">${dish.emoji}</div><div><h2>${dish.name}</h2><p class="price">¥${dish.price}</p></div></div><h3 style="margin-top:16px">选择规格</h3><button class="option selected" data-size="标准份" onclick="pickOption(this,'size')">标准份 <b>¥${dish.price}</b></button><button class="option" data-size="大份" onclick="pickOption(this,'size')">大份 <b>+ ¥4</b></button><h3 style="margin-top:16px">口味</h3><button class="option selected" data-spice="不辣" onclick="pickOption(this,'spice')">不辣</button><button class="option" data-spice="微辣" onclick="pickOption(this,'spice')">微辣</button><button class="option" data-extra="加卤蛋" onclick="this.classList.toggle('selected')">加卤蛋 <b>+ ¥2</b></button><button class="primary" onclick="addSelectedDish('${dish.id}')">加入购物车</button><button class="link" style="width:100%" onclick="closeModal()">取消</button></section></div>`; }
function pickOption(button, group) { modalRoot.querySelectorAll(`[data-${group}]`).forEach(el => el.classList.remove('selected')); button.classList.add('selected'); }
function addSelectedDish(dishId) { const size = modalRoot.querySelector('[data-size].selected')?.dataset.size || '标准份'; const spice = modalRoot.querySelector('[data-spice].selected')?.dataset.spice || '不辣'; const extra = modalRoot.querySelector('[data-extra].selected') ? '加卤蛋' : ''; addToCart(dishId, { size, spice, extra }); closeModal(); }
function addToCart(dishId, options = {}) { const dish = dishes.find(d => d.id === dishId); const optionsText = [options.size || '标准份', options.spice || '不辣', options.extra].filter(Boolean).join(' · '); const price = dish.price + (options.size === '大份' ? 4 : 0) + (options.extra ? 2 : 0); const itemId = `${dishId}-${optionsText}`; const existing = cart.find(item => item.id === itemId); if (existing) existing.quantity += 1; else cart.push({ id:itemId, name:dish.name, emoji:dish.emoji, price, quantity:1, options:optionsText }); render(); toast(`${dish.name} 已加入购物车`); }
function changeQuantity(itemId, delta) { const item = cart.find(entry => entry.id === itemId); if (!item) return; item.quantity += delta; if (item.quantity <= 0) cart = cart.filter(entry => entry.id !== itemId); render(); }
function confirmOrder() { if (!cart.length) return toast('请先添加菜品'); modalRoot.innerHTML = `<div class="modal-backdrop"><section class="sheet confirm-card"><div class="handle"></div><div style="font-size:44px">🤖</div><h2 style="margin:10px 0">确认这份订单吗？</h2><p class="muted">饭搭子已核对口味、地址和预算，共 ${money(cartTotal())}。</p><div class="confirm-actions"><button class="secondary" onclick="closeModal()">取消</button><button class="primary" style="margin:0" onclick="closeModal();navigate('payment')">确认支付</button></div></section></div>`; }
function submitMockPayment() { orderCreated = true; orderStatus = 0; navigate('order-detail'); toast('支付成功，商家正在处理订单'); }
function advanceOrderStatus() { orderStatus = Math.min(orderStatus + 1, statusSteps.length - 1); render(); toast(`订单状态：${statusSteps[orderStatus]}`); }
function toggleAutomation() { automationEnabled = !automationEnabled; render(); toast(automationEnabled ? '已开启自动确认偏好' : '已关闭自动确认偏好'); }
function selectAddress(address) { selectedAddress = address; toast('已切换收货地址'); setTimeout(() => navigate('checkout'), 300); }
function selectAddressById(addressId) {
  const address = addresses.find(item => item.id === addressId);
  if (address) selectAddress(address.full);
}
function completeRegistration() {
  memberName = document.querySelector('#register-name').value.trim() || '新搭子';
  navigate('home');
  toast(`欢迎你，${memberName}`);
}
function openAddressForm() {
  modalRoot.innerHTML = `<div class="modal-backdrop"><section class="sheet">
    <div class="handle"></div><h2>新增收货地址</h2>
    <input id="address-label" class="field" value="新地址" placeholder="地址标签，如：学校">
    <input id="address-detail" class="field" value="杭州市西湖区 灵隐路 1 号" placeholder="详细地址">
    <button class="primary" onclick="saveAddress()">保存并设为当前地址</button>
    <button class="link" style="width:100%" onclick="closeModal()">取消</button>
  </section></div>`;
}
function addAddress(label, detail) {
  const safeLabel = label.trim() || '新地址';
  const safeDetail = detail.trim() || '杭州市西湖区 灵隐路 1 号';
  const full = `${safeLabel} · ${safeDetail.replace(/^杭州市西湖区\s*/, '')}`;
  addresses.push({ id: `address-${addresses.length + 1}`, label: safeLabel, full, detail: safeDetail, isDefault: false });
  selectedAddress = full;
  return full;
}
function saveAddress() {
  const label = document.querySelector('#address-label').value;
  const detail = document.querySelector('#address-detail').value;
  addAddress(label, detail);
  closeModal();
  navigate('addresses');
  toast('已新增并设为当前收货地址');
}
function agentConfirmationCard() {
  if (!agentEvents.length) return '';
  if (agentOrderConfirmed) return `<div class="agent-confirm confirmed"><b>✓ 订单已确认</b><p>招牌牛肉面 · 不辣 · ¥25</p></div>`;
  if (agentOrderCancelled) return `<div class="agent-confirm cancelled"><b>已取消本次推荐</b><p>你可以继续告诉我想吃什么。</p></div>`;
  return `<div class="agent-confirm"><span class="pill">待你确认</span><h3>阿牛牛肉面馆 · 招牌牛肉面</h3><p class="muted">标准份 · 不辣 · 送至 ${escapeHtml(selectedAddress)}</p><div class="confirm-actions"><button class="secondary" onclick="cancelAgentOrder()">取消</button><button class="primary" style="margin:0" onclick="confirmAgentOrder()">确认订单 ¥25</button></div></div>`;
}
function openAgent() {
  const eventHtml = agentEvents.length ? `<div class="agent-log">${agentEvents.map(e => `<div>${e}</div>`).join('')}</div>` : '';
  modalRoot.innerHTML = `<div class="modal-backdrop"><section id="agent" class="sheet ${agentMode === 'full' ? 'full' : ''}">
    <div class="handle"></div><div class="assistant-head"><div class="between"><div><span class="pill">饭搭子 AI</span><h2 style="margin-top:9px">你的点餐搭子</h2></div><div><button class="icon-btn" onclick="toggleAgentFullscreen()">⛶</button><button class="icon-btn" onclick="closeModal()">×</button></div></div></div>
    <div class="messages"><div class="bubble">你好！告诉我想吃什么、预算和口味，我来帮你搭配。</div>${agentEvents.length ? '<div class="bubble user">帮我点一份不辣的面</div><div class="bubble">我找到了阿牛牛肉面馆，已为你准备不辣的招牌牛肉面。</div>' : ''}</div>
    ${eventHtml}${agentConfirmationCard()}<div class="assistant-input"><input id="agent-prompt" placeholder="例如：想吃不辣的热汤面" value=""><button class="round" title="模拟录音识别" onclick="mockRecord()">🎙</button><button class="round" title="发送" onclick="sendAgent()">↑</button></div>
  </section></div>`;
}
function toggleAgentFullscreen() { agentMode = agentMode === 'full' ? 'half' : 'full'; openAgent(); }
function mockRecord() { const input = document.querySelector('#agent-prompt'); input.value = '帮我点一份不辣的热汤面'; input.focus(); toast('已识别本地模拟语音'); }
function sendAgent() { const prompt = document.querySelector('#agent-prompt').value.trim(); if (!prompt) return toast('先告诉搭子你想吃什么'); runAgentDemo(prompt); }
function confirmAgentOrder() {
  agentOrderConfirmed = true;
  agentOrderCancelled = false;
  openAgent();
  toast('订单已确认');
}
function cancelAgentOrder() {
  agentOrderCancelled = true;
  agentOrderConfirmed = false;
  openAgent();
  toast('已取消本次 AI 推荐');
}
function runAgentDemo(prompt) {
  agentEvents = ['解析意图', '搜索商家', '查询菜品', '构建购物车', '校验订单', '等待确认'];
  agentOrderConfirmed = false;
  agentOrderCancelled = false;
  openAgent();
  toast(`已收到：“${prompt}”`);
}
function closeModal() { modalRoot.innerHTML = ''; }
function toast(message) { const old = document.querySelector('.toast'); if (old) old.remove(); const el = document.createElement('div'); el.className = 'toast'; el.textContent = message; document.querySelector('.phone-shell').append(el); setTimeout(() => el.remove(), 1800); }
Object.assign(window, { navigate, addToCart, changeQuantity, submitMockPayment, advanceOrderStatus, toggleAutomation, runAgentDemo, openDish, pickOption, addSelectedDish, closeModal, confirmOrder, openAgent, toggleAgentFullscreen, mockRecord, sendAgent, selectAddress, selectAddressById, completeRegistration, openAddressForm, addAddress, saveAddress, confirmAgentOrder, cancelAgentOrder, escapeHtml, toast });
render();
