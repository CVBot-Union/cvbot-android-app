package com.cvbotunion.cvtwipush.Model;

import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Nullable;

import com.cvbotunion.cvtwipush.Activities.Timeline;
import com.cvbotunion.cvtwipush.Adapters.TweetDetailCardAdapter;
import com.cvbotunion.cvtwipush.CustomViews.TweetDetailCard;
import com.cvbotunion.cvtwipush.DBModel.DBTwitterStatus;
import com.cvbotunion.cvtwipush.Service.WebService;
import com.scwang.smart.refresh.layout.api.RefreshLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.litepal.LitePal;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import kotlin.text.Regex;
import okhttp3.Response;

/**
 * In order to avoid {@code NullPointerException}, all ArrayList objects should NOT be {@code null} even nothing inside.
 */
public class TwitterStatus implements Parcelable, Comparable<TwitterStatus> {
    //推文类型
    public final static int NORMAL=0;
    public final static int REPLY=1;
    public final static int QUOTE=2;

    public static final SimpleDateFormat dateFormatIn = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.UK);
    public static final SimpleDateFormat dateFormatOut = new SimpleDateFormat("HH:mm:ss · yyyy年MM月dd日",Locale.CHINA);

    public static final Regex tcoRegex = new Regex("\n*https://t\\.co/\\w+\\s*\\Z");

    public boolean hadQueried = false;  // 是否已经首次调用过queryTranslations()

    public String created_at;
    public String id;
    @Nullable public String text;
    public TwitterUser user;
    @Nullable public String in_reply_to_status_id;
    @Nullable public String in_reply_to_user_id;
    @Nullable public String in_reply_to_screen_name;
    @Nullable public String quoted_status_id;
    @Nullable public String location;
    public ArrayList<String> hashtags;
    public ArrayList<TwitterUser> user_mentions;
    public ArrayList<TwitterMedia> media;
    public ArrayList<HashMap<String,String>> translations;  // map keys: "userName","groupName","content"

    public TwitterStatus(){
        hashtags = new ArrayList<>();
        user_mentions = new ArrayList<>();
        media = new ArrayList<>();
        translations = new ArrayList<>();
    }

    public TwitterStatus(String created_at, String id, @Nullable String text, TwitterUser user){
        this();
        this.created_at = created_at;
        this.id = id;
        this.user = user;
        if(text != null) {
            this.text = text;
        }
    }

    public TwitterStatus(String created_at, String id, @Nullable String text, TwitterUser user,int type, @Nullable String parent_status_id){
        this(created_at,id,text,user);
        switch(type) {
            case REPLY:
                this.in_reply_to_status_id = parent_status_id;
                break;
            case QUOTE:
                this.quoted_status_id = parent_status_id;
                break;
            default:
                Log.w("TwitterStatus", "未指定父推文类型");
                break;
        }
    }

    public TwitterStatus(String created_at, String id, @Nullable String text, TwitterUser user, @Nullable ArrayList<TwitterMedia> media){
        this(created_at,id,text,user);
        this.media = media;
    }

    public TwitterStatus(String created_at, String id, @Nullable String text, TwitterUser user, ArrayList<TwitterMedia> media, int type, @Nullable String parent_status_id){
        this(created_at,id,text,user,media);
        switch(type){
            case REPLY:
                this.in_reply_to_status_id = parent_status_id;
                break;
            case QUOTE:
                this.quoted_status_id = parent_status_id;
                break;
            default:
                Log.w("TwitterStatus","未指定父推文类型");
                break;
        }
    }

    public TwitterStatus(JSONObject tweet) throws JSONException, ParseException {
        this(tweet, false);
    }

    public TwitterStatus(JSONObject tweet, boolean saveToDB) throws JSONException, ParseException {
        this();
        this.created_at = toUTC8(tweet.getString("created_at"));
        this.id = tweet.getString("id_str");
        this.text = tweet.getBoolean("truncated") ?
                tweet.getString("text") : textModify(tweet.getString("text"), tweet.getJSONObject("entities").getJSONArray("urls"));
        this.user = new TwitterUser(tweet.getJSONObject("user"));
        if(tweet.has("userNickname")) {
            this.user.name_in_group = tweet.getString("userNickname");
        }
        this.location = tweet.isNull("place") ? null : tweet.getJSONObject("place").getString("full_name");
        if(tweet.getBoolean("truncated") && tweet.has("extended_tweet")) {
            this.text = textModify(tweet.getJSONObject("extended_tweet").getString("full_text"),
                    tweet.getJSONObject("extended_tweet").getJSONObject("entities").getJSONArray("urls"));
            if(tweet.getJSONObject("extended_tweet").has("extended_entities")) {
                TransformMedia(tweet.getJSONObject("extended_tweet").getJSONObject("extended_entities").getJSONArray("media"));
            }
        }
        if(!tweet.isNull("in_reply_to_status_id_str")) {
            this.in_reply_to_status_id = tweet.getString("in_reply_to_status_id_str");
        } else if(tweet.has("retweeted_status")) {
            this.text = "";
            TwitterStatus retweetedStatus = new TwitterStatus(tweet.getJSONObject("retweeted_status"));
            this.quoted_status_id = retweetedStatus.id;
            if (LitePal.where("tsid = ?", this.quoted_status_id).find(DBTwitterStatus.class).isEmpty()) {
                DBTwitterStatus dbRetweetedStatus = new DBTwitterStatus(retweetedStatus);
                dbRetweetedStatus.save();
            }
        } else if(tweet.has("quoted_status")) {
            TwitterStatus quotedStatus = new TwitterStatus(tweet.getJSONObject("quoted_status"));
            this.quoted_status_id = quotedStatus.id;
            if (LitePal.where("tsid = ?", this.quoted_status_id).find(DBTwitterStatus.class).isEmpty()) {
                DBTwitterStatus dbQuotedStatus = new DBTwitterStatus(quotedStatus);
                dbQuotedStatus.save();
            }
        }
        if(tweet.has("extended_entities")) {
            TransformMedia(tweet.getJSONObject("extended_entities").getJSONArray("media"));
        }
        if(tweet.has("translations")) {
            for(int i=0;i<tweet.getJSONArray("translations").length();i++) {
                addTranslation(tweet.getJSONArray("translations").getJSONObject(i));
            }
        }

        if(saveToDB) {
            if (LitePal.where("tsid = ?", this.id).find(DBTwitterStatus.class).isEmpty()) {
                DBTwitterStatus dbStatus = new DBTwitterStatus(this);
                dbStatus.save();
            }
        }
    }

    public static String textModify(String text, JSONArray urls) throws JSONException {
        String htmlDecodedText = text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        for(int k=0;k<urls.length();k++) {
            htmlDecodedText = htmlDecodedText.replace(
                    urls.getJSONObject(k).getString("url"), urls.getJSONObject(k).getString("expanded_url"));
        }
        return tcoRegex.replace(htmlDecodedText,"");
    }

    public static String toUTC8(String created_at) throws ParseException {
        String timeString = created_at.replace("+0000 ", "");
        long timeStamp = dateFormatIn.parse(timeString).getTime() + 28800*1000;
        return dateFormatOut.format(new Date(timeStamp));
    }

    /**
     * Will not check {@code location}, {@code hashtags}, {@code user_mentions} and {@code translations}.<br>
     * 不检查{@code location}、{@code hashtags}、{@code user_mentions}和{@code translations}<br>
     * When it comes to REPLY fields, will only check {@code in_reply_to_status_id}.<br>
     * 当涉及关于回复的字段时，只检查{@code in_reply_to_status_id}
     */
    public boolean equals(Object obj) {
        if(this==obj) return true;
        if(obj instanceof TwitterStatus) {
            TwitterStatus anotherStatus = (TwitterStatus)obj;
            if(media.size()!=anotherStatus.media.size())
                return false;
            for(int i=0;i<media.size();i++) {
                if(!media.get(i).equals(anotherStatus.media.get(i)))
                    return false;
            }
            return created_at.equals(anotherStatus.created_at) && id.equals(anotherStatus.id) && user.equals(anotherStatus.user)
                    && text!=null?text.equals(anotherStatus.text):anotherStatus.text==null
                    && in_reply_to_status_id!=null?in_reply_to_status_id.equals(anotherStatus.in_reply_to_status_id):anotherStatus.in_reply_to_status_id==null
                    && quoted_status_id!=null?quoted_status_id.equals(anotherStatus.quoted_status_id):anotherStatus.quoted_status_id==null;
        }
        return false;
    }

    private void TransformMedia(JSONArray medias) throws JSONException {
        for(int i=0;i<medias.length();i++) {
            JSONObject mediaJson = medias.getJSONObject(i);
            this.media.add(new TwitterMedia(mediaJson));
        }
    }

    public void addTranslation(JSONObject translation) throws JSONException {
        HashMap<String,String> transMap = new HashMap<>();
        transMap.put("userName", translation.getJSONObject("author").getString("name"));
        transMap.put("groupName", translation.getJSONObject("author").getString("group"));
        transMap.put("content", translation.getString("translationContent"));
        this.translations.add(transMap);
    }

    public void addTranslation(HashMap<String,String> translation) {
        if(translation.containsKey("userName") && translation.containsKey("groupName") && translation.containsKey("content")) {
            this.translations.add(translation);
        } else {
            throw new IllegalArgumentException("Missing key(s): userName, groupName or content.");
        }
    }

    public void queryTranslations(final Handler handler, final TweetDetailCard parentCard) {
        queryTranslations(handler, parentCard,null, null);
    }

    public void queryTranslations(final Handler handler, final TweetDetailCardAdapter parentAdapter, final RefreshLayout refreshLayout) {
        queryTranslations(handler, null, parentAdapter, refreshLayout);
    }

    private void queryTranslations(final Handler handler, final TweetDetailCard parentCard, final TweetDetailCardAdapter parentAdapter, final RefreshLayout refreshLayout) {
        hadQueried = true;
        final String finalId = this.id;
        if(translations==null) {
            translations = new ArrayList<>();
        }else {
            translations.clear();  // 避免重复
        }
        new Thread(() -> {
            try {
                Response response = Timeline.connection.webService.get(WebService.SERVER_API+"/tweet/"+finalId+"/translations");
                if(response.code()==200) {
                    JSONObject resJson = new JSONObject(response.body().string());
                    response.close();
                    if(resJson.getBoolean("success")) {
                        final JSONArray translations = resJson.getJSONArray("response");
                        for(int i=0;i<translations.length();i++) {
                            addTranslation(translations.getJSONObject(i));
                        }
                        if(handler!=null) {
                            if (parentCard!=null) {
                                handler.post(() -> {
                                    parentCard.historyButton.setText(String.valueOf(translations.length()));
                                    parentCard.getHistoryAdapter().notifyDataSetChanged();
                                });
                            } else if(parentAdapter!=null&&refreshLayout!=null) {
                                handler.post(() -> {
                                    refreshLayout.finishRefresh(true);
                                    parentAdapter.notifyDataSetChanged();
                                });
                            }
                        }
                    } else {
                        Log.e("TwitterStatus.queryTranslations", resJson.toString());
                    }
                } else {
                    Log.e("TwitterStatus.queryTranslations", response.code()+" "+response.message());
                    response.close();
                }
            } catch (Exception e) {
                Log.e("TwitterStatus.queryTranslations", e.toString());
            } finally {
                if(handler!=null&&refreshLayout!=null&&refreshLayout.isRefreshing()) {
                    handler.post(() -> refreshLayout.finishRefresh(false));
                }
            }
        }).start();
    }

    protected TwitterStatus(Parcel in) {
        created_at = in.readString();
        id = in.readString();
        text = in.readString();
        user = in.readParcelable(TwitterUser.class.getClassLoader());
        in_reply_to_status_id = in.readString();
        in_reply_to_user_id = in.readString();
        in_reply_to_screen_name = in.readString();
        quoted_status_id = in.readString();
        location = in.readString();
        hashtags = in.createStringArrayList();
        user_mentions = in.createTypedArrayList(TwitterUser.CREATOR);
        media = in.createTypedArrayList(TwitterMedia.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(created_at);
        dest.writeString(id);
        dest.writeString(text);
        dest.writeParcelable(user, flags);
        dest.writeString(in_reply_to_status_id);
        dest.writeString(in_reply_to_user_id);
        dest.writeString(in_reply_to_screen_name);
        dest.writeString(quoted_status_id);
        dest.writeString(location);
        dest.writeStringList(hashtags);
        dest.writeTypedList(user_mentions);
        dest.writeTypedList(media);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TwitterStatus> CREATOR = new Creator<TwitterStatus>() {
        @Override
        public TwitterStatus createFromParcel(Parcel in) {
            return new TwitterStatus(in);
        }

        @Override
        public TwitterStatus[] newArray(int size) {
            return new TwitterStatus[size];
        }
    };

    public String getCreated_at(){
        return created_at;
    }

    public String getId(){
        return id;
    }

    public String getText(){
        return text==null?"":text;
    }

    public TwitterUser getUser(){
        return user;
    }

    public int getTweetType(){
        if(in_reply_to_status_id != null){
            return REPLY;
        } else if(quoted_status_id != null){
            return QUOTE;
        } else {
            return NORMAL;
        }
    }

    public String getFullText() {
        return getFullText(RTGroup.DEFAULT_FORMAT, "");
    }

    public String getFullText(String format) {
        return getFullText(format, "");
    }

    public String getFullText(String format, String translatedText) {
        if(!translatedText.equals(""))
            translatedText = translatedText+"\n\n";
        String typeString = "";
        TwitterStatus parentStatus = null;
        switch (getTweetType()){
            case REPLY:
                typeString = "回复：\n\n";
                parentStatus = LitePal.where("tsid = ?", in_reply_to_status_id)
                        .findFirst(DBTwitterStatus.class).toTwitterStatus();
                break;
            case QUOTE:
                typeString = "转推：\n\n";
                parentStatus = LitePal.where("tsid = ?", quoted_status_id)
                        .findFirst(DBTwitterStatus.class).toTwitterStatus();
                break;
            default:
                break;
        }
        return String.format(format,
                user.name_in_group,
                user.screen_name,
                created_at,
                text==null||text.equals("")?"":text+"\n\n",
                text==null||text.equals("")?"":translatedText,
                typeString,
                parentStatus==null?"":"#"+parentStatus.user.name_in_group+"#",
                parentStatus==null?"":parentStatus.user.screen_name,
                parentStatus==null?"":parentStatus.text);
    }

    public void clearMediaCache(){
        if(media != null){
            for(TwitterMedia singleMedia:media){
                singleMedia.cached_image_preview=null;
                singleMedia.cached_image=null;
            }
        }
    }

    /**
     * 注意：为自动实现倒序，返回结果与compareTo的默认规定相反
     */
    @Override
    public int compareTo(TwitterStatus status) {
        if(status==null) throw new NullPointerException();
        return Long.compare(Long.parseLong(status.id),Long.parseLong(this.id));
    }
}

