const { requireOwner } = require('./ownerContext');

const getRequestOwner = (ctx) => requireOwner(ctx.state.ownerUsername);

const withOwnerOptions = (ctx, options = {}) => ({
  ...options,
  ownerUsername: getRequestOwner(ctx),
});

module.exports = {
  getRequestOwner,
  withOwnerOptions,
};
