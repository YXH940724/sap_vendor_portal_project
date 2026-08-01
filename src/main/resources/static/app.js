const resources = {
  dashboard: { title: '工作台', type: 'dashboard' }, analytics: { title: '数据看板', type: 'analytics' },
  purchaseOrders: { title: '采购订单', description: '订单抬头、订单行、交期与履约状态' }, asns: { title: 'ASN / 发运', description: '基于已选采购订单行创建及跟踪发运通知' },
  materialDocuments: { title: '收货凭证', description: '按采购订单范围查询收货数量与过账信息' }, invoices: { title: '结算对账', description: '按采购订单范围查询发票与对账基础数据' }, suppliers: { title: '供应商资料', description: '当前登录供应商的 SAP 主数据' }
};
const columns = {
  purchaseOrders: ['PurchaseOrder', 'PurchaseOrderItem', 'Material', 'MaterialDescription', 'OrderQuantity', 'PurchaseOrderQuantityUnit', 'DeliveryDate', 'PurchaseOrderStatus'],
  asns: ['InbDelivery', 'PurchaseOrder', 'PurchaseOrderItem', 'Material', 'MaterialDescription', 'DeliveryDate', 'OverallStatus'],
  materialDocuments: ['MaterialDocument', 'MaterialDocumentItem', 'PurchaseOrder', 'Material', 'MaterialDescription', 'PostingDate', 'GoodsMovementType', 'QuantityInEntryUnit', 'EntryUnit'],
  invoices: ['SupplierInvoice', 'PurchaseOrder', 'DocumentDate', 'InvoiceGrossAmount', 'DocumentCurrency', 'SupplierInvoiceStatus']
};
const labels = {
  PurchaseOrder: '采购订单号', PurchaseOrderItem: '行项目', Material: '物料编码', MaterialDescription: '物料描述', DeliveryDate: '交货日期', PurchaseOrderStatus: '订单状态', OrderQuantity: '订单数量', PurchaseOrderQuantityUnit: '单位',
  InbDelivery: 'ASN 编号', PlannedDeliveryDate: '计划到货日期', OverallStatus: '状态', MaterialDocument: '物料凭证', MaterialDocumentItem: '项目', PostingDate: '过账日期', GoodsMovementType: '移动类型', QuantityInEntryUnit: '收货数量', EntryUnit: '单位',
  SupplierInvoice: '发票号码', SupplierInvoiceStatus: '发票状态', DocumentDate: '凭证日期', InvoiceGrossAmount: '发票金额', DocumentCurrency: '币种', Supplier: '供应商编码', SupplierName: '供应商名称', BusinessPartner: '供应商编码',
  CompanyCode: '公司代码', PurchasingOrganization: '采购组织', PaymentTerms: '付款条款', Country: '国家/地区', CityName: '城市', StreetName: '地址', TaxNumber1: '税号', TransportReference: '运输单号', PurchaseOrderDate: '订单日期',
  SupplierInvoiceID: '供应商发票号', PaymentBlockingReason: '付款冻结原因', InvoiceStatus: '处理状态', BusinessPlace: '业务地点', EmailAddress: '邮箱', PhoneNumber: '联系电话'
};
const detailFields = {
  purchaseOrders: [['订单抬头', ['PurchaseOrder', 'Supplier', 'CompanyCode', 'PurchasingOrganization', 'PurchaseOrderDate']], ['订单行项目', ['PurchaseOrderItem', 'Material', 'MaterialDescription', 'OrderQuantity', 'PurchaseOrderQuantityUnit', 'DeliveryDate', 'PurchaseOrderStatus']]],
  asns: [['发运通知', ['InbDelivery', 'PurchaseOrder', 'PurchaseOrderItem', 'DeliveryDate', 'OverallStatus', 'TransportReference']], ['物料信息', ['Material', 'MaterialDescription']]],
  materialDocuments: [['收货凭证', ['MaterialDocument', 'MaterialDocumentItem', 'PostingDate', 'GoodsMovementType', 'PurchaseOrder']], ['收货项目', ['Material', 'MaterialDescription', 'QuantityInEntryUnit', 'EntryUnit']]],
  invoices: [['发票抬头', ['SupplierInvoice', 'PurchaseOrder', 'DocumentDate', 'SupplierInvoiceStatus']], ['结算金额', ['InvoiceGrossAmount', 'DocumentCurrency', 'PaymentBlockingReason']]]
};
const profileGroups = [['基本资料', ['Supplier', 'SupplierName', 'BusinessPartner', 'CompanyCode', 'PurchasingOrganization']], ['联系信息', ['Country', 'CityName', 'StreetName', 'EmailAddress', 'PhoneNumber']], ['结算信息', ['PaymentTerms', 'TaxNumber1']]];
let currentRoute = 'dashboard'; let records = []; let vendorId = '—'; const selectedOrderLines = new Map();
const $ = (selector) => document.querySelector(selector); const page = $('#pageContainer');

document.querySelectorAll('.nav-item').forEach((item) => item.addEventListener('click', () => navigate(item.dataset.route)));
$('#refreshButton').addEventListener('click', () => navigate(currentRoute)); $('#menuButton').addEventListener('click', () => $('.app-shell').classList.toggle('nav-open'));
$('#closeDrawer').addEventListener('click', closeDrawer); $('#drawerOverlay').addEventListener('click', closeDrawer); $('#closeModal').addEventListener('click', closeModal); $('#cancelModal').addEventListener('click', closeModal); $('#asnForm').addEventListener('submit', submitAsn);

async function boot() {
  try { const [health, session] = await Promise.all([api('/api/health'), api('/api/session')]); vendorId = session.vendorId; $('#vendorScope').textContent = `供应商 ${vendorId}`; $('#vendorName').textContent = `供应商工作空间`; $('#connectionLabel').textContent = health.configured ? '已就绪' : '待配置'; } catch { $('#connectionLabel').textContent = '连接异常'; }
  navigate(location.hash.replace('#/', '') || 'dashboard');
}
function navigate(route) {
  currentRoute = resources[route] ? route : 'dashboard'; location.hash = `/${currentRoute}`; $('.app-shell').classList.remove('nav-open');
  document.querySelectorAll('.nav-item').forEach((item) => item.classList.toggle('active', item.dataset.route === currentRoute)); $('#breadcrumbPage').textContent = resources[currentRoute].title;
  if (resources[currentRoute].type === 'dashboard') renderDashboard(); else if (resources[currentRoute].type === 'analytics') renderAnalytics(); else if (currentRoute === 'suppliers') renderSupplierProfile(); else renderDocument(currentRoute);
}
window.addEventListener('hashchange', () => { const route = location.hash.replace('#/', ''); if (route && route !== currentRoute) navigate(route); });

function renderDashboard() {
  page.innerHTML = `<section class="dashboard-hero"><h1>今天，<span>优先处理什么？</span></h1><p>围绕采购订单、发运、收货和结算，处理当前供应商的实时协同事项。</p><div class="chain-progress"><b>采购执行全链路</b><div class="chain-steps">${[['✓', '订单确认', 'purchaseOrders'], ['2', '发运协同', 'asns'], ['3', '收货核验', 'materialDocuments'], ['4', '结算对账', 'invoices']].map(([n, title, route], i) => `<button class="chain-step ${i === 0 ? 'active' : ''}" data-go="${route}"><i>${n}</i><span>${title}</span></button>`).join('')}</div></div></section><section class="dashboard-grid"><div><article class="card"><header class="card-header"><h2>实时业务概览</h2><button class="link-button" data-go="analytics">查看数据看板 →</button></header><div id="dashboardKpis" class="dashboard-kpis"><div class="loading">正在汇总 SAP 业务数据…</div></div></article><article class="card"><header class="card-header"><h2>建议处理</h2></header><div id="dashboardTodos" class="todo-list"><div class="loading">正在生成待办…</div></div></article></div><aside class="supplier-summary"><header><span class="summary-mark">♧</span><div><small>当前供应商</small><h2>供应商 ${escape(vendorId)}</h2></div><span class="tag">已隔离</span></header><dl><div><dt>数据来源</dt><dd>SAP OData</dd></div><div><dt>访问范围</dt><dd>仅本供应商</dd></div><div><dt>订单协同</dt><dd>订单行级</dd></div><div><dt>ASN 创建</dt><dd>支持多行</dd></div></dl><button class="btn btn-secondary" data-go="suppliers">查看供应商资料</button></aside></section>`;
  bindGo(); loadDashboardData();
}
async function loadDashboardData() {
  try {
    const data = await api('/api/dashboard'); const metrics = data.metrics || [];
    $('#dashboardKpis').innerHTML = `<div class="dashboard-metrics">${metrics.map((metric) => `<button class="dashboard-metric" data-go="${metric.code === 'PO' ? 'purchaseOrders' : metric.code === 'ASN' ? 'asns' : metric.code === 'GR' ? 'materialDocuments' : 'invoices'}"><small>${escape(metric.code)}</small><b>${escape(metric.value)}</b><span>${escape(metric.label)}</span><em>${escape(metric.hint)}</em></button>`).join('')}</div>${renderIssues(data.dataIssues || [])}`;
    $('#dashboardTodos').innerHTML = metrics.map((metric) => todo(metric.code === 'PO' ? '▤' : metric.code === 'ASN' ? '↗' : metric.code === 'GR' ? '▣' : '▭', `${metric.label}：${metric.value} 条`, metric.hint, metric.code === 'PO' ? 'purchaseOrders' : metric.code === 'ASN' ? 'asns' : metric.code === 'GR' ? 'materialDocuments' : 'invoices', String(metric.value), 'order')).join('');
    bindGo(); $('#updateTime').textContent = '刚刚';
  } catch (error) { $('#dashboardKpis').innerHTML = `<div class="loading">数据看板读取失败：${escape(error.message)}</div>`; $('#dashboardTodos').innerHTML = `<div class="loading">请检查 SAP 服务配置后刷新。</div>`; }
}
function todo(icon, title, desc, go, count, tone) { return `<button class="todo-item" data-go="${go}"><i class="todo-icon ${tone}">${icon}</i><span><b>${escape(title)}</b><small>${escape(desc)}</small></span><em>${escape(count)}</em><strong>›</strong></button>`; }
function bindGo() { page.querySelectorAll('[data-go]').forEach((button) => button.addEventListener('click', () => navigate(button.dataset.go))); }
function renderIssues(issues) { return issues.length ? `<div class="data-issues"><b>部分数据暂不可用</b>${issues.map((issue) => `<span>${escape(issue.name)}：${escape(issue.message)}</span>`).join('')}</div>` : ''; }

async function renderSupplierProfile() {
  page.innerHTML = `<div class="page-header"><div><p>SUPPLIER MASTER DATA</p><h1>供应商资料</h1><span>当前登录供应商的 SAP 主数据；页面仅显示业务需要的资料。</span></div><div class="page-actions"><button class="btn btn-primary" id="profileRefresh">↻ 刷新数据</button></div></div><section id="supplierProfile" class="supplier-profile card"><div class="loading">正在读取 SAP 供应商主数据…</div></section>`;
  $('#profileRefresh').addEventListener('click', renderSupplierProfile);
  try {
    const payload = await api('/api/data/suppliers?top=10'); const supplier = (payload.records || []).find((row) => String(row.Supplier || row.BusinessPartner) === String(vendorId)) || payload.records?.[0];
    if (!supplier) { $('#supplierProfile').innerHTML = `<div class="loading">SAP 未返回当前供应商 ${escape(vendorId)} 的主数据。请核对 PORTAL_VENDOR_ID 与 SAP 供应商编码。</div>`; return; }
    const name = supplier.SupplierName || supplier.BusinessPartnerFullName || supplier.OrganizationBPName1 || `供应商 ${vendorId}`;
    $('#supplierProfile').innerHTML = `<header><div class="profile-avatar">供</div><div><small>SUPPLIER PROFILE</small><h2>${escape(name)}</h2><p>供应商编码：${escape(supplier.Supplier || supplier.BusinessPartner || vendorId)}</p></div><span class="tag">SAP 实时数据</span></header>${profileGroups.map(([group, fields]) => profileSection(group, fields, supplier)).join('')}`;
  } catch (error) { $('#supplierProfile').innerHTML = `<div class="loading">供应商主数据读取失败：${escape(error.message)}</div>`; }
}
function profileSection(group, fields, supplier) { const visible = fields.filter((field) => present(supplier[field])); return `<section><h3>${group}</h3>${visible.length ? `<dl class="profile-grid">${visible.map((field) => `<div><dt>${labels[field] || field}</dt><dd>${fieldValue(field, supplier[field])}</dd></div>`).join('')}</dl>` : '<p class="profile-empty">SAP 当前未提供该组资料。</p>'}</section>`; }

function renderDocument(route) {
  const resource = resources[route]; const actions = route === 'purchaseOrders' ? `<button class="btn btn-secondary" id="bulkAsn" disabled>基于已选 0 行创建 ASN</button>` : route === 'asns' ? `<button class="btn btn-secondary" id="newAsn">＋ 选择订单行创建 ASN</button>` : '';
  page.innerHTML = `<div class="page-header"><div><p>LIVE SAP QUERY</p><h1>${resource.title}</h1><span>${resource.description}</span></div><div class="page-actions">${actions}<button class="btn btn-secondary" id="exportBtn">⇩ 导出</button><button class="btn btn-primary" id="pageRefresh">↻ 刷新数据</button></div></div><section class="card"><div class="filter-bar"><label class="search-box">⌕ <input id="keyword" placeholder="输入单据号、物料或关键字查询" /></label><select id="statusFilter"><option value="">全部状态</option><option value="待确认">待确认</option><option value="已确认">已确认</option><option value="部分完成">部分完成</option><option value="已完成">已完成</option><option value="已取消">已取消</option></select><input id="dateFrom" type="date" title="开始日期" /><input id="dateTo" type="date" title="结束日期" /></div><div id="tableMeta" class="table-meta">正在读取 SAP 实时数据…</div><div class="table-scroll"><table class="data-table"><thead id="tableHead"></thead><tbody id="tableBody"><tr><td class="loading" colspan="8">正在读取 SAP 实时数据…</td></tr></tbody></table></div><footer id="tableFooter" class="table-footer"></footer></section>`;
  $('#pageRefresh').addEventListener('click', () => loadRecords(route)); $('#exportBtn').addEventListener('click', exportCsv); $('#keyword').addEventListener('keydown', (event) => { if (event.key === 'Enter') loadRecords(route); }); $('#statusFilter').addEventListener('change', () => renderTable(route)); ['dateFrom', 'dateTo'].forEach((id) => $(`#${id}`).addEventListener('change', () => renderTable(route)));
  if (route === 'purchaseOrders') $('#bulkAsn').addEventListener('click', () => openModal([...selectedOrderLines.values()])); if (route === 'asns') $('#newAsn').addEventListener('click', () => navigate('purchaseOrders'));
  loadRecords(route);
}
async function loadRecords(route) {
  try { const keyword = $('#keyword')?.value?.trim(); const payload = await api(`/api/data/${route}?top=100${keyword ? `&search=${encodeURIComponent(keyword)}` : ''}`); records = payload.records || []; $('#updateTime').textContent = '刚刚'; $('#tableMeta').textContent = `SAP 返回 ${payload.count ?? records.length} 条记录 · 已按供应商 ${vendorId} 隔离`; renderTable(route); } catch (error) { $('#tableMeta').textContent = `读取失败：${error.message}`; $('#tableBody').innerHTML = `<tr><td class="loading">未能读取 SAP 数据，请检查配置后刷新。</td></tr>`; }
}
function renderTable(route) {
  let shown = [...records]; const keyword = $('#keyword').value.trim().toLowerCase(); const status = $('#statusFilter').value; const from = $('#dateFrom').value; const to = $('#dateTo').value;
  if (keyword) shown = shown.filter((row) => JSON.stringify(row).toLowerCase().includes(keyword)); if (status) shown = shown.filter((row) => Object.entries(row).some(([key, val]) => /status/i.test(key) && displayStatus(val).includes(status))); if (from || to) shown = shown.filter((row) => { const date = Object.entries(row).find(([key]) => /date/i.test(key))?.[1]; const normalized = String(date || '').slice(0, 10); return !normalized || ((!from || normalized >= from) && (!to || normalized <= to)); });
  const keys = columns[route] || []; const selection = route === 'purchaseOrders' ? '<th class="select-col"><input id="selectAll" type="checkbox" aria-label="全选订单行" /></th>' : '';
  $('#tableHead').innerHTML = `<tr>${selection}${keys.map((key) => `<th>${escape(labels[key] || key)}</th>`).join('')}<th>操作</th></tr>`;
  $('#tableBody').innerHTML = shown.length ? shown.slice(0, 100).map((row, index) => `<tr>${route === 'purchaseOrders' ? `<td class="select-col"><input type="checkbox" data-select-line="${index}" ${selectedOrderLines.has(lineKey(row)) ? 'checked' : ''} aria-label="选择订单行" /></td>` : ''}${keys.map((key) => `<td>${cell(key, row[key])}</td>`).join('')}<td><button class="table-link" data-detail="${index}">查看详情</button>${route === 'purchaseOrders' ? ` <button class="table-link" data-create-asn="${index}">创建 ASN</button>` : ''}</td></tr>`).join('') : `<tr><td class="loading" colspan="${keys.length + 1 + (route === 'purchaseOrders' ? 1 : 0)}">暂无符合条件的数据。请核对 SAP 供应商编码与接口实体配置。</td></tr>`;
  $('#tableFooter').textContent = `共 ${shown.length} 条记录 · 已展示 ${Math.min(shown.length, 100)} 条`;
  page.querySelectorAll('[data-detail]').forEach((button) => button.addEventListener('click', () => openDrawer(shown[Number(button.dataset.detail)], route)));
  page.querySelectorAll('[data-create-asn]').forEach((button) => button.addEventListener('click', () => openModal([shown[Number(button.dataset.createAsn)]])));
  page.querySelectorAll('[data-select-line]').forEach((checkbox) => checkbox.addEventListener('change', () => { const row = shown[Number(checkbox.dataset.selectLine)]; checkbox.checked ? selectedOrderLines.set(lineKey(row), row) : selectedOrderLines.delete(lineKey(row)); updateBulkAsn(); }));
  const all = $('#selectAll'); if (all) { all.checked = shown.length > 0 && shown.every((row) => selectedOrderLines.has(lineKey(row))); all.addEventListener('change', () => { shown.forEach((row) => all.checked ? selectedOrderLines.set(lineKey(row), row) : selectedOrderLines.delete(lineKey(row))); renderTable(route); }); } updateBulkAsn();
}
function updateBulkAsn() { const button = $('#bulkAsn'); if (button) { const size = selectedOrderLines.size; button.disabled = size === 0; button.textContent = `基于已选 ${size} 行创建 ASN`; } }
function lineKey(row) { return `${value(row.PurchaseOrder)}:${value(row.PurchaseOrderItem)}`; }

async function renderAnalytics() {
  page.innerHTML = `<div class="page-header"><div><p>PURCHASE EXECUTION ANALYTICS</p><h1>数据看板</h1><span>采购执行、发运、收货与结算的实时分析</span></div></div><div id="analytics" class="analytics-loading">正在汇总 SAP 实时数据…</div>`;
  try { const data = await api('/api/dashboard'); $('#analytics').innerHTML = `<div class="kpi-grid">${(data.metrics || []).map((metric) => `<article class="kpi"><span>${escape(metric.code)}</span><b>${escape(metric.value)}</b><p>${escape(metric.label)}</p><small>${escape(metric.hint)}</small></article>`).join('')}</div>${renderIssues(data.dataIssues || [])}<div class="analytics-grid"><article class="card chart-card"><h2>订单执行状态</h2>${bars(data.orderStatus || [], 'blue')}</article><article class="card chart-card"><h2>发运协同状态</h2>${bars(data.asnStatus || [], 'green')}</article></div><article class="card advice"><h2>行动指引</h2><p>优先处理待确认订单和临近交期的行项目；按已选订单行创建 ASN，并结合收货凭证与发票完成结算核对。</p><button class="btn btn-primary" data-go="purchaseOrders">处理采购订单</button></article>`; bindGo(); } catch (error) { $('#analytics').textContent = `看板读取失败：${error.message}`; }
}
function bars(items, color) { if (!items.length) return '<p class="chart-empty">SAP 暂未返回可分析的状态数据。</p>'; const max = Math.max(1, ...items.map((item) => Number(item.value))); return `<div class="bars">${items.map((item) => `<div><span>${escape(displayStatus(item.label))}</span><i><b class="${color}" style="width:${Math.round(Number(item.value) / max * 100)}%"></b></i><em>${escape(item.value)}</em></div>`).join('')}</div>`; }

function openDrawer(record, route) {
  $('#drawerTitle').textContent = `${resources[route].title}详情`; const groups = detailFields[route] || [];
  $('#drawerBody').innerHTML = groups.map(([title, fields]) => { const visible = fields.filter((field) => present(record[field])); return visible.length ? `<section class="detail-section"><h3>${title}</h3><dl>${visible.map((field) => `<div><dt>${labels[field] || field}</dt><dd>${fieldValue(field, record[field])}</dd></div>`).join('')}</dl></section>` : ''; }).join('') || '<p class="chart-empty">SAP 未返回可展示的业务字段。</p>';
  $('#drawer').classList.add('open'); $('#drawerOverlay').classList.add('open');
}
function closeDrawer() { $('#drawer').classList.remove('open'); $('#drawerOverlay').classList.remove('open'); }
function openModal(orderLines) {
  const lines = (orderLines || []).filter((line) => present(line.PurchaseOrder) && present(line.PurchaseOrderItem)); if (!lines.length) { navigate('purchaseOrders'); return; }
  $('#asnForm').reset(); $('#asnLines').innerHTML = ''; $('#asnMessage').textContent = ''; const orderIds = [...new Set(lines.map((line) => value(line.PurchaseOrder)))];
  $('#modalTitle').textContent = `基于 ${lines.length} 个订单行创建 ASN`; $('#asnForm [name=purchaseOrder]').value = orderIds.length === 1 ? orderIds[0] : '多采购订单合并发运'; $('#asnForm [name=sourcePurchaseOrders]').value = orderIds.join(','); $('#asnForm [name=plannedDeliveryDate]').value = String(lines.map((line) => line.DeliveryDate).find(Boolean) || '').slice(0, 10); $('#asnForm [name=shippingDate]').value = new Date().toISOString().slice(0, 10);
  lines.forEach(addAsnLine); $('#modalOverlay').hidden = false;
}
function closeModal() { $('#modalOverlay').hidden = true; }
function addAsnLine(order) {
  const row = document.createElement('div'); row.className = 'line-row line-row-source'; row.dataset.purchaseOrder = value(order.PurchaseOrder); row.dataset.purchaseOrderItem = value(order.PurchaseOrderItem);
  row.innerHTML = `<input readonly aria-label="采购订单" value="${escapeAttr(value(order.PurchaseOrder))}" /><input readonly aria-label="行项目" value="${escapeAttr(value(order.PurchaseOrderItem))}" /><input readonly aria-label="物料" value="${escapeAttr(value(order.Material))}" /><input class="asn-quantity" required type="number" min="0.001" step="0.001" aria-label="发运数量" value="${escapeAttr(value(order.OrderQuantity))}" /><input readonly class="asn-unit" aria-label="单位" value="${escapeAttr(value(order.PurchaseOrderQuantityUnit || order.EntryUnit || 'EA'))}" />`;
  $('#asnLines').append(row);
}
async function submitAsn(event) {
  event.preventDefault(); const form = new FormData(event.currentTarget); const rows = [...$('#asnLines').children]; const sourcePurchaseOrders = [...new Set(rows.map((row) => row.dataset.purchaseOrder))];
  const items = rows.map((row) => ({ sourcePurchaseOrder: row.dataset.purchaseOrder, sourcePurchaseOrderItem: row.dataset.purchaseOrderItem, material: row.children[2].value, quantity: Number(row.querySelector('.asn-quantity').value), unit: row.querySelector('.asn-unit').value }));
  $('#asnMessage').textContent = '正在提交…'; try { await api('/api/asns', { method: 'POST', body: JSON.stringify({ purchaseOrder: sourcePurchaseOrders.length === 1 ? sourcePurchaseOrders[0] : '', sourcePurchaseOrders, shippingDate: form.get('shippingDate'), plannedDeliveryDate: form.get('plannedDeliveryDate'), transportReference: form.get('transportReference'), items }) }); $('#asnMessage').textContent = 'ASN 已提交 SAP。'; selectedOrderLines.clear(); setTimeout(closeModal, 700); } catch (error) { $('#asnMessage').textContent = `未提交：${error.message}`; }
}
function exportCsv() { if (!records.length) return; const keys = columns[currentRoute] || Object.keys(records[0]); const csv = '\ufeff' + [keys.map((key) => labels[key] || key), ...records.map((record) => keys.map((key) => value(record[key])))].map((row) => row.map((item) => `"${String(item).replaceAll('"', '""')}"`).join(',')).join('\n'); const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })); const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${resources[currentRoute].title}.csv`; anchor.click(); URL.revokeObjectURL(url); }
function cell(key, raw) { const text = fieldValue(key, raw); return /status/i.test(key) ? `<span class="status ${tone(displayStatus(raw))}">${text}</span>` : text; }
function fieldValue(key, raw) { if (!present(raw)) return '—'; if (/Date/.test(key)) return escape(String(raw).slice(0, 10)); if (/status/i.test(key)) return escape(displayStatus(raw)); return escape(value(raw)); }
function displayStatus(raw) { const text = value(raw); const status = { '01': '待确认', '1': '待确认', '02': '已确认', '2': '已确认', '03': '部分完成', '3': '部分完成', '04': '已完成', '4': '已完成', C: '已完成', X: '已取消', COMPLETED: '已完成', CANCELLED: '已取消' }; return status[text.toUpperCase()] || text; }
function tone(status) { return /逾期|异常|取消/.test(status) ? 'danger' : /待/.test(status) ? 'warning' : /完成|确认|收货|对账/.test(status) ? 'success' : 'info'; }
function present(value) { return value !== null && value !== undefined && value !== ''; } function value(value) { return !present(value) ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value); } function escape(value) { return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'); } function escapeAttr(value) { return escape(value === '—' ? '' : value); }
async function api(url, options = {}) { const response = await fetch(url, { ...options, headers: { accept: 'application/json', ...(options.body ? { 'content-type': 'application/json' } : {}), ...(options.headers || {}) } }); const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.message || `请求失败（${response.status}）`); return payload; }
boot();
