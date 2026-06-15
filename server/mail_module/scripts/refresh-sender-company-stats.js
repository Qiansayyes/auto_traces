const database = require('../services/DatabaseService');

console.time('[sender-company-stats] refreshAll');
const result = database.refreshAllSenderCompanyStats();
console.timeEnd('[sender-company-stats] refreshAll');
console.log(`[sender-company-stats] refreshed=${result.refreshed}`);
