const REQUIRED_SERVICE_KEYS = [
  'SAP_BUSINESS_PARTNER_URL',
  'SAP_PURCHASE_ORDER_URL',
  'SAP_ASN_URL',
  'SAP_MATERIAL_DOCUMENT_URL',
  'SAP_SUPPLIER_INVOICE_URL'
];

export function readConfig(env = process.env) {
  const authMode = env.PORTAL_AUTH_MODE || 'single_vendor';
  const sapAuthMode = env.SAP_AUTH_MODE || 'basic';
  if (!['single_vendor', 'proxy_hmac'].includes(authMode)) {
    throw new Error('PORTAL_AUTH_MODE 仅支持 single_vendor 或 proxy_hmac。');
  }
  if (!['basic', 'bearer'].includes(sapAuthMode)) {
    throw new Error('SAP_AUTH_MODE 仅支持 basic 或 bearer。');
  }

  return {
    port: Number(env.PORT || 3000),
    portalAuthMode: authMode,
    staticVendorId: env.PORTAL_VENDOR_ID || '',
    identityHmacSecret: env.PORTAL_IDENTITY_HMAC_SECRET || '',
    sapAuthMode,
    sapUsername: env.SAP_USERNAME || '',
    sapPassword: env.SAP_PASSWORD || '',
    sapBearerToken: env.SAP_BEARER_TOKEN || '',
    services: {
      suppliers: service(env.SAP_BUSINESS_PARTNER_URL, env.SAP_BP_ENTITY || 'A_BusinessPartner', 'BusinessPartner'),
      purchaseOrders: service(env.SAP_PURCHASE_ORDER_URL, env.SAP_PO_ENTITY || 'PurchaseOrder', env.SAP_PO_SUPPLIER_FIELD),
      asns: service(env.SAP_ASN_URL, env.SAP_ASN_ENTITY || 'A_InbDeliveryHeader', env.SAP_ASN_SUPPLIER_FIELD),
      materialDocuments: service(env.SAP_MATERIAL_DOCUMENT_URL, env.SAP_MATERIAL_ENTITY || 'A_MaterialDocumentHeader', env.SAP_MATERIAL_SUPPLIER_FIELD),
      invoices: service(env.SAP_SUPPLIER_INVOICE_URL, env.SAP_INVOICE_ENTITY || 'A_SupplierInvoice', env.SAP_INVOICE_SUPPLIER_FIELD)
    },
    asnCreatePath: env.SAP_ASN_CREATE_PATH || '',
    asnCreateVendorField: env.SAP_ASN_CREATE_VENDOR_FIELD || ''
  };
}

function service(baseUrl, entity, supplierField) {
  return { baseUrl: (baseUrl || '').replace(/\/$/, ''), entity: entity || '', supplierField: supplierField || '' };
}

export function validateRuntimeConfig(config) {
  const missing = REQUIRED_SERVICE_KEYS.filter((key) => !process.env[key]);
  if (missing.length) return `缺少 SAP 服务地址：${missing.join('、')}`;
  if (config.sapAuthMode === 'basic' && (!config.sapUsername || !config.sapPassword)) {
    return '尚未配置 SAP_USERNAME 或 SAP_PASSWORD。';
  }
  if (config.sapAuthMode === 'bearer' && !config.sapBearerToken) return '尚未配置 SAP_BEARER_TOKEN。';
  if (config.portalAuthMode === 'single_vendor' && !config.staticVendorId) return '尚未配置 PORTAL_VENDOR_ID。';
  if (config.portalAuthMode === 'proxy_hmac' && !config.identityHmacSecret) return '尚未配置 PORTAL_IDENTITY_HMAC_SECRET。';
  return '';
}
