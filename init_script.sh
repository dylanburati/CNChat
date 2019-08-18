#!/bin/sh

### BEGIN INIT INFO
# Provides:    cnchat 
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
DESC=CNChat
NAME=cnchat
CHECK_TIME=1
CNCHAT_DIR=""

. /lib/lsb/init-functions

is_running() {
    [ -f "$PID_FILE" ] && ps -p $(cat "$PID_FILE") > /dev/null 2>&1
}

do_start() {
    cd "$CNCHAT_DIR"
    echo >> "$LOG_FILE"
    java -jar ChatServer/bin/ChatServer.jar >> $LOG_FILE 2>&1 &
    echo $! > "$PID_FILE"

    sleep $CHECK_TIME
    is_running || return 1
}

do_stop() {
    kill $(cat "$PID_FILE")
    sleep $CHECK_TIME
    is_running && return 1 || return 0
}

do_upgrade() {
    cd "$CNCHAT_DIR"
    git fetch >/dev/null
    git reset --hard origin/master >/dev/null
}

case "$1" in
    start)
        if is_running; then
            echo "already started"
        else
            log_action_begin_msg "starting $DESC"
            do_start
            log_action_end_msg "$?"
        fi
        ;;
    stop)
        if ! is_running; then
            echo "not running"
        else
            log_action_begin_msg "stopping $DESC"
            do_stop
            log_action_end_msg "$?"
        fi
        ;;
    restart)
        IS_STOPPED=1
        if is_running; then
            log_action_begin_msg "stopping $DESC"
            do_stop
            IS_STOPPED="$?"
            log_action_end_msg "$IS_STOPPED"
        fi

        if [ $IS_STOPPED -eq 0 ]; then
            log_action_begin_msg "starting $DESC"
            do_start
            log_action_end_msg "$?"
        fi
        ;;
    status)
        is_running && exit 0 || exit 1
        ;;
    upgrade)
        is_running
        WAS_RUNNING="$?"
        IS_STOPPED=0
        if [ $WAS_RUNNING -eq 0 ]; then
            log_action_begin_msg "stopping $DESC"
            do_stop
            IS_STOPPED="$?"
            log_action_end_msg "$IS_STOPPED"
        fi
        [ $IS_STOPPED -eq 0 ] || exit 1
        
        log_action_begin_msg "upgrading $DESC"
        do_upgrade
        UPGRADE_SUCCESS="$?"
        log_action_end_msg "$UPGRADE_SUCCESS"
        [ $UPGRADE_SUCCESS -eq 0 ] || exit 1
                
        if [ $WAS_RUNNING -eq 0 ]; then
            log_action_begin_msg "starting $DESC"
            do_start
            log_action_end_msg "$?"
        fi
        ;;
    *)
        echo "Usage: $NAME {start|stop|restart|status|upgrade}" >&2
        exit 1
        ;;
esac

exit 0
