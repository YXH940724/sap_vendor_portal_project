import test from 'node:test';
import assert from 'node:assert/strict';
import { buildScopedUrl, normalizeOData, SapError } from '../lib/sap-client.mjs';

test('buildScopedUrl 强制加入供应商范围并转义单引号', () => {
  const url = buildScopedUrl({ baseUrl: 'https://example.test/odata', entity: 'PurchaseOrder', supplierField: 'Supplier' }, "V'01", { top: 999 });
  assert.equal(url.pathname, '/odata/PurchaseOrder');
  assert.match(url.searchParams.get('$filter'), /Supplier eq 'V''01'/);
  assert.equal(url.searchParams.get('$top'), '100');
});

test('未配置供应商字段时拒绝构建 OData 请求', () => {
  assert.throws(() => buildScopedUrl({ baseUrl: 'https://example.test', entity: 'A_Test', supplierField: '' }, '1000'), SapError);
});

test('兼容 OData V2 与 V4 响应记录格式', () => {
  assert.deepEqual(normalizeOData({ value: [{ id: 'v4' }] }), [{ id: 'v4' }]);
  assert.deepEqual(normalizeOData({ d: { results: [{ id: 'v2' }] } }), [{ id: 'v2' }]);
});
