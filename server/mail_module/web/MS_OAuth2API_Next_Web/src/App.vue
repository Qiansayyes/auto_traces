<script setup lang="ts"></script>

<template>
  <router-view></router-view>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  min-height: 100%;
  background: #f6f7f9;
}

body {
  overflow-x: hidden;
}

.home-container {
  margin: 0 auto;
  padding: 16px 18px 14px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', sans-serif;
  color: #333;
  line-height: 1.6;
}

.table,
.panel,
.endpoint-panel,
.classification-card {
  max-width: 100%;
}

.el-dialog {
  max-width: calc(100vw - 32px);
}

.el-dialog__body {
  max-height: calc(100vh - 180px);
  overflow: auto;
}

.el-table {
  max-width: 100%;
}

.el-table .el-button + .el-button {
  margin-left: 6px;
}

.pagination,
.sender-pagination {
  max-width: 100%;
  overflow-x: auto;
  padding-bottom: 4px;
}

@media (max-width: 900px) {
  .home-container,
  .api-docs-page,
  .project-api-page,
  .project-mailboxes-page {
    padding: 10px;
  }

  .panel,
  .endpoint-panel,
  .toolbar-band {
    padding: 12px;
  }

  .el-form--inline {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    align-items: start;
    gap: 10px;
  }

  .el-form--inline .el-form-item {
    width: 100%;
    margin-right: 0;
    margin-bottom: 0;
  }

  .el-form-item__content {
    min-width: 0;
  }

  .el-form--inline .el-input,
  .el-form--inline .el-select,
  .el-form--inline .el-input-number,
  .el-form--inline .el-button,
  .toolbar-input,
  .password-input,
  .filter-input,
  .status-select,
  .project-input,
  .email-input {
    width: 100% !important;
  }

  .el-row {
    row-gap: 0;
  }

  .el-row > .el-col {
    flex: 0 0 100%;
    max-width: 100%;
  }

  .table,
  .panel,
  .endpoint-panel {
    overflow-x: auto;
  }

  .el-table {
    min-width: 720px;
  }

  .classification-grid,
  .summary-grid,
  .endpoint-grid {
    grid-template-columns: 1fr !important;
  }

  .endpoint-header,
  .group-header,
  .group-header-main,
  .group-header-actions,
  .email-item,
  .action-row,
  .link-row {
    align-items: stretch;
  }

  .endpoint-header,
  .group-header,
  .email-item,
  .action-row {
    flex-direction: column;
  }

  .action-row .el-button,
  .group-header-actions .el-button,
  .email-item .el-button {
    width: 100%;
    margin-left: 0 !important;
  }

  .el-pagination {
    min-width: max-content;
  }
}

@media (max-width: 560px) {
  .home-container,
  .api-docs-page,
  .project-api-page,
  .project-mailboxes-page {
    padding: 8px;
  }

  .el-dialog {
    width: calc(100vw - 20px) !important;
    max-width: calc(100vw - 20px);
    margin-top: 8vh !important;
  }

  .el-dialog__body {
    padding: 12px;
    max-height: calc(100vh - 150px);
  }

  .el-dialog__footer {
    padding: 10px 12px 14px;
  }

  .el-dialog__footer .el-button {
    width: 100%;
    margin: 6px 0 0 !important;
  }

  .el-form--inline {
    grid-template-columns: 1fr;
  }

  .el-form-item {
    margin-bottom: 12px;
  }

  .el-button {
    min-height: 34px;
  }

  .el-card__body {
    padding: 12px;
  }

  .el-card__header {
    padding: 12px;
  }
}
</style>
