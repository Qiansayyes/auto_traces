const database = require('../services/DatabaseService');

const dropDetails = process.env.DROP_MAIL_DETAILS !== '0';
const vacuum = process.env.VACUUM_MAIL_DB !== '0';

console.time('[sender-migration] migrate');
const result = database.migrateReceivedMessagesToSenderReceipts({
  dropDetails,
  vacuum,
  reset: true,
});
console.timeEnd('[sender-migration] migrate');
console.log(`[sender-migration] migrated=${result.migrated} refreshed=${result.refreshed} droppedDetails=${result.dropped_details}`);
