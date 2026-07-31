import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readConfig, validateRuntimeConfig } from './lib/config.mjs';
import { IdentityError, resolveVendorScope } from './lib/identity.mjs';
import { SapError, sapCreateAsn, sapGet } from './lib/sap-client.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const config = readConfig();
const resourceMap = {
  suppliers: { service: 'suppliers', searchField: 'BusinessPartner', orderBy: 'BusinessPartner asc' },
  purchaseOrders: { service: 'purchaseOrders', searchField: 'PurchaseOrder', orderBy: 'LastChangeDateTime desc' },
  asns: { service: 'asns', searchField: 'InbDelivery', orderBy: 'LastChangeDateTime desc' },
  materialDocuments: { service: 'materialDocuments', searchField: 'MaterialDocument', orderBy: 'LastChangeDateTime desc' },
  invoices: { service: 'invoices', searchField: 'SupplierInvoice', orderBy: 'LastChangeDateTime desc' }
};

http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host}`);
    if (url.pathname === '/api/health') return json(response, 200, { ok: true, configured: !validateRuntimeConfig(config), issue: validateRuntimeConfig(config) || undefined });
    if (url.pathname === '/api/session') {
      const scope = resolveVendorScope(request, config);
      return json(response, 200, { vendorId: scope.vendorId, identitySource: scope.source, storage: 'stateless_portal' });
    }
    if (url.pathname.startsWith('/api/data/') && request.method === 'GET') {
      const resource = resourceMap[url.pathname.split('/').pop()];
      if (!resource) return json(response, 404, { message: '未知资源。' });
      const issue = validateRuntimeConfig(config);
      if (issue) return json(response, 503, { message: issue });
      const scope = resolveVendorScope(request, config);
      const data = await sapGet(config.services[resource.service], scope.vendorId, config, {
        search: url.searchParams.get('search') || '',
        searchField: resource.searchField,
        top: url.searchParams.get('top') || '30',
        orderBy: resource.orderBy
      });
      return json(response, 200, { resource: resource.service, vendorId: scope.vendorId, records: data.records, count: data.records.length, retrievedAt: new Date().toISOString() });
    }
    if (url.pathname === '/api/asns' && request.method === 'POST') {
      const issue = validateRuntimeConfig(config);
      if (issue) return json(response, 503, { message: issue });
      const scope = resolveVendorScope(request, config);
      const input = await readJson(request);
      const data = await sapCreateAsn(config, scope.vendorId, input);
      return json(response, 201, { vendorId: scope.vendorId, result: data.raw });
    }
    if (request.method === 'GET') return serveStatic(url.pathname, response);
    return json(response, 405, { message: '不支持的请求方法。' });
  } catch (error) {
    const status = error instanceof IdentityError || error instanceof SapError ? error.status : 500;
    const message = error instanceof SyntaxError ? '请求体不是有效 JSON。' : error.message || '服务发生未知错误。';
    console.error(`[${new Date().toISOString()}] ${status}: ${message}`);
    return json(response, status, { message });
  }
}).listen(config.port, () => console.log(`SAP Vendor Portal 已启动：http://localhost:${config.port}`));

async function serveStatic(pathname, response) {
  const safePath = pathname === '/' ? '/index.html' : pathname;
  const filePath = path.resolve(root, 'public', `.${safePath}`);
  if (!filePath.startsWith(path.resolve(root, 'public'))) return json(response, 403, { message: '禁止访问。' });
  try {
    const content = await readFile(filePath);
    const type = filePath.endsWith('.css') ? 'text/css; charset=utf-8' : filePath.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8';
    response.writeHead(200, { 'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
    response.end(content);
  } catch {
    json(response, 404, { message: '页面不存在。' });
  }
}

function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  response.end(JSON.stringify(body));
}

async function readJson(request) {
  let text = '';
  for await (const chunk of request) {
    text += chunk;
    if (text.length > 100_000) throw new Error('请求体过大。');
  }
  return JSON.parse(text || '{}');
}
