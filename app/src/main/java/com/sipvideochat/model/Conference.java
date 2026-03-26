package com.sipvideochat.model;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 音视频会议实体类
 * (从桌面端 src/com/location/im/common/Conference.java 移植)
 */
public class Conference implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String hostId;
    private Set<String> participantIds;
    private ConferenceType type;
    private ConferenceStatus status;
    private int maxParticipants;
    private boolean videoEnabled;
    private boolean audioEnabled;

    public enum ConferenceType {
        AUDIO_ONLY, VIDEO, SCREEN_SHARE
    }

    public enum ConferenceStatus {
        WAITING, ACTIVE, PAUSED, ENDED
    }

    public Conference() {
        this.id = UUID.randomUUID().toString();
        this.participantIds = new HashSet<>();
        this.status = ConferenceStatus.WAITING;
        this.maxParticipants = 10;
        this.videoEnabled = true;
        this.audioEnabled = true;
    }

    public Conference(String name, String hostId, ConferenceType type) {
        this();
        this.name = name;
        this.hostId = hostId;
        this.type = type;
        this.participantIds.add(hostId);
    }

    public boolean addParticipant(String userId) {
        if (participantIds.size() >= maxParticipants) return false;
        return participantIds.add(userId);
    }

    public boolean removeParticipant(String userId) {
        if (userId.equals(hostId)) return false;
        return participantIds.remove(userId);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }

    public Set<String> getParticipantIds() { return participantIds; }
    public ConferenceType getType() { return type; }
    public ConferenceStatus getStatus() { return status; }
    public void setStatus(ConferenceStatus status) { this.status = status; }
}
