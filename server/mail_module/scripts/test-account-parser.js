const assert = require('assert');
const {
  parseAccountLine,
  validateAccountFields,
  toAccountRecord,
} = require('../utils/accountParser');

const SAMPLE = {
  email: 'testuser@outlook.com',
  password: 'MyPass123!',
  client_id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  refresh_token: 'M.C502_BAY.0.U.-CqMockTokenExampleForUnitTestPurposesOnlyLongEnoughToPassValidation1234567890',
};

const formats = [
  [SAMPLE.email, SAMPLE.password, SAMPLE.client_id, SAMPLE.refresh_token].join('----'),
  [SAMPLE.email, SAMPLE.password, SAMPLE.client_id, SAMPLE.refresh_token].join(' '),
  [SAMPLE.email, SAMPLE.password, SAMPLE.client_id, SAMPLE.refresh_token].join('\t'),
  [SAMPLE.email, SAMPLE.client_id, SAMPLE.password, SAMPLE.refresh_token].join('----'),
  `${SAMPLE.email}----${SAMPLE.password}----${SAMPLE.client_id}----${SAMPLE.refresh_token}`,
];

for (const line of formats) {
  const parsed = parseAccountLine(line);
  assert.ok(!parsed.error, `failed to parse: ${line} -> ${parsed.error}`);
  assert.strictEqual(parsed.email, SAMPLE.email);
  assert.strictEqual(parsed.password, SAMPLE.password);
  assert.strictEqual(parsed.client_id, SAMPLE.client_id);
  assert.strictEqual(parsed.refresh_token, SAMPLE.refresh_token);
}

const invalid = parseAccountLine(`${SAMPLE.email}----${SAMPLE.password}----bad-client`);
assert.ok(invalid.error, 'incomplete line should be rejected');

const missingPassword = validateAccountFields({
  email: SAMPLE.email,
  password: '',
  client_id: SAMPLE.client_id,
  refresh_token: SAMPLE.refresh_token,
});
assert.ok(!missingPassword.ok, 'missing password should fail validation');

const legacyUiRecord = {
  email: formats[2],
  password: '',
  client_id: '',
  refresh_token: '',
};
const reparsedLegacy = toAccountRecord(legacyUiRecord);
assert.ok(!reparsedLegacy.error, `legacy UI record should reparsed: ${reparsedLegacy.error}`);
assert.strictEqual(reparsedLegacy.email, SAMPLE.email);

console.log('account parser tests passed');
