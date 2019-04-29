package info.nightscout.androidaps.plugins.source;

import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.utils.SP;

public class SourceLibre2Plugin extends PluginBase implements BgSourceInterface {

    private static Logger log = LoggerFactory.getLogger(L.BGSOURCE);

    private static SourceLibre2Plugin plugin = null;

    public static SourceLibre2Plugin getPlugin() {
        if (plugin == null) plugin = new SourceLibre2Plugin();
        return plugin;
    }

    private SourceLibre2Plugin() {
        super(new PluginDescription()
                .mainType(PluginType.BGSOURCE)
                .fragmentClass(BGSourceFragment.class.getName())
                .preferencesId(R.xml.pref_bgsource_libre2)
                .pluginName(R.string.libre2_app)
                .shortName(R.string.libre2_short)
                .description(R.string.libre2_description));
    }

    @Override
    public boolean advancedFilteringSupported() {
        return true;
    }

    @Override
    public void handleNewData(Intent intent) {
        if (!isEnabled(PluginType.BGSOURCE)) return;
        if (intent.hasExtra("glucose") && intent.hasExtra("timestamp")) {
            double glucose = intent.getDoubleExtra("glucose", 0);
            long timestamp = intent.getLongExtra("timestamp", 0);
            log.debug("Received BG reading from LibreLink: glucose=" + glucose + " timestamp=" + timestamp);

            Libre2RawValue currentRawValue = new Libre2RawValue();
            currentRawValue.timestamp = timestamp;
            currentRawValue.glucose = glucose;

            List<Libre2RawValue> previousRawValues = MainApp.getDbHelper().getLibre2RawValuesBetween(timestamp - 330000, timestamp);
            MainApp.getDbHelper().createOrUpdate(currentRawValue);
            previousRawValues.add(currentRawValue);
            BgReading bgReading = determineBGReading(previousRawValues);

            MainApp.getDbHelper().createIfNotExists(bgReading, "Libre2");

            if (SP.getBoolean(R.string.key_dexcomg5_nsupload, false))
                NSUpload.uploadBg(bgReading, "AndroidAPS-Libre2");

            if (SP.getBoolean(R.string.key_dexcomg5_xdripupload, false))
                NSUpload.sendToXdrip(bgReading);
        } else {
            log.error("Received faulty intent from LibreLink.");
        }
    }

    private static BgReading determineBGReading(List<Libre2RawValue> rawValues) {
        Collections.sort(rawValues, (o1, o2) -> Long.compare(o1.timestamp, o2.timestamp));

        long oldestTimestamp = rawValues.get(0).timestamp;
        double sumX = 0;
        double sumY = 0;
        for (Libre2RawValue value : rawValues) {
            sumX += (double) (value.timestamp - oldestTimestamp) / 60000D;
            sumY += value.glucose;
        }
        double averageTimestamp = sumX / rawValues.size();
        double averageGlucose = sumY / rawValues.size();
        double a = 0;
        double b = 0;
        for (Libre2RawValue value : rawValues) {
            a += ((double) (value.timestamp - oldestTimestamp) / 60000D - averageTimestamp) * (value.glucose - averageGlucose);
            b += Math.pow((double) (value.timestamp - oldestTimestamp) / 60000D - averageTimestamp, 2);
        }
        double slope = a / b;

        Libre2RawValue last = rawValues.get(rawValues.size() - 1);

        BgReading bgReading = new BgReading();
        bgReading.value = averageGlucose;
        bgReading.raw = last.glucose;
        bgReading.date = last.timestamp;
        bgReading.direction = rawValues.size() > 1 ? determineTrendArrow(slope) : "NONE";
        return bgReading;
    }

    private static String determineTrendArrow(double slope) {
        if (slope <= -3.5) return "DoubleDown";
        else if (slope <= -2) return "SingleDown";
        else if (slope <= -1) return "FortyFiveDown";
        else if (slope <= 1) return "Flat";
        else if (slope <= 2) return "FortyFiveUp";
        else if (slope <= 3.5) return "SingleUp";
        else return "DoubleUp";
    }
}
