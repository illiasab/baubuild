// Tiny hash router: route('#/projects/:id', handler); handler({params, query, path}).
const routes = [];
let notFound = null;
let listener = null;

function compile(pattern) {
  const keys = [];
  const source = pattern
    .replace(/^#?\/?/, '')
    .split('/')
    .map((part) => {
      if (part.startsWith(':')) { keys.push(part.slice(1)); return '([^/]+)'; }
      return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  return { regex: new RegExp(`^${source}/?$`), keys };
}

export function route(pattern, handler) {
  routes.push({ pattern, handler, ...compile(pattern) });
}

export function clearRoutes() {
  routes.length = 0;
  notFound = null;
}

export function fallback(handler) {
  notFound = handler;
}

// {path: 'projects/12', pattern: '#/projects/:id', params: {id: '12'}, query: {…}} or null.
export function currentRoute() {
  const hash = location.hash.replace(/^#\/?/, '');
  const [rawPath, rawQuery = ''] = hash.split('?');
  const path = rawPath.replace(/\/+$/, '');
  const query = Object.fromEntries(new URLSearchParams(rawQuery));
  for (const r of routes) {
    const m = path.match(r.regex);
    if (m) {
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { path, pattern: r.pattern, params, query, handler: r.handler };
    }
  }
  return { path, pattern: null, params: {}, query, handler: null };
}

export function navigate(hash, { replace = false } = {}) {
  const target = hash.startsWith('#') ? hash : `#${hash.startsWith('/') ? '' : '/'}${hash}`;
  if (location.hash === target) { dispatch(); return; }
  if (replace) {
    history.replaceState(null, '', `${location.pathname}${location.search}${target}`);
    dispatch();
  } else {
    location.hash = target;
  }
}

export function dispatch() {
  const current = currentRoute();
  const handler = current.handler || notFound;
  if (handler) handler({ params: current.params, query: current.query, path: current.path, pattern: current.pattern });
}

export function start() {
  if (!listener) {
    listener = () => dispatch();
    window.addEventListener('hashchange', listener);
  }
  dispatch();
}
