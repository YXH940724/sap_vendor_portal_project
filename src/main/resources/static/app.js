const resources = [
  { id: 'overview', label: '业务总览', code: '00', description: '连接状态与无数据库架构说明' },
  { id: 'suppliers', label: '供应商主数据', code: '01', description: 'SAP Business Partner：当前供应商主数据' },
  { id: 'purchaseOrders', label: '采购订单', code: '02', description: 'SAP Purchase Order：订单与行项目实时记录' },
  { id: 'asns', label: 'ASN / 发货协同', code: '03', description: 'SAP Inbound Delivery：发货与到货协同状态' },
  { id: 'materialDocuments', label: '物料凭证', code: '04', description: 'SAP Material Document：收货与物料移动记录' },
  { id: 'invoices', label: '结算对账', code: '05', description: 'SAP Supplier Invoice：发票、付款与对账基础数据' }
];

let current = 'overview';
const navigation = document.querySelector('#navigation');
const pageTitle = document.querySelector('#pageTitle');
const dashboard = document.querySelector('#dashboard');
const resourceView = document.querySelector('#resourceView');
const statusStrip = document.querySelector('#statusStrip');
const vendorScope = document.querySelector('#vendorScope');
const resourceDescription = document.querySelector('#resourceDescription');
const retrievedAt = document.querySelector('#retrievedAt');
const tableWrap = document.querySelector('#tableWrap');
const searchInput = document.querySelector('#searchInput');

for (const resource of resources) {
  const button = document.querySelector('#navTemplate').content.firstElementChild.cloneNode(true);
  button.dataset.resource = resource.id;
  button.querySelector('.nav-code').textContent = resource.code;
  button.querySelector('.nav-label').textContent = resource.label;
  button.addEventListener('click', () => selectResource(resource.id));
  navigation.append(button);
}
document.querySelector('#refreshButton').addEventListener('click', loadCurrent);
searchInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') loadCurrent(); });

async function bootstrap() {
  try {
    const [health, session] = await Promise.all([getJson('/api/health'), getJson('/api/session')]);
    vendorScope.textContent = `供应商 ${session.vendorId}`;
    statusStrip.className = health.configured ? 'status-strip ready' : 'status-strip warning';
    statusStrip.textContent = health.configured ? `服务已就绪 · 身份范围来自 ${session.identitySource === 'server_environment' ? '服务器部署配置' : '签名身份代理'}` : `等待配置 · ${health.issue}`;
  } catch (error) {
    statusStrip.className = 'status-strip error';
    statusStrip.textContent = `无法建立安全会话 · ${error.message}`;
    vendorScope.textContent = '未授权';
  }
  selectResource('overview', false);
}

function selectResource(id, shouldLoad = true) {
  current = id;
  const resource = resources.find((item) => item.id === id);
  pageTitle.textContent = resource.label;
  document.querySelectorAll('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.resource === id));
  const isOverview = id === 'overview';
  dashboard.hidden = !isOverview;
  resourceView.hidden = isOverview;
  if (!isOverview) {
    resourceDescription.textContent = resource.description;
    retrievedAt.textContent = '';
    tableWrap.innerHTML = '<div class="empty">点击“刷新 SAP 数据”加载实时记录。</div>';
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
    retrievedAt.textContent = `最近读取：${new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(payload.retrievedAt))} · ${payload.count} 条`;
    renderTable(payload.records);
  } catch (error) {
    tableWrap.innerHTML = `<div class="empty error-copy">未能读取 SAP 数据：${escapeHtml(error.message)}</div>`;
  }
}

function renderTable(records) {
  if (!records.length) { tableWrap.innerHTML = '<div class="empty">当前范围内没有可展示的记录。</div>'; return; }
  const keys = [...new Set(records.flatMap((record) => Object.keys(record)))].slice(0, 9);
  const header = keys.map((key) => `<th>${escapeHtml(key)}</th>`).join('');
  const rows = records.map((record) => `<tr>${keys.map((key) => `<td>${escapeHtml(formatValue(record[key]))}</td>`).join('')}</tr>`).join('');
  tableWrap.innerHTML = `<table><thead><tr>${header}</tr></thead><tbody>${rows}</tbody></table>`;
}

function formatValue(value) { if (value === null || value === undefined) return '—'; if (typeof value === 'object') return JSON.stringify(value); return String(value); }
function escapeHtml(value) { return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'); }
async function getJson(url) { const response = await fetch(url, { headers: { accept: 'application/json' } }); const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.message || `请求失败（${response.status}）`); return payload; }

bootstrap();
