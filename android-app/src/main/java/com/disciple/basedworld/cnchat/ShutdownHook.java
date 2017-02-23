package com.disciple.basedworld.cnchat;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;

interface CallbackSetter {
    void setCallback(Reckoner reckoner);
}

public class ShutdownHook extends Service {

    private final Binder binder = new LocalBinder();
    private Reckoner reckoner = null;

    public ShutdownHook() {
        super();
    }

    class LocalBinder extends Binder {
        CallbackSetter onTaskRemovedHandler = new CallbackSetter() {
            @Override
            public void setCallback(Reckoner reckoner) {
                ShutdownHook.this.reckoner = reckoner;
            }
        };
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if(reckoner != null) {
            reckoner.execute();
        }
        stopSelf();
    }

}
