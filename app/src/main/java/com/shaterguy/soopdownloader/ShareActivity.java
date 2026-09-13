package com.shaterguy.soopdownloader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Transparent, short-lived share receiver. Never opens the download screen. */
public final class ShareActivity extends Activity {
    private boolean delivered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        delivered=state!=null && state.getBoolean("delivered",false);
    }

    @Override protected void onResume() {
        super.onResume();
        if(!delivered) {
            delivered=true;
            try {
                Intent intent=getIntent();
                if(intent==null || !Intent.ACTION_SEND.equals(intent.getAction()))
                    throw new IllegalArgumentException("공유한 내용에 영상 주소가 없습니다.");
                CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if(text==null && intent.getClipData()!=null && intent.getClipData().getItemCount()>0)
                    text=intent.getClipData().getItemAt(0).getText();
                String url=DownloadService.normalize(text==null?"":text.toString());
                startForegroundService(new Intent(this,DownloadService.class)
                        .setAction(DownloadService.ENQUEUE).putExtra("url",url).putExtra("shared",true));
            } catch(Exception e) {
                Toast.makeText(this,"다운로드를 시작하지 못했습니다. SOOP 영상 주소를 확인해 주세요.",Toast.LENGTH_LONG).show();
            }
        }
        finish();
        overridePendingTransition(0,0);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("delivered",delivered);
        super.onSaveInstanceState(state);
    }
}
