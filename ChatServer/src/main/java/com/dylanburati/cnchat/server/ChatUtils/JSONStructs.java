package com.dylanburati.cnchat.server.ChatUtils;

import com.jsoniter.annotation.JsonCreator;
import com.jsoniter.annotation.JsonIgnore;
import com.jsoniter.annotation.JsonProperty;
import com.jsoniter.output.JsonStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JSONStructs {
    public static class KeySet {
        public String user;
        public String identity_public;
        public String identity_private;
        public String prekey_public;
        public String prekey_private;

        public KeySet() {
        }

        @JsonCreator
        public KeySet(@JsonProperty("user") String user,
                      @JsonProperty("identity_public") String identity_public,
                      @JsonProperty("identity_private") String identity_private,
                      @JsonProperty("prekey_public") String prekey_public,
                      @JsonProperty("prekey_private") String prekey_private) {
            this.user = user;
            this.identity_public = identity_public;
            this.identity_private = identity_private;
            this.prekey_public = prekey_public;
            this.prekey_private = prekey_private;
        }
    }

    public static class KeySet2 {
        public String user;
        public String identity_public;
        public String prekey_public;

        public KeySet2() {}

        @JsonCreator
        public KeySet2(@JsonProperty("user") String user,
                       @JsonProperty("identity_public") String identity_public,
                       @JsonProperty("prekey_public") String prekey_public) {
            this.user = user;
            this.identity_public = identity_public;
            this.prekey_public = prekey_public;
        }
    }

    public static class ConversationUser {
        public String user;
        public int role;
        public String key_ephemeral_public;
        public String key_wrapped;
        public String initial_message;

        public ConversationUser() {}

        @JsonCreator
        public ConversationUser(@JsonProperty("user") String user,
                                @JsonProperty("role") int role,
                                @JsonProperty("key_ephemeral_public") String key_ephemeral_public,
                                @JsonProperty("key_wrapped") String key_wrapped,
                                @JsonProperty("initial_message") String initial_message) {
            this.user = user;
            this.role = role;
            this.key_ephemeral_public = key_ephemeral_public;
            this.key_wrapped = key_wrapped;
            this.initial_message = initial_message;
        }

        public boolean validateWrappedKey(String wrappedKey) {
            String[] keyFields = wrappedKey.split(";");
            if(keyFields.length != 3) {
                return false;
            }
            if(!keyFields[0].matches("[0-9A-Za-z+/]{22}[=]{0,2}")) {
                // IV is 16 bytes -> 22 base64 chars
                return false;
            }
            if(!keyFields[1].matches("[0-9A-Za-z+/]{43}[=]{0,1}")) {
                // HMAC is exactly 32 bytes -> 43 base64 chars
                return false;
            }
            if(!keyFields[2].matches("[0-9A-Za-z+/]{43}[=]{0,1}") &&
                    !keyFields[2].matches("[0-9A-Za-z+/]{64}")) {
                // Wrapped key can be 32 or 48 bytes (multiple of AES block size)
                // 43 base64 chars or 64
                return false;
            }

            return true;
        }
    }

    private static class Conversation2 {
        public int id;
        public boolean exchange_complete;
        public List<String> users;
        public String role1;
        public String key_ephemeral_public;
        public String key_wrapped;
        public String initial_message;

        public Conversation2() {}

        @JsonCreator
        public Conversation2(@JsonProperty("id") int id,
                             @JsonProperty("exchange_complete") boolean exchange_complete,
                             @JsonProperty("users") List<String> users,
                             @JsonProperty("role1") String role1,
                             @JsonProperty("key_ephemeral_public") String key_ephemeral_public,
                             @JsonProperty("key_wrapped") String key_wrapped,
                             @JsonProperty("initial_message") String initial_message) {
            this.id = id;
            this.exchange_complete = exchange_complete;
            this.users = users;
            this.role1 = role1;
            this.key_ephemeral_public = key_ephemeral_public;
            this.key_wrapped = key_wrapped;
            this.initial_message = initial_message;
        }
    }

    public static class Conversation {
        public int id;
        public boolean exchange_complete;
        public long crypt_expiration;
        public List<ConversationUser> users = new ArrayList<>();
        @JsonIgnore
        public List<String> userNameList = new ArrayList<>();

        public Conversation() {}

        @JsonCreator
        public Conversation(@JsonProperty("id") Integer id,
                            @JsonProperty("exchange_complete") Boolean exchange_complete,
                            @JsonProperty("crypt_expiration") Long crypt_expiration,
                            @JsonProperty("users") ConversationUser[] users) {
            if(id != null) this.id = id;
            if(exchange_complete != null) this.exchange_complete = exchange_complete;
            if(crypt_expiration != null) this.crypt_expiration = crypt_expiration;
            this.users = Arrays.asList(users);
        }

        public synchronized boolean validate() {
            if(this.id <= 0) return false;
            if(this.users == null || this.users.size() < 2) return false;
            if(!this.exchange_complete) {
                if(this.crypt_expiration < System.currentTimeMillis()) return false;
            }
            boolean hasRole1 = false, hasRole2 = false;
            for(ConversationUser u : this.users) {
                if(u.role != 1 && u.role != 2) return false;
                if((u.role == 1 || this.exchange_complete) && u.key_wrapped == null) return false;
                if(u.role != 1 && !this.exchange_complete &&
                        (u.key_ephemeral_public == null || u.initial_message == null)) return false;

                if(u.role == 1) {
                    if(hasRole1) return false;
                    hasRole1 = true;
                } else if(u.role == 2) {
                    hasRole2 = true;
                }
                this.userNameList.add(u.user);
            }
            if(!(hasRole1 && hasRole2)) return false;
            return true;
        }

        public synchronized boolean validateNew(String userName) {
            if(this.id <= 0) return false;
            if(this.users == null || this.users.size() < 2) return false;
            if(this.exchange_complete) {
                return false;
            }
            if(this.crypt_expiration < System.currentTimeMillis()) return false;
            boolean hasRole1 = false, hasRole2 = false;
            for(ConversationUser u : this.users) {
                if(u.role != 1 && u.role != 2) return false;
                if(u.role == 1 && u.key_wrapped == null) return false;
                if(u.role != 1 && (u.key_ephemeral_public == null || u.initial_message == null)) return false;

                if(u.role == 1) {
                    if(hasRole1 || !userName.equals(u.user)) return false;
                    hasRole1 = true;
                } else if(u.role == 2) {
                    hasRole2 = true;
                }

                if(this.userNameList.contains(u.user)) return false;
                this.userNameList.add(u.user);
            }
            if(!(hasRole1 && hasRole2)) return false;
            if(!this.userNameList.contains(userName)) return false;
            return true;
        }

        public synchronized ConversationUser getUser(String userName) {
            for(ConversationUser u : this.users) {
                if(userName.equals(u.user)) return u;
            }
            return null;
        }

        public synchronized ConversationUser getRole(int role) {
            if(role != 1 && role != 2) {
                return null;
            }
            for(ConversationUser u : this.users) {
                if(u.role == role) return u;
            }
            return null;
        }

        public synchronized boolean hasUser(String userName) {
            return (this.getUser(userName) != null);
        }

        public synchronized String sendToUser(String userName) {
            ConversationUser u = this.getUser(userName);
            if(u == null) return null;
            ConversationUser u1 = this.getRole(1);
            if(u1 == null) return null;
            Conversation2 c2 = new Conversation2(this.id, this.exchange_complete, this.userNameList, u1.user,
                    u.key_ephemeral_public, u.key_wrapped, u.initial_message);
            return JsonStream.serialize(c2);
        }

        public synchronized void checkExchangeComplete() {
            for(ConversationUser u : this.users) {
                if(u.key_wrapped == null) return;
            }
            this.exchange_complete = true;
        }
    }

    public static class Preferences {
        public Boolean markdown;

        public Preferences() {
            this.markdown = false;
        }

        @JsonCreator
        public Preferences(@JsonProperty("markdown") Boolean markdown) {
            if(markdown != null) this.markdown = markdown;
        }

        public synchronized void assign(Preferences other) {
            if(other == null) return;
            if(other.markdown != null) this.markdown = other.markdown;
        }
    }
}
