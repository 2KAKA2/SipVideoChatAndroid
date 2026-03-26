package com.sipvideochat.ui.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.sipvideochat.R;

import java.util.ArrayList;

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

        adapter = new ContactAdapter(new ArrayList<>(activity.getContacts()), activity::openChat);
        rvContacts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvContacts.setAdapter(adapter);
        for (String contact : activity.getContacts()) {
            java.util.List<com.sipvideochat.model.Message> history = activity.getChatHistory(contact);
            if (!history.isEmpty()) {
                adapter.updateLastMessage(contact, MessageUiUtil.buildPreviewText(history.get(history.size() - 1)));
            }
        }

        btnAddContact.setOnClickListener(v -> addContact());
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
}
