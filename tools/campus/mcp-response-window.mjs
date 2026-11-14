// Client-issued IDs are monotonically increasing positive safe integers.
// Replies older than the bounded window close the connection, rather than
// retaining every historical ID or silently accepting unverifiable duplicates.
export class McpResponseWindow {
 constructor(capacity=4096){
  if(!Number.isSafeInteger(capacity)||capacity<16||capacity>65536)throw Error('MCP_WINDOW_INVALID')
  this.slots=new Float64Array(capacity);this.capacity=capacity;this.size=0
 }
 observe(id,lastIssued){
  if(!Number.isSafeInteger(lastIssued)||!Number.isSafeInteger(id)||id<1||id>lastIssued||id<=lastIssued-this.capacity)throw Error('MCP_UNEXPECTED_RESPONSE')
  const slot=id%this.capacity
  if(this.slots[slot]===id)throw Error('MCP_DUPLICATE_RESPONSE')
  if(this.slots[slot]===0)this.size++
  this.slots[slot]=id
 }
}
