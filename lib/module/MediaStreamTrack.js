function _defineProperty(e, r, t) { return (r = _toPropertyKey(r)) in e ? Object.defineProperty(e, r, { value: t, enumerable: !0, configurable: !0, writable: !0 }) : e[r] = t, e; }
function _toPropertyKey(t) { var i = _toPrimitive(t, "string"); return "symbol" == typeof i ? i : i + ""; }
function _toPrimitive(t, r) { if ("object" != typeof t || !t) return t; var e = t[Symbol.toPrimitive]; if (void 0 !== e) { var i = e.call(t, r || "default"); if ("object" != typeof i) return i; throw new TypeError("@@toPrimitive must return a primitive value."); } return ("string" === r ? String : Number)(t); }
import { EventTarget, Event, defineEventAttribute } from 'event-target-shim/index';
import { NativeModules } from 'react-native';
import { addListener, removeListener } from './EventEmitter';
import Logger from './Logger';
import { videoTrackDimensionChangedEventQueue } from './MediaDevices';
import { deepClone, normalizeConstraints } from './RTCUtil';
const log = new Logger('pc');
const {
  WebRTCModule
} = NativeModules;
export default class MediaStreamTrack extends EventTarget {
  constructor(info) {
    super();
    _defineProperty(this, "_constraints", void 0);
    _defineProperty(this, "_enabled", void 0);
    _defineProperty(this, "_settings", void 0);
    _defineProperty(this, "_muted", void 0);
    _defineProperty(this, "_peerConnectionId", void 0);
    _defineProperty(this, "_readyState", void 0);
    _defineProperty(this, "id", void 0);
    _defineProperty(this, "kind", void 0);
    _defineProperty(this, "label", '');
    _defineProperty(this, "remote", void 0);
    this.id = info.id;
    this.kind = info.kind;
    this.remote = info.remote;
    this._constraints = info.constraints || {};
    this._enabled = info.enabled;
    this._settings = info.settings || {};
    this._muted = false;
    this._peerConnectionId = info.peerConnectionId;
    this._readyState = info.readyState;
    if (!this.remote) {
      this._registerEvents();
      if (this.kind === 'video') {
        this._processVideoTrackDimensionChangedQueue();
      }
    }
  }
  get enabled() {
    return this._enabled;
  }
  set enabled(enabled) {
    if (enabled === this._enabled) {
      return;
    }
    this._enabled = Boolean(enabled);
    if (this._readyState === 'ended') {
      return;
    }
    WebRTCModule.mediaStreamTrackSetEnabled(this.remote ? this._peerConnectionId : -1, this.id, this._enabled);
  }
  get muted() {
    return this._muted;
  }
  get readyState() {
    return this._readyState;
  }
  stop() {
    this.enabled = false;
    this._readyState = 'ended';
  }

  /**
   * Private / custom API for switching the cameras on the fly, without the
   * need for adding / removing tracks or doing any SDP renegotiation.
   *
   * This is how the reference application (AppRTCMobile) implements camera
   * switching.
   *
   * @deprecated Use applyConstraints instead.
   */
  _switchCamera() {
    if (this.remote) {
      throw new Error('Not implemented for remote tracks');
    }
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    const constraints = deepClone(this._settings);
    delete constraints.deviceId;
    constraints.facingMode = this._settings.facingMode === 'user' ? 'environment' : 'user';
    this.applyConstraints(constraints);
  }
  _setVideoEffects(names) {
    if (this.remote) {
      throw new Error('Not implemented for remote tracks');
    }
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    WebRTCModule.mediaStreamTrackSetVideoEffects(this.id, names);
  }
  _setVideoEffect(name) {
    if (name === null || name === undefined) {
      this._setVideoEffects([]);
      return;
    }
    this._setVideoEffects([name]);
  }

  /**
   * Internal function which is used to set the muted state on remote tracks and
   * emit the mute / unmute event.
   *
   * @param muted Whether the track should be marked as muted / unmuted.
   */
  _setMutedInternal(muted) {
    if (!this.remote) {
      throw new Error('Track is not remote!');
    }
    this._muted = muted;
    this.dispatchEvent(new Event(muted ? 'mute' : 'unmute'));
  }

  /**
   * Internal function which is used to set the video dimensions on video tracks.
   *
   * @param width The new width of the video track.
   * @param height The new height of the video track.
   */
  _setVideoTrackDimensions(width, height) {
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    this._settings = {
      ...this._settings,
      width,
      height
    };
  }

  /**
   * Custom API for setting the volume on an individual audio track.
   *
   * @param volume a gain value in the range of 0-10. defaults to 1.0
   */
  _setVolume(volume) {
    if (this.kind !== 'audio') {
      throw new Error('Only implemented for audio tracks');
    }
    WebRTCModule.mediaStreamTrackSetVolume(this.remote ? this._peerConnectionId : -1, this.id, volume);
  }

  /**
   * Applies a new set of constraints to the track.
   *
   * @param constraints An object listing the constraints
   * to apply to the track's constrainable properties; any existing
   * constraints are replaced with the new values specified, and any
   * constrainable properties not included are restored to their default
   * constraints. If this parameter is omitted, all currently set custom
   * constraints are cleared.
   */
  async applyConstraints(constraints) {
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    const normalized = normalizeConstraints({
      video: constraints !== null && constraints !== void 0 ? constraints : true
    });
    this._settings = await WebRTCModule.mediaStreamTrackApplyConstraints(this.id, normalized.video);
    this._constraints = constraints !== null && constraints !== void 0 ? constraints : {};
  }
  clone() {
    if (this.remote) {
      throw new Error('clone is not implemented for remote tracks');
    }
    const id = WebRTCModule.mediaStreamTrackClone(this.id);
    return new MediaStreamTrack({
      id,
      kind: this.kind,
      remote: this.remote,
      constraints: deepClone(this._constraints),
      enabled: this._enabled,
      settings: deepClone(this._settings),
      peerConnectionId: this._peerConnectionId,
      readyState: this._readyState
    });
  }
  getCapabilities() {
    throw new Error('Not implemented.');
  }
  getConstraints() {
    return deepClone(this._constraints);
  }
  getSettings() {
    return deepClone(this._settings);
  }
  _registerEvents() {
    addListener(this, 'mediaStreamTrackEnded', ev => {
      if (ev.trackId !== this.id || this._readyState === 'ended') {
        return;
      }
      log.debug(`${this.id} mediaStreamTrackEnded`);
      this._readyState = 'ended';
      this.dispatchEvent(new Event('ended'));
    });

    // Add dimension change listener for local video tracks
    if (this.kind === 'video') {
      addListener(this, 'videoTrackDimensionChanged', ev => {
        // Only handle local tracks (pcId === -1) and only for this track
        if (ev.pcId !== -1 || ev.trackId !== this.id) {
          return;
        }
        this._setVideoTrackDimensions(ev.width, ev.height);
      });
    }
  }

  /**
   * Processes any queued `videoTrackDimensionChanged` events for this track.
   */
  _processVideoTrackDimensionChangedQueue() {
    const eventData = videoTrackDimensionChangedEventQueue.get(this.id);
    if (!eventData) {
      return;
    }
    this._setVideoTrackDimensions(eventData.width, eventData.height);
    videoTrackDimensionChangedEventQueue.delete(this.id);
  }
  release() {
    if (this.remote) {
      return;
    }
    removeListener(this);
    WebRTCModule.mediaStreamTrackRelease(this.id);
    if (this.kind === 'video') {
      videoTrackDimensionChangedEventQueue.delete(this.id);
    }
  }
}

/**
 * Define the `onxxx` event handlers.
 */
const proto = MediaStreamTrack.prototype;
defineEventAttribute(proto, 'ended');
defineEventAttribute(proto, 'mute');
defineEventAttribute(proto, 'unmute');
//# sourceMappingURL=MediaStreamTrack.js.map