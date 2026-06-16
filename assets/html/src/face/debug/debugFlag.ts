const STORAGE_KEY = 'face.debug';

/**
 * Returns whether the debug panel should be available.
 *
 * Resolution order:
 *   1. URL `?debug=1` → enable (and persist to localStorage)
 *   2. URL `?debug=0` → disable (and clear localStorage)
 *   3. localStorage `face.debug=1` → enable
 *   4. Vite dev mode (`import.meta.env.DEV`) → enable
 *   5. Otherwise → disabled (production default)
 */
export function readInitialDebugFlag(): boolean {
  try {
    const url = new URL(window.location.href);
    const p = url.searchParams.get('debug');
    if (p === '1' || p === 'true') {
      localStorage.setItem(STORAGE_KEY, '1');
      return true;
    }
    if (p === '0' || p === 'false') {
      localStorage.removeItem(STORAGE_KEY);
      return false;
    }
    if (localStorage.getItem(STORAGE_KEY) === '0') return false;
    if (localStorage.getItem(STORAGE_KEY) === '1') return true;
  } catch {
    // localStorage may be unavailable in some embedded contexts
  }
  return !!import.meta.env.DEV;
}

export function setDebugEnabled(on: boolean) {
  try {
    if (on) localStorage.setItem(STORAGE_KEY, '1');
    else localStorage.setItem(STORAGE_KEY, '0');
  } catch {
    // ignore
  }
}
