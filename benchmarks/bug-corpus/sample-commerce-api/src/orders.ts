import { type Cart, subtotal } from "./cart.ts";
import { salesTax } from "./money.ts";
import type { InventoryService } from "./inventory.ts";

export interface AuditLog {
  write(entry: { type: string; orderId: string; total: number }): Promise<void>;
}

export interface Order {
  id: string;
  subtotal: number;
  tax: number;
  total: number;
}

let sequence = 0;

/**
 * Reserves stock, prices the cart and records an audit entry.
 *
 * SEEDED DEFECT D3 (unhandled asynchronous operation): the audit write is
 * not awaited. If it fails, placeOrder still reports success, the order has
 * no audit record, and the rejection surfaces later as an unhandled
 * rejection unrelated to the caller.
 */
export async function placeOrder(cart: Cart, inventory: InventoryService, audit: AuditLog, taxRatePercent: number): Promise<Order> {
  for (const line of cart.lines) {
    await inventory.reserve(line.sku, line.quantity);
  }
  const net = subtotal(cart);
  const tax = salesTax(net, taxRatePercent);
  const order: Order = { id: `ord-${++sequence}`, subtotal: net, tax, total: Math.round((net + tax) * 100) / 100 };
  audit.write({ type: "order.placed", orderId: order.id, total: order.total });
  return order;
}
