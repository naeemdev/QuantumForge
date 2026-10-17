import { roundCurrency } from "./money.ts";

export interface CartLine {
  sku: string;
  unitPrice: number;
  quantity: number;
}

export interface Cart {
  lines: CartLine[];
}

export function emptyCart(): Cart {
  return { lines: [] };
}

/**
 * Adds an item, merging with an existing line for the same SKU.
 *
 * SEEDED DEFECT D2 (missing input validation): quantity is not checked, so
 * zero, negative and fractional quantities are accepted and can drive the
 * cart total below zero.
 */
export function addItem(cart: Cart, sku: string, unitPrice: number, quantity: number): Cart {
  if (sku.trim() === "") throw new TypeError("sku is required");
  if (!Number.isFinite(unitPrice) || unitPrice < 0) throw new RangeError("unitPrice must be a non-negative number");
  const existing = cart.lines.find((line) => line.sku === sku);
  if (existing) {
    return {
      lines: cart.lines.map((line) => (line.sku === sku ? { ...line, quantity: line.quantity + quantity } : line)),
    };
  }
  return { lines: [...cart.lines, { sku, unitPrice, quantity }] };
}

export function itemCount(cart: Cart): number {
  return cart.lines.reduce((sum, line) => sum + line.quantity, 0);
}

export function subtotal(cart: Cart): number {
  return roundCurrency(cart.lines.reduce((sum, line) => sum + line.unitPrice * line.quantity, 0));
}

/**
 * Average price paid per unit, used by the loyalty programme.
 *
 * SEEDED DEFECT D6 (missing edge case): an empty cart divides 0 by 0 and
 * returns NaN, which then poisons every downstream calculation.
 */
export function averageUnitPrice(cart: Cart): number {
  return roundCurrency(subtotal(cart) / itemCount(cart));
}
