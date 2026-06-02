function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import RTCRtcpParameters from './RTCRtcpParameters';
import RTCRtpCodecParameters from './RTCRtpCodecParameters';
import RTCRtpHeaderExtension from './RTCRtpHeaderExtension';
import { deepClone } from './RTCUtil';
export default class RTCRtpParameters {
  constructor(init) {
    _defineProperty(this, "codecs", []);
    _defineProperty(this, "headerExtensions", []);
    _defineProperty(this, "rtcp", void 0);
    for (const codec of init.codecs) {
      this.codecs.push(new RTCRtpCodecParameters(codec));
    }
    for (const ext of init.headerExtensions) {
      this.headerExtensions.push(new RTCRtpHeaderExtension(ext));
    }
    this.rtcp = new RTCRtcpParameters(init.rtcp);
  }
  toJSON() {
    return {
      codecs: this.codecs.map(c => deepClone(c)),
      headerExtensions: this.headerExtensions.map(he => deepClone(he)),
      rtcp: deepClone(this.rtcp)
    };
  }
}
//# sourceMappingURL=RTCRtpParameters.js.map