from pathlib import Path

p = Path(__file__).resolve().parents[1] / "web-admin" / "app.js"
t = p.read_text(encoding="utf-8")
start = t.index("function renderGroupManagerList")
end = t.index("async function refreshDeviceGroupsDetail")
new = r'''function renderGroupManagerList(items = []) {
  if (!groupManagerListEl) return;
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {
    groupManagerListEl.innerHTML = '<motion class="hint">暂无分组数据</motion>';
    groupManagerListEl.innerHTML = '<motion class="hint">暂无分组数据</motion>';
'''.replace("motion", "d" + "iv") + r'''
    if (groupManagerSummaryEl) groupManagerSummaryEl.textContent = "当前没有任何分组。";
    return;
  }
  const totalAliases = rows.reduce((sum, row) => sum + Number(row?.count || 0), 0);
  if (groupManagerSummaryEl) {
    groupManagerSummaryEl.textContent = `共 ${rows.length} 个分组，覆盖 ${totalAliases} 个别名。`;
  }
  groupManagerListEl.innerHTML = rows.map((row) => {
    const name = String(row?.group_name || "").trim() || "__ungrouped__";
    const aliases = Array.isArray(row?.aliases) ? row.aliases : [];
    const aliasTags = (aliases.length ? aliases : ["无"]).map((a) => `<li>${escapeHtml(a)}</li>`).join("");
    return (
      '<motion class="panel-card" style="margin:0 0 10px 0;padding:12px;background:#f8faff;">' +
      '<motion style="display:flex;justify-content:space-between;gap:8px;align-items:center;">' +
      `<strong>${escapeHtml(displayGroupName(name))}</strong>` +
      `<span class="hint">${aliases.length} 个别名</span>` +
      '</motion>' +
      `<motion class="tag-list" style="margin-top:8px;">${aliasTags}</motion>` +
      '</motion>'
    );
  }).join("").replace(/<\/?motion/g, (m) => m.replace("motion", "div"));
}

'''
# fix the broken replace - do it properly
D = "d" + "iv"
new = f'''function renderGroupManagerList(items = []) {{
  if (!groupManagerListEl) return;
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {{
    groupManagerListEl.innerHTML = '<{D} class="hint">暂无分组数据</{D}>';
    if (groupManagerSummaryEl) groupManagerSummaryEl.textContent = "当前没有任何分组。";
    return;
  }}
  const totalAliases = rows.reduce((sum, row) => sum + Number(row?.count || 0), 0);
  if (groupManagerSummaryEl) {{
    groupManagerSummaryEl.textContent = `共 ${{rows.length}} 个分组，覆盖 ${{totalAliases}} 个别名。`;
  }}
  groupManagerListEl.innerHTML = rows.map((row) => {{
    const name = String(row?.group_name || "").trim() || "__ungrouped__";
    const aliases = Array.isArray(row?.aliases) ? row.aliases : [];
    const aliasTags = (aliases.length ? aliases : ["无"]).map((a) => `<li>${{escapeHtml(a)}}</li>`).join("");
    return (
      '<{D} class="panel-card" style="margin:0 0 10px 0;padding:12px;background:#f8faff;">' +
      '<{D} style="display:flex;justify-content:space-between;gap:8px;align-items:center;">' +
      `<strong>${{escapeHtml(displayGroupName(name))}}</strong>` +
      `<span class="hint">${{aliases.length}} 个别名</span>` +
      '</{D}>' +
      `<{D} class="tag-list" style="margin-top:8px;">${{aliasTags}}</{D}>` +
      '</{D}>'
    );
  }}).join("");
}}

'''
p.write_text(t[:start] + new + t[end:], encoding="utf-8")
print("ok")
