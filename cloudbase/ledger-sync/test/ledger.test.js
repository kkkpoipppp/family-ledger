'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  applySettlement,
  applySettlementReversal,
  applyWorkerPurge,
  emptyBackup,
  mergeBackups,
  validateBackup,
} = require('../ledger');

function worker(id = 'w1') {
  return { id, name: '张姐', createdAt: 1, updatedAt: 1, revision: 1, originDeviceId: 'phone-a', isDeleted: false };
}

function workEntry(id, amountMicros, deviceId = 'phone-a') {
  return {
    id,
    workerId: 'w1',
    entryType: 'WORK',
    entryEpochDay: 20000,
    description: '长裤',
    workItemId: 'item-1',
    garmentTypeSnapshot: '裤子',
    lengthTypeSnapshot: '长款',
    processNameSnapshot: '',
    quantity: 10,
    unitSnapshot: '条',
    unitPriceMicros: amountMicros / 10,
    amountMicros,
    note: '',
    settlementId: null,
    createdAt: 2,
    updatedAt: 2,
    revision: 1,
    originDeviceId: deviceId,
    isDeleted: false,
  };
}

function workItem(id = 'item-1') {
  return {
    id,
    name: '长裤',
    garmentType: '裤子',
    lengthType: '长款',
    processName: '',
    unit: '条',
    defaultUnitPriceMicros: 500000,
    sortOrder: 1,
    createdAt: 1,
    updatedAt: 1,
    revision: 1,
    originDeviceId: 'phone-a',
    isDeleted: false,
  };
}

function validBackup() {
  const backup = emptyBackup();
  backup.workers.push(worker());
  backup.workItems.push(workItem());
  backup.entries.push(workEntry('entry-1', 5000000));
  return backup;
}

test('merges records created independently on two phones', () => {
  const left = emptyBackup();
  left.workers.push(worker('w1'));
  const right = emptyBackup();
  right.workers.push(worker('w2'));
  const merged = mergeBackups(left, right);
  assert.deepEqual(merged.workers.map((item) => item.id).sort(), ['w1', 'w2']);
});

test('newer revision wins when the same record changed', () => {
  const left = emptyBackup();
  left.workers.push(worker());
  const right = emptyBackup();
  right.workers.push({ ...worker(), name: '张师傅', revision: 2, updatedAt: 3, originDeviceId: 'phone-b' });
  assert.equal(mergeBackups(left, right).workers[0].name, '张师傅');
});

test('settlement is atomic and a second settlement sees zero balance', () => {
  const backup = validBackup();
  applySettlement(backup, {
    operationId: 'settlement-1', workerId: 'w1', startEpochDay: 19999, endEpochDay: 20001, deviceId: 'phone-a',
  }, 100);
  assert.equal(backup.settlements.length, 1);
  assert.equal(backup.entries.at(-1).amountMicros, -5000000);
  assert.throws(() => applySettlement(backup, {
    operationId: 'settlement-2', workerId: 'w1', startEpochDay: 19999, endEpochDay: 20001, deviceId: 'phone-b',
  }, 101), /没有需要支付的余额/);
});

test('settlement reversal is idempotent for the same operation id', () => {
  const backup = validBackup();
  applySettlement(backup, {
    operationId: 'settlement-1', workerId: 'w1', startEpochDay: 19999, endEpochDay: 20001, deviceId: 'phone-a',
  }, 100);
  const request = { operationId: 'reversal-1', settlementId: 'settlement-1', reason: '日期选错', deviceId: 'phone-a' };
  applySettlementReversal(backup, request, 200);
  applySettlementReversal(backup, request, 201);
  assert.equal(backup.entries.filter((item) => item.id === 'reversal-1').length, 1);
  assert.equal(backup.entries.at(-1).amountMicros, 5000000);
});

test('rejects work entries whose amount does not match quantity and unit price', () => {
  const backup = validBackup();
  backup.entries[0].amountMicros += 1;
  assert.throws(() => validateBackup(backup), /金额与数量单价不一致/);
});

test('rejects invalid entry types and revisions', () => {
  const invalidType = validBackup();
  invalidType.entries[0].entryType = 'UNKNOWN';
  assert.throws(() => validateBackup(invalidType), /流水类型无效/);

  const invalidRevision = validBackup();
  invalidRevision.workers[0].revision = 0;
  assert.throws(() => validateBackup(invalidRevision), /修订号无效/);
});

test('rejects missing foreign-key references', () => {
  const missingWorker = validBackup();
  missingWorker.entries[0].workerId = 'missing-worker';
  assert.throws(() => validateBackup(missingWorker), /缺少对应工人/);

  const missingWorkItem = validBackup();
  missingWorkItem.entries[0].workItemId = 'missing-item';
  assert.throws(() => validateBackup(missingWorkItem), /缺少对应做工项目/);
});

test('rejects settlement records with inconsistent totals', () => {
  const backup = validBackup();
  applySettlement(backup, {
    operationId: 'settlement-1', workerId: 'w1', startEpochDay: 19999, endEpochDay: 20001, deviceId: 'phone-a',
  }, 100);
  backup.settlements[0].balanceMicros += 1;
  assert.throws(() => validateBackup(backup), /汇总金额不一致/);
});

test('permanent worker purge removes all personal and ledger records', () => {
  const backup = validBackup();
  applySettlement(backup, {
    operationId: 'settlement-1', workerId: 'w1', startEpochDay: 19999, endEpochDay: 20001, deviceId: 'phone-a',
  }, 100);
  applyWorkerPurge(backup, { workerId: 'w1', deviceId: 'phone-a' }, 200);
  assert.equal(backup.workers.length, 0);
  assert.equal(backup.entries.length, 0);
  assert.equal(backup.settlements.length, 0);
  assert.equal(backup.settlementLines.length, 0);
  assert.deepEqual(backup.purgedWorkers, [{ id: 'w1', purgedAt: 200, originDeviceId: 'phone-a' }]);
});

test('a stale offline phone cannot resurrect a permanently deleted worker', () => {
  const stalePhone = validBackup();
  const server = validBackup();
  applyWorkerPurge(server, { workerId: 'w1', deviceId: 'phone-a' }, 200);
  const merged = mergeBackups(server, stalePhone);
  assert.equal(merged.workers.length, 0);
  assert.equal(merged.entries.length, 0);
  assert.equal(merged.purgedWorkers.length, 1);
});
