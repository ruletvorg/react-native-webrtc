"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _RTCRtpEncodingParameters = _interopRequireDefault(require("./RTCRtpEncodingParameters"));
var _RTCRtpParameters = _interopRequireDefault(require("./RTCRtpParameters"));
var _RTCUtil = require("./RTCUtil");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
/**
 * Class to convert degradation preference format. Native has a format such as
 * MAINTAIN_FRAMERATE whereas the web APIs expect maintain-framerate
 */
class DegradationPreference {
  static fromNative(nativeFormat) {
    const stringFormat = nativeFormat.toLowerCase().replace('_', '-');
    return stringFormat;
  }
  static toNative(format) {
    return format.toUpperCase().replace('-', '_');
  }
}
class RTCRtpSendParameters extends _RTCRtpParameters.default {
  constructor(init) {
    super(init);
    _defineProperty(this, "transactionId", void 0);
    _defineProperty(this, "encodings", void 0);
    _defineProperty(this, "degradationPreference", void 0);
    this.transactionId = init.transactionId;
    this.encodings = [];
    this.degradationPreference = init.degradationPreference ? DegradationPreference.fromNative(init.degradationPreference) : null;
    for (const enc of init.encodings) {
      this.encodings.push(new _RTCRtpEncodingParameters.default(enc));
    }
  }
  toJSON() {
    const obj = super.toJSON();
    obj['transactionId'] = this.transactionId;
    obj['encodings'] = this.encodings.map(e => (0, _RTCUtil.deepClone)(e));
    if (this.degradationPreference !== null) {
      obj['degradationPreference'] = DegradationPreference.toNative(this.degradationPreference);
    }
    return obj;
  }
}
exports.default = RTCRtpSendParameters;
//# sourceMappingURL=RTCRtpSendParameters.js.map