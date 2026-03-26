package com.sipvideochat.model;

import java.io.Serializable;

/**
 * 用户实体类
 * (从桌面端 src/com/location/im/common/User.java 移植)
 */
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String email;
    private String phone;
    private UserStatus status;
    private String sipUri;

    public enum UserStatus {
        ONLINE, OFFLINE, BUSY, AWAY
    }

    public User() {
        this.status = UserStatus.OFFLINE;
    }

    public User(String id, String username, String password) {
        this();
        this.id = id;
        this.username = username;
        this.password = password;
        this.nickname = username;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public String getSipUri() { return sipUri; }
    public void setSipUri(String sipUri) { this.sipUri = sipUri; }

    @Override
    public String toString() {
        return "User{id='" + id + "', username='" + username + "', nickname='" + nickname + "', status=" + status + "}";
    }
}
