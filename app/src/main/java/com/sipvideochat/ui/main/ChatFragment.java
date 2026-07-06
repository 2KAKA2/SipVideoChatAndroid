package com.sipvideochat.ui.main;

import android.Manifest;
import android.content.Intent;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.sipvideochat.R;
import com.sipvideochat.model.Message;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat fragment.
 */
public class ChatFragment extends Fragment {
    private static final String TAG = "ChatFragment";
    private static final String ARG_CONVERSATION_KEY = "conversation_key";
    private static final String ARG_TITLE = "title";
    private static final String ARG_IS_GROUP = "is_group";

    private String conversationKey;
    private String conversationTitle;
    private boolean groupConversation;
    private RecyclerView rvMessages;
    private ChatMessageAdapter adapter;
    private TextInputEditText etMessage;
    private List<Message> messages = new ArrayList<>();
    private String pendingAudioAction = null;
    private boolean isRecording = false;
    private MediaRecorder mediaRecorder;
    private String recordingFilePath;
    private long recordingStartTimeMs;
    private ImageButton btnVoiceMessage;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    MainActivity activity = (MainActivity) requireActivity();
                    if (groupConversation) {
                        activity.makeGroupVideoCall(conversationKey);
                    } else {
                        activity.makeVideoCall(conversationKey);
                    }
                } else {
                    showSnackbar("Camera permission is required for video calls.");
                }
            });

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    showSnackbar("Microphone permission is required.");
                    pendingAudioAction = null;
                    return;
                }

                MainActivity activity = (MainActivity) requireActivity();
                if ("audio_call".equals(pendingAudioAction)) {
                    if (groupConversation) {
                        activity.makeGroupAudioCall(conversationKey);
                    } else {
                        activity.makeAudioCall(conversationKey);
                    }
                } else if ("video_call".equals(pendingAudioAction)) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                } else if ("voice_record".equals(pendingAudioAction)) {
                    startVoiceRecording();
                }
                pendingAudioAction = null;
            });

    private final ActivityResultLauncher<String[]> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                takePersistableReadPermission(uri);
                ((MainActivity) requireActivity()).sendImageMessage(conversationKey, uri);
            });

    private final ActivityResultLauncher<String[]> videoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                takePersistableReadPermission(uri);
                ((MainActivity) requireActivity()).sendVideoMessage(conversationKey, uri);
            });

    public static ChatFragment newInstance(String conversationKey, String title, boolean isGroup) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_KEY, conversationKey);
        args.putString(ARG_TITLE, title);
        args.putBoolean(ARG_IS_GROUP, isGroup);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            conversationKey = getArguments().getString(ARG_CONVERSATION_KEY);
            conversationTitle = getArguments().getString(ARG_TITLE);
            groupConversation = getArguments().getBoolean(ARG_IS_GROUP, false);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MainActivity activity = (MainActivity) requireActivity();

        TextView tvChatUser = view.findViewById(R.id.tvChatUser);
        tvChatUser.setText(conversationTitle);

        ImageButton btnBack = view.findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> {
            activity.showContactsList();
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        ImageButton btnAudioCall = view.findViewById(R.id.btnAudioCall);
        ImageButton btnVideoCall = view.findViewById(R.id.btnVideoCall);
        btnAudioCall.setOnClickListener(v -> {
            pendingAudioAction = "audio_call";
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        });

        btnVideoCall.setOnClickListener(v -> {
            pendingAudioAction = "video_call";
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        });

        ImageButton btnImageMessage = view.findViewById(R.id.btnImageMessage);
        btnImageMessage.setOnClickListener(v -> imagePickerLauncher.launch(new String[]{"image/*"}));

        ImageButton btnVideoMessage = view.findViewById(R.id.btnVideoMessage);
        btnVideoMessage.setOnClickListener(v -> videoPickerLauncher.launch(new String[]{"video/*"}));

        btnVoiceMessage = view.findViewById(R.id.btnVoiceMessage);
        btnVoiceMessage.setOnClickListener(v -> onVoiceMessageClicked());
        updateVoiceButtonState();

        rvMessages = view.findViewById(R.id.rvMessages);
        messages = new ArrayList<>(activity.getChatHistory(conversationKey));
        adapter = new ChatMessageAdapter(messages, activity.getMyUsername());
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);
        scrollToBottom();

        etMessage = view.findViewById(R.id.etMessage);
        MaterialButton btnSend = view.findViewById(R.id.btnSend);

        btnSend.setOnClickListener(v -> sendTextMessage());
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage();
                return true;
            }
            return false;
        });

        activity.getMessageEventsLiveData().observe(getViewLifecycleOwner(), event -> {
            if (event == null || !conversationKey.equals(event.contactUser)) {
                return;
            }

            int index = indexOfMessage(event.message.getId());
            if (index >= 0) {
                messages.set(index, event.message);
                adapter.notifyItemChanged(index);
            } else {
                messages.add(event.message);
                adapter.notifyItemInserted(messages.size() - 1);
            }
            scrollToBottom();
        });
    }

    private void sendTextMessage() {
        if (etMessage.getText() == null) {
            return;
        }
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }

        ((MainActivity) requireActivity()).sendTextMessage(conversationKey, text);
        etMessage.setText("");
    }

    private void onVoiceMessageClicked() {
        if (isRecording) {
            stopVoiceRecordingAndSend();
            return;
        }
        pendingAudioAction = "voice_record";
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void startVoiceRecording() {
        if (isRecording) {
            return;
        }

        try {
            File outputFile = new File(requireContext().getCacheDir(),
                    "voice_" + System.currentTimeMillis() + ".m4a");
            recordingFilePath = outputFile.getAbsolutePath();
            recordingStartTimeMs = System.currentTimeMillis();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(recordingFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            updateVoiceButtonState();
            Toast.makeText(requireContext(), "Recording started. Tap again to send.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start voice recording", e);
            releaseRecorder();
            showSnackbar("Failed to start recording.");
        }
    }

    private void stopVoiceRecordingAndSend() {
        if (!isRecording) {
            return;
        }

        try {
            mediaRecorder.stop();
            int durationSeconds = Math.max(1, (int) ((System.currentTimeMillis() - recordingStartTimeMs) / 1000L));
            Uri uri = Uri.fromFile(new File(recordingFilePath));
            ((MainActivity) requireActivity()).sendVoiceMessage(conversationKey, uri, durationSeconds);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop/send voice recording", e);
            showSnackbar("Failed to send voice message.");
        } finally {
            releaseRecorder();
            updateVoiceButtonState();
        }
    }

    private void releaseRecorder() {
        isRecording = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {
            }
            mediaRecorder = null;
        }
    }

    private void updateVoiceButtonState() {
        if (btnVoiceMessage == null) {
            return;
        }
        btnVoiceMessage.setImageResource(isRecording
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_btn_speak_now);
        btnVoiceMessage.setContentDescription(isRecording
                ? "Stop and send voice message"
                : "Record voice message");
    }

    private void takePersistableReadPermission(Uri uri) {
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private int indexOfMessage(String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            rvMessages.scrollToPosition(messages.size() - 1);
        }
    }

    private void showSnackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        if (adapter != null) {
            adapter.release();
        }
        if (isRecording) {
            releaseRecorder();
        }
        super.onDestroyView();
    }
}