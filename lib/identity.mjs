import crypto from 'node:crypto';

export function resolveVendorScope(request, config) {
  if (config.portalAuthMode === 'single_vendor') {
    if (!config.staticVendorId) throw new IdentityError('服务器未配置供应商范围。', 503);
    return { vendorId: config.staticVendorId, source: 'server_environment' };
  }

  const vendorId = request.headers['x-portal-vendor-id'];
  const timestamp = request.headers['x-portal-timestamp'];
  const signature = request.headers['x-portal-signature'];
  if (!vendorId || !timestamp || !signature) throw new IdentityError('身份代理未提供供应商范围签名。', 401);
  if (!/^\d{10,13}$/.test(timestamp)) throw new IdentityError('身份代理时间戳格式无效。', 401);
  if (Math.abs(Date.now() - Number(timestamp)) > 5 * 60 * 1000) throw new IdentityError('身份代理签名已过期。', 401);

  const expected = crypto.createHmac('sha256', config.identityHmacSecret).update(`${vendorId}.${timestamp}`).digest('hex');
  const provided = Buffer.from(signature, 'hex');
  const expectedBuffer = Buffer.from(expected, 'hex');
  if (provided.length !== expectedBuffer.length || !crypto.timingSafeEqual(provided, expectedBuffer)) {
    throw new IdentityError('身份代理签名无效。', 401);
  }
  return { vendorId, source: 'signed_identity_proxy' };
}

export class IdentityError extends Error {
  constructor(message, status = 401) {
    super(message);
    this.status = status;
  }
}
