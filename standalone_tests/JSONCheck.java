import com.jsoniter.JsonIterator;
import java.io.*;
import java.io.IOException;

import com.jsoniter.annotation.JsonCreator;
import com.jsoniter.annotation.JsonProperty;
import com.jsoniter.any.Any;
import com.jsoniter.output.EncodingMode;
import com.jsoniter.output.JsonStream;
import com.jsoniter.spi.Decoder;
import com.jsoniter.spi.DecodingMode;
import com.jsoniter.spi.JsoniterSpi;
import com.jsoniter.spi.TypeLiteral;
import com.jsoniter.static_codegen.StaticCodegenConfig;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class JSONCheck {
    static class ConversationUser {
        public String user;
        public int role;
        public String key_ephemeral_public;
        public String key_wrapped;

        public ConversationUser() {}

        @JsonCreator
        public ConversationUser(@JsonProperty("user") String user,
                                @JsonProperty("role") int role,
                                @JsonProperty("key_ephemeral_public") String key_ephemeral_public,
                                @JsonProperty("key_wrapped") String key_wrapped) {
            this.user = user;
            this.role = role;
            this.key_ephemeral_public = key_ephemeral_public;
            this.key_wrapped = key_wrapped;
        }
    }

   static class Conversation {
       public int id;
       public boolean exchange_complete;
       public long crypt_expiration;
       public ConversationUser[] users;

       public Conversation() {
       }

       @JsonCreator
       public Conversation(@JsonProperty("id") Integer id,
                           @JsonProperty("exchange_complete") Boolean exchange_complete,
                           @JsonProperty("crypt_expiration") Long crypt_expiration,
                           @JsonProperty("users") ConversationUser[] users) {
           if(id != null) this.id = id;
           if(exchange_complete != null) this.exchange_complete = exchange_complete;
           if(crypt_expiration != null) this.crypt_expiration = crypt_expiration;
           this.users = users;
       }
   }

    public static void main(String[] args) {
        Any.registerEncoders();
        JsonIterator.setMode(DecodingMode.REFLECTION_MODE);
        JsonStream.setMode(EncodingMode.REFLECTION_MODE);

        String path = new File(JSONCheck.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                getParent() + System.getProperty("file.separator") + "standalone_tests" +
                System.getProperty("file.separator") + "conversations.json";

        try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(path), UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = in.readLine()) != null) {
                sb.append(line).append("\n");
            }
            Any any = JsonIterator.deserialize(sb.substring(0, sb.length() - 1));
            Conversation c = new Conversation();
            c = any.bindTo(c);
            System.out.println("Conversation.crypt_expiration: " + c.crypt_expiration);
            System.out.println("Conversation.users[0].user: " + c.users[0].user);
//            for(Any a : any) {
//                Conversation c = new Conversation();
//                c = a.bindTo(c);
//                System.out.println("Conversation.crypt_expiration: " + c.crypt_expiration);
//                System.out.println("Conversation.users[0].user: " + c.users[0].user);
//            }
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
}