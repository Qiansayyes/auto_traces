const database = require('../services/DatabaseService');

const batchSize = Math.max(1, Number(process.env.SENDER_COMPANY_BACKFILL_BATCH || 5000) || 5000);

let totalUpdated = 0;
let rounds = 0;

while (true) {
  const result = database.backfillSenderCompanies(batchSize);
  rounds += 1;
  totalUpdated += result.updated;
  console.log(`[sender-company-backfill] round=${rounds} updated=${result.updated} remaining=${result.remaining}`);

  if (result.updated === 0 || result.remaining === 0) {
    break;
  }
}

console.log(`[sender-company-backfill] done totalUpdated=${totalUpdated}`);

console.time('[sender-company-backfill] refreshStats');
const refresh = database.refreshAllSenderCompanyStats();
console.timeEnd('[sender-company-backfill] refreshStats');
console.log(`[sender-company-backfill] statsRefreshed=${refresh.refreshed}`);
