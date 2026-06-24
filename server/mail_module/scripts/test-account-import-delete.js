const fs = require('fs');
const path = require('path');
const os = require('os');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mail-db-test-'));
process.env.MAIL_DATA_DIR = tempDir;

delete require.cache[require.resolve('../services/DatabaseService')];
const database = require('../services/DatabaseService');

const owner = 'test_owner';
const line = [
  'parser-test@outlook.com',
  'Passw0rd!',
  'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  'M.C502_BAY.0.U.-CqMockTokenExampleForUnitTestPurposesOnlyLongEnoughToPassValidation1234567890',
].join('----');

const imported = database.importAccounts([line], {
  ownerUsername: owner,
  source: 'test',
});
if (!imported.inserted_or_updated) {
  throw new Error('import failed');
}

const listed = database.listAccounts({ ownerUsername: owner, page: 1, pageSize: 10 });
if (listed.total !== 1) {
  throw new Error(`expected 1 account, got ${listed.total}`);
}

const mixed = database.importAccounts([line, 'bad-line'], {
  ownerUsername: owner,
  source: 'test-mixed',
});
if (mixed.inserted_or_updated !== 1) {
  throw new Error(`partial import should keep valid row, got ${mixed.inserted_or_updated}`);
}
if (mixed.skipped_records !== 1 || !mixed.rejected_records?.length) {
  throw new Error('partial import should report rejected row');
}

const listedAfterMixed = database.listAccounts({ ownerUsername: owner, page: 1, pageSize: 10 });
if (listedAfterMixed.total !== 1) {
  throw new Error(`expected 1 account after mixed import, got ${listedAfterMixed.total}`);
}

const deleted = database.deleteAccounts({
  ownerUsername: owner,
  emails: ['parser-test@outlook.com'],
});
if (deleted.deleted !== 1) {
  throw new Error(`expected 1 deleted, got ${deleted.deleted}`);
}

const listedAfter = database.listAccounts({ ownerUsername: owner, page: 1, pageSize: 10 });
if (listedAfter.total !== 0) {
  throw new Error(`expected 0 accounts after delete, got ${listedAfter.total}`);
}

try {
  database.importAccounts(['bad-line'], { ownerUsername: owner });
  throw new Error('invalid import should have been rejected');
} catch (error) {
  if (error.status !== 400) {
    throw error;
  }
}

console.log('database import/delete tests passed');
