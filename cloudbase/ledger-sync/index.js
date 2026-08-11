'use strict';

const crypto = require('crypto');
const cloudbase = require('@cloudbase/node-sdk');
const {
  LedgerError,
  applySettlement,
  applySettlementReversal,
  applyWorkerPurge,
  mergeBackups,
  parseBackupJson,
} = require('./ledger');

const COLLECTION = process.env.LEDGER_COLLECTION || 'family_ledger_sync';
const DOCUMENT_ID = 'main';
const MAX_BODY_CHARS = 20 * 1024 * 1024;
const app = cloudbase.init({ env: cloudbase.SYMBOL_CURRENT_ENV });
const db = app.database();

function response(statusCode, value) {
  return {
    statusCode,
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(value),
  };
}

function header(event, name) {
  const headers = event.headers || {};
  const wanted = name.toLowerCase();
  const key = Object.keys(headers).find((candidate) => candidate.toLowerCase() === wanted);
  return key ? String(headers[key]) : '';
}

function sha256(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function verifySyncKey(event) {
  const expected = String(process.env.LEDGER_SYNC_KEY_SHA256 || '').toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(expected)) throw new LedgerError('云端尚未配置家庭同步码', 503);
  const actual = sha256(header(event, 'x-ledger-sync-key'));
  const expectedBuffer = Buffer.from(expected, 'hex');
  const actualBuffer = Buffer.from(actual, 'hex');
  if (!crypto.timingSafeEqual(expectedBuffer, actualBuffer)) throw new LedgerError('家庭同步码不正确', 401);
}

function parseEventBody(event) {
  const raw = typeof event.body === 'string' ? event.body : JSON.stringify(event.body || event);
  if (raw.length > MAX_BODY_CHARS) throw new LedgerError('账本数据过大', 413);
  try {
    return JSON.parse(raw);
  } catch (_) {
    throw new LedgerError('请求内容不是有效的 JSON');
  }
}

function documentData(result) {
  if (!result || result.data == null) return null;
  return Array.isArray(result.data) ? (result.data[0] || null) : result.data;
}

async function updateLedger(payload) {
  if (payload.protocolVersion !== 1) throw new LedgerError('同步协议版本不支持');
  const incoming = parseBackupJson(payload.backupJson);
  const transactionResult = await db.runTransaction(async (transaction) => {
    const reference = transaction.collection(COLLECTION).doc(DOCUMENT_ID);
    let stored = null;
    try {
      stored = documentData(await reference.get());
    } catch (error) {
      if (!String(error && error.message).toLowerCase().includes('not exist')) throw error;
    }
    const current = stored && typeof stored.backupJson === 'string' ? parseBackupJson(stored.backupJson) : null;
    let merged = mergeBackups(current, incoming);
    if (payload.action === 'settle') merged = applySettlement(merged, payload);
    else if (payload.action === 'reverseSettlement') merged = applySettlementReversal(merged, payload);
    else if (payload.action === 'purgeWorker') merged = applyWorkerPurge(merged, payload);
    else if (payload.action !== 'sync') throw new LedgerError('未知同步操作');
    const backupJson = JSON.stringify(merged);
    const serverVersion = Number(stored && stored.serverVersion || 0) + 1;
    await reference.set({
      data: {
        backupJson,
        serverVersion,
        updatedAt: Date.now(),
      },
    });
    return { backupJson, serverVersion };
  });
  return transactionResult && transactionResult.result ? transactionResult.result : transactionResult;
}

exports.main = async (event) => {
  try {
    if (event.httpMethod && event.httpMethod !== 'POST') return response(405, { ok: false, message: '只支持 POST 请求' });
    verifySyncKey(event);
    const payload = parseEventBody(event);
    const result = await updateLedger(payload);
    return response(200, { ok: true, ...result });
  } catch (error) {
    const statusCode = error instanceof LedgerError ? error.statusCode : 500;
    if (statusCode >= 500) console.error('ledger-sync failed', error && error.stack ? error.stack : error);
    return response(statusCode, {
      ok: false,
      message: statusCode >= 500 && !(error instanceof LedgerError) ? '云端同步暂时不可用' : error.message,
    });
  }
};
