package com.sipvideochat.ui.main;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.sipvideochat.R;
import com.sipvideochat.model.ChatGroup;
import com.sipvideochat.model.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Contacts list fragment.
 */
public class ContactsFragment extends Fragment {

    private ContactAdapter adapter;
    private TextInputEditText etAddContact;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MainActivity activity = (MainActivity) requireActivity();

        RecyclerView rvContacts = view.findViewById(R.id.rvContacts);
        etAddContact = view.findViewById(R.id.etAddContact);
        MaterialButton btnAddContact = view.findViewById(R.id.btnAddContact);
        MaterialButton btnCreateGroup = view.findViewById(R.id.btnCreateGroup);

        adapter = new ContactAdapter(
                new ArrayList<>(activity.getContacts()),
                activity::openChat,
                this::onConversationLongClick);
        rvContacts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvContacts.setAdapter(adapter);
        for (MainActivity.ConversationItem contact : activity.getContacts()) {
            if (contact.section) {
                continue;
            }
            List<Message> history = activity.getChatHistory(contact.key);
            if (!history.isEmpty()) {
                adapter.updateLastMessage(contact.key, MessageUiUtil.buildPreviewText(history.get(history.size() - 1)));
            }
        }

        btnAddContact.setOnClickListener(v -> addContact());
        btnCreateGroup.setOnClickListener(v -> showCreateGroupDialog());
        etAddContact.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addContact();
                return true;
            }
            return false;
        });

        activity.getContactListLiveData().observe(getViewLifecycleOwner(), adapter::updateContacts);

        activity.getMessageEventsLiveData().observe(getViewLifecycleOwner(), event -> {
            if (event == null || event.message == null) {
                return;
            }
            adapter.updateLastMessage(event.contactUser, MessageUiUtil.buildPreviewText(event.message));
        });
    }

    private void addContact() {
        if (etAddContact.getText() == null) {
            return;
        }
        String username = etAddContact.getText().toString().trim();
        if (!username.isEmpty()) {
            ((MainActivity) requireActivity()).addContact(username);
            etAddContact.setText("");
        }
    }

    private void showCreateGroupDialog() {
        MainActivity activity = (MainActivity) requireActivity();
        List<String> knownContacts = new ArrayList<>();
        for (MainActivity.ConversationItem item : activity.getContacts()) {
            if (item.section) {
                continue;
            }
            if (!item.group && item.key != null && !item.key.trim().isEmpty() && !item.key.equals(activity.getMyUsername())) {
                knownContacts.add(item.key);
            }
        }

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * requireContext().getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        EditText etGroupName = new EditText(requireContext());
        etGroupName.setHint("Group name");
        container.addView(etGroupName);

        EditText etDescription = new EditText(requireContext());
        etDescription.setHint("Description");
        container.addView(etDescription);

        TextView tvKnownMembers = new TextView(requireContext());
        tvKnownMembers.setText("Select members");
        container.addView(tvKnownMembers);

        LinearLayout memberList = new LinearLayout(requireContext());
        memberList.setOrientation(LinearLayout.VERTICAL);
        List<CheckBox> memberBoxes = new ArrayList<>();
        for (String username : knownContacts) {
            CheckBox checkBox = new CheckBox(requireContext());
            checkBox.setText(username);
            memberList.addView(checkBox);
            memberBoxes.add(checkBox);
        }

        ScrollView memberScroll = new ScrollView(requireContext());
        memberScroll.addView(memberList);
        int maxHeight = (int) (180 * requireContext().getResources().getDisplayMetrics().density);
        memberScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeight));
        container.addView(memberScroll);

        EditText etExtraMembers = new EditText(requireContext());
        etExtraMembers.setHint("Extra usernames, comma separated");
        etExtraMembers.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(etExtraMembers);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Create group")
                .setView(container)
                .setPositiveButton("Create", (dialog, which) -> {
                    try {
                        Set<String> members = new LinkedHashSet<>();
                        for (CheckBox box : memberBoxes) {
                            if (box.isChecked()) {
                                members.add(box.getText().toString().trim());
                            }
                        }
                        members.addAll(parseMembers(etExtraMembers.getText() == null ? "" : etExtraMembers.getText().toString()));
                        activity.createGroup(
                                etGroupName.getText() == null ? "" : etGroupName.getText().toString(),
                                etDescription.getText() == null ? "" : etDescription.getText().toString(),
                                new ArrayList<>(members));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onConversationLongClick(MainActivity.ConversationItem item) {
        if (!item.group) {
            return;
        }
        showManageGroupDialog(item.key);
    }

    private void showManageGroupDialog(String conversationKey) {
        MainActivity activity = (MainActivity) requireActivity();
        ChatGroup group = activity.getGroupForConversation(conversationKey);
        if (group == null) {
            Toast.makeText(requireContext(), "Group not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * requireContext().getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        EditText etGroupName = new EditText(requireContext());
        etGroupName.setHint("Group name");
        etGroupName.setText(group.getName());
        container.addView(etGroupName);

        EditText etDescription = new EditText(requireContext());
        etDescription.setHint("Description");
        etDescription.setText(group.getDescription());
        container.addView(etDescription);

        EditText etMembers = new EditText(requireContext());
        etMembers.setHint("Members, comma separated");
        etMembers.setInputType(InputType.TYPE_CLASS_TEXT);
        etMembers.setText(joinMembers(group.getMemberIds(), activity.getMyUsername()));
        container.addView(etMembers);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Manage group")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        activity.updateGroup(
                                group.getId(),
                                etGroupName.getText() == null ? "" : etGroupName.getText().toString(),
                                etDescription.getText() == null ? "" : etDescription.getText().toString(),
                                parseMembers(etMembers.getText() == null ? "" : etMembers.getText().toString()));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Delete", (dialog, which) -> activity.deleteGroup(group.getId()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<String> parseMembers(String rawMembers) {
        List<String> members = new ArrayList<>();
        for (String item : Arrays.asList(rawMembers.split(","))) {
            String member = item.trim();
            if (!member.isEmpty()) {
                members.add(member);
            }
        }
        return members;
    }

    private String joinMembers(List<String> members, String selfUser) {
        List<String> others = new ArrayList<>();
        if (members != null) {
            for (String member : members) {
                if (member != null && !member.trim().isEmpty() && !member.equals(selfUser)) {
                    others.add(member.trim());
                }
            }
        }
        return String.join(", ", others);
    }
}
