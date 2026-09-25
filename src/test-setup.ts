import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
afterEach(() => cleanup());
window.scrollTo = vi.fn();
Element.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.hasPointerCapture = () => false;
window.HTMLElement.prototype.setPointerCapture = () => {};
window.HTMLElement.prototype.releasePointerCapture = () => {};
if (!window.PointerEvent)
  window.PointerEvent = MouseEvent as typeof PointerEvent;
class Observer {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = Observer;

// Node 25 exposes its own localStorage; use a browser-shaped store in jsdom.
const values = new Map<string, string>();
Object.defineProperty(globalThis, "localStorage", {
  configurable: true,
  value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, String(value)),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: (i: number) => Array.from(values.keys())[i] ?? null,
    get length() {
      return values.size;
    },
  },
});
