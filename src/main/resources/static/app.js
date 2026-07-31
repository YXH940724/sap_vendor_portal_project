const resources = [
  { id: 'overview', label: '工作台', icon: '⌂', description: '今日协同待办与全链路状态' },
  { id: 'purchaseOrders', label: '采购订单', icon: 'PO', description: '查看采购订单、行项目、交期与订单变更' },
  { id: 'asns', label: 'ASN / 发运', icon: '↗', description: '跟踪发运通知、到货协同与 ASN 状态' },
  { id: 'materialDocuments', label: '收货凭证', icon: 'GR', description: '查询收货与物料移动凭证' },
  { id: 'invoices', label: '结算对账', icon: '¥', description: '查询供应商发票与结算对账基础数据' },
  { id: 'suppliers', label: '供应商资料', icon: 'ID', description: '查看当前供应商主数据' }
];

let current = 'overview';
const navigation = document.querySelector('#navigation');
const moduleCards = document.querySelector('#moduleCards');
const pageTitle = document.querySelector('#pageTitle');
const dashboard = document.querySelector('#dashboard');
const resourceView = document.querySelector('#resourceView');
const statusStrip = document.querySelector('#statusStrip');
const connectionLabel = document.querySelector('#connectionLabel');
const vendorScope = document.querySelector('#vendorScope');
const scopeTitle = document.querySelector('#scopeTitle');
const resourceDescription = document.querySelector('#resourceDescription');
const retrievedAt = document.querySelector('#retrievedAt');
const dataCount = document.querySelector('#dataCount');
const tableWrap = document.querySelector('#tableWrap');
const searchInput = document.querySelector('#searchInput');

for (const resource of resources) {
  const navItem = document.querySelector('#navTemplate').content.firstElementChild.cloneNode(true);
  navItem.dataset.resource = resource.id;
  navItem.querySelector('.nav-icon').textContent = resource.icon;
  navItem.querySelector('.nav-label').textContent = resource.label;
  navItem.addEventListener('click', () => selectResource(resource.id));
  navigation.append(navItem);
  if (resource.id !== 'overview') {
    const module = document.querySelector('#moduleTemplate').content.firstElementChild.cloneNode(true);
    module.dataset.resource = resource.id;
    module.querySelector('.module-icon').textContent = resource.icon;
    module.querySelector('.module-copy small').textContent = `0${resources.indexOf(resource)}`;
    module.querySelector('.module-copy b').textContent = resource.label;
    module.querySelector('.module-copy em').textContent = resource.description;
    module.addEventListener('click', () => selectResource(resource.id));
    moduleCards.append(module);
  }
}

document.querySelectorAll('[data-jump]').forEach((button) => button.addEventListener('click', () => selectResource(button.dataset.jump)));
document.querySelector('#backToOverview').addEventListener('click', () => selectResource('overview', false));
document.querySelector('#refreshButton').addEventListener('click', loadCurrent);
searchInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') loadCurrent(); });
document.querySelector('#utilityDate').textContent = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date());

async function bootstrap() {
  try {
    const [health, session] = await Promise.all([getJson('/api/health'), getJson('/api/session')]);
    const scope = `供应商 ${session.vendorId}`;
    vendorScope.textContent = scope;
    scopeTitle.textContent = scope;
    connectionLabel.textContent = health.configured ? '已就绪，实时读取中' : '等待 SAP 连接配置';
    statusStrip.className = health.configured ? 'system-status ready' : 'system-status warning';
    statusStrip.innerHTML = `<span></span>${health.configured ? '系统已就绪 · 安全范围已锁定' : `等待配置 · ${escapeHtml(health.issue)}`}`;
  } catch (error) {
    connectionLabel.textContent = '无法建立安全会话';
    vendorScope.textContent = '未授权';
    scopeTitle.textContent = '未获得供应商范围';
    statusStrip.className = 'system-status error';
    statusStrip.innerHTML = `<span></span>无法建立安全会话 · ${escapeHtml(error.message)}`;
  }
  selectResource('overview', false);
}

function selectResource(id, shouldLoad = true) {
  current = id;
  const resource = resources.find((item) => item.id === id);
  document.querySelectorAll('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.resource === id));
  document.querySelectorAll('.flow-step').forEach((button) => button.classList.toggle('active', button.dataset.jump === id));
  const isOverview = id === 'overview';
  dashboard.hidden = !isOverview;
  resourceView.hidden = isOverview;
  if (!isOverview) {
    pageTitle.textContent = resource.label;
    resourceDescription.textContent = resource.description;
    retrievedAt.textContent = '尚未读取 SAP 数据';
    dataCount.textContent = '';
    tableWrap.innerHTML = '<div class="empty-state"><span>◎</span><b>准备读取实时业务数据</b><p>点击“刷新数据”开始查询。</p></div>';
    window.scrollTo({ top: 0, behavior: 'smooth' });
    if (shouldLoad) loadCurrent();
  }
}

async function loadCurrent() {
  if (current === 'overview') return;
  tableWrap.innerHTML = '<div class="loading"><span></span>正在向 SAP 请求实时数据</div>';
  try {
    const params = new URLSearchParams({ top: '30' });
    if (searchInput.value.trim()) params.set('search', searchInput.value.trim());
    const payload = await getJson(`/api/data/${current}?${params}`);
    const time = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(payload.retrievedAt));
    retrievedAt.textContent = `最近读取：${time}`;
    dataCount.textContent = `返回 ${payload.count} 条记录`;
    renderTable(payload.records);
  } catch (error) {
    retrievedAt.textContent = '查询未完成';
    dataCount.textContent = '';
    tableWrap.innerHTML = `<div class="error-copy">未能读取 SAP 数据：${escapeHtml(error.message)}</div>`;
  }
}

function renderTable(records) {
  if (!records.length) { tableWrap.innerHTML = '<div class="empty-state"><span>◎</span><b>当前范围内没有记录</b><p>可调整检索条件后再次查询。</p></div>'; return; }
  const keys = [...new Set(records.flatMap((record) => Object.keys(record)))].slice(0, 9);
  const header = keys.map((key) => `<th>${escapeHtml(key)}</th>`).join('');
  const rows = records.map((record) => `<tr>${keys.map((key) => `<td>${escapeHtml(formatValue(record[key]))}</td>`).join('')}</tr>`).join('');
  tableWrap.innerHTML = `<table><thead><tr>${header}</tr></thead><tbody>${rows}</tbody></table>`;
}

function formatValue(value) { if (value === null || value === undefined) return '—'; if (typeof value === 'object') return JSON.stringify(value); return String(value); }
function escapeHtml(value) { return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'); }
async function getJson(url) { const response = await fetch(url, { headers: { accept: 'application/json' } }); const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.message || `请求失败（${response.status}）`); return payload; }

bootstrap();
