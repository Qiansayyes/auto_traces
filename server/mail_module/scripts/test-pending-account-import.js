const fs = require('fs');
const path = require('path');
const os = require('os');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pending-mail-db-test-'));
process.env.MAIL_DATA_DIR = tempDir;

delete require.cache[require.resolve('../services/DatabaseService')];
const database = require('../services/DatabaseService');

const owner = 'test_owner';
const baseRecord = {
  email: 'pending-test@outlook.com',
  password: 'Passw0rd!',
  client_id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  refresh_token: 'M.C502_BAY.0.U.-CqMockTokenExampleForPendingImportValidation1234567890',
};

const importToMain = database.importAccounts([baseRecord], {
  ownerUsername: owner,
  source: 'test-main',
});
if (importToMain.inserted_or_updated !== 1) {
  throw new Error(`expected 1 main account import, got ${importToMain.inserted_or_updated}`);
}

const skipExisting = database.importPendingTkAccounts([baseRecord], {
  ownerUsername: owner,
  source: 'test-pending-existing',
});
if (skipExisting.inserted_or_updated !== 0 || skipExisting.skipped_existing_main !== 1) {
  throw new Error(`expected pending import to skip existing main account, got ${JSON.stringify(skipExisting)}`);
}
if (skipExisting.rejected_records?.[0]?.reason !== '已存在于邮箱账户管理') {
  throw new Error(`expected existing-main reason, got ${skipExisting.rejected_records?.[0]?.reason}`);
}

const claimedRecord = {
  email: 'claimed-test@outlook.com',
  password: 'Passw0rd!',
  client_id: 'b1b2c3d4-e5f6-7890-abcd-ef1234567890',
  refresh_token: 'M.C502_BAY.0.U.-CqMockTokenExampleForClaimedPendingValidation1234567890',
};

const firstPendingImport = database.importPendingTkAccounts([claimedRecord], {
  ownerUsername: owner,
  source: 'test-pending-first',
});
if (firstPendingImport.inserted_or_updated !== 1) {
  throw new Error(`expected 1 pending account import, got ${firstPendingImport.inserted_or_updated}`);
}

const acquired = database.acquirePendingTkAccount({
  ownerUsername: owner,
  email: claimedRecord.email,
});
if (!acquired?.available || !acquired?.claimed) {
  throw new Error(`expected claimed pending account, got ${JSON.stringify(acquired)}`);
}

const skipClaimed = database.importPendingTkAccounts([claimedRecord], {
  ownerUsername: owner,
  source: 'test-pending-claimed',
});
if (skipClaimed.inserted_or_updated !== 0 || skipClaimed.skipped_claimed !== 1) {
  throw new Error(`expected pending import to skip claimed account, got ${JSON.stringify(skipClaimed)}`);
}
if (skipClaimed.rejected_records?.[0]?.reason !== '已被领取（注册中）') {
  throw new Error(`expected claimed reason, got ${skipClaimed.rejected_records?.[0]?.reason}`);
}

console.log('pending account import tests passed');
