# Updates

## 2026-08-28
- Fixed Graph mail fetch: `use_graph_api` no longer requests `https://graph.microsoft.com/.default` (causes AADSTS90023 on personal MSA tokens). It now requests `Mail.Read` / `Mail.ReadWrite` + `offline_access`, which successfully redeems the imported refresh tokens and can list inbox mail.
