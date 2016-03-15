package cc.intx.owntrack;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.URLUtil;

import java.security.cert.Certificate;
import java.util.ArrayList;

public class TrackingService extends Service {
    private String TAG; //Debug tag

    //The pending intent is going to get called from the alarm manager
    private PendingIntent pendingIntent;
    private AlarmManager alarmManager;
    private Preferences preferences;
    private ArrayList<Preferences.Item> preferenceItems;
    private ArrayList<String> preferenceItemsKeys;
    private Preferences.Item intervalPreference;
    private LocationReceiver locationReceiver;
    private SendLocation sendLocation;

    //Current settings
    private String serverUrl;

    //Indicates if the alarm manager is scheduled. If this is false the service will shutdown if the app is closed
    private boolean isRunning = false;

    private int locationInterval = -1;
    private boolean changedInterval = false;

    //Binder object to give the app an interface for communication
    private final IBinder serviceBinder = new ServiceBinder();
    public class ServiceBinder extends Binder {
        TrackingService getService() {
            return TrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        TAG = getString(R.string.app_name); //Set debug string to app name

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);//TODO the urlstuff goes in a new thread, so the mainthread doesn't get stuck, instead of this workaround

        if (locationReceiver == null) {
            locationReceiver = new LocationReceiver(TAG, this);
            Log.d(TAG, "Created location receiver to get last location");
        }

        if (sendLocation == null) {
            sendLocation = new SendLocation(TAG, this);
            Log.d(TAG, "Created send Location to send locations to server");
        }

        Log.d(TAG, "Created service");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bound");

        //Pass the service interface to the app
        return serviceBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbound");
        isRunningListener = null;//Remove the status change listener, which was passed from the app
        newLocationListener = null;

        //If the app closes/unbinds and has chosen to stop the service, stop now
        if (!isRunning) {
            stopSelf();
        }

        return false;//Do not call rebind on rebind, but bind
    }

    private void createPreferences() {
        if (preferences == null) {
            preferences = new Preferences(this, TAG);

            preferenceItemsKeys = preferences.getKeys();
            preferenceItems = preferences.getItems();

            intervalPreference = preferenceItems.get(preferenceItemsKeys.indexOf("interval"));
        }
    }

    private boolean allowSelfSigned = false;
    public void changedSettings() {
        createPreferences();

        if (locationInterval != Integer.parseInt(intervalPreference.getPossibleValues().get(intervalPreference.getCurrentValue()))) {
            locationInterval = Integer.parseInt(intervalPreference.getPossibleValues().get(intervalPreference.getCurrentValue()));
            locationInterval = Integer.parseInt(intervalPreference.getPossibleValues().get(intervalPreference.getCurrentValue()));
            changedInterval = true;

            Log.d(TAG, "Changed interval");
        }

        if (changedInterval) {
            rewriteAlarm();
        }

        allowSelfSigned = preferences.getPreferenceObject().getInt("allowselfsigned", 0) == 1;
        sendLocation.changeSelfsigned(allowSelfSigned);

        //Load current url
        getUrl();

        //Load common secret
        getCommonSecret();
    }

    private void rewriteAlarm() {
        if (isRunning) {
            if (alarmManager == null) {
                alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            }

            if (pendingIntent == null) {
                //Create pending intend for the alarm manager to call TODO MAYBE is the service intend created correctly?
                Intent serviceIntent = new Intent(this, TrackingService.class);//Create new intent, to ignore the callee intend (autostart or app)
                pendingIntent = PendingIntent.getService(this, 998566, serviceIntent, 0);
            }

            if (alarmManager != null && pendingIntent != null) {
                Log.d(TAG, "Cancel all alarms for rewrite.");

                alarmManager.cancel(pendingIntent);
            }

            Log.d(TAG, "Rewrite alarm to " + locationInterval + " minutes.");
            /*
            Schedule tracking service. Inexact repeating to reduce battery draining, but *_WAKEUP to
            track while the phone sleeps, otherwise the repeat can be REALLY inexact if the phone is
            not used, which would make this app useless for a lot os usecases
             */
            alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000, locationInterval * 60 * 1000, pendingIntent);

            changedInterval = false;
        }
    }

    private Runnable newLocationListener;
    public void setNewLocationListener(Runnable runnable) {
        newLocationListener = runnable;
    }
    private Runnable newLocationListenerWrapper = new Runnable() {
        @Override
        public void run() {
            if (newLocationListener != null) {
                newLocationListener.run();
            }
        }
    };

    /*
    Is called on start and from the alarm manager
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Debugging stuff
        Log.d(TAG, "We Run (" + isRunning + "). intent: " + (intent == null ? "null" : intent.toString()) + ", flags: " + flags + ", id: " + startId);

        changeIsRunning(true);//Change running state
        changedSettings();

        if (intent != null) {
            locationReceiver.getLocation(newLocationListenerWrapper);
        }

        return START_STICKY;//Service will stay active even if the Activity is not
    }

    //Called if the service should stop with the activity
    public void stopService() {
        //Cancel the service scheduling, otherwise the service is reactivated from the alarm manager, even if it got stopped from the user
        if (alarmManager != null && pendingIntent != null) {
            Log.d(TAG, "Cancel all alarms");

            alarmManager.cancel(pendingIntent);
        }

        changeIsRunning(false);//Change running state
    }

    @Override
    public void onDestroy() {
        //Cleanup the scheduling, to not get reactivated after destroy
        stopService();

        Log.d(TAG, "Destroyed service");
    }

    //Public interface for the app, to pass status changes to the ui. Updates status on new listener
    private Runnable isRunningListener;
    public void setIsRunningListener(Runnable runnable) {
        isRunningListener = runnable;
        changeIsRunning(isRunning);
    }

    //Changes status and calls status listener
    public void changeIsRunning(boolean isRunning) {
        this.isRunning = isRunning;

        if (isRunningListener != null) {
            isRunningListener.run();
        }
    }

    public LocationReceiver.LocationData getLastLocation() {
        if (locationReceiver != null) {
            return locationReceiver.getLastLocation();
        } else {
            return null;
        }
    }

    public int getToSendLocationsNumber() {
        if (locationReceiver != null) {
            return locationReceiver.getListSize();
        } else {
            return 0;
        }
    }


    private int lastError = 0;
    public int getLastError() {
        int returnError = lastError;

        //Reset error
        lastError = 0;

        return returnError;
    }

    public int checkServerSettings() {
        return sendLocation.checkServerSettings();
    }

    public int pinCertificate(String fingerprint) {
        SharedPreferences sharedPreferences = preferences.getPreferenceObject();
        SharedPreferences.Editor editor = sharedPreferences.edit();

        if (fingerprint.equals("")) {
            editor.remove("pinnedcert");
        } else {
            editor.putString("pinnedcert", fingerprint);
        }

        if (!editor.commit()) {
            return 5;
        } else {
            return 0;
        }
    }

    public String getPinnedCert() {
        createPreferences();

        return preferences.getPreferenceObject().getString("pinnedcert", "none");
    }

    public boolean getAllowSelfSigning() {
        return allowSelfSigned;
    }

    public Certificate[] getCerts() {
        Certificate[] certificates = sendLocation.getCerts(serverUrl);

        if (certificates == null) {
            lastError = sendLocation.getLastError();
        }

        return certificates;
    }

    public String getCommonSecret() {
        createPreferences();

        SharedPreferences sharedPreferences = preferences.getPreferenceObject();

        String commonSecret = sharedPreferences.getString("commonsecret", "nosecret");
        sendLocation.changeCommonSecret(commonSecret);
        return commonSecret;
    }

    public int saveCommonSecret(String commonSecret) {
        SharedPreferences sharedPreferences = preferences.getPreferenceObject();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("commonsecret", commonSecret);

        if (!editor.commit()) {
            return 5;
        } else {
            return 0;
        }
    }

    public String getUrl() {
        createPreferences();

        SharedPreferences sharedPreferences = preferences.getPreferenceObject();
        serverUrl = sharedPreferences.getString("url", this.getString(R.string.exampleurl));

        sendLocation.changeUrl(serverUrl);

        return serverUrl;
    }

    public int saveUrl(String url) {
        if (!URLUtil.isValidUrl(url)) {
            return 2;
        }

        if (!URLUtil.isHttpsUrl(url)) {
            return 3;
        }

        if (sendLocation != null) {
            int result = sendLocation.pingNewServer(url);

            if (result == 0) {
                SharedPreferences sharedPreferences = preferences.getPreferenceObject();
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("url", url);

                if (!editor.commit()) {
                    return 5;
                }

                changedSettings();
            }

            return result;
        } else {
            return -1;
        }
    }


    //Public interface for checking status
    public boolean getIsRunning() {
        return isRunning;
    }
}
