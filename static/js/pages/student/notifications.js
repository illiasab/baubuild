// Notifications: GET /v1/notifications, mark read with POST /v1/notifications/read {ids}.
import { h, str, items, relTime, button, pageHead, loader, emptyView, clickable, toast, errorText } from './_kit.js';

let cached = null;

export default async function render(root, ctx) {
  let alive = true;
  const markAll = button('Mark all as read', { kind: 'secondary', small: true });
  markAll.hidden = true;
  const body = h('div', { class: 'sp-notes-body' });
  root.replaceChildren(h('div', { class: 'sp-page sp-notes' }, pageHead('Notifications', markAll), body));

  const board = loader(body, {
    initial: cached,
    isAlive: () => alive && body.isConnected,
    load: async () => items(await ctx.api('/v1/notifications')).filter((n) => n && n.id != null),
    view: (list) => {
      cached = list;
      const unread = list.filter((n) => !n.read);
      markAll.hidden = unread.length === 0;
      if (!list.length) return emptyView({ title: 'You’re all caught up', body: 'Joins, reviews and approvals show up here.' });
      return h('ul', { class: 'sp-card sp-card--flush sp-notes-list' }, list.map((n) => {
        const li = h('li', { class: ['sp-note-item', !n.read && 'is-unread'] },
          h('span', { class: 'sp-unread-dot', 'aria-hidden': 'true' }),
          h('div', { class: 'sp-note-text' },
            h('span', {}, str(n.text ?? n.title ?? n.body)),
            h('span', { class: 'sp-small sp-muted' }, relTime(n.created_at), !n.read ? h('span', { class: 'sp-sr' }, ' · unread') : null)));
        return n.read ? li : clickable(li, () => mark([n.id]), `Mark as read: ${str(n.text)}`);
      }));
    },
  });

  async function mark(ids) {
    if (!ids.length) return;
    const set = new Set(ids.map(String));
    const before = board.data;
    board.data = before.map((n) => (set.has(str(n.id)) ? { ...n, read: true } : n));
    try {
      await ctx.api('/v1/notifications/read', { method: 'POST', body: { ids } });
      ctx.refreshUnread?.();
    } catch (err) {
      if (alive) board.data = before;
      toast(ctx, errorText(err), 'error');
    }
  }

  markAll.addEventListener('click', () => mark((board.data || []).filter((n) => !n.read).map((n) => n.id)));
  return () => { alive = false; };
}
