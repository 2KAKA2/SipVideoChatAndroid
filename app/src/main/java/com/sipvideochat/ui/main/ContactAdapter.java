package com.sipvideochat.ui.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sipvideochat.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RecyclerView adapter for contacts.
 */
public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    private List<String> contacts;
    private final Map<String, String> lastMessages = new HashMap<>();
    private final OnContactClickListener listener;

    public interface OnContactClickListener {
        void onContactClick(String username);
    }

    public ContactAdapter(List<String> contacts, OnContactClickListener listener) {
        this.contacts = contacts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String username = contacts.get(position);
        holder.tvAvatar.setText(username.substring(0, 1).toUpperCase());
        holder.tvContactName.setText(username);

        String lastMsg = lastMessages.get(username);
        if (lastMsg != null && !lastMsg.isEmpty()) {
            holder.tvLastMessage.setText(lastMsg);
            holder.tvLastMessage.setVisibility(View.VISIBLE);
        } else {
            holder.tvLastMessage.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> listener.onContactClick(username));
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    public void updateContacts(List<String> newContacts) {
        this.contacts = newContacts;
        notifyDataSetChanged();
    }

    public void updateLastMessage(String username, String message) {
        lastMessages.put(username, message);
        int pos = contacts.indexOf(username);
        if (pos >= 0) {
            notifyItemChanged(pos);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAvatar;
        TextView tvContactName;
        TextView tvLastMessage;
        TextView tvUnreadBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tvAvatar);
            tvContactName = itemView.findViewById(R.id.tvContactName);
            tvLastMessage = itemView.findViewById(R.id.tvLastMessage);
            tvUnreadBadge = itemView.findViewById(R.id.tvUnreadBadge);
        }
    }
}
