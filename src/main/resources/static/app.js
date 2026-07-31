const resources = [
  { id: 'overview', label: '工作台', icon: '⌂', description: '今日协同待办与全链路状态', type: 'overview' },
  { id: 'dashboard', label: '数据看板', icon: '▥', description: '采购执行、发运、收货与结算的实时分析', type: 'analytics' },
  { id: 'purchaseOrders', label: '采购订单', icon: 'PO', description: '按筛选条件查询订单头、行项目、交期与订单变更', type: 'document' },
  { id: 'asns', label: 'ASN / 发运', icon: '↗', description: '基于采购订单创建 ASN，并跟踪发运与到货状态', type: 'document' },
  { id: 'materialDocuments', label: '收货凭证', icon: 'GR', description: '按凭证号、物料和过账日期查询收货明细', type: 'document' },
  { id: 'invoices', label: '结算对账', icon: '¥', description: '查询供应商发票与结算对账基础数据', type: 'document' },
  { id: 'suppliers', label: '供应商资料', icon: 'ID', description: '查看当前供应商主数据', type: 'document' }
];

const filterDefinitions = {
  purchaseOrders: [
    { key: 'purchaseOrder', label: '采购订单', placeholder: '订单号' }, { key: 'status', label: '订单状态', type: 'select', options: ['全部状态', '待确认', '已确认', '已完成'] },
    { key: 'deliveryFrom', label: '交货日期自', type: 'date' }, { key: 'deliveryTo', label: '交货日期至', type: 'date' }, { key: 'plant', label: '工厂', placeholder: '工厂代码' }
  ],
  materialDocuments: [
    { key: 'materialDocument', label: '物料凭证', placeholder: '凭证编号' }, { key: 'material', label: '物料', placeholder: '物料编码' },
    { key: 'postingFrom', label: '过账日期自', type: 'date' }, { key: 'postingTo', label: '过账日期至', type: 'date' }, { key: 'movementType', label: '移动类型', placeholder: '例如 101' }
  ],
  asns: [{ key: 'asn', label: 'ASN 编号', placeholder: '交货单号' }, { key: 'purchaseOrder', label: '采购订单', placeholder: '订单号' }, { key: 'deliveryFrom', label: '计划到货自', type: 'date' }, { key: 'deliveryTo', label: '计划到货至', type: 'date' }],
  invoices: [{ key: 'invoice', label: '发票号', placeholder: '供应商发票号' }, { key: 'purchaseOrder', label: '采购订单', placeholder: '订单号' }, { key: 'status', label: '状态', type: 'select', options: ['全部状态', '待对账', '已完成'] }]
};

let current = 'overview';
let currentRecords = [];
let activeFilters = {};
let pageIndex = 1;
const $ = (selector) => document.querySelector(selector);
const navigation = $('#navigation'), moduleCards = $('#moduleCards'), pageTitle = $('#pageTitle'), dashboard = $('#dashboard'), resourceView = $('#resourceView');
const statusStrip = $('#statusStrip'), connectionLabel = $('#connectionLabel'), vendorScope = $('#vendorScope'), scopeTitle = $('#scopeTitle');
const resourceDescription = $('#resourceDescription'), retrievedAt = $('#retrievedAt'), dataCount = $('#dataCount'), tableWrap = $('#tableWrap'), searchInput = $('#searchInput');
const queryPanel = $('#queryPanel'), filterPanel = $('#filterPanel'), analyticsView = $('#analyticsView'), createAsnButton = $('#createAsnButton');

for (const resource of resources) {
  const navItem = $('#navTemplate').content.firstElementChild.cloneNode(true);
  navItem.dataset.resource = resource.id; navItem.querySelector('.nav-icon').textContent = resource.icon; navItem.querySelector('.nav-label').textContent = resource.label;
  navItem.addEventListener('click', () => selectResource(resource.id)); navigation.append(navItem);
  if (resource.type === 'document') {
    const module = $('#moduleTemplate').content.firstElementChild.cloneNode(true);
    module.dataset.resource = resource.id; module.querySelector('.module-icon').textContent = resource.icon;
    module.querySelector('.module-copy small').textContent = `0${resources.filter((item) => item.type === 'document').indexOf(resource) + 1}`;
    module.querySelector('.module-copy b').textContent = resource.label; module.querySelector('.module-copy em').textContent = resource.description;
    module.addEventListener('click', () => selectResource(resource.id)); moduleCards.append(module);
  }
}

document.querySelectorAll('[data-jump]').forEach((button) => button.addEventListener('click', () => selectResource(button.dataset.jump)));
$('#backToOverview').addEventListener('click', () => selectResource('overview', false));
$('#refreshButton').addEventListener('click', loadCurrent); searchInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') loadCurrent(); });
$('#menuButton').addEventListener('click', () => document.querySelector('.app-frame').classList.toggle('mobile-nav-open'));
$('#utilityDate').textContent = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date());
createAsnButton.addEventListener('click', openAsnModal); $('#closeDrawer').addEventListener('click', closeDrawer); $('#closeAsnModal').addEventListener('click', closeAsnModal); $('#cancelAsn').addEventListener('click', closeAsnModal); $('#addAsnLine').addEventListener('click', () => addAsnLine()); $('#asnForm').addEventListener('submit', submitAsn);

async function bootstrap() {
  try {
    const [health, session] = await Promise.all([getJson('/api/health'), getJson('/api/session')]);
    const scope = `供应商 ${session.vendorId}`; vendorScope.textContent = scope; scopeTitle.textContent = scope;
    connectionLabel.textContent = health.configured ? '已就绪，实时读取中' : '等待 SAP 连接配置'; statusStrip.className = health.configured ? 'system-status ready' : 'system-status warning';
    statusStrip.innerHTML = `<span></span>${health.configured ? '系统已就绪 · 安全范围已锁定' : `等待配置 · ${escapeHtml(health.issue)}`}`;
  } catch (error) { connectionLabel.textContent = '无法建立安全会话'; vendorScope.textContent = '未授权'; scopeTitle.textContent = '未获得供应商范围'; statusStrip.className = 'system-status error'; statusStrip.innerHTML = `<span></span>无法建立安全会话 · ${escapeHtml(error.message)}`; }
  selectResource('overview', false);
}

function selectResource(id, shouldLoad = true) {
  current = id; activeFilters = {}; currentRecords = []; pageIndex = 1; closeDrawer(); document.querySelector('.app-frame').classList.remove('mobile-nav-open');
  const resource = resources.find((item) => item.id === id); document.querySelectorAll('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.resource === id));
  document.querySelectorAll('.flow-step').forEach((button) => button.classList.toggle('active', button.dataset.jump === id));
  const isOverview = resource.type === 'overview'; dashboard.hidden = !isOverview; resourceView.hidden = isOverview;
  if (isOverview) return;
  pageTitle.textContent = resource.label; resourceDescription.textContent = resource.description; $('#resourceEyebrow').textContent = resource.type === 'analytics' ? 'PURCHASE EXECUTION ANALYTICS' : 'LIVE SAP QUERY';
  const isAnalytics = resource.type === 'analytics'; queryPanel.hidden = isAnalytics; filterPanel.hidden = isAnalytics; $('.data-bar').hidden = isAnalytics; tableWrap.hidden = isAnalytics; analyticsView.hidden = !isAnalytics; createAsnButton.hidden = id !== 'asns';
  if (isAnalytics) { if (shouldLoad) loadDashboard(); } else { renderFilters(id); retrievedAt.textContent = '尚未读取 SAP 数据'; dataCount.textContent = ''; tableWrap.innerHTML = emptyState('准备读取实时业务数据', '设置筛选条件后点击“刷新数据”开始查询。'); if (shouldLoad) loadCurrent(); }
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function renderFilters(resourceId) {
  const filters = filterDefinitions[resourceId] || [];
  if (!filters.length) { filterPanel.hidden = true; return; }
  filterPanel.hidden = false;
  filterPanel.innerHTML = `<div class="filter-title"><b>单据筛选</b><span>筛选仅作用于当前供应商范围内的查询结果</span></div><form id="filterForm" class="filter-fields">${filters.map((filter) => filter.type === 'select' ? `<label>${filter.label}<select name="${filter.key}">${filter.options.map((value) => `<option value="${value === '全部状态' ? '' : value}">${value}</option>`).join('')}</select></label>` : `<label>${filter.label}<input name="${filter.key}" type="${filter.type || 'text'}" placeholder="${filter.placeholder || ''}" /></label>`).join('')}<div class="filter-buttons"><button type="button" id="resetFilters" class="plain-button">重置</button><button class="secondary-button" type="submit">应用筛选</button></div></form>`;
  $('#filterForm').addEventListener('submit', (event) => { event.preventDefault(); activeFilters = Object.fromEntries(new FormData(event.currentTarget).entries()); loadCurrent(); });
  $('#resetFilters').addEventListener('click', () => { activeFilters = {}; $('#filterForm').reset(); loadCurrent(); });
}

async function loadCurrent() {
  const resource = resources.find((item) => item.id === current); if (!resource || resource.type !== 'document') return;
  tableWrap.innerHTML = '<div class="loading"><span></span>正在向 SAP 请求实时数据</div>';
  try {
    const params = new URLSearchParams({ top: '100' }); if (searchInput.value.trim()) params.set('search', searchInput.value.trim());
    const payload = await getJson(`/api/data/${current}?${params}`); currentRecords = applyFilters(payload.records, activeFilters, current); pageIndex = 1;
    const time = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(payload.retrievedAt)); retrievedAt.textContent = `最近读取：${time}`; dataCount.textContent = `SAP 返回 ${payload.count} 条 · 当前筛选 ${currentRecords.length} 条`;
    renderDocumentTable(currentRecords, resource);
  } catch (error) { retrievedAt.textContent = '查询未完成'; dataCount.textContent = ''; tableWrap.innerHTML = `<div class="error-copy">未能读取 SAP 数据：${escapeHtml(error.message)}</div>`; }
}

async function loadDashboard() {
  analyticsView.innerHTML = '<div class="loading"><span></span>正在汇总采购执行数据</div>';
  try { const payload = await getJson('/api/dashboard'); renderAnalytics(payload); } catch (error) { analyticsView.innerHTML = `<div class="error-copy">未能生成数据看板：${escapeHtml(error.message)}</div>`; }
}

function renderAnalytics(payload) {
  const metrics = payload.metrics || []; const orderStatus = payload.orderStatus || []; const asnStatus = payload.asnStatus || [];
  analyticsView.innerHTML = `<div class="analytics-note"><span>▣</span><p><b>采购执行数据看板</b>　基于当前供应商范围内实时读取的记录汇总。每类数据最多读取 ${payload.sampleLimit || 100} 条，适用于协同监控，不替代 SAP 正式报表。</p><small>更新于 ${formatDate(payload.retrievedAt)}</small></div><div class="metric-grid">${metrics.map((metric) => `<article class="metric-card"><span>${escapeHtml(metric.code)}</span><b>${metric.value}</b><p>${escapeHtml(metric.label)}</p><small>${escapeHtml(metric.hint)}</small></article>`).join('')}</div><div class="chart-grid"><article class="panel status-chart"><div class="panel-heading"><div><p class="section-kicker">ORDER STATUS</p><h2>订单执行状态</h2></div></div>${barChart(orderStatus, '#3B82F6')}</article><article class="panel status-chart"><div class="panel-heading"><div><p class="section-kicker">ASN STATUS</p><h2>发运协同状态</h2></div></div>${barChart(asnStatus, '#10B981')}</article></div><article class="panel analytics-guidance"><p class="section-kicker">ACTION GUIDE</p><h2>用看板识别协同优先级</h2><div><p><b>订单：</b>优先核对待确认订单和临近交期的行项目。<button data-analytics-jump="purchaseOrders">立即处理</button></p><p><b>发运：</b>对无 ASN 的订单补充发运计划；对运输中 ASN 跟踪到货。<button data-analytics-jump="asns">查看发运</button></p><p><b>结算：</b>结合收货凭证与发票状态完成差异沟通。<button data-analytics-jump="invoices">核对发票</button></p></div></article>`;
  analyticsView.querySelectorAll('[data-analytics-jump]').forEach((button) => button.addEventListener('click', () => selectResource(button.dataset.analyticsJump)));
}
function barChart(items, color) { const max = Math.max(...items.map((item) => Number(item.value)), 1); return items.length ? `<div class="bar-chart">${items.map((item) => `<div class="bar-row"><span>${escapeHtml(item.label)}</span><div><i style="width:${Math.max(5, Math.round(Number(item.value) / max * 100))}%;background:${color}"></i></div><b>${item.value}</b></div>`).join('')}</div>` : '<div class="chart-empty">SAP 未返回可用于统计的状态字段。</div>'; }

function renderDocumentTable(records, resource) {
  if (!records.length) { tableWrap.innerHTML = emptyState('当前范围内没有匹配记录', '可调整筛选条件后再次查询。'); return; }
  const perPage = 10, totalPages = Math.max(1, Math.ceil(records.length / perPage)); pageIndex = Math.min(pageIndex, totalPages);
  const keys = selectColumns(records, resource.id); const header = keys.map((key) => `<th>${escapeHtml(labelFor(key))}</th>`).join(''); const sliceStart = (pageIndex - 1) * perPage;
  const rows = records.slice(sliceStart, sliceStart + perPage).map((record, index) => `<tr data-index="${sliceStart + index}" tabindex="0">${keys.map((key) => `<td>${renderCell(key, record[key])}</td>`).join('')}<td class="row-action">查看详情 →</td></tr>`).join('');
  tableWrap.innerHTML = `<table class="document-table"><thead><tr>${header}<th></th></tr></thead><tbody>${rows}</tbody></table><div class="table-footer"><span>共 ${records.length} 条记录</span><div class="pagination"><button type="button" data-page="${pageIndex - 1}" ${pageIndex === 1 ? 'disabled' : ''}>←</button><b>${pageIndex}</b><span>/ ${totalPages}</span><button type="button" data-page="${pageIndex + 1}" ${pageIndex === totalPages ? 'disabled' : ''}>→</button></div></div>`;
  tableWrap.querySelectorAll('tbody tr').forEach((row) => { const open = () => showDetail(records[Number(row.dataset.index)], resource); row.addEventListener('click', open); row.addEventListener('keydown', (event) => { if (event.key === 'Enter') open(); }); });
  tableWrap.querySelectorAll('[data-page]').forEach((button) => button.addEventListener('click', () => { pageIndex = Number(button.dataset.page); renderDocumentTable(records, resource); }));
}
function selectColumns(records, resourceId) { const preferred = { purchaseOrders: ['PurchaseOrder', 'PurchaseOrderItem', 'Supplier', 'PurchaseOrderDate', 'DeliveryDate', 'PurchaseOrderStatus'], materialDocuments: ['MaterialDocument', 'MaterialDocumentItem', 'Material', 'PostingDate', 'GoodsMovementType', 'QuantityInEntryUnit'], asns: ['InbDelivery', 'PurchaseOrder', 'PlannedDeliveryDate', 'OverallStatus'], invoices: ['SupplierInvoice', 'PurchaseOrder', 'DocumentDate', 'SupplierInvoiceStatus', 'InvoiceGrossAmount'] }[resourceId] || []; const present = preferred.filter((key) => records.some((record) => record[key] !== undefined)); return (present.length ? present : [...new Set(records.flatMap((record) => Object.keys(record)))]).slice(0, 7); }
function showDetail(record, resource) {
  const isPo = resource.id === 'purchaseOrders', isMaterial = resource.id === 'materialDocuments'; const titleKey = isPo ? 'PurchaseOrder' : isMaterial ? 'MaterialDocument' : Object.keys(record)[0];
  $('#drawerTitle').textContent = `${resource.label} · ${formatValue(record[titleKey])}`; const items = extractItems(record); const headerEntries = Object.entries(record).filter(([, value]) => !Array.isArray(value) && typeof value !== 'object').slice(0, 14);
  $('#drawerBody').innerHTML = `<section class="detail-section"><h3>${isPo || isMaterial ? '抬头信息' : '单据信息'}</h3><dl class="detail-grid">${headerEntries.map(([key, value]) => `<div><dt>${escapeHtml(labelFor(key))}</dt><dd>${escapeHtml(formatValue(value))}</dd></div>`).join('')}</dl></section>${isPo || isMaterial ? `<section class="detail-section"><div class="detail-section-head"><h3>${isPo ? '行项目' : '物料明细'}</h3><span>${items.length ? `${items.length} 行` : '等待行项目导航数据'}</span></div>${items.length ? renderItemTable(items) : '<div class="line-empty">当前抬头记录未带行项目导航数据。请在 SAP $metadata 确认行项目实体后配置 $expand；页面结构已预留。</div>'}</section>` : ''}`;
  $('#detailDrawer').hidden = false; document.body.classList.add('drawer-open');
}
function extractItems(record) { const entry = Object.entries(record).find(([key, value]) => Array.isArray(value) && /item|items|to_/i.test(key)); return entry ? entry[1] : []; }
function renderItemTable(items) { const keys = [...new Set(items.flatMap((item) => Object.keys(item)))].slice(0, 5); return `<div class="detail-table-wrap"><table><thead><tr>${keys.map((key) => `<th>${escapeHtml(labelFor(key))}</th>`).join('')}</tr></thead><tbody>${items.map((item) => `<tr>${keys.map((key) => `<td>${escapeHtml(formatValue(item[key]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`; }
function closeDrawer() { $('#detailDrawer').hidden = true; document.body.classList.remove('drawer-open'); }

function openAsnModal() { $('#asnForm').reset(); $('#asnFormMessage').textContent = ''; $('#asnLineRows').innerHTML = ''; addAsnLine(); $('#asnModal').hidden = false; }
function closeAsnModal() { $('#asnModal').hidden = true; }
function addAsnLine() { const row = document.createElement('div'); row.className = 'asn-line-row'; row.innerHTML = `<input name="material" required placeholder="物料编码" /><input name="quantity" required type="number" min="0.001" step="0.001" placeholder="发运数量" /><input name="unit" required value="EA" placeholder="单位" /><button type="button" class="remove-line" aria-label="删除行">×</button>`; row.querySelector('.remove-line').addEventListener('click', () => { if (document.querySelectorAll('.asn-line-row').length > 1) row.remove(); }); $('#asnLineRows').append(row); }
async function submitAsn(event) { event.preventDefault(); const form = new FormData(event.currentTarget); const lines = [...document.querySelectorAll('.asn-line-row')].map((row) => ({ material: row.querySelector('[name=material]').value, quantity: Number(row.querySelector('[name=quantity]').value), unit: row.querySelector('[name=unit]').value })); const payload = { purchaseOrder: form.get('purchaseOrder'), plannedDeliveryDate: form.get('plannedDeliveryDate'), shippingDate: form.get('shippingDate'), transportReference: form.get('transportReference'), items: lines }; const message = $('#asnFormMessage'); message.textContent = '正在提交 ASN…'; try { await getJson('/api/asns', { method: 'POST', body: JSON.stringify(payload) }); message.textContent = 'ASN 已提交 SAP。'; setTimeout(closeAsnModal, 900); } catch (error) { message.textContent = `未提交：${error.message}`; } }

function applyFilters(records, filters, resource) { return records.filter((record) => Object.entries(filters).every(([key, value]) => { if (!value) return true; const comparable = Object.entries(record).filter(([field]) => field.toLowerCase().includes(key.toLowerCase().replace(/from|to/g, '')) || key === 'status' && /status/i.test(field)).map(([, fieldValue]) => String(fieldValue ?? '')).join(' '); if (/from$/.test(key) || /to$/.test(key)) { const dateField = Object.entries(record).find(([field]) => /date/i.test(field)); if (!dateField) return true; const date = String(dateField[1]).slice(0, 10); return key.endsWith('From') ? date >= value : date <= value; } return comparable ? comparable.toLowerCase().includes(String(value).toLowerCase()) : true; })); }
function emptyState(title, body) { return `<div class="empty-state"><span>◎</span><b>${title}</b><p>${body}</p></div>`; }
function labelFor(key) { return ({ PurchaseOrder: '采购订单', PurchaseOrderItem: '项目', Supplier: '供应商', PurchaseOrderDate: '订单日期', DeliveryDate: '交货日期', PurchaseOrderStatus: '订单状态', MaterialDocument: '物料凭证', MaterialDocumentItem: '项目', Material: '物料', PostingDate: '过账日期', GoodsMovementType: '移动类型', QuantityInEntryUnit: '收货数量', InbDelivery: 'ASN 编号', PlannedDeliveryDate: '计划到货', OverallStatus: '状态', SupplierInvoice: '供应商发票', DocumentDate: '凭证日期', SupplierInvoiceStatus: '发票状态', InvoiceGrossAmount: '发票金额' })[key] || key; }
function renderCell(key, value) { const text = escapeHtml(formatValue(value)); if (/status/i.test(key)) return `<span class="status-badge ${statusTone(value)}">${text}</span>`; if (/amount|quantity|price|value/i.test(key)) return `<span class="numeric-cell">${text}</span>`; return text; }
function statusTone(value) { const text = String(value || ''); if (/逾期|异常|错误|拒绝/.test(text)) return 'danger'; if (/待|草稿|取消/.test(text)) return 'warning'; if (/已收货|已对账|已完成|已启用/.test(text)) return 'success'; return 'info'; }
function formatValue(value) { if (value === null || value === undefined || value === '') return '—'; if (typeof value === 'object') return Array.isArray(value) ? `${value.length} 条明细` : JSON.stringify(value); return String(value); }
function formatDate(value) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'; }
function escapeHtml(value) { return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'); }
async function getJson(url, options = {}) { const response = await fetch(url, { ...options, headers: { accept: 'application/json', ...(options.body ? { 'content-type': 'application/json' } : {}), ...(options.headers || {}) } }); const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.message || `请求失败（${response.status}）`); return payload; }
bootstrap();
