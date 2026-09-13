package com.shaterguy.soopdownloader;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int INK=0xff153d32, MUTED=0xff65746a, BG=0xfff5f7f3, GREEN=0xff146a51;
    private EditText input;
    private TextView phase,queueLabel;
    private ProgressBar progress;
    private LinearLayout records;
    private Button cancel;
    private String shownHistory="";
    private String shownJobId="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){renderStatus();handler.postDelayed(this,700);}};
    private SharedPreferences prefs(){return getSharedPreferences(DownloadService.PREFS,0);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(!DownloadService.alive) DownloadService.recover(this);
        buildScreen();
        if(saved==null) handleShare(getIntent());
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShare(intent);}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void handleShare(Intent intent){
        if(intent!=null && Intent.ACTION_SEND.equals(intent.getAction())){
            CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if(text==null && intent.getClipData()!=null && intent.getClipData().getItemCount()>0)text=intent.getClipData().getItemAt(0).getText();
            if(text!=null){input.setText(text);start(text.toString());}
            else toast("공유한 내용에 영상 주소가 없습니다.");
            // Consume the delivered event. Recreating the screen must not download twice.
            intent.setAction(Intent.ACTION_MAIN);intent.removeExtra(Intent.EXTRA_TEXT);intent.setClipData(null);
        }
    }
    private void start(String raw){
        try{
            String url=DownloadService.normalize(raw);input.setText(url);
            startForegroundService(new Intent(this,DownloadService.class).setAction(DownloadService.ENQUEUE).putExtra("url",url));
            phase.setText("최고 화질 확인 중");
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        }catch(Exception e){toast(e.getMessage()==null?"다운로드를 시작하지 못했습니다.":e.getMessage());}
    }
    private void buildScreen(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(24),dp(24),dp(28));scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});
        setContentView(scroll);
        LinearLayout mast=new LinearLayout(this);mast.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.mipmap.ic_launcher);mast.addView(logo,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView brand=label("SOOP Downloader",20,INK,true);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=dp(12);mast.addView(brand,bp);root.addView(mast);
        TextView eyebrow=label("SAVE THE MOMENT",11,GREEN,true);eyebrow.setLetterSpacing(.18f);add(root,eyebrow,32);
        add(root,label("좋아하는 순간을,\n가장 선명하게.",31,INK,true),9);
        add(root,label("영상 링크 하나면, 제공되는 최고 화질로 저장합니다.",14,MUTED,false),12);
        LinearLayout card=card();add(root,card,26);
        TextView badge=label("최고 화질 자동 선택",12,GREEN,true);card.addView(badge);
        input=new EditText(this);input.setTextSize(15);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setHint("SOOP 영상 주소를 붙여 넣으세요");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setMaxLines(3);input.setMinHeight(dp(64));input.setSelectAllOnFocus(false);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(shape(0xfff2f5f0,12));add(card,input,14);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);add(card,row,12);
        Button paste=button("붙여넣기",false);row.addView(paste,new LinearLayout.LayoutParams(0,dp(52),1));paste.setOnClickListener(v->{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cb.hasPrimaryClip()&&cb.getPrimaryClip().getItemCount()>0){input.setText(cb.getPrimaryClip().getItemAt(0).coerceToText(this));}else toast("복사한 주소가 없습니다.");});
        Button download=button("다운로드",true);LinearLayout.LayoutParams db=new LinearLayout.LayoutParams(0,dp(52),1);db.leftMargin=dp(10);row.addView(download,db);download.setOnClickListener(v->start(input.getText().toString()));
        add(card,label("다시보기 · 캐치  /  Movies/SOOP Downloader에 저장",11,MUTED,false),12);
        LinearLayout state=card();add(root,state,14);
        phase=label("다운로드할 영상을 기다리고 있습니다.",14,INK,true);state.addView(phase);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(8));pp.topMargin=dp(16);state.addView(progress,pp);
        queueLabel=label("",12,MUTED,false);add(state,queueLabel,8);
        cancel=button("다운로드 취소",false);add(state,cancel,8);cancel.setOnClickListener(v->startService(new Intent(this,DownloadService.class).setAction(DownloadService.CANCEL).putExtra("jobId",shownJobId)));
        add(root,label("공유로 더 빠르게",16,INK,true),26);
        add(root,label("SOOP에서 공유 → SOOP Downloader를 선택하면\n앱 화면 없이 백그라운드에서 다운로드합니다.",14,MUTED,false),8);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);add(root,header,28);
        header.addView(label("저장한 영상",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView folder=label("폴더 열기 ↗",13,GREEN,true);folder.setPadding(dp(8),dp(12),0,dp(12));header.addView(folder);folder.setOnClickListener(v->{try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FSOOP%20Downloader"),"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}catch(Exception e){toast("파일 앱에서 Movies/SOOP Downloader를 열어 주세요.");}});
        records=new LinearLayout(this);records.setOrientation(LinearLayout.VERTICAL);add(root,records,12);
        TextView version=label("v"+version()+"  ·  최고 화질 · 기기에 직접 저장",11,MUTED,false);version.setGravity(Gravity.CENTER);add(root,version,28);
    }
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "1.0.0";}}
    private void renderStatus(){
        shownJobId=prefs().getString("activeJobId","");
        boolean busy=prefs().getBoolean("busy",false);
        phase.setText(prefs().getString("phase","다운로드할 영상을 기다리고 있습니다."));
        int p=prefs().getInt("progress",0);progress.setIndeterminate(busy&&p==0);progress.setProgress(p);
        int queued=prefs().getInt("queued",0);queueLabel.setText(queued>0?"대기 중 "+queued+"개":busy?"앱을 나가도 알림에서 진행 상황을 확인할 수 있습니다.":"완료된 영상은 갤러리와 파일 앱에서도 열 수 있습니다.");
        cancel.setVisibility(busy?View.VISIBLE:View.GONE);
        String h=prefs().getString("history","[]");if(h.equals(shownHistory))return;shownHistory=h;records.removeAllViews();
        try{
            JSONArray items=new JSONArray(h);
            if(items.length()==0){TextView empty=label("아직 저장한 영상이 없습니다.\n링크를 붙여 넣거나 SOOP에서 공유해 보세요.",14,MUTED,false);empty.setPadding(0,dp(14),0,dp(18));records.addView(empty);}
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);LinearLayout r=card();add(records,r,i==0?0:10);
                r.addView(label(item.optString("title","SOOP 영상"),16,INK,true));
                add(r,label(item.optString("quality")+"  ·  "+String.format(Locale.KOREA,"%.1f MB",item.optLong("bytes")/1048576.0),12,MUTED,false),6);
                Button open=button("영상 열기  ↗",false);add(r,open,10);String uri=item.getString("uri");open.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast("영상을 열지 못했습니다. 파일이 이동되었는지 확인해 주세요.");}});
            }
        }catch(JSONException e){records.addView(label("저장 기록을 읽지 못했습니다. 파일 앱에서 영상을 확인해 주세요.",13,MUTED,false));}
    }
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackground(shape(Color.WHITE,18));return l;}
    private GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView label(String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String text,boolean primary){Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(shape(primary?GREEN:0xffeaf1e6,12));b.setMinHeight(dp(48));b.setStateListAnimator(null);return b;}
    private void add(LinearLayout parent,View view,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}

