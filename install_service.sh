#!/bin/bash

if [[ $EUID -ne 0 ]]; then
    echo "Error: this script must be run as root" 1>&2
    exit 1
fi

if [ ! -f ./ChatServer/bin/ChatServer.jar ]; then
    echo "Error: this script must be run from the CNChat directory" 1>&2
    exit 2
fi

cd $(dirname $0)
WORKING_DIR=$(pwd)
CNCHAT_USER=$(whoami)
REPLACE_1='s|CNCHAT_DIR=.*|CNCHAT_DIR=\"'"$WORKING_DIR"'\"|'
cp init_script.sh /etc/init.d/cnchat
chown root: /etc/init.d/cnchat
chmod 755 /etc/init.d/cnchat
sed -i $REPLACE_1 /etc/init.d/cnchat

cd /etc/init.d
update-rc.d cnchat defaults
