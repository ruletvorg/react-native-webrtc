"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
class RTCIceCandidate {
  constructor({
    candidate = '',
    sdpMLineIndex = null,
    sdpMid = null
  }) {
    _defineProperty(this, "candidate", void 0);
    _defineProperty(this, "sdpMLineIndex", void 0);
    _defineProperty(this, "sdpMid", void 0);
    if (sdpMLineIndex === null && sdpMid === null) {
      throw new TypeError('`sdpMLineIndex` and `sdpMid` must not be both null');
    }
    this.candidate = candidate;
    this.sdpMLineIndex = sdpMLineIndex;
    this.sdpMid = sdpMid;
  }
  toJSON() {
    return {
      candidate: this.candidate,
      sdpMLineIndex: this.sdpMLineIndex,
      sdpMid: this.sdpMid
    };
  }
}
exports.default = RTCIceCandidate;
//# sourceMappingURL=RTCIceCandidate.js.map