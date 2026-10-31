// Six single-digit boxes for the event code. Paste, typing, backspace and arrow keys work;
// on phones the numeric keypad opens and SMS/one-time-code autofill fills all six.
import { api } from './common.js';

export function codeBoxes(container, { value = '', onChange = () => {}, onComplete = () => {} } = {}) {
  container.classList.add('codeboxes');
  container.innerHTML = Array.from({ length: 6 }, (_, i) =>
    `<input class="cb" inputmode="numeric" pattern="[0-9]*" maxlength="1" ${i === 0 ? 'autocomplete="one-time-code"' : 'autocomplete="off"'} aria-label="Digit ${i + 1} of 6">`).join('');
  const boxes = [...container.querySelectorAll('input')];
  const get = () => boxes.map((b) => b.value).join('');
  const set = (s) => {
    const d = String(s || '').replace(/\D/g, '').slice(0, 6);
    boxes.forEach((b, i) => { b.value = d[i] || ''; });
    changed();
  };
  const changed = () => {
    const v = get();
    container.classList.remove('bad');
    onChange(v);
    if (v.length === 6) onComplete(v);
  };
  boxes.forEach((b, i) => {
    b.addEventListener('input', () => {
      const d = b.value.replace(/\D/g, '');
      if (d.length > 1) { set(get().slice(0, i) + d); boxes[Math.min(5, i + d.length)].focus(); return; }
      b.value = d;
      if (d && i < 5) boxes[i + 1].focus();
      changed();
    });
    b.addEventListener('keydown', (e) => {
      if (e.key === 'Backspace' && !b.value && i > 0) { boxes[i - 1].value = ''; boxes[i - 1].focus(); changed(); e.preventDefault(); }
      if (e.key === 'ArrowLeft' && i > 0) boxes[i - 1].focus();
      if (e.key === 'ArrowRight' && i < 5) boxes[i + 1].focus();
    });
    b.addEventListener('paste', (e) => { e.preventDefault(); set((e.clipboardData || window.clipboardData).getData('text')); boxes[Math.min(5, get().length)].focus(); });
    b.addEventListener('focus', () => b.select());
  });
  if (value) set(value);
  return {
    get, set, focus: () => boxes[Math.min(5, get().length)].focus(),
    shake: () => { container.classList.remove('bad'); void container.offsetWidth; container.classList.add('bad'); },
    clear: () => { set(''); boxes[0].focus(); },
  };
}

/** Send a code; returns the server reply or throws an Error with .data (triesLeft...). */
export function sendCode(code, extra = {}) {
  return api('POST', '/auth/code', { code, ...extra });
}

