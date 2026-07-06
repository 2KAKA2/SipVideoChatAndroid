package com.sipvideochat.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 群组实体类
 * (从桌面端 src/com/location/im/common/ChatGroup.java 移植)
 */
public class ChatGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String ownerId;
    private String avatar;
    private List<String> memberIds;
    private List<String> adminIds;
    private int maxMembers;

    public ChatGroup() {
        this.id = UUID.randomUUID().toString();
        this.memberIds = new ArrayList<>();
        this.adminIds = new ArrayList<>();
        this.maxMembers = 100;
    }

    public ChatGroup(String name, String ownerId) {
        this();
        this.name = name;
        this.ownerId = ownerId;
        this.memberIds.add(ownerId);
        this.adminIds.add(ownerId);
    }

    public boolean addMember(String userId) {
        if (memberIds.size() >= maxMembers) return false;
        if (!memberIds.contains(userId)) {
            memberIds.add(userId);
            return true;
        }
        return false;
    }

    public boolean removeMember(String userId) {
        if (userId.equals(ownerId)) return false;
        adminIds.remove(userId);
        return memberIds.remove(userId);
    }

    public boolean isMember(String userId) { return memberIds.contains(userId); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public List<String> getMemberIds() { return memberIds; }
    public void setMemberIds(List<String> memberIds) { this.memberIds = memberIds; }

    public List<String> getAdminIds() { return adminIds; }
    public void setAdminIds(List<String> adminIds) { this.adminIds = adminIds; }

    public int getMemberCount() { return memberIds.size(); }
}
