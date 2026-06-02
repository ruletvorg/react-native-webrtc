"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.videoTrackDimensionChangedEventQueue = exports.default = void 0;
var _index = require("event-target-shim/index");
var _reactNative = require("react-native");
var _EventEmitter = require("./EventEmitter");
var _getDisplayMedia = _interopRequireDefault(require("./getDisplayMedia"));
var _getUserMedia = _interopRequireDefault(require("./getUserMedia"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const {
  WebRTCModule
} = _reactNative.NativeModules;
const videoTrackDimensionChangedEventQueue = exports.videoTrackDimensionChangedEventQueue = new Map();
let listenersReady = false;
function ensureListeners() {
  if (listenersReady) {
    return;
  }
  (0, _EventEmitter.addListener)('MediaDevices', 'videoTrackDimensionChanged', ev => {
    // We only want to queue events for local tracks.
    if (ev.pcId !== -1) {
      return;
    }
    const {
      trackId,
      width,
      height
    } = ev;
    videoTrackDimensionChangedEventQueue.set(trackId, {
      width,
      height
    });
  });
  listenersReady = true;
}
class MediaDevices extends _index.EventTarget {
  /**
   * W3C "Media Capture and Streams" compatible {@code enumerateDevices}
   * implementation.
   */
  enumerateDevices() {
    return new Promise(resolve => WebRTCModule.enumerateDevices(resolve));
  }

  /**
   * W3C "Screen Capture" compatible {@code getDisplayMedia} implementation.
   * See: https://w3c.github.io/mediacapture-screen-share/
   *
   * @returns {Promise}
   */
  getDisplayMedia() {
    ensureListeners();
    return (0, _getDisplayMedia.default)();
  }

  /**
   * W3C "Media Capture and Streams" compatible {@code getUserMedia}
   * implementation.
   * See: https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-enumeratedevices
   *
   * @param {*} constraints
   * @returns {Promise}
   */
  getUserMedia(constraints) {
    ensureListeners();
    return (0, _getUserMedia.default)(constraints);
  }
}

/**
 * Define the `onxxx` event handlers.
 */
const proto = MediaDevices.prototype;
(0, _index.defineEventAttribute)(proto, 'devicechange');
var _default = exports.default = new MediaDevices();
//# sourceMappingURL=MediaDevices.js.map