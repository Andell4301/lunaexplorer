'use strict';

const terminal = new Terminal({
  cursorBlink: true,
  fontFamily: 'monospace',
  fontSize: 14,
  scrollback: 10000,
  rightClickSelectsWord: true,
  logLevel: 'off',
  screenReaderMode: LunaTerminal.screenReaderEnabled(),
  theme: { background: '#111318', foreground: '#e2e2e9', cursor: '#e2e2e9' },
  linkHandler: { activate() {} }
});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById('terminal'));
terminal.textarea.setAttribute('autocapitalize', 'off');
terminal.textarea.setAttribute('autocomplete', 'off');
terminal.textarea.setAttribute('autocorrect', 'off');
terminal.textarea.setAttribute('spellcheck', 'false');
terminal.textarea.setAttribute('aria-label', 'Terminal');
terminal.textarea.readOnly = true;
terminal.textarea.setAttribute('inputmode', 'none');
document.addEventListener('click', () => {
  setTimeout(() => LunaTerminal.tapped(), 0);
}, true);
const compositionView = terminal.element.querySelector('.composition-view');

function positionComposition() {
  compositionView.style.left = terminal.textarea.style.left;
  compositionView.style.top = terminal.textarea.style.top;
  compositionView.style.height = terminal.textarea.style.height;
  compositionView.style.lineHeight = terminal.textarea.style.lineHeight;
  compositionView.style.fontFamily = terminal.options.fontFamily;
  compositionView.style.fontSize = terminal.options.fontSize + 'px';
}
terminal.onRender(positionComposition);

function focusInput() {
  terminal.focus();
}

function encode(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

let started = false;
terminal.onData(data => LunaTerminal.input(encode(new TextEncoder().encode(data))));
terminal.onBinary(data => LunaTerminal.input(btoa(data)));
terminal.onResize(size => { if (started) LunaTerminal.resize(size.cols, size.rows); });
terminal.parser.registerOscHandler(7, value => {
  LunaTerminal.directory(value);
  return true;
});

window.luna = {
  write(data, id) {
    const bytes = Uint8Array.from(atob(data), value => value.charCodeAt(0));
    terminal.write(bytes, () => LunaTerminal.written(id));
  },
  paste(text) { terminal.paste(text); },
  input(text) { terminal.input(text, true); },
  selection() { return terminal.getSelection() || window.getSelection().toString(); },
  focus: focusInput,
  focusForInput() {
    if (terminal.hasSelection() || window.getSelection().toString()) return false;
    focusInput();
    return document.activeElement === terminal.textarea;
  },
  screenReader(enabled) { terminal.options.screenReaderMode = enabled; },
  composition(text) {
    compositionView.textContent = text;
    compositionView.classList.toggle('active', text.length > 0);
    positionComposition();
  },
  key(name) {
    const cursor = terminal.modes.applicationCursorKeysMode ? '\x1bO' : '\x1b[';
    const keys = {
      ESCAPE: '\x1b', TAB: '\t', ENTER: '\r', BACKSPACE: '\x7f',
      UP: cursor + 'A', DOWN: cursor + 'B', RIGHT: cursor + 'C', LEFT: cursor + 'D',
      HOME: cursor + 'H', END: cursor + 'F', PAGE_UP: '\x1b[5~', PAGE_DOWN: '\x1b[6~',
      INSERT: '\x1b[2~', DELETE: '\x1b[3~',
      F1: '\x1bOP', F2: '\x1bOQ', F3: '\x1bOR', F4: '\x1bOS',
      F5: '\x1b[15~', F6: '\x1b[17~', F7: '\x1b[18~', F8: '\x1b[19~',
      F9: '\x1b[20~', F10: '\x1b[21~', F11: '\x1b[23~', F12: '\x1b[24~'
    };
    if (keys[name] !== undefined) terminal.input(keys[name]);
    focusInput();
  }
};

new ResizeObserver(([entry]) => {
  if (entry.contentRect.width <= 0 || entry.contentRect.height <= 0) return;
  const size = fit.proposeDimensions();
  if (!size || !Number.isFinite(size.cols) || !Number.isFinite(size.rows)) return;
  fit.fit();
  if (!started) {
    started = true;
    LunaTerminal.resize(terminal.cols, terminal.rows);
    LunaTerminal.ready();
  }
}).observe(document.getElementById('terminal'));
