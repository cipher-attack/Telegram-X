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
 * File created on 19/07/2026
 * Group Call & Live Stream UI
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.voip.GroupCallController;
import org.thunderdog.challegram.widget.AvatarView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.vkryl.android.widget.FrameLayoutFix;

/**
 * Full-screen overlay for:
 *  - Voice chat (GroupConnectionMode = RTC)
 *  - Live stream viewer (GroupConnectionMode = Broadcast)
 *
 * Open via: TdlibUi.openVoiceChat() or TdlibUi.openLiveStream()
 */
public class GroupCallActivity extends ViewController<GroupCallActivity.Args>
    implements GroupCallController.Listener {

  // ── Arguments ─────────────────────────────────────────────────────────────

  public static class Args {
    public final long   chatId;
    public final int    groupCallId;
    public final boolean isLiveStream;

    public Args (long chatId, int groupCallId, boolean isLiveStream) {
      this.chatId      = chatId;
      this.groupCallId = groupCallId;
      this.isLiveStream = isLiveStream;
    }
  }

  // ── Participant model ──────────────────────────────────────────────────────

  private static class Participant {
    long   userId;
    String name;
    float  audioLevel;
    boolean isMuted;
    boolean isSpeaking;

    Participant (long userId, String name) {
      this.userId = userId;
      this.name   = name;
    }
  }

  // ── Fields ────────────────────────────────────────────────────────────────

  private static final String TAG = "GroupCallActivity";

  private GroupCallController controller;
  private final Handler       mainHandler = new Handler(Looper.getMainLooper());

  // Participants (voice chat)
  private final List<Participant>         participants    = new ArrayList<>();
  private final Map<Integer, Participant> ssrcMap         = new HashMap<>();
  private ParticipantAdapter              adapter;

  // UI
  private FrameLayoutFix contentView;
  private TextView       titleView;
  private TextView       statusView;
  private RecyclerView   participantsView;
  private ImageView      muteButton;
  private ImageView      leaveButton;
  private View           speakingIndicator;

  private boolean isMuted    = true;
  private boolean isConnected = false;

  // ── Constructor ───────────────────────────────────────────────────────────

  public GroupCallActivity (@NonNull Context context, @NonNull Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_groupCall;
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  @Override
  protected View onCreateView (Context context) {
    contentView = new FrameLayoutFix(context);
    contentView.setBackgroundColor(0xFF1A1A2E); // dark navy background

    buildUI(context);
    startCall();

    return contentView;
  }

  @Override
  public void onDestroy () {
    super.onDestroy();
    if (controller != null) {
      controller.destroy();
      controller = null;
    }
  }

  // ── UI Construction ───────────────────────────────────────────────────────

  private void buildUI (Context context) {
    Args args = getArguments();
    boolean isLiveStream = args != null && args.isLiveStream;

    // ── Header ──────────────────────────────────────────────────────────────
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setGravity(Gravity.CENTER_HORIZONTAL);
    header.setPadding(0, Screen.dp(48), 0, Screen.dp(16));

    titleView = new TextView(context);
    titleView.setTextColor(Color.WHITE);
    titleView.setTextSize(20);
    titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    titleView.setText(isLiveStream ? "Live Stream" : "Voice Chat");
    titleView.setGravity(Gravity.CENTER);
    header.addView(titleView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    statusView = new TextView(context);
    statusView.setTextColor(0xFFAAAAAA);
    statusView.setTextSize(14);
    statusView.setText("Connecting...");
    statusView.setGravity(Gravity.CENTER);
    header.addView(statusView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    contentView.addView(header, headerParams);

    // ── Speaking indicator (animated ring) ──────────────────────────────────
    speakingIndicator = new View(context) {
      private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      private float alpha = 0f;

      @Override
      protected void onDraw (Canvas c) {
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        int r  = Math.min(cx, cy) - Screen.dp(4);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Screen.dp(3));
        ringPaint.setColor(Color.argb((int)(alpha * 255), 0x53, 0xE5, 0x81));
        c.drawCircle(cx, cy, r, ringPaint);
      }

      public void setAlpha2 (float a) {
        alpha = a;
        invalidate();
      }
    };
    speakingIndicator.setVisibility(View.INVISIBLE);

    // ── Participants list (voice chat) / Stream placeholder (live stream) ───
    if (isLiveStream) {
      buildLiveStreamView(context, header);
    } else {
      buildParticipantsView(context);
    }

    // ── Bottom controls ──────────────────────────────────────────────────────
    buildControls(context, isLiveStream);
  }

  private void buildParticipantsView (Context context) {
    participantsView = new RecyclerView(context);
    participantsView.setLayoutManager(new LinearLayoutManager(context));
    adapter = new ParticipantAdapter();
    participantsView.setAdapter(adapter);
    participantsView.setBackgroundColor(Color.TRANSPARENT);

    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT);
    params.topMargin    = Screen.dp(140);
    params.bottomMargin = Screen.dp(120);
    contentView.addView(participantsView, params);
  }

  private void buildLiveStreamView (Context context, LinearLayout parent) {
    // Placeholder for video stream surface
    View streamPlaceholder = new View(context);
    streamPlaceholder.setBackgroundColor(0xFF000000);

    TextView streamLabel = new TextView(context);
    streamLabel.setText("📡  Live Stream");
    streamLabel.setTextColor(0xFF53E581);
    streamLabel.setTextSize(16);
    streamLabel.setGravity(Gravity.CENTER);

    FrameLayout streamContainer = new FrameLayout(context);
    streamContainer.addView(streamPlaceholder, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    streamContainer.addView(streamLabel, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER));

    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        Screen.dp(240));
    params.topMargin = Screen.dp(140);
    contentView.addView(streamContainer, params);
  }

  private void buildControls (Context context, boolean isLiveStream) {
    LinearLayout controls = new LinearLayout(context);
    controls.setOrientation(LinearLayout.HORIZONTAL);
    controls.setGravity(Gravity.CENTER);
    controls.setPadding(Screen.dp(32), Screen.dp(16), Screen.dp(32), Screen.dp(32));

    // Mute button (voice chat only)
    if (!isLiveStream) {
      muteButton = new ImageView(context);
      muteButton.setImageResource(R.drawable.baseline_mic_off_24);
      muteButton.setBackgroundColor(0xFF2A2A3E);
      muteButton.setPadding(Screen.dp(16), Screen.dp(16), Screen.dp(16), Screen.dp(16));
      muteButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      muteButton.setColorFilter(isMuted ? Color.RED : Color.WHITE);
      muteButton.setOnClickListener(v -> toggleMute());

      LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(
          Screen.dp(64), Screen.dp(64));
      muteParams.setMargins(Screen.dp(16), 0, Screen.dp(16), 0);
      controls.addView(muteButton, muteParams);
    }

    // Leave / stop button
    leaveButton = new ImageView(context);
    leaveButton.setImageResource(R.drawable.baseline_call_end_24);
    leaveButton.setBackgroundColor(0xFFE53935);
    leaveButton.setPadding(Screen.dp(16), Screen.dp(16), Screen.dp(16), Screen.dp(16));
    leaveButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    leaveButton.setColorFilter(Color.WHITE);
    leaveButton.setOnClickListener(v -> leaveCall());

    LinearLayout.LayoutParams leaveParams = new LinearLayout.LayoutParams(
        Screen.dp(64), Screen.dp(64));
    leaveParams.setMargins(Screen.dp(16), 0, Screen.dp(16), 0);
    controls.addView(leaveButton, leaveParams);

    FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM);
    contentView.addView(controls, controlsParams);
  }

  // ── Call Management ───────────────────────────────────────────────────────

  private void startCall () {
    Args args = getArguments();
    if (args == null) return;

    controller = new GroupCallController(tdlib(), args.isLiveStream);
    controller.setListener(this);
    controller.create(isMuted, true);
    controller.join(args.groupCallId);

    // Load participants from TDLib
    loadParticipants(args.groupCallId);
  }

  private void loadParticipants (int groupCallId) {
    tdlib().client().send(
        new TdApi.LoadGroupCallParticipants(groupCallId, 100),
        result -> {
          if (result instanceof TdApi.Error) {
            Log.e(TAG, "LoadGroupCallParticipants error: " + ((TdApi.Error)result).message);
          }
        }
    );
  }

  private void toggleMute () {
    isMuted = !isMuted;
    if (controller != null) controller.setMuted(isMuted);
    if (muteButton != null) {
      muteButton.setColorFilter(isMuted ? Color.RED : Color.WHITE);
      muteButton.setImageResource(isMuted
          ? R.drawable.baseline_mic_off_24
          : R.drawable.baseline_mic_24);
    }
    // Notify TDLib
    Args args = getArguments();
    if (args != null) {
      tdlib().client().send(
          new TdApi.ToggleGroupCallParticipantIsMuted(args.groupCallId, null, isMuted),
          r -> {}
      );
    }
  }

  private void leaveCall () {
    if (controller != null) {
      controller.destroy();
      controller = null;
    }
    UI.getContext(context()).runOnUiThread(this::navigateBack);
  }

  // ── GroupCallController.Listener ──────────────────────────────────────────

  @Override
  public void onNetworkStateChanged (boolean connected, boolean isBroadcast) {
    mainHandler.post(() -> {
      isConnected = connected;
      if (statusView != null) {
        statusView.setText(connected
            ? (isBroadcast ? "Watching" : "Connected")
            : "Reconnecting...");
        statusView.setTextColor(connected ? 0xFF53E581 : 0xFFAAAAAA);
      }
    });
  }

  @Override
  public void onAudioLevelsUpdated (int[] ssrcs, float[] levels, boolean[] voices) {
    mainHandler.post(() -> {
      boolean anyoneSpeaking = false;
      for (int i = 0; i < ssrcs.length; i++) {
        Participant p = ssrcMap.get(ssrcs[i]);
        if (p != null) {
          p.audioLevel  = levels[i];
          p.isSpeaking  = voices[i] && levels[i] > 0.1f;
          if (p.isSpeaking) anyoneSpeaking = true;
        }
      }
      if (adapter != null) adapter.notifyDataSetChanged();

      // Speaking indicator
      if (speakingIndicator != null) {
        speakingIndicator.setVisibility(anyoneSpeaking ? View.VISIBLE : View.INVISIBLE);
      }
    });
  }

  @Override
  public void onJoinPayloadReady (String payloadJson) {
    Log.d(TAG, "onJoinPayloadReady");
  }

  @Override
  public void onStats (String statsJson) {
    Log.d(TAG, "Stats: " + statsJson);
  }

  // ── Participants Adapter ──────────────────────────────────────────────────

  private class ParticipantAdapter extends RecyclerView.Adapter<ParticipantViewHolder> {

    @NonNull
    @Override
    public ParticipantViewHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      LinearLayout row = new LinearLayout(parent.getContext());
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(Screen.dp(16), Screen.dp(8), Screen.dp(16), Screen.dp(8));
      row.setLayoutParams(new RecyclerView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(56)));
      return new ParticipantViewHolder(row);
    }

    @Override
    public void onBindViewHolder (@NonNull ParticipantViewHolder holder, int position) {
      holder.bind(participants.get(position));
    }

    @Override
    public int getItemCount () { return participants.size(); }
  }

  private static class ParticipantViewHolder extends RecyclerView.ViewHolder {
    private final TextView nameView;
    private final View     levelBar;

    ParticipantViewHolder (LinearLayout row) {
      super(row);

      // Audio level indicator circle
      levelBar = new View(row.getContext()) {
        private float level = 0f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        protected void onDraw (Canvas c) {
          p.setColor(level > 0.1f ? 0xFF53E581 : 0xFF555555);
          c.drawCircle(getWidth() / 2f, getHeight() / 2f,
              getWidth() / 2f - Screen.dp(1), p);
        }

        public void setLevel (float l) { level = l; invalidate(); }
      };
      LinearLayout.LayoutParams lvlParams =
          new LinearLayout.LayoutParams(Screen.dp(40), Screen.dp(40));
      lvlParams.setMargins(0, 0, Screen.dp(12), 0);
      row.addView(levelBar, lvlParams);

      nameView = new TextView(row.getContext());
      nameView.setTextColor(Color.WHITE);
      nameView.setTextSize(15);
      row.addView(nameView, new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    void bind (Participant p) {
      nameView.setText(p.name + (p.isMuted ? "  🔇" : ""));
      levelBar.invalidate();
    }
  }

  // ── TDLib update handler (called from Tdlib listeners) ───────────────────

  /**
   * Call this when TDLib sends updateGroupCallParticipant.
   * Should be wired from TdlibListeners in a future update.
   */
  public void onParticipantUpdate (TdApi.GroupCallParticipant participant) {
    mainHandler.post(() -> {
      int ssrc = participant.audioSourceId;
      Participant p = ssrcMap.get(ssrc);
      if (p == null && !participant.isCurrentUser) {
        // Get name from TDLib cache
        String name = "User " + participant.participantId;
        if (participant.participantId instanceof TdApi.MessageSenderUser) {
          long uid = ((TdApi.MessageSenderUser) participant.participantId).userId;
          TdApi.User user = tdlib().cache().user(uid);
          if (user != null) name = user.firstName + " " + user.lastName;
        }
        p = new Participant(ssrc, name);
        ssrcMap.put(ssrc, p);
        participants.add(p);
      }
      if (p != null) {
        p.isMuted = participant.isMuted;
        if (adapter != null) adapter.notifyDataSetChanged();
      }
    });
  }
}
