// Money helpers. Prices are expressed in dollars as JavaScript numbers.

/**
 * Rounds a dollar amount to whole cents, half away from zero.
 *
 * SEEDED DEFECT D1 (incorrect rounding): toFixed() works on the binary
 * floating-point value, so 1.005 (stored as 1.00499999999999989...) rounds
 * down to 1.00 instead of 1.01.
 */
export function roundCurrency(amount: number): number {
  return Number(amount.toFixed(2));
}

export function formatUsd(amount: number): string {
  const rounded = roundCurrency(amount);
  const sign = rounded < 0 ? "-" : "";
  const [whole, cents] = Math.abs(rounded).toFixed(2).split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${sign}$${grouped}.${cents}`;
}

/** Sales tax for a subtotal at a percentage rate, e.g. rate 8.25 means 8.25%. */
export function salesTax(subtotal: number, ratePercent: number): number {
  if (!Number.isFinite(ratePercent) || ratePercent < 0 || ratePercent > 100) {
    throw new RangeError(`invalid tax rate: ${ratePercent}`);
  }
  return roundCurrency((subtotal * ratePercent) / 100);
}
