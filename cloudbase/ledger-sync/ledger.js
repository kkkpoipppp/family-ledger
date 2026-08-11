'use strict';

const FORMAT = 'family-ledger-backup';
const SUPPORTED_SCHEMA = 3;
const MAX_RECORDS_PER_TABLE = 50000;
const ENTRY_TYPES = new Set(['WORK', 'ADVANCE', 'PAYMENT', 'ADJUSTMENT']);
const GARMENT_TYPES = new Set(['上衣', '裤子', '其他']);
const LENGTH_TYPES = new Set(['长款', '短款', '不区分']);

class LedgerError extends Error {
  constructor(message, statusCode = 400) {
    super(message);
    this.name = 'LedgerError';
    this.statusCode = statusCode;
  }
}

function parseBackupJson(value) {
  if (typeof value !== 'string' || value.length === 0) throw new LedgerError('缺少账本数据');
  let backup;
  try {
    backup = JSON.parse(value);
  } catch (_) {
    throw new LedgerError('账本数据不是有效的 JSON');
  }
  normalizeBackupShape(backup);
  validateBackup(backup);
  return backup;
}

function normalizeBackupShape(backup) {
  if (!isPlainObject(backup) || !Number.isInteger(backup.schemaVersion)) return;
  if (backup.schemaVersion < 2 && backup.settlementLines === undefined) backup.settlementLines = [];
  if (backup.schemaVersion < 3 && backup.purgedWorkers === undefined) backup.purgedWorkers = [];
}

function validateBackup(backup) {
  if (!isPlainObject(backup) || backup.format !== FORMAT) throw new LedgerError('账本格式不正确');
  if (!Number.isInteger(backup.schemaVersion) || backup.schemaVersion < 1 || backup.schemaVersion > SUPPORTED_SCHEMA) {
    throw new LedgerError('账本版本暂不支持');
  }
  requireSafeInteger(backup.exportedAt, '账本导出时间无效', 0);
  const idSets = {};
  for (const table of ['workers', 'workItems', 'entries', 'settlements', 'settlementLines', 'purgedWorkers']) {
    if (!Array.isArray(backup[table])) throw new LedgerError(`账本缺少 ${table}`);
    if (backup[table].length > MAX_RECORDS_PER_TABLE) throw new LedgerError(`${table} 记录过多`);
    const ids = new Set();
    for (const record of backup[table]) {
      if (!isPlainObject(record)) throw new LedgerError(`${table} 中有无效记录`);
      requireText(record.id, `${table} 中有无效编号`, 200);
      if (ids.has(record.id)) throw new LedgerError(`${table} 中有重复编号`);
      ids.add(record.id);
    }
    idSets[table] = ids;
  }

  for (const worker of backup.workers) validateWorker(worker);
  for (const workItem of backup.workItems) validateWorkItem(workItem);
  for (const entry of backup.entries) validateEntry(entry);
  for (const settlement of backup.settlements) validateSettlement(settlement);
  for (const line of backup.settlementLines) validateSettlementLine(line);
  for (const purgedWorker of backup.purgedWorkers) validatePurgedWorker(purgedWorker);

  for (const entry of backup.entries) {
    requireReference(idSets.workers, entry.workerId, '流水缺少对应工人');
    if (entry.workItemId != null) requireReference(idSets.workItems, entry.workItemId, '流水缺少对应做工项目');
    if (entry.settlementId != null) requireReference(idSets.settlements, entry.settlementId, '流水缺少对应结算');
  }
  for (const settlement of backup.settlements) {
    requireReference(idSets.workers, settlement.workerId, '结算缺少对应工人');
    const payment = backup.entries.find((entry) =>
      entry.settlementId === settlement.id && entry.entryType === 'PAYMENT' && entry.isDeleted === false
    );
    if (!payment || payment.workerId !== settlement.workerId || payment.amountMicros !== -settlement.settledPaymentMicros) {
      throw new LedgerError('结算缺少对应的付款流水');
    }
    if (settlement.reversedAt != null) {
      const reversal = backup.entries.find((entry) => entry.id === settlement.reversalEntryId);
      if (!reversal || reversal.entryType !== 'ADJUSTMENT' || reversal.settlementId !== settlement.id ||
          reversal.workerId !== settlement.workerId || reversal.amountMicros !== settlement.settledPaymentMicros ||
          reversal.isDeleted !== false) {
        throw new LedgerError('已撤销结算缺少对应的冲正流水');
      }
    }
  }
  for (const line of backup.settlementLines) {
    requireReference(idSets.settlements, line.settlementId, '结算明细缺少对应结算');
    if (line.workItemId != null) requireReference(idSets.workItems, line.workItemId, '结算明细缺少对应做工项目');
  }
  for (const purgedWorker of backup.purgedWorkers) {
    if (idSets.workers.has(purgedWorker.id) || backup.entries.some((entry) => entry.workerId === purgedWorker.id) ||
        backup.settlements.some((settlement) => settlement.workerId === purgedWorker.id)) {
      throw new LedgerError('账本仍包含已永久删除工人的资料');
    }
  }
}

function isPlainObject(value) {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function requireText(value, message, maxLength = 500, allowEmpty = false) {
  if (typeof value !== 'string' || value.length > maxLength || (!allowEmpty && value.trim().length === 0)) {
    throw new LedgerError(message);
  }
  return value;
}

function requireOptionalText(value, message, maxLength = 5000) {
  if (value === undefined) return '';
  return requireText(value, message, maxLength, true);
}

function requireNullableText(value, message, maxLength = 200) {
  if (value === null) return null;
  return requireText(value, message, maxLength);
}

function requireSafeInteger(value, message, min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new LedgerError(message);
  return value;
}

function requireBoolean(value, message) {
  if (typeof value !== 'boolean') throw new LedgerError(message);
  return value;
}

function requireReference(ids, value, message) {
  if (!ids.has(value)) throw new LedgerError(message);
}

function validateMutableMetadata(record, label) {
  requireSafeInteger(record.createdAt, `${label}创建时间无效`, 0);
  requireSafeInteger(record.updatedAt, `${label}更新时间无效`, record.createdAt);
  requireSafeInteger(record.revision, `${label}修订号无效`, 1);
  requireText(record.originDeviceId, `${label}设备编号无效`, 200);
  requireBoolean(record.isDeleted, `${label}删除状态无效`);
}

function validateWorker(worker) {
  requireText(worker.name, '工人姓名无效');
  requireOptionalText(worker.note, '工人备注无效');
  validateMutableMetadata(worker, '工人');
}

function validateWorkItem(item) {
  requireText(item.name, '做工项目名称无效');
  if (!GARMENT_TYPES.has(item.garmentType)) throw new LedgerError('做工项目服装类型无效');
  if (!LENGTH_TYPES.has(item.lengthType)) throw new LedgerError('做工项目长短类型无效');
  requireOptionalText(item.processName, '做工项目工序无效');
  requireText(item.unit, '做工项目单位无效', 100);
  requireSafeInteger(item.defaultUnitPriceMicros, '做工项目单价无效', 0);
  requireSafeInteger(item.sortOrder, '做工项目排序无效', -2147483648, 2147483647);
  validateMutableMetadata(item, '做工项目');
}

function validateEntry(entry) {
  requireText(entry.workerId, '流水工人编号无效', 200);
  if (!ENTRY_TYPES.has(entry.entryType)) throw new LedgerError('流水类型无效');
  requireSafeInteger(entry.entryEpochDay, '流水日期无效');
  requireText(entry.description, '流水说明无效');
  requireNullableText(entry.workItemId, '流水做工项目编号无效');
  requireOptionalText(entry.garmentTypeSnapshot, '流水服装快照无效');
  requireOptionalText(entry.lengthTypeSnapshot, '流水长短快照无效');
  requireOptionalText(entry.processNameSnapshot, '流水工序快照无效');
  if (entry.quantity !== null) requireSafeInteger(entry.quantity, '流水数量无效', 1);
  requireOptionalText(entry.unitSnapshot, '流水单位快照无效', 100);
  requireSafeInteger(entry.unitPriceMicros, '流水单价无效', 0);
  requireSafeInteger(entry.amountMicros, '流水金额无效');
  requireOptionalText(entry.note, '流水备注无效');
  requireNullableText(entry.settlementId, '流水结算编号无效');
  validateMutableMetadata(entry, '流水');

  if (entry.entryType === 'WORK') {
    if (entry.workItemId == null || entry.quantity == null || entry.unitPriceMicros <= 0 ||
        entry.unitSnapshot.trim().length === 0 || entry.garmentTypeSnapshot.trim().length === 0 ||
        entry.lengthTypeSnapshot.trim().length === 0) {
      throw new LedgerError('做工流水字段不完整');
    }
    const calculated = entry.quantity * entry.unitPriceMicros;
    if (!Number.isSafeInteger(calculated) || calculated !== entry.amountMicros || calculated <= 0) {
      throw new LedgerError('做工流水金额与数量单价不一致');
    }
    if (entry.settlementId != null) throw new LedgerError('做工流水不能关联结算');
  } else {
    if (entry.workItemId != null || entry.quantity != null || entry.unitPriceMicros !== 0) {
      throw new LedgerError('非做工流水包含无效计件字段');
    }
    if ((entry.entryType === 'ADVANCE' || entry.entryType === 'PAYMENT') && entry.amountMicros >= 0) {
      throw new LedgerError('预支或付款金额方向无效');
    }
    if (entry.entryType === 'ADVANCE' && entry.settlementId != null) throw new LedgerError('预支流水不能关联结算');
    if (entry.entryType === 'PAYMENT' && entry.settlementId == null) throw new LedgerError('付款流水缺少对应结算');
    if (entry.entryType === 'ADJUSTMENT' && entry.amountMicros === 0) throw new LedgerError('调整流水金额无效');
  }
}

function validateSettlement(settlement) {
  requireText(settlement.workerId, '结算工人编号无效', 200);
  requireSafeInteger(settlement.startEpochDay, '结算开始日期无效');
  requireSafeInteger(settlement.endEpochDay, '结算结束日期无效');
  if (settlement.endEpochDay < settlement.startEpochDay) throw new LedgerError('结算日期区间无效');
  for (const key of ['earnedMicros', 'advancesMicros', 'paymentsMicros']) {
    requireSafeInteger(settlement[key], '结算汇总金额无效', 0);
  }
  requireSafeInteger(settlement.adjustmentsMicros, '结算调整金额无效');
  requireSafeInteger(settlement.balanceMicros, '结算余额无效', 1);
  requireSafeInteger(settlement.settledPaymentMicros, '结算付款金额无效', 1);
  const calculated = settlement.earnedMicros - settlement.advancesMicros - settlement.paymentsMicros + settlement.adjustmentsMicros;
  if (!Number.isSafeInteger(calculated) || calculated !== settlement.balanceMicros ||
      settlement.settledPaymentMicros !== settlement.balanceMicros) {
    throw new LedgerError('结算汇总金额不一致');
  }
  if (settlement.reversedAt === null) {
    requireOptionalText(settlement.reversalReason, '结算撤销原因无效');
    if (settlement.reversalReason !== '' || settlement.reversalEntryId !== null) {
      throw new LedgerError('未撤销结算包含撤销信息');
    }
  } else {
    requireSafeInteger(settlement.reversedAt, '结算撤销时间无效', 0);
    requireText(settlement.reversalReason, '结算撤销原因无效');
    requireNullableText(settlement.reversalEntryId, '结算撤销流水编号无效');
  }
  validateMutableMetadata(settlement, '结算');
}

function validateSettlementLine(line) {
  requireText(line.settlementId, '结算明细结算编号无效', 200);
  requireNullableText(line.workItemId, '结算明细做工项目编号无效');
  requireText(line.description, '结算明细说明无效');
  requireOptionalText(line.garmentTypeSnapshot, '结算明细服装快照无效');
  requireOptionalText(line.lengthTypeSnapshot, '结算明细长短快照无效');
  requireOptionalText(line.processNameSnapshot, '结算明细工序快照无效');
  requireSafeInteger(line.quantity, '结算明细数量无效', 1);
  requireText(line.unitSnapshot, '结算明细单位无效', 100);
  requireSafeInteger(line.amountMicros, '结算明细金额无效', 1);
}

function validatePurgedWorker(record) {
  requireSafeInteger(record.purgedAt, '永久删除时间无效', 0);
  requireText(record.originDeviceId, '永久删除设备编号无效', 200);
}

function safeAdd(left, right, message) {
  const result = left + right;
  if (!Number.isSafeInteger(result)) throw new LedgerError(message);
  return result;
}

function compareMutableRecords(left, right) {
  for (const key of ['revision', 'updatedAt']) {
    const a = Number(left[key] || 0);
    const b = Number(right[key] || 0);
    if (a !== b) return a > b ? 1 : -1;
  }
  const aOrigin = String(left.originDeviceId || '');
  const bOrigin = String(right.originDeviceId || '');
  return aOrigin.localeCompare(bOrigin);
}

function mergeMutable(serverRecords, incomingRecords) {
  const merged = new Map(serverRecords.map((record) => [record.id, record]));
  for (const candidate of incomingRecords) {
    const current = merged.get(candidate.id);
    if (!current || compareMutableRecords(candidate, current) > 0) merged.set(candidate.id, candidate);
  }
  return [...merged.values()];
}

function mergeImmutable(serverRecords, incomingRecords) {
  const merged = new Map(serverRecords.map((record) => [record.id, record]));
  for (const candidate of incomingRecords) if (!merged.has(candidate.id)) merged.set(candidate.id, candidate);
  return [...merged.values()];
}

function mergePurgedWorkers(serverRecords, incomingRecords) {
  const merged = new Map(serverRecords.map((record) => [record.id, record]));
  for (const candidate of incomingRecords) {
    const current = merged.get(candidate.id);
    if (!current || candidate.purgedAt > current.purgedAt ||
        (candidate.purgedAt === current.purgedAt && candidate.originDeviceId > current.originDeviceId)) {
      merged.set(candidate.id, candidate);
    }
  }
  return [...merged.values()];
}

function applyWorkerPurges(backup) {
  const purgedIds = new Set(backup.purgedWorkers.map((record) => record.id));
  if (purgedIds.size === 0) return backup;
  const removedSettlementIds = new Set(
    backup.settlements.filter((settlement) => purgedIds.has(settlement.workerId)).map((settlement) => settlement.id),
  );
  backup.workers = backup.workers.filter((worker) => !purgedIds.has(worker.id));
  backup.entries = backup.entries.filter((entry) => !purgedIds.has(entry.workerId));
  backup.settlements = backup.settlements.filter((settlement) => !purgedIds.has(settlement.workerId));
  backup.settlementLines = backup.settlementLines.filter((line) => !removedSettlementIds.has(line.settlementId));
  return backup;
}

function emptyBackup() {
  return {
    format: FORMAT,
    schemaVersion: SUPPORTED_SCHEMA,
    exportedAt: Date.now(),
    workers: [],
    workItems: [],
    entries: [],
    settlements: [],
    settlementLines: [],
    purgedWorkers: [],
  };
}

function mergeBackups(serverBackup, incomingBackup) {
  const server = serverBackup || emptyBackup();
  validateBackup(server);
  validateBackup(incomingBackup);
  const merged = {
    format: FORMAT,
    schemaVersion: Math.max(server.schemaVersion, incomingBackup.schemaVersion),
    exportedAt: Date.now(),
    workers: mergeMutable(server.workers, incomingBackup.workers),
    workItems: mergeMutable(server.workItems, incomingBackup.workItems),
    entries: mergeMutable(server.entries, incomingBackup.entries),
    settlements: mergeMutable(server.settlements, incomingBackup.settlements),
    settlementLines: mergeImmutable(server.settlementLines, incomingBackup.settlementLines),
    purgedWorkers: mergePurgedWorkers(server.purgedWorkers, incomingBackup.purgedWorkers),
  };
  applyWorkerPurges(merged);
  validateBackup(merged);
  return merged;
}

function applyWorkerPurge(backup, request, now = Date.now()) {
  validateBackup(backup);
  const workerId = requireString(request.workerId, '工人编号无效');
  const deviceId = requireString(request.deviceId, '设备编号无效');
  const existing = backup.purgedWorkers.find((record) => record.id === workerId);
  if (!existing) {
    const worker = backup.workers.find((record) => record.id === workerId);
    if (!worker) throw new LedgerError('找不到要永久删除的工人', 409);
    backup.purgedWorkers.push({ id: workerId, purgedAt: now, originDeviceId: deviceId });
  }
  applyWorkerPurges(backup);
  backup.schemaVersion = SUPPORTED_SCHEMA;
  backup.exportedAt = now;
  validateBackup(backup);
  return backup;
}

function requireString(value, message) {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > 500) throw new LedgerError(message);
  return value.trim();
}

function requireEpochDay(value, message) {
  if (!Number.isSafeInteger(value)) throw new LedgerError(message);
  return value;
}

function activeEntriesForRange(backup, workerId, startEpochDay, endEpochDay) {
  return backup.entries.filter((entry) =>
    entry.workerId === workerId &&
    entry.isDeleted !== true &&
    Number.isSafeInteger(entry.entryEpochDay) &&
    entry.entryEpochDay >= startEpochDay &&
    entry.entryEpochDay <= endEpochDay
  );
}

function formatEpochDay(epochDay) {
  return new Date(epochDay * 86400000).toISOString().slice(0, 10);
}

function applySettlement(backup, request, now = Date.now()) {
  validateBackup(backup);
  const operationId = requireString(request.operationId, '结算操作编号无效');
  const workerId = requireString(request.workerId, '工人编号无效');
  const startEpochDay = requireEpochDay(request.startEpochDay, '开始日期无效');
  const endEpochDay = requireEpochDay(request.endEpochDay, '结束日期无效');
  const deviceId = requireString(request.deviceId, '设备编号无效');
  if (endEpochDay < startEpochDay) throw new LedgerError('结束日期不能早于开始日期');

  const existing = backup.settlements.find((item) => item.id === operationId);
  if (existing) {
    if (existing.workerId !== workerId || existing.startEpochDay !== startEpochDay || existing.endEpochDay !== endEpochDay) {
      throw new LedgerError('这个操作编号已经用于其他结算', 409);
    }
    return backup;
  }
  const worker = backup.workers.find((item) => item.id === workerId && item.isDeleted !== true);
  if (!worker) throw new LedgerError('找不到这位在职工人', 409);

  const entries = activeEntriesForRange(backup, workerId, startEpochDay, endEpochDay);
  const totals = {
    earnedMicros: 0,
    advancesMicros: 0,
    paymentsMicros: 0,
    adjustmentsMicros: 0,
    balanceMicros: 0,
  };
  for (const entry of entries) {
    totals.balanceMicros = safeAdd(totals.balanceMicros, entry.amountMicros, '结算余额超出范围');
    if (entry.entryType === 'WORK') {
      totals.earnedMicros = safeAdd(totals.earnedMicros, entry.amountMicros, '结算做工金额超出范围');
    } else if (entry.entryType === 'ADVANCE') {
      totals.advancesMicros = safeAdd(totals.advancesMicros, -entry.amountMicros, '结算预支金额超出范围');
    } else if (entry.entryType === 'PAYMENT') {
      totals.paymentsMicros = safeAdd(totals.paymentsMicros, -entry.amountMicros, '结算付款金额超出范围');
    } else if (entry.entryType === 'ADJUSTMENT') {
      totals.adjustmentsMicros = safeAdd(totals.adjustmentsMicros, entry.amountMicros, '结算调整金额超出范围');
    }
  }
  if (totals.balanceMicros <= 0) throw new LedgerError('这个日期区间没有需要支付的余额', 409);

  const summary = new Map();
  for (const entry of entries.filter((item) => item.entryType === 'WORK')) {
    const key = [
      entry.workItemId || '', entry.description || '', entry.garmentTypeSnapshot || '',
      entry.lengthTypeSnapshot || '', entry.processNameSnapshot || '', entry.unitSnapshot || '',
    ].join('\u001f');
    const current = summary.get(key) || {
      workItemId: entry.workItemId || null,
      description: entry.description || '',
      garmentTypeSnapshot: entry.garmentTypeSnapshot || '',
      lengthTypeSnapshot: entry.lengthTypeSnapshot || '',
      processNameSnapshot: entry.processNameSnapshot || '',
      unitSnapshot: entry.unitSnapshot || '',
      quantity: 0,
      amountMicros: 0,
    };
    current.quantity = safeAdd(current.quantity, entry.quantity, '结算数量超出范围');
    current.amountMicros = safeAdd(current.amountMicros, entry.amountMicros, '结算明细金额超出范围');
    summary.set(key, current);
  }

  backup.settlements.push({
    id: operationId,
    workerId,
    startEpochDay,
    endEpochDay,
    ...totals,
    settledPaymentMicros: totals.balanceMicros,
    reversedAt: null,
    reversalReason: '',
    reversalEntryId: null,
    createdAt: now,
    updatedAt: now,
    revision: 1,
    originDeviceId: deviceId,
    isDeleted: false,
  });
  [...summary.values()].forEach((line, index) => {
    backup.settlementLines.push({
      id: `${operationId}-line-${index}`,
      settlementId: operationId,
      ...line,
    });
  });
  backup.entries.push({
    id: `${operationId}-payment`,
    workerId,
    entryType: 'PAYMENT',
    entryEpochDay: endEpochDay,
    description: '工资结清',
    workItemId: null,
    garmentTypeSnapshot: '',
    lengthTypeSnapshot: '',
    processNameSnapshot: '',
    quantity: null,
    unitSnapshot: '',
    unitPriceMicros: 0,
    amountMicros: -totals.balanceMicros,
    note: `${formatEpochDay(startEpochDay)} 至 ${formatEpochDay(endEpochDay)}`,
    settlementId: operationId,
    createdAt: now,
    updatedAt: now,
    revision: 1,
    originDeviceId: deviceId,
    isDeleted: false,
  });
  backup.exportedAt = now;
  validateBackup(backup);
  return backup;
}

function applySettlementReversal(backup, request, now = Date.now()) {
  validateBackup(backup);
  const operationId = requireString(request.operationId, '撤销操作编号无效');
  const settlementId = requireString(request.settlementId, '结算编号无效');
  const reason = requireString(request.reason, '请填写撤销原因');
  const deviceId = requireString(request.deviceId, '设备编号无效');
  const settlement = backup.settlements.find((item) => item.id === settlementId && item.isDeleted !== true);
  if (!settlement) throw new LedgerError('找不到这笔结算', 409);
  if (settlement.reversedAt != null) {
    if (settlement.reversalEntryId === operationId) return backup;
    throw new LedgerError('这笔结算已经撤销', 409);
  }
  if (!Number.isSafeInteger(settlement.settledPaymentMicros) || settlement.settledPaymentMicros <= 0) {
    throw new LedgerError('结算金额无效');
  }
  settlement.reversedAt = now;
  settlement.reversalReason = reason;
  settlement.reversalEntryId = operationId;
  settlement.updatedAt = now;
  settlement.revision = Number(settlement.revision || 1) + 1;
  settlement.originDeviceId = deviceId;
  backup.entries.push({
    id: operationId,
    workerId: settlement.workerId,
    entryType: 'ADJUSTMENT',
    entryEpochDay: settlement.endEpochDay,
    description: '撤销结算',
    workItemId: null,
    garmentTypeSnapshot: '',
    lengthTypeSnapshot: '',
    processNameSnapshot: '',
    quantity: null,
    unitSnapshot: '',
    unitPriceMicros: 0,
    amountMicros: settlement.settledPaymentMicros,
    note: reason,
    settlementId,
    createdAt: now,
    updatedAt: now,
    revision: 1,
    originDeviceId: deviceId,
    isDeleted: false,
  });
  backup.exportedAt = now;
  validateBackup(backup);
  return backup;
}

module.exports = {
  LedgerError,
  applySettlement,
  applySettlementReversal,
  applyWorkerPurge,
  applyWorkerPurges,
  emptyBackup,
  mergeBackups,
  parseBackupJson,
  validateBackup,
};
