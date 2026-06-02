function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import debug from 'debug';
export default class Logger {
  static enable(ns) {
    debug.enable(ns);
  }
  constructor(prefix) {
    _defineProperty(this, "_debug", void 0);
    _defineProperty(this, "_info", void 0);
    _defineProperty(this, "_warn", void 0);
    _defineProperty(this, "_error", void 0);
    const _prefix = `${Logger.ROOT_PREFIX}:${prefix}`;
    this._debug = debug(`${_prefix}:DEBUG`);
    this._info = debug(`${_prefix}:INFO`);
    this._warn = debug(`${_prefix}:WARN`);
    this._error = debug(`${_prefix}:ERROR`);
    const log = console.log.bind(console);
    this._debug.log = log;
    this._info.log = log;
    this._warn.log = log;
    this._error.log = log;
  }
  debug(msg) {
    this._debug(msg);
  }
  info(msg) {
    this._info(msg);
  }
  warn(msg) {
    this._warn(msg);
  }
  error(msg, err) {
    var _err$stack;
    const trace = (_err$stack = err === null || err === void 0 ? void 0 : err.stack) !== null && _err$stack !== void 0 ? _err$stack : 'N/A';
    this._error(`${msg} Trace: ${trace}`);
  }
}
_defineProperty(Logger, "ROOT_PREFIX", 'rn-webrtc');
//# sourceMappingURL=Logger.js.map