function escapeOData(value) {
  return String(value).replaceAll("'", "''");
}

export function normalizeOData(payload) {
  if (Array.isArray(payload?.value)) return payload.value;
  if (Array.isArray(payload?.d?.results)) return payload.d.results;
  if (payload?.d && typeof payload.d === 'object') return [payload.d];
  return payload?.value ? [payload.value] : [];
}

export function buildScopedUrl(service, vendorId, query = {}) {
  if (!service.baseUrl || !service.entity || !service.supplierField) {
    throw new SapError('该业务服务尚未配置实体集或供应商字段；为防止越权查询，已拒绝调用。', 503);
  }
  const url = new URL(`${service.baseUrl}/${service.entity}`);
  const filters = [`${service.supplierField} eq '${escapeOData(vendorId)}'`];
  if (query.searchField && query.search) filters.push(`contains(${query.searchField},'${escapeOData(query.search)}')`);
  url.searchParams.set('$filter', filters.join(' and '));
  url.searchParams.set('$top', String(Math.min(Math.max(Number(query.top) || 30, 1), 100)));
  url.searchParams.set('$orderby', query.orderBy || 'LastChangeDateTime desc');
  return url;
}

export async function sapGet(service, vendorId, config, query) {
  const url = buildScopedUrl(service, vendorId, query);
  const response = await fetch(url, { headers: sapHeaders(config, { accept: 'application/json' }), signal: AbortSignal.timeout(15000) });
  return handleResponse(response);
}

export async function sapCreateAsn(config, vendorId, input) {
  if (!config.asnCreatePath || !config.asnCreateVendorField) {
    throw new SapError('尚未配置 SAP_ASN_CREATE_PATH 和 SAP_ASN_CREATE_VENDOR_FIELD，不能创建 ASN。', 503);
  }
  const base = config.services.asns.baseUrl;
  if (!base) throw new SapError('ASN 服务地址未配置。', 503);
  const csrfResponse = await fetch(base, { headers: sapHeaders(config, { 'x-csrf-token': 'fetch' }), signal: AbortSignal.timeout(15000) });
  if (!csrfResponse.ok) throw await responseError(csrfResponse);
  const csrfToken = csrfResponse.headers.get('x-csrf-token');
  if (!csrfToken) throw new SapError('SAP 未返回 CSRF Token，不能创建 ASN。', 502);
  const body = { ...input, [config.asnCreateVendorField]: vendorId };
  const response = await fetch(`${base}/${config.asnCreatePath.replace(/^\//, '')}`, {
    method: 'POST',
    headers: sapHeaders(config, { 'content-type': 'application/json', accept: 'application/json', 'x-csrf-token': csrfToken }),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000)
  });
  return handleResponse(response);
}

function sapHeaders(config, extra = {}) {
  const authorization = config.sapAuthMode === 'bearer'
    ? `Bearer ${config.sapBearerToken}`
    : `Basic ${Buffer.from(`${config.sapUsername}:${config.sapPassword}`).toString('base64')}`;
  return { authorization, ...extra };
}

async function handleResponse(response) {
  if (!response.ok) throw await responseError(response);
  const payload = await response.json();
  return { records: normalizeOData(payload), raw: payload };
}

async function responseError(response) {
  let detail = '';
  try {
    const body = await response.json();
    detail = body?.error?.message?.value || body?.error?.message || body?.error?.details?.[0]?.message || '';
  } catch {
    detail = await response.text().catch(() => '');
  }
  return new SapError(detail || `SAP OData 返回 HTTP ${response.status}。`, response.status);
}

export class SapError extends Error {
  constructor(message, status = 502) {
    super(message);
    this.status = status;
  }
}
