DB_USER=$(whoami)
echo '{
    "keyStoreLocation": "/'$HOME'/cert.jks",
  
    "mariaDBUser": "'$DB_USER'",
    "mariaDBPassword": "",
    "database": "testing"
}' > bin/config.json
echo "CREATE DATABASE testing;
USE testing;
CREATE TABLE \$keystore (
  name VARCHAR(64) NOT NULL,
  identity_public VARCHAR(768) NOT NULL,
  identity_private VARCHAR(256) NOT NULL,
  prekey_public VARCHAR(768) NOT NULL,
  prekey_private VARCHAR(256) NOT NULL,
  PRIMARY KEY (name)
);
CREATE USER '$DB_USER'@'127.0.0.1' IDENTIFIED BY '';
GRANT ALL ON testing.* TO '$DB_USER'@'127.0.0.1';" | sudo mysql -u root --protocol=tcp
mysql -u $DB_USER -e "SELECT * FROM \$keystore" testing || true
java -jar bin/ChatServer.jar
