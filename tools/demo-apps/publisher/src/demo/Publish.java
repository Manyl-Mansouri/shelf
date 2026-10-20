package demo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Stand-in for a streaming app's launcher integration: publishes one "continue watching" entry
 * and a preview channel, the same way real apps do, from assets/content.tsv
 * (title, subtitle, image URL per line). Started once by publisher.sh, then finishes.
 */
public class Publish extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            publish();
        } catch (Exception e) {
            android.util.Log.e("DemoPublish", "failed", e);
        }
        finish();
    }

    private void publish() throws Exception {
        ContentResolver cr = getContentResolver();
        cr.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null);
        cr.delete(TvContract.PreviewPrograms.CONTENT_URI, null, null);
        cr.delete(TvContract.Channels.CONTENT_URI, null, null);

        ContentValues ch = new ContentValues();
        ch.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        ch.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Top Picks");
        ch.put(TvContract.Channels.COLUMN_INPUT_ID, TvContract.buildInputId(new ComponentName(this, Publish.class)));
        long channel = ContentUris.parseId(cr.insert(TvContract.Channels.CONTENT_URI, ch));

        BufferedReader in = new BufferedReader(new InputStreamReader(getAssets().open("content.tsv")));
        String line;
        int i = 0;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            String link = new Intent(this, Publish.class).putExtra("item", i).toUri(Intent.URI_INTENT_SCHEME);
            if (i == 0) {
                ContentValues w = new ContentValues();
                w.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_TV_EPISODE);
                w.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
                w.put(TvContract.WatchNextPrograms.COLUMN_TITLE, f[0]);
                w.put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, "2");
                w.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, "4");
                w.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, f[1]);
                w.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, f[2]);
                w.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
                w.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 1_860_000);
                w.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, 3_300_000);
                w.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
                w.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, link);
                cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, w);
            } else {
                ContentValues p = new ContentValues();
                p.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channel);
                p.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_MOVIE);
                p.put(TvContract.PreviewPrograms.COLUMN_TITLE, f[0]);
                p.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, f[1]);
                p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, f[2]);
                p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
                p.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, 100 - i);
                p.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, link);
                cr.insert(TvContract.PreviewPrograms.CONTENT_URI, p);
            }
            i++;
        }
        in.close();
    }
}
