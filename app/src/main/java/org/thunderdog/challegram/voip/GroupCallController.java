/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 18/07/2026
 * Group Call & Live Stream Controller
 */
package org.thunderdog.challegram.voip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a single group voice chat or live stream session.
 *
 * Lifecycle:
 *   create() → join() → [use] → leave() → destroy()
 *
 * Thread safety: all public methods are safe to call from any thread.
 * Callbacks are delivered on the main thread.
 */
public class GroupCallController {

  private static final String TAG = "GroupCallController";

  // ── Connection mode constants (mirror C++ GroupConnectionMode) ───────────
  public static final int MODE_NONE      = 0;
  public static final int MODE_RTC       = 1;  // voice chat
  public static final int MODE_BROADCAST = 2;  // live stream viewer

  // ── Broadcast part status (mirror C++ BroadcastPart::Status) ────────────
  public static final int PART_SUCCESS       = 0;
  public static final int PART_NOT_READY     = 1;
  public static final int PART_RESYNC_NEEDED = 2;

  // ── Listener ─────────────────────────────────────────────────────────────

  public interface Listener {
    /** Network connected/disconnected; isBroadcast=true when in live-stream mode */
    void onNetworkStateChanged (boolean connected, boolean isBroadcast);

    /** Audio levels for participants. Arrays are parallel and same length. */
    void onAudioLevelsUpdated  (int[] ssrcs, float[] levels, boolean[] voices);

    /** C++ has produced a join payload JSON — send it to TDLib */
    void onJoinPayloadReady    (String payloadJson);

    /** Stats JSON delivered (see nativeGetStats) */
    void onStats               (String statsJson);
  }

  // ── Fields ───────────────────────────────────────────────────────────────

  private final Tdlib   tdlib;
  private final boolean isLiveStream;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private volatile long   nativePtr   = 0;
  private volatile long   groupCallId = 0;

  @Nullable private Listener listener;

  // ── Constructor ──────────────────────────────────────────────────────────

  public GroupCallController (@NonNull Tdlib tdlib, boolean isLiveStream) {
    this.tdlib        = tdlib;
    this.isLiveStream = isLiveStream;
  }

  // ── Public API ───────────────────────────────────────────────────────────

  public void setListener (@Nullable Listener l) {
    this.listener = l;
  }

  /**
   * Create the native instance.
   * Must be called before join().
   */
  public synchronized void create (boolean startMuted, boolean noiseSuppressionEnabled) {
    if (nativePtr != 0) return;
    nativePtr = nativeCreate(startMuted, isLiveStream, noiseSuppressionEnabled);
    Log.i(TAG, "Created native ptr=" + nativePtr + " isLiveStream=" + isLiveStream);
  }

  /**
   * Join a group call or live stream by its TDLib groupCallId.
   * Internally calls emitJoinPayload → waits for onJoinPayloadReady →
   * then calls TdApi.JoinGroupCall / TdApi.StartGroupCallStream.
   */
  public synchronized void join (long callId) {
    if (nativePtr == 0) {
      Log.e(TAG, "join() called but native not created");
      return;
    }
    this.groupCallId = callId;
    // Ask C++ to produce the SDP/join payload
    nativeEmitJoinPayload(nativePtr);
  }

  /** Mute or unmute local microphone */
  public void setMuted (boolean muted) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeSetIsMuted(ptr, muted);
  }

  /** Enable/disable noise suppression */
  public void setNoiseSuppressionEnabled (boolean enabled) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeSetNoiseSuppressionEnabled(ptr, enabled);
  }

  /** Set per-participant volume (0.0 – 2.0) */
  public void setParticipantVolume (int ssrc, double volume) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeSetVolume(ptr, ssrc, volume);
  }

  /** Remove participants by ssrc (called when they leave) */
  public void removeSsrcs (int[] ssrcs) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeRemoveSsrcs(ptr, ssrcs);
  }

  /**
   * Switch between RTC (voice) and Broadcast (live stream) modes.
   * keepBroadcast: keep broadcast audio while transitioning.
   * isUnified: unified broadcast mode (Telegram's newer live stream).
   */
  public void setConnectionMode (int mode, boolean keepBroadcast, boolean isUnified) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeSetConnectionMode(ptr, mode, keepBroadcast, isUnified);
  }

  /** Request stats (delivered async via onStats callback) */
  public void requestStats () {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeGetStats(ptr);
  }

  /** Leave and destroy. Safe to call multiple times. */
  public synchronized void destroy () {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativePtr = 0;
    // Leave group call on TDLib side
    if (groupCallId != 0) {
      tdlib.client().send(
          new TdApi.LeaveGroupCall((int) groupCallId),
          result -> Log.i(TAG, "LeaveGroupCall result: " + result.getClass().getSimpleName())
      );
    }
    nativeDestroy(ptr);
    Log.i(TAG, "Destroyed");
  }

  // ── Live stream broadcast part providers ─────────────────────────────────
  // Called from Java when TDLib responds to our broadcast part requests.

  /** Provide current broadcast time (millis) back to C++ */
  public void provideCurrentTime (long timeMillis) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeProvideCurrentTime(ptr, timeMillis);
  }

  /**
   * Provide an audio broadcast chunk.
   * @param data   raw OGG/Opus bytes (null if status != SUCCESS)
   * @param status PART_SUCCESS / PART_NOT_READY / PART_RESYNC_NEEDED
   */
  public void provideAudioBroadcastPart (
      long timestampMillis, double responseTimestamp,
      int status, @Nullable byte[] data) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeProvideAudioPart(ptr, timestampMillis, responseTimestamp, status, data);
  }

  /**
   * Provide a video broadcast chunk.
   * @param data   raw video bytes (null if status != SUCCESS)
   * @param status PART_SUCCESS / PART_NOT_READY / PART_RESYNC_NEEDED
   */
  public void provideVideoBroadcastPart (
      long timestampMillis, double responseTimestamp,
      int status, @Nullable byte[] data) {
    long ptr = nativePtr;
    if (ptr == 0) return;
    nativeProvideVideoPart(ptr, timestampMillis, responseTimestamp, status, data);
  }

  // ── C++ → Java callbacks (called from native threads) ───────────────────

  /** Called by C++ when network state changes */
  @SuppressWarnings("unused")
  private void onNetworkStateChanged (boolean connected, boolean isBroadcast) {
    mainHandler.post(() -> {
      Listener l = listener;
      if (l != null) l.onNetworkStateChanged(connected, isBroadcast);
    });
  }

  /** Called by C++ with periodic audio level updates */
  @SuppressWarnings("unused")
  private void onAudioLevelsUpdated (int[] ssrcs, float[] levels, boolean[] voices) {
    mainHandler.post(() -> {
      Listener l = listener;
      if (l != null) l.onAudioLevelsUpdated(ssrcs, levels, voices);
    });
  }

  /**
   * Called by C++ when the SDP join payload is ready.
   * We forward it to TDLib's JoinGroupCall.
   */
  @SuppressWarnings("unused")
  private void onJoinPayloadReady (String payloadJson) {
    Log.d(TAG, "onJoinPayloadReady: " + payloadJson);
    mainHandler.post(() -> {
      Listener l = listener;
      if (l != null) l.onJoinPayloadReady(payloadJson);

      if (groupCallId == 0) return;

      // Build TdApi.JoinGroupCall with our payload
      TdApi.JoinGroupCall joinCall = new TdApi.JoinGroupCall(
          (int) groupCallId, null, 0, payloadJson, false, false, "");
      tdlib.client().send(joinCall, result -> {
        if (result instanceof TdApi.Text) {
          String responseJson = ((TdApi.Text) result).text;
          long ptr = nativePtr;
          if (ptr != 0) nativeSetJoinResponsePayload(ptr, responseJson);
        } else if (result instanceof TdApi.Error) {
          TdApi.Error err = (TdApi.Error) result;
          Log.e(TAG, "JoinGroupCall error: " + err.code + " " + err.message);
        }
      });
          }
        } else if (result instanceof TdApi.Error) {
          TdApi.Error err = (TdApi.Error) result;
          Log.e(TAG, "JoinGroupCall error: " + err.code + " " + err.message);
        }
      });
    });
  }

  /** Called by C++ to request current broadcast time from TDLib */
  @SuppressWarnings("unused")
  private void requestCurrentTime () {
    tdlib.client().send(
        new TdApi.GetGroupCallStreams((int) groupCallId),
        result -> {
          long timeMillis = System.currentTimeMillis();
          if (result instanceof TdApi.GroupCallStreams) {
            TdApi.GroupCallStreams streams = (TdApi.GroupCallStreams) result;
            if (streams.streams.length > 0) {
              timeMillis = streams.streams[0].timeOffset;
            }
          }
          provideCurrentTime(timeMillis);
        }
    );
  }

  /** Called by C++ to request an audio broadcast chunk from TDLib */
  @SuppressWarnings("unused")
  private void requestAudioBroadcastPart (long timestamp, long duration) {
    TdApi.GetGroupCallStreamSegment req = new TdApi.GetGroupCallStreamSegment();
    req.groupCallId    = (int) groupCallId;
    req.timeOffset     = timestamp;
    req.scale          = 0;
    req.channelId      = 0;
    req.videoQuality   = null;

    tdlib.client().send(req, result -> {
      if (result instanceof TdApi.Error) {
        provideAudioBroadcastPart(timestamp, 0, PART_NOT_READY, null);
      } else {
        try {
          java.lang.reflect.Field fd = result.getClass().getField("data");
          byte[] data = (byte[]) fd.get(result);
          provideAudioBroadcastPart(timestamp, System.currentTimeMillis() / 1000.0, PART_SUCCESS, data);
        } catch (Exception e) {
          provideAudioBroadcastPart(timestamp, 0, PART_NOT_READY, null);
        }
      }
    });
  }

  /** Called by C++ to request a video broadcast chunk from TDLib */
  @SuppressWarnings("unused")
  private void requestVideoBroadcastPart (long timestamp, long duration, int channelId, int quality) {
    TdApi.GetGroupCallStreamSegment req = new TdApi.GetGroupCallStreamSegment();
    req.groupCallId  = (int) groupCallId;
    req.timeOffset   = timestamp;
    req.scale        = 0;
    req.channelId    = channelId;

    // Map quality int → TdApi quality object
    switch (quality) {
      case 2:  req.videoQuality = new TdApi.GroupCallVideoQualityFull();   break;
      case 1:  req.videoQuality = new TdApi.GroupCallVideoQualityMedium(); break;
      default: req.videoQuality = new TdApi.GroupCallVideoQualityThumbnail(); break;
    }

    tdlib.client().send(req, result -> {
      if (result instanceof TdApi.Error) {
        provideVideoBroadcastPart(timestamp, 0, PART_NOT_READY, null);
      } else {
        try {
          java.lang.reflect.Field fd = result.getClass().getField("data");
          byte[] data = (byte[]) fd.get(result);
          provideVideoBroadcastPart(timestamp, System.currentTimeMillis() / 1000.0, PART_SUCCESS, data);
        } catch (Exception e) {
          provideVideoBroadcastPart(timestamp, 0, PART_NOT_READY, null);
        }
      }
    });
  }

  /** Called by C++ with SSRCs that need media channel descriptions */
  @SuppressWarnings("unused")
  private void requestMediaChannelDescriptions (int[] ssrcs) {
    // TDLib handles this internally via updateGroupCallParticipant.
    // For now we just log; full implementation maps ssrc → participant.
    Log.d(TAG, "requestMediaChannelDescriptions: " + ssrcs.length + " ssrcs");
  }

  /** Stats JSON delivered from C++ */
  @SuppressWarnings("unused")
  private void onStats (String statsJson) {
    mainHandler.post(() -> {
      Listener l = listener;
      if (l != null) l.onStats(statsJson);
    });
  }

  // ── Native method declarations ───────────────────────────────────────────

  private native long nativeCreate                    (boolean isMuted, boolean isLiveStream, boolean noiseSuppressionEnabled);
  private native void nativeDestroy                   (long ptr);
  private native void nativeEmitJoinPayload           (long ptr);
  private native void nativeSetJoinResponsePayload    (long ptr, String payload);
  private native void nativeSetIsMuted                (long ptr, boolean isMuted);
  private native void nativeSetNoiseSuppressionEnabled(long ptr, boolean enabled);
  private native void nativeSetVolume                 (long ptr, int ssrc, double volume);
  private native void nativeRemoveSsrcs               (long ptr, int[] ssrcs);
  private native void nativeSetConnectionMode         (long ptr, int mode, boolean keepBroadcast, boolean isUnified);
  private native void nativeProvideCurrentTime        (long ptr, long timeMillis);
  private native void nativeProvideAudioPart          (long ptr, long timestampMillis, double responseTimestamp, int status, byte[] data);
  private native void nativeProvideVideoPart          (long ptr, long timestampMillis, double responseTimestamp, int status, byte[] data);
  private native void nativeGetStats                  (long ptr);
}
