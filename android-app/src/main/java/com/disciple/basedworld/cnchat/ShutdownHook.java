package com.disciple.basedworld.cnchat;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;

interface CallbackSetter {
    void setCallback(Runnable reckoner);
}

public class ShutdownHook extends Service {

    private final Binder binder = new LocalBinder();
    private Runnable reckoner = null;

    public ShutdownHook() {
        super();
    }

    class LocalBinder extends Binder {
        final CallbackSetter onTaskRemovedHandler = new CallbackSetter() {
            @Override
            public void setCallback(Runnable reckoner) {
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
            reckoner.run();
        }
        stopSelf();
    }

}
