export class HistoryStack {
  private past: string[] = [];
  private future: string[] = [];
  private readonly limit = 80;

  push(serialized: string): void {
    if (this.past[this.past.length - 1] === serialized) return;
    this.past.push(serialized);
    if (this.past.length > this.limit) this.past.shift();
    this.future.length = 0;
  }

  canUndo(): boolean {
    return this.past.length > 0;
  }

  canRedo(): boolean {
    return this.future.length > 0;
  }

  undo(current: string): string | null {
    if (this.past.length === 0) return null;
    this.future.push(current);
    return this.past.pop() ?? null;
  }

  redo(current: string): string | null {
    if (this.future.length === 0) return null;
    this.past.push(current);
    return this.future.pop() ?? null;
  }

  clear(): void {
    this.past = [];
    this.future = [];
  }
}
