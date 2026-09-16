const Router = require("koa-router");
const controller = require("../controllers/api");
const { authPasswordMiddleware, authParamsMiddleware } = require("../middlewares/auth.middleware");
const { ownerMiddleware } = require("../middlewares/owner.middleware");

const router = new Router();
const withOwner = [authPasswordMiddleware, ownerMiddleware];
const withOwnerAndMailParams = [...withOwner, authParamsMiddleware];

router.get("/", async (ctx) => {
  ctx.body = {
    message: `Welcome to Be ${process.env.API_NAME || "MS_OAuth2API_Next"}`,
  };
});

router.all("/api/mail_all", ...withOwnerAndMailParams, controller.mail_all);
router.all("/api/mail_new", ...withOwnerAndMailParams, controller.mail_new);
router.all("/api/mail_one", ...withOwnerAndMailParams, controller.mail_one);
router.all("/api/process-mailbox", ...withOwnerAndMailParams, controller.process_mailbox);

router.all("/api/test-proxy", ...withOwner, controller.test_proxy);

router.get("/api/template/list", ...withOwner, controller.template_list);
router.post("/api/template/save", ...withOwner, controller.template_save);
router.post("/api/template/delete", ...withOwner, controller.template_delete);

router.post("/api/sender_filter", ...withOwner, controller.sender_filter);
router.post("/api/sender_groups", ...withOwner, controller.sender_groups);
router.all("/api/sender_options", ...withOwner, controller.sender_options);

router.post("/api/accounts/import", ...withOwner, controller.accounts_import);
router.post("/api/accounts/list", ...withOwner, controller.accounts_list);
router.all("/api/accounts/token-mailboxes/list", ...withOwner, controller.token_mailboxes_list);
router.post("/api/accounts/token-mailboxes/check", ...withOwner, controller.token_mailboxes_check);
router.post("/api/accounts/delete", ...withOwner, controller.accounts_delete);
router.post("/api/tk-pending/accounts/import", ...withOwner, controller.tk_pending_import);
router.all("/api/tk-pending/accounts/list", ...withOwner, controller.tk_pending_list);
router.post("/api/tk-pending/accounts/delete", ...withOwner, controller.tk_pending_delete);
router.all("/api/tk-pending/accounts/acquire", ...withOwner, controller.tk_pending_acquire);
router.all("/api/tk-pending/accounts/take", ...withOwner, controller.tk_pending_take);
router.all("/api/tk-pending/accounts/complete", ...withOwner, controller.tk_pending_complete);
router.post("/api/mail/cache/import", ...withOwner, controller.mail_cache_import);
router.post("/api/database/stats", ...withOwner, controller.database_stats);

router.all("/api/project/upsert", ...withOwner, controller.project_upsert);
router.all("/api/project/list", ...withOwner, controller.project_list);
router.all("/api/project/mailboxes", ...withOwner, controller.project_mailboxes);
router.all("/api/project/acquire", ...withOwner, controller.project_acquire);
router.all("/api/project/mail", ...withOwner, controller.project_mail);
router.all("/api/project/code", ...withOwner, controller.project_mail);
router.all("/api/project/status", ...withOwner, controller.project_status);
router.all("/api/project/marks", ...withOwner, controller.project_marks);

router.get("/api/server_time", (ctx) => {
  ctx.body = { code: "200", data: { nowMs: Date.now() } };
});

module.exports = router;
