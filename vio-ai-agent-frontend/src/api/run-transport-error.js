// Transport recovery may clear its own message, never a business/protocol failure.
export function createTransportErrorTracker() {
  const pending = new WeakMap()
  return {
    record(model, message) {
      model.error = message
      pending.set(model, message)
    },
    recover(model) {
      if (!pending.has(model)) return
      const message = pending.get(model)
      pending.delete(model)
      if (!['failed', 'timed_out'].includes(model.status) && model.error === message) model.error = null
    }
  }
}
