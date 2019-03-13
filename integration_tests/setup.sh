DB_USER=$(whoami)
WORKING_DIR=$(pwd)
cd $HOME

[ -r ssl.conf ] || sed '/^\[ v3_ca \]/ a\subjectAltName=DNS:localhost' /etc/ssl/openssl.cnf > ssl.conf
[ -r cert.jks ] ||
    openssl req -newkey rsa:4096 -nodes -batch -keyout cert.key -x509 -days 90 -config ssl.conf -out cert.crt &&
    openssl pkcs12 -export -in cert.crt -inkey cert.key -out tmp.p12 -password "pass:1nopassword" -name localhost &&
    keytool -importkeystore -deststorepass "nopassword" -destkeypass "nopassword" -destkeystore cert.jks -srckeystore tmp.p12 -srcstoretype PKCS12 -srcstorepass "1nopassword" -alias "localhost"

cd $WORKING_DIR
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

echo "function credentials() {
  return $CREDENTIAL_JSON;
}" >> integration_tests/chat.spec.js

