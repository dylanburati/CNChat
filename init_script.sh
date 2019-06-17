#!/bin/sh

### BEGIN INIT INFO
# Provides:	cnchat 
# Required-Start:    $remote_fs $network $syslog
# Required-Stop:     $remote_fs $network $syslog
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: starts the cnchat server
# Description:       starts the cnchat server
### END INIT INFO

PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
PID_FILE=/var/run/cnchat.pid
LOG_FILE=/var/log/cnchat.log
NAME=cnchat
CHECK_TIME=1
CNCHAT_DIR="/home/dylan/CNChat"

is_running() {
    [ -f "$PID_FILE" ] && ps -p $(cat "$PID_FILE") > /dev/null 2>&1
}

do_start() {
    cd "$CNCHAT_DIR"
    sudo java -jar bin/ChatServer.jar >> $LOG_FILE &
    echo $! > "$PID_FILE"
}

do_stop() {
    kill $(cat "$PID_FILE")
}

do_upgrade() {
    cd "$CNCHAT_DIR"
    sudo git fetch
    sudo git stash save
    sudo git reset --hard origin/master
    sudo git stash pop
}

case "$1" in
	start)
        if is_running; then
            echo "already started"
        else
            echo "starting $NAME"
            do_start
        fi
		;;
	stop)
	    if ! is_running; then
            echo "not running"
        else
            echo "stopping $NAME"
            do_stop
        fi
		;;
	restart)
        if is_running; then
            echo "stopping $NAME"
            do_stop
            sleep $CHECK_TIME
        fi

        if ! is_running; then
            echo "starting $NAME"
            do_start
        else
            echo "failed to stop $NAME"
        fi
		;;
	status)
		is_running && exit 0 || exit $1
		;;
	upgrade)
        WAS_RUNNING=""
        if is_running; then
            echo "stopping $NAME"
            do_stop
            sleep $CHECK_TIME
            WAS_RUNNING="true"
        fi
        
        echo "upgrading $NAME"
        do_upgrade
        if [ -n "$WAS_RUNNING" ]; then
            echo "starting $NAME"
            do_start
        fi
		;;
	*)
		echo "Usage: $NAME {start|stop|restart|status|upgrade}" >&2
		exit 1
		;;
esac

exit 0
