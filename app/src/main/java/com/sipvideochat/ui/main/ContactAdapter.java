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

    private List<MainActivity.ConversationItem> contacts;
    private final Map<String, String> lastMessages = new HashMap<>();
    private final OnContactClickListener listener;
    private final OnContactLongClickListener longClickListener;

    public interface OnContactClickListener {
        void onContactClick(String conversationKey);
    }

    public interface OnContactLongClickListener {
        void onContactLongClick(MainActivity.ConversationItem item);
    }

    public ContactAdapter(List<MainActivity.ConversationItem> contacts,
                          OnContactClickListener listener,
                          OnContactLongClickListener longClickListener) {
        this.contacts = contacts;
        this.listener = listener;
        this.longClickListener = longClickListener;
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
        MainActivity.ConversationItem item = contacts.get(position);
        String title = item.title == null || item.title.trim().isEmpty() ? item.key : item.title;
        String avatar = item.group ? "群" : title.substring(0, 1).toUpperCase();
        holder.tvAvatar.setText(avatar);
        holder.tvContactName.setText(title);

        String lastMsg = lastMessages.get(item.key);
        if (lastMsg != null && !lastMsg.isEmpty()) {
            holder.tvLastMessage.setText(lastMsg);
            holder.tvLastMessage.setVisibility(View.VISIBLE);
        } else {
            holder.tvLastMessage.setVisibility(View.VISIBLE);
            holder.tvLastMessage.setText(item.group ? "群聊" : "点击开始聊天");
        }

        holder.itemView.setOnClickListener(v -> listener.onContactClick(item.key));
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener == null) {
                return false;
            }
            longClickListener.onContactLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    public void updateContacts(List<MainActivity.ConversationItem> newContacts) {
        this.contacts = newContacts;
        notifyDataSetChanged();
    }

    public void updateLastMessage(String conversationKey, String message) {
        lastMessages.put(conversationKey, message);
        for (int i = 0; i < contacts.size(); i++) {
            if (conversationKey.equals(contacts.get(i).key)) {
                notifyItemChanged(i);
                return;
            }
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
