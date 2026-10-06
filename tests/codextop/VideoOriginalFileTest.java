package com.butang.codextop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 执行真实视频入口、原文档准备与预览分支；平台对象只提供合成数据，不访问图库或账号。 */
public final class VideoOriginalFileTest {
    /** 前像先执行原 init，保证 RED 是原行为失败，不是缺少新增方法造成编译失败。 */
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?"TMessagesProj/src/main/java/org/telegram":args[0]);
        String alert=Files.readString(root.resolve(args.length==0?"ui/Components/ChatAttachAlert.java":"ChatAttachAlert.java"));
        String helper=Files.readString(root.resolve(args.length==0?"messenger/SendMessagesHelper.java":"SendMessagesHelper.java"));
        String viewer=Files.readString(root.resolve(args.length==0?"ui/PhotoViewer.java":"PhotoViewer.java"));
        String marker="if (com.butang.codextop.CodexRuntime.ownsConversation(getDialogId()))";
        int end=alert.indexOf("photoLayout.onInit(videosEnabled, photosEnabled, documentsEnabled)");
        String init=block(alert,alert.lastIndexOf(marker,end));
        run("InitProbe","package com.butang.codextop; public class InitProbe {boolean photosEnabled,documentsEnabled,videosEnabled,musicEnabled=true,pollsEnabled=true,todoEnabled=true,allowLivePhotos=true;long getDialogId(){return 7;}void init(){"+init+"}"+"public static void main(String[] args){InitProbe p=new InitProbe();p.init();if(!p.videosEnabled)throw new AssertionError(\"Codex图库仍禁止视频\");if(!p.photosEnabled||!p.documentsEnabled||p.musicEnabled||p.pollsEnabled||p.todoEnabled||p.allowLivePhotos)throw new AssertionError(\"无关入口改变\");CodexRuntime.owned=false;p=new InitProbe();p.init();if(p.videosEnabled||!p.musicEnabled)throw new AssertionError(\"普通TG改变\");System.out.println(\"init actual branch GREEN\");}}");
        runSending(helper);
        runViewer(viewer);
    }

    /** 真实新增调用接到完整原文档准备方法；媒体循环其余图像处理仅作为有序平台边界。 */
    private static void runSending(String source)throws Exception{
        String start="for (int a = 0; a < count; a++) {\n                final SendingMediaInfo info = media.get(a);";
        int loop=source.lastIndexOf(start,source.indexOf("isCodexVideoFile(dialogId, info.isVideo)")),boundary=source.indexOf("                if (info.searchImage != null",loop);
        if(loop<0||boundary<0)throw new AssertionError("原媒体循环提取边界变化");
        String dispatch=source.substring(loop,boundary)+"mediaCount++; ui.add(()->events.add(\"photo:\"+info.path)); }";
        String tail=block(source,source.indexOf("if (lastGroupId != 0 && !forcedPollDoNotSendFinal)",boundary));
        String direct=block(source,source.indexOf("if (isCodexVideoFile(dialogId, true))"));
        run("SendMessagesHelper",SEND_FIXTURE+method(source,"private static boolean isCodexVideoFile(")+method(source,"private static int prepareSendingDocumentInternal(")
            +"void batch(ArrayList<SendingMediaInfo> media){int count=media.size();long groupId=0,lastGroupId=0;int mediaCount=0;"+dispatch+tail+"}"
            +"void direct(String videoPath){"+direct+"fallback++;}"+SEND_CASES+"}");
    }

    /** 执行视频/照片切换原片段、完整元数据异步方法及完整静音更新，覆盖延迟回调后仍是原件模式。 */
    private static void runViewer(String source)throws Exception{
        int start=source.indexOf("                    boolean isMuted = false;",source.indexOf("private void setIsAboutToSwitchToIndex(int index, boolean init, boolean animated, boolean force)"));
        int end=source.indexOf("                    if (isLivePhoto && object instanceof",start);
        String show=source.substring(start,end);
        int photoStart=source.indexOf("                } else {\n                    showVideoTimeline(false, animated);",end)+"                } else {".length();
        int photoEnd=source.indexOf("                if (object instanceof MediaController.PhotoEntry)",photoStart);
        String photo=source.substring(photoStart,photoEnd).stripTrailing();photo=photo.substring(0,photo.lastIndexOf('}'));
        int restore=source.indexOf("                    editState.savedFilterState = entry.savedFilterState;",source.indexOf("private void setImageIndex(int index, boolean init, boolean animateCaption, boolean force)"));
        String state=source.substring(restore,source.indexOf("                    File file = new File(entry.path);",restore));
        String selectPath=block(source,source.indexOf("if (object instanceof MediaController.MediaEditState)",restore));
        int cropStart=source.indexOf("cropState = ",source.indexOf("MediaController.PhotoEntry photoEntry =",source.indexOf("private void setIndexToImage(")));
        String cropLine=source.substring(cropStart,source.indexOf(';',cropStart)+1);
        int thumb=source.indexOf("if (photoEntry.thumbPath != null",source.indexOf("private void setIndexToImage("));
        String thumbCondition=source.substring(thumb+4,source.indexOf(") {",thumb));
        int timer=source.indexOf("boolean allowTimeItem = ");
        String timerGate=source.substring(timer+"boolean allowTimeItem = ".length(),source.indexOf(" && (",timer));
        String edited=method(source,"private VideoEditedInfo getCurrentVideoEditedInfo(");
        edited=edited.substring(0,edited.indexOf("        if (!isCurrentVideo"))+"return fallbackEdited; }";
        run("ViewerProbe",VIEW_FIXTURE+method(source,"private boolean isCodexVideoFile(")+method(source,"private boolean isCodexCurrentVideoFile(")
            +edited+method(source,"private void processOpenVideo(")+method(source,"private void updateVideoInfo(")+method(source,"public void updateMuteButton(")
            +method(source,"private void setIndexToPaintingOverlay(")
            +"void showVideo(Object object){boolean animated=false;long livePhotoVideoOffset=0,livePhotoTimestampUs=0;String currentPathObject=((MediaController.PhotoEntry)object).path;"+show+"}"
            +"void showPhoto(Object object){boolean animated=false,animateCaption=true,highQuality=false,isAnimation=false;int index=currentIndex;"+photo+"}"
            +"void restore(MediaController.PhotoEntry entry){"+state+"}"
            +"String selectedPath(Object object){String currentPathObject=((MediaController.PhotoEntry)object).path,currentImagePath=null;"+selectPath+"return currentImagePath;}"
            +"boolean timerGate(Object entry){return "+timerGate+";}"
            +"boolean useThumbnail(MediaController.PhotoEntry photoEntry){Object object=photoEntry;return "+thumbCondition+";}"
            +"Object crop(MediaController.PhotoEntry photoEntry){Object object=photoEntry;MediaController.CropState cropState=null;"+cropLine+"return cropState;}"
            +VIEW_CASES+"}");
    }

    /** 每组独立编译、类加载及清理；真实业务判断不得在替身里复写。 */
    private static void run(String name,String source)throws Exception{
        Path tmp=Files.createTempDirectory("codex-video-original-test-");
        try{
            Files.writeString(tmp.resolve("CodexRuntime.java"),"package com.butang.codextop; public class CodexRuntime {public static boolean owned=true,on=true;public static boolean enabled(){return on;}public static boolean ownsConversation(long id){return owned&&id==7;}}");
            Files.writeString(tmp.resolve(name+".java"),source);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",tmp.toString(),tmp.resolve("CodexRuntime.java").toString(),tmp.resolve(name+".java").toString())!=0)throw new AssertionError("实际源码夹具编译失败: "+name);
            try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()},null)){
                try{loader.loadClass("com.butang.codextop."+name).getMethod("main",String[].class).invoke(null,(Object)new String[]{tmp.toString()});}
                catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError("实际源码行为失败: "+name,e.getCause());}
            }
        }finally{try(var paths=Files.walk(tmp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    /** 读取真实完整方法，使用词法扫描跳过注释与字符串中的括号。 */
    private static String method(String text,String marker){return block(text,text.indexOf(marker));}
    /** 不根据待测逻辑重新构造条件，保留原源中的每个语句。 */
    private static String block(String text,int start){
        if(start<0)throw new AssertionError("真实方法提取失败");int brace=text.indexOf('{',start),depth=1,i=brace+1;boolean quote=false,character=false,line=false,comment=false,escape=false;
        for(;depth>0&&i<text.length();i++){
            char c=text.charAt(i),next=i+1<text.length()?text.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(comment){if(c=='*'&&next=='/'){comment=false;i++;}continue;}
            if(quote||character){if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(quote&&c=='"')quote=false;if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&next=='/'){line=true;i++;continue;}if(c=='/'&&next=='*'){comment=true;i++;continue;}if(c=='"'){quote=true;continue;}if(c=='\''){character=true;continue;}if(c=='{')depth++;if(c=='}')depth--;
        }if(depth!=0)throw new AssertionError("原代码括号边界变化");return text.substring(start,i);
    }
    private static final String SEND_FIXTURE="""
        package com.butang.codextop;
        import java.io.*;import java.nio.file.*;import java.util.*;
        public class SendMessagesHelper {
          static int checks; static final int ERROR_TYPE_UNSUPPORTED=1,ERROR_TYPE_FILE_TOO_LARGE=2;
          static ArrayList<String> events=new ArrayList<>();static ArrayList<SendMessageParams> sent=new ArrayList<>();static ArrayList<Integer> errors=new ArrayList<>();static ArrayList<Runnable> ui=new ArrayList<>();
          AccountInstance accountInstance=new AccountInstance();long dialogId=7,effectId=8,payStars=9,stars=9,monoForumPeerId=10,forcedPollGroupId;boolean groupMediaFinal=true,forcedPollDoNotSendFinal,forceDocument,notify=true,invertMedia=true;int scheduleDate=42,scheduleRepeatPeriod=0,fallback;
          MessageObject replyToMsg=new MessageObject(),replyToTopMsg=new MessageObject(),editingMessageObject;TL_stories.StoryItem storyItem=new TL_stories.StoryItem();ChatActivity.ReplyQuote quote=new ChatActivity.ReplyQuote();ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();CharSequence caption="direct caption";
          SendMessageChatArguments sendMessageChatArguments=new SendMessageChatArguments();MessageSuggestionParams suggestionParams=new MessageSuggestionParams();PollSendParams pollSendParams;
          static class SendingMediaInfo {boolean isVideo,isLivePhoto;String path,caption;Uri uri;ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();int pollIndex=-1;SendingMediaInfo(String p,boolean v){path=p;isVideo=v;caption="caption:"+new File(p).getName();}}
          static class Uri {File file;static Uri fromFile(File f){Uri u=new Uri();u.file=f;return u;}}
          static class AndroidUtilities {static boolean isInternalUri(Uri u){return false;}static void runOnUIThread(Runnable r){ui.add(r);}}
          static class MimeTypeMap {static MimeTypeMap getSingleton(){return new MimeTypeMap();}String getExtensionFromMimeType(String m){return "mp4";}String getMimeTypeFromExtension(String e){return switch(e){case "mp4"->"video/mp4";case "mov"->"video/quicktime";case "webm"->"video/webm";default->null;};}}
          static class AccountInstance {int getCurrentAccount(){return 0;}MessagesStorage getMessagesStorage(){return new MessagesStorage();}ConnectionsManager getConnectionsManager(){return new ConnectionsManager();}SendMessagesHelper getSendMessagesHelper(){return owner;}}
          static SendMessagesHelper owner;static class ConnectionsManager {int getCurrentTime(){return 12;}}
          static class MessagesStorage {static final int SENT_FILE_TYPE_AUDIO=1,SENT_FILE_TYPE_AUDIO_ENCRYPTED=2;Object[] getSentFile(String path,int kind){return null;}}
          static class MessageObject {long getGroupIdForUse(){return 0;}static boolean canPreviewDocument(Object d){return false;}}
          static class TL_stories {static class StoryItem {}}static class ChatActivity {static class ReplyQuote {}}
          static class TLRPC {static class PhotoSize{}static class MessageEntity{}static class TL_inputStickerSetEmpty{}static class TL_documentAttributeAnimated{}static class TL_documentAttributeFilename{String file_name;}static class TL_documentAttributeAudio{int duration,flags;String title,performer;boolean voice;}static class TL_documentAttributeSticker{String alt;Object stickerset;}static class TL_documentAttributeImageSize{int w,h;}static class TL_document{long id,size;int date,dc_id,flags;byte[] file_reference;String mime_type;ArrayList<Object> attributes=new ArrayList<>();ArrayList<PhotoSize> thumbs=new ArrayList<>();}}
          static class MediaController {static String copyFileToCache(Uri u,String e){return u.file.getPath();}static int isOpusFile(String p){return 0;}}
          static class FileLoader {static boolean checkUploadFileSize(int a,long n){return true;}}
          static class DialogObject {static boolean isEncryptedDialog(long d){return false;}}
          static class Bitmap {enum CompressFormat{PNG}void recycle(){}}
          static class BitmapFactory {static class Options{boolean inJustDecodeBounds;int outWidth,outHeight;}static Bitmap decodeByteArray(byte[] b,int a,int c){return null;}static Bitmap decodeFile(String p,Options o){return null;}}
          static class ImageLoader {static Bitmap loadBitmap(Object...a){return null;}static TLRPC.PhotoSize scaleAndSaveImage(Object...a){return null;}}
          static class AudioInfo {static AudioInfo getAudioInfo(File f){return null;}long getDuration(){return 0;}String getArtist(){return null;}String getTitle(){return null;}Bitmap getCover(){return null;}}
          static class MediaMetadataRetriever {static final int METADATA_KEY_DURATION=1,METADATA_KEY_TITLE=2,METADATA_KEY_ARTIST=3;void setDataSource(String s){}String extractMetadata(int i){return null;}byte[] getEmbeddedPicture(){return null;}void release(){}}
          static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
          static class PollSendParams{long groupId;}static class SendMessageChatArguments{}static class MessageSuggestionParams{}static class Utilities{static Random random=new Random(7);}
          static class SendMessageParams {TLRPC.TL_document document;String path,caption;Object videoInfo,entities,replyToStoryItem,replyQuote,sendMessageChatArguments,suggestionParams,pollSendParams;long effect_id,payStars,monoForumPeer;int pollIndex;boolean invert_media;HashMap<String,String> params;static SendMessageParams of(Object... a){SendMessageParams p=new SendMessageParams();p.document=(TLRPC.TL_document)a[0];p.videoInfo=a[1];p.path=(String)a[2];p.caption=(String)a[6];p.entities=a[7];p.params=(HashMap<String,String>)a[9];return p;}}
          void sendMessage(SendMessageParams p){sent.add(p);events.add("file:"+p.path);}void editMessage(Object...a){throw new AssertionError("unexpected edit");}
          static void ensureMediaThumbExists(Object...a){}static boolean checkFileSize(AccountInstance a,Uri u){return false;}
          static void finishGroup(AccountInstance a,long id,int date){ui.add(()->events.add("finish:"+id));}static void handleError(int error,AccountInstance a){if(error!=0)errors.add(error);}
          static void flush(){for(Runnable r:new ArrayList<>(ui))r.run();ui.clear();}static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
          static SendMessagesHelper fresh(){sent.clear();events.clear();errors.clear();ui.clear();CodexRuntime.on=CodexRuntime.owned=true;return owner=new SendMessagesHelper();}
        """;
    private static final String SEND_CASES="""
          public static void main(String[] args)throws Exception{
            Path dir=Path.of(args[0]);String[] names={"first.mp4","second.MOV","third.webm","opaque.xyz"};ArrayList<SendingMediaInfo> videos=new ArrayList<>();
            for(String n:names){Path p=dir.resolve(n);Files.write(p,new byte[]{1,4,8,2,9});videos.add(new SendingMediaInfo(p.toString(),true));}
            SendMessagesHelper p=fresh();p.batch(videos);check(sent.isEmpty(),"原UI队列被跳过");flush();check(sent.size()==4,"视频未走文档");
            String[] mime={"video/mp4","video/quicktime","video/webm","application/octet-stream"};String group=sent.get(0).params.get("groupId");
            for(int i=0;i<4;i++){SendMessageParams s=sent.get(i);check(s.path.equals(videos.get(i).path),"原件路径被转换");check(s.document.mime_type.equals(mime[i]),"MIME未沿原扩展名");check(s.document.size==5,"原件大小不符");check(s.videoInfo==null,"文件仍带转码参数");check(s.caption.equals(videos.get(i).caption)&&s.entities==videos.get(i).entities,"caption/entities丢失");check("1".equals(s.params.get("forceDocument")),"未声明文档");check(group.equals(s.params.get("groupId")),"变长文件被拆组");check(s.params.containsKey("final")== (i==3),"最后标记错误");check(((TLRPC.TL_documentAttributeFilename)s.document.attributes.get(0)).file_name.equals(names[i]),"文件名变化");check(Arrays.equals(Files.readAllBytes(Path.of(s.path)),new byte[]{1,4,8,2,9}),"字节变化");}
            check(events.stream().noneMatch(s->s.startsWith("finish:")),"成功末项重复final");
            p=fresh();ArrayList<SendingMediaInfo> many=new ArrayList<>();for(int i=0;i<11;i++)many.add(videos.get(0));p.batch(many);flush();check(sent.size()==11,"11项缺失");check(sent.get(9).params.containsKey("final")&&sent.get(10).params.containsKey("final"),"十项分组末项错误");check(!sent.get(9).params.get("groupId").equals(sent.get(10).params.get("groupId")),"未按十项分组");
            p=fresh();ArrayList<SendingMediaInfo> mix=new ArrayList<>();mix.add(new SendingMediaInfo("photo-A",false));mix.add(videos.get(0));mix.add(new SendingMediaInfo("photo-B",false));p.batch(mix);check(ui.size()==4,"文件被推迟到批末");flush();check(sent.size()==1&&events.get(1).equals("file:"+videos.get(0).path)&&events.get(0).equals("photo:photo-A")&&events.get(2).equals("photo:photo-B"),"混排文件未执行");
            p=fresh();Path empty=dir.resolve("empty.mp4");Files.write(empty,new byte[0]);p.batch(new ArrayList<>(List.of(videos.get(0),new SendingMediaInfo(empty.toString(),true))));flush();check(sent.size()==1&&errors.equals(List.of(1)),"无效末文件未报错");check(events.get(events.size()-1).startsWith("finish:"),"失败末项令前项永远等final");
            p=fresh();p.groupMediaFinal=false;p.batch(new ArrayList<>(List.of(videos.get(0))));flush();check(!sent.get(0).params.containsKey("groupId"),"单独发送引入分组");
            p=fresh();p.direct(videos.get(0).path);flush();check(sent.size()==1&&p.fallback==0&&sent.get(0).caption.equals("direct caption"),"单视频未保留原说明");
            p=fresh();p.forceDocument=true;p.batch(new ArrayList<>(List.of(videos.get(0))));flush();check(sent.isEmpty(),"显式文件路径被提前发送打乱顺序");
            p=fresh();CodexRuntime.owned=false;p.batch(new ArrayList<>(List.of(videos.get(0))));p.direct(videos.get(0).path);flush();check(sent.isEmpty()&&p.fallback==1,"普通Telegram被接管");
            p=fresh();CodexRuntime.on=false;p.direct(videos.get(0).path);check(p.fallback==1&&ui.isEmpty(),"禁用构建被接管");
            p=fresh();SendingMediaInfo animation=new SendingMediaInfo("animated-photo",false);p.batch(new ArrayList<>(List.of(animation)));flush();check(sent.isEmpty(),"照片动画被当视频文件");
            p=fresh();SendingMediaInfo live=new SendingMediaInfo(videos.get(0).path,true);live.isLivePhoto=true;p.batch(new ArrayList<>(List.of(live)));flush();check(sent.isEmpty(),"LivePhoto被错接");
            System.out.println("send actual branches + full document method GREEN assertions="+checks);
          }
        """;
    private static final String VIEW_FIXTURE="""
        package com.butang.codextop;
        import java.io.*;import java.nio.file.*;import java.util.*;
        public class ViewerProbe {
          static int checks;static final int SELECT_TYPE_NO_SELECT=-1,SELECT_TYPE_AVATAR=1,SELECT_TYPE_STICKER=11,SELECT_TYPE_QR=10;
          int sendPhotoType,currentIndex,switchingToIndex;ChatActivity parentChatActivity=new ChatActivity();Object parentActivity=new Object();ArrayList<Object> imagesArrLocals=new ArrayList<>();
          static class ChatActivity {long id=7;long getDialogId(){return id;}Object getCurrentUser(){return null;}Object getCurrentChat(){return null;}}
          static class MediaController {static class CropState{}static class SavedFilterState{}static class MediaEditState{String imagePath,filterPath,paintPath;ArrayList<VideoEditedInfo.MediaEntity> mediaEntities;}static class PhotoEntry extends MediaEditState {boolean isVideo=true,live;String path,caption,coverPath,croppedPaintPath,thumbPath;Object coverPhotoParentObject;TLRPC.Photo coverPhoto;VideoEditedInfo editedInfo;SavedFilterState savedFilterState;CropState cropState;ArrayList<VideoEditedInfo.MediaEntity> croppedMediaEntities;long averageDuration;boolean isLivePhoto(){return live;}}static class SearchImage extends MediaEditState{}static int getVideoBitrate(String p){return 800000;}static boolean isH264Video(String p){return true;}}
          static class VideoEditedInfo {static class MediaEntity{}boolean muted=true;float start=.3f,end=.7f;int compressQuality=0;}
          VideoEditedInfo fallbackEdited;static class EditState{Object savedFilterState,cropState,mediaEntities,croppedMediaEntities;String paintPath,croppedPaintPath;long averageDuration;void reset(){savedFilterState=cropState=mediaEntities=croppedMediaEntities=null;paintPath=croppedPaintPath=null;averageDuration=0;}}EditState editState=new EditState();
          static class View {static final int VISIBLE=0,GONE=8;int visibility=VISIBLE;Object tag;boolean enabled=true,clickable=true;float alpha=1;void setVisibility(int v){visibility=v;}void setTag(Object t){tag=t;}Object getTag(){return tag;}void setEnabled(boolean v){enabled=v;}void setClickable(boolean v){clickable=v;}View animate(){return this;}View alpha(float f){alpha=f;return this;}View setDuration(int v){return this;}void start(){}void setAlpha(float v){alpha=v;}void setState(boolean a,boolean b,int c){}void setValue(boolean a,boolean b){}void setImage(Object...a){}void setPhotoState(boolean b){}void invalidate(){}void requestLayout(){}void beginDelayedTransition(){}}
          View actionBar=new View(),compressItem=new View(),itemsLayout=new View(),muteButton=new View(),livePhotoButton=new View(),editCoverButton=new View(),videoAvatarTooltip=new View(),cropItem=new View(),tuneItem=new View(),paintItem=new View(),rotateItem=new View(),mirrorItem=new View(),qualityChooseView=new View();
          static class Bar {CharSequence subtitle;void setSubtitle(CharSequence s){subtitle=s;}}Bar actionBarContainer=new Bar();String currentSubtitle,customTitle;
          static class R {static class string{static final int SendAsFile=1,SoundMuted=2;}}static String getString(int id){return id==1?"Send as file":"Muted";}static String getString(String s,int id){return getString(id);}
          static class AndroidUtilities {static ArrayList<Runnable> queue=new ArrayList<>();static void runOnUIThread(Runnable r){queue.add(r);}static void updateViewVisibilityAnimated(View v,boolean show,float scale,boolean animated){v.setVisibility(show?0:8);}static String formatShortDuration(int s){return "duration";}static String formatFileSize(long n){return "size";}}
          static class Queue {ArrayList<Runnable> items=new ArrayList<>();void postRunnable(Runnable r){items.add(r);}void cancelRunnable(Runnable r){items.remove(r);}}
          static class Utilities{static Queue globalQueue=new Queue();}
          static class VideoTimelinePlayView {static final int MODE_VIDEO=0,MODE_AVATAR=1;float left,right;void setVideoPath(String p,long offset,float start,float end,long t){left=start;right=end;}float getLeftProgress(){return left;}float getRightProgress(){return right;}void setMode(int m){}void setMaxProgressDiff(float d){}}
          VideoTimelinePlayView videoTimelineView=new VideoTimelinePlayView();boolean timeline;void showVideoTimeline(boolean show,boolean a){timeline=show;}
          static class VideoPlayer {boolean mute;void setMute(boolean m){mute=m;}}VideoPlayer videoPlayer=new VideoPlayer();static class CastSync{static boolean isActive(){return false;}}
          static class MuteDrawable {void setMuted(boolean a,boolean b){}}MuteDrawable muteDrawable=new MuteDrawable();
          static class TLRPC{static class Photo{}}static class UserObject{static boolean isUserSelf(Object u){return false;}}static class ChatObject{static boolean isChannelAndNotMegaGroup(Object c){return false;}}
          static class Provider {boolean allowLivePhotos(){return true;}CharSequence getSubtitleFor(int i){return null;}}Provider placeProvider=new Provider();boolean isUnalivePhoto(){return false;}
          boolean isDocumentsPicker,isLivePhoto,isCurrentVideo,sendPhotoTypeIsGif,sendPhotoTypeIsPollMedia,centerImageIsLivePhoto,videoConvertSupported=true,muteVideo,isH264Video;
          int duration=30,compressionsCount,rotationValue,videoFramerate,resultWidth=1920,resultHeight=1080,originalWidth,originalHeight,originalBitrate,bitrate,selectedCompression;
          long originalSize,audioFramesSize,videoFramesSize,estimatedDuration,estimatedSize,startTime,endTime;float videoDuration,videoCutStart,videoCutEnd;Runnable currentLoadingVideoRunnable;Object videoPreviewMessageObject;
          static class AnimatedFileInfo {static final int PARAM_NUM_COUNT=10,PARAM_NUM_HAS_AUDIO=0,PARAM_NUM_SUPPORTED_VIDEO_CODEC=1,PARAM_NUM_SUPPORTED_AUDIO_CODEC=2,PARAM_NUM_BITRATE=3,PARAM_NUM_WIDTH=4,PARAM_NUM_HEIGHT=5,PARAM_NUM_AUDIO_FRAME_SIZE=6,PARAM_NUM_DURATION=7,PARAM_NUM_FRAMERATE=8,PARAM_NUM_ROTATION=9;}
          static class AnimatedFileNative {static boolean supported=true;static void getVideoInfo(String p,int[] a,long offset){a[0]=1;a[1]=a[2]=supported?1:0;a[3]=800000;a[4]=1920;a[5]=1080;a[6]=99;a[7]=30000;a[8]=30;}}
          static class BuildVars{static final boolean LOGS_ENABLED=false;}static class FileLog{static void d(String s){throw new AssertionError("unexpected log");}}
          void updateCompressionsCount(int w,int h){compressionsCount=3;}int selectCompression(){return 0;}void prepareRealEncoderBitrate(){}void updateWidthHeightBitrateForCompression(){resultWidth=640;resultHeight=360;}boolean needEncoding(){return true;}boolean hasAnimatedMediaEntities(){return editState.mediaEntities!=null;}void calculateEstimatedVideoSize(boolean a,boolean b){estimatedSize=100;}
          static class PaintingOverlay extends View {boolean data;void reset(){data=false;}void setData(Object...args){data=true;}}
          static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}static void completeMetadata(){for(Runnable r:new ArrayList<>(Utilities.globalQueue.items))r.run();Utilities.globalQueue.items.clear();for(Runnable r:new ArrayList<>(AndroidUtilities.queue))r.run();AndroidUtilities.queue.clear();}
          void hidden(){check(!timeline,"原件显示时间轴");for(View v:List.of(cropItem,tuneItem,paintItem,rotateItem,mirrorItem,muteButton,livePhotoButton,editCoverButton,compressItem))check(v.visibility==View.GONE,"原件编辑动作仍可见");check("作为文件发送".contentEquals(actionBarContainer.subtitle),"原件中文文案被压缩预计值覆盖");}
        """;
    private static final String VIEW_CASES="""
          public static void main(String[] args)throws Exception{
            Path file=Path.of(args[0],"synthetic.mp4");Files.write(file,new byte[]{7,3,9});MediaController.PhotoEntry video=new MediaController.PhotoEntry();video.path=file.toString();video.caption="caption kept";video.editedInfo=new VideoEditedInfo();video.savedFilterState=new MediaController.SavedFilterState();video.paintPath="old-paint";video.cropState=new MediaController.CropState();video.mediaEntities=new ArrayList<>();video.filterPath="old-filter";video.imagePath="old-image";video.thumbPath="old-thumb";
            ViewerProbe p=new ViewerProbe();p.imagesArrLocals.add(video);p.fallbackEdited=new VideoEditedInfo();
            for(int type:new int[]{0,2}){p.sendPhotoType=type;check(p.isCodexCurrentVideoFile(),"图库/拍摄未启用原件模式");p.showVideo(video);p.hidden();check(p.videoTimelineView.left==0&&p.videoTimelineView.right==1&&!p.muteVideo,"原件预览仍裁短或静音");check(p.getCurrentVideoEditedInfo()==null,"原件仍产出编辑参数");p.restore(video);check(p.editState.savedFilterState==null&&p.editState.cropState==null&&p.editState.paintPath==null,"原件预览保留旧编辑");check(p.selectedPath(video).equals(video.path)&&p.crop(video)==null&&!p.useThumbnail(video),"原件路径或缩略仍读旧编辑");check(video.caption.equals("caption kept")&&video.cropState!=null&&video.editedInfo!=null,"清预览误改源选择/说明");PaintingOverlay overlay=new PaintingOverlay();p.setIndexToPaintingOverlay(0,overlay);check(!overlay.data&&overlay.visibility==View.GONE,"原件旧画层仍显示");completeMetadata();p.hidden();check(!p.videoPlayer.mute,"元数据完成后原件静音");}
            MediaController.PhotoEntry photo=new MediaController.PhotoEntry();photo.isVideo=false;photo.path="photo";p.imagesArrLocals.add(photo);p.currentIndex=1;p.fallbackEdited=null;p.showPhoto(photo);check(!p.isCodexCurrentVideoFile()&&p.cropItem.visibility==View.VISIBLE&&p.tuneItem.visibility==View.VISIBLE&&p.paintItem.visibility==View.VISIBLE,"滑回照片编辑未恢复");check("".contentEquals(p.actionBarContainer.subtitle),"原件模式文案留在照片");
            check(!p.timerGate(video),"目标视频计时门禁误读前项照片");p.currentIndex=0;check(p.timerGate(photo),"目标照片计时门禁误读前项视频");p.showVideo(video);completeMetadata();p.hidden();
            CodexRuntime.owned=false;p.fallbackEdited=new VideoEditedInfo();p.showVideo(video);completeMetadata();check(!p.isCodexCurrentVideoFile()&&p.timeline&&p.cropItem.visibility==0&&p.compressItem.visibility==0&&p.muteButton.visibility==0,"复用viewer时普通Telegram编辑未恢复");check(p.videoTimelineView.left==.3f&&p.videoTimelineView.right==.7f&&p.muteVideo&&p.getCurrentVideoEditedInfo()==p.fallbackEdited,"普通Telegram旧编辑语义丢失");p.restore(video);check(p.editState.cropState==video.cropState&&p.editState.savedFilterState==video.savedFilterState,"普通Telegram预览状态被清");check(p.selectedPath(video).equals(video.imagePath)&&p.crop(video)==video.cropState&&p.useThumbnail(video),"普通Telegram旧预览缓存被禁用");
            CodexRuntime.owned=true;for(int type:new int[]{-1,1,3,4,5,10,11,12,13,14}){p.sendPhotoType=type;check(!p.isCodexCurrentVideoFile(),"非聊天选择模式误入原文件");}
            p.sendPhotoType=0;video.live=true;check(!p.isCodexCurrentVideoFile(),"LivePhoto被接管");video.live=false;CodexRuntime.on=false;check(!p.isCodexCurrentVideoFile(),"禁用构建被接管");CodexRuntime.on=true;
            p.parentChatActivity=null;check(!p.isCodexCurrentVideoFile(),"无聊天被接管");p.parentChatActivity=new ChatActivity();p.parentChatActivity.id=8;check(!p.isCodexCurrentVideoFile(),"其他对话被接管");p.parentChatActivity.id=7;
            p.currentIndex=-1;check(!p.isCodexCurrentVideoFile(),"负索引被接管");p.currentIndex=2;check(!p.isCodexCurrentVideoFile(),"越界索引被接管");p.currentIndex=0;
            AnimatedFileNative.supported=false;p.showVideo(video);completeMetadata();p.hidden();check(!p.muteButton.enabled,"不支持编码的原平台状态改变");
            AnimatedFileNative.supported=true;p.showVideo(video);check(Utilities.globalQueue.items.size()==1,"未进入原元数据队列");p.showVideo(video);check(Utilities.globalQueue.items.size()==1,"旧视频任务未取消");completeMetadata();p.hidden();
            p.showVideo(video);for(Runnable r:new ArrayList<>(Utilities.globalQueue.items))r.run();Utilities.globalQueue.items.clear();p.parentActivity=null;p.actionBarContainer.subtitle="closed";completeMetadata();check("closed".contentEquals(p.actionBarContainer.subtitle),"退出预览迟回包仍更新");
            System.out.println("viewer actual branches + full metadata/mute methods GREEN assertions="+checks);
          }
        """;
}
