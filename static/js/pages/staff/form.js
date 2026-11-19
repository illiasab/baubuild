// Small form controls shared by New inquiry and Inquiry detail (design: chk chips, segmented groups).
import { el, icon } from './shared.js';

let seq = 0;
export const uid = (prefix) => `${prefix}-${++seq}`;

/** Label + control + hint/error line. Returns {wrap, setError}. */
export function field(label, control, { hint, id } = {}) {
  const errId = uid('err');
  const controlId = id || control.id || uid('f');
  control.id = controlId;
  const error = el('span', { class: 'st-field-error', id: errId, role: 'alert', hidden: true });
  const wrap = el('div', { class: 'st-field' },
    el('label', { for: controlId, class: 'st-label' }, label),
    hint ? el('span', { class: 'st-hint' }, hint) : null,
    control, error);
  return {
    wrap,
    setError(message) {
      error.textContent = message || '';
      error.hidden = !message;
      if (message) control.setAttribute('aria-invalid', 'true');
      else control.removeAttribute('aria-invalid');
      control.setAttribute('aria-describedby', errId);
    },
  };
}

export function input(attrs) {
  return el('input', { class: 'st-input', type: 'text', ...attrs });
}

export function textarea(attrs) {
  return el('textarea', { class: 'st-input st-textarea', rows: 4, ...attrs });
}

/** Multi-select chips. options: [{key,label}]; onChange(keys). */
export function checkGroup(label, options, selected, onChange, { hint = 'Tick all that apply' } = {}) {
  const labelId = uid('grp');
  const chosen = new Set(selected || []);
  const error = el('span', { class: 'st-field-error', role: 'alert', hidden: true });
  const buttons = options.map((o) => {
    const b = el('button', {
      type: 'button', class: 'st-chk', 'aria-pressed': String(chosen.has(o.key)),
      onclick: () => {
        if (chosen.has(o.key)) chosen.delete(o.key); else chosen.add(o.key);
        b.setAttribute('aria-pressed', String(chosen.has(o.key)));
        onChange(options.map((x) => x.key).filter((k) => chosen.has(k)));
      },
    }, el('span', { class: 'st-chk-box', 'aria-hidden': 'true' }, icon('check', 14)), el('span', {}, o.label));
    return b;
  });
  const wrap = el('div', { class: 'st-field' },
    el('div', { class: 'st-label-row' }, el('span', { class: 'st-label', id: labelId }, label), hint ? el('span', { class: 'st-hint' }, hint) : null),
    el('div', { class: 'st-chk-group', role: 'group', 'aria-labelledby': labelId }, buttons),
    error);
  return {
    wrap,
    setError(message) { error.textContent = message || ''; error.hidden = !message; },
    setDisabled(on) { buttons.forEach((b) => { b.disabled = on; }); },
  };
}

/** Single-select segmented control. options: [{key,label}]; onChange(key). */
export function segmented(label, options, value, onChange) {
  let current = value;
  const buttons = options.map((o) => el('button', {
    type: 'button', class: 'st-seg-btn', 'aria-pressed': String(current === o.key),
    onclick: () => {
      current = o.key;
      buttons.forEach((b, i) => b.setAttribute('aria-pressed', String(options[i].key === current)));
      onChange(o.key);
    },
  }, o.label));
  const wrap = el('div', { class: 'st-field st-field--seg' },
    el('span', { class: 'st-label' }, label),
    el('div', { class: 'st-seg', role: 'group', 'aria-label': label }, buttons));
  return { wrap, setDisabled(on) { buttons.forEach((b) => { b.disabled = on; }); } };
}
