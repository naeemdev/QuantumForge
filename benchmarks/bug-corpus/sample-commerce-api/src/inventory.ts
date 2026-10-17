export interface StockStore {
  get(sku: string): Promise<number>;
  set(sku: string, quantity: number): Promise<void>;
}

/** In-memory store with an artificial I/O delay, standing in for a database. */
export class MemoryStockStore implements StockStore {
  private readonly stock = new Map<string, number>();
  private readonly latencyMs: number;

  constructor(initial: Record<string, number>, latencyMs = 1) {
    this.latencyMs = latencyMs;
    for (const [sku, quantity] of Object.entries(initial)) this.stock.set(sku, quantity);
  }

  async get(sku: string): Promise<number> {
    await new Promise((resolve) => setTimeout(resolve, this.latencyMs));
    return this.stock.get(sku) ?? 0;
  }

  async set(sku: string, quantity: number): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, this.latencyMs));
    this.stock.set(sku, quantity);
  }
}

export class OutOfStockError extends Error {
  constructor(sku: string) {
    super(`insufficient stock for ${sku}`);
    this.name = "OutOfStockError";
  }
}

export class InventoryService {
  private readonly store: StockStore;

  constructor(store: StockStore) {
    this.store = store;
  }

  /**
   * Reserves units of a SKU.
   *
   * SEEDED DEFECT D5 (concurrency-sensitive update): read-check-write across
   * two awaits. Two concurrent reservations both read the same stock level,
   * both pass the check and both write, so the last unit is sold twice.
   */
  async reserve(sku: string, quantity: number): Promise<void> {
    const available = await this.store.get(sku);
    if (available < quantity) throw new OutOfStockError(sku);
    await this.store.set(sku, available - quantity);
  }

  available(sku: string): Promise<number> {
    return this.store.get(sku);
  }
}
