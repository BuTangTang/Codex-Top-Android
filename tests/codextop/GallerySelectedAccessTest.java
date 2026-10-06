package com.butang.codextop;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import javax.tools.ToolProvider;

/** 执行真实图库权限及刷新方法，Android 授权、媒体查询和调度均由合成边界提供。 */
public final class GallerySelectedAccessTest {
    /** 可指定原始源码树重放旧行为；测试不读取设备、相册或真实权限。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String media = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java"));
        String photo = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java"));
        StringBuilder methods = new StringBuilder();
        for (String name : new String[]{"isNoGalleryPermissions", "requestGalleryPermission", "loadGalleryPhotos", "checkStorage", "onResume", "shouldLoadAllMedia", "updateAlbumsDropDown", "didReceivedNotification", "clearSelectedPhotos", "suspendGallerySelections", "restoreGallerySelections", "removeUnavailableGallerySelections", "isCurrentGallerySelection", "discardGallerySelectionsAwaitingRefresh", "onDismiss", "onDismissWithButtonClick", "onDestroy", "withReadableGalleryPreviewSelection", "gallerySelectionIdentity", "getPhotoEntryAtPosition", "onClose"}) { if (photo.contains(" " + name + "(")) methods.append(method(photo, name)); }
        StringBuilder mediaMethods = new StringBuilder();
        for (String name : new String[]{"canReadGalleryMedia", "hasSelectedGalleryAccess", "galleryPermissions", "areGallerySelectionsReadable", "galleryPermissionState", "refreshGalleryPhotosAlbums", "loadGalleryPhotosAlbums", "broadcastNewPhotos", "checkGallery"}) {
            if (media.contains(" " + name + "(")) mediaMethods.append(method(media, name));
        }
        String base = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/BasePermissionsActivity.java"));
        String callback = permissionCallback(base);
        String alert = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java"));
        callback += method(alert, "checkPhotoAndCameraPermission");
        // 完整 onShow 其余内容是原动画；只提取真实首段权限刷新条件执行，不改写条件。
        var show = StaticJavaParser.parse(photo).findAll(MethodDeclaration.class, m -> m.getNameAsString().equals("onShow")).get(0);
        String showGate = show.getBody().get().getStatements().stream().filter(n -> n.isIfStmt()
            && n.asIfStmt().getCondition().toString().contains("galleryRefreshPending")).map(Object::toString).findFirst().orElse("");
        callback += "void showRefresh(Object previousLayout) {" + showGate + "}\n";
        var provider = StaticJavaParser.parse(photo).findAll(MethodDeclaration.class, m -> m.getNameAsString().equals("sendButtonPressed") && m.toString().contains("parentAlert.sent = true")).get(0);
        var actualProvider = provider.clone(); actualProvider.getAnnotations().clear();
        String guard = photo.contains(" void withReadableGalleryPreviewSelection(") ? "withReadableGalleryPreviewSelection(index, () -> sent++);" : "sent++;";
        callback += actualProvider.toString() + "\nint sent;void providerEntry(int index){" + guard + "}void paidEntry(int index){" + guard + "}\n";
        String source = PREFIX + "static class MediaController {" + MEDIA_BOUNDARY + mediaMethods + "}\n" + methods + callback + SCENARIOS + "}\n";
        source = source.replace("android.text.TextUtils.equals", "TextUtils.equals").replace("ChatAttachAlertPhotoLayout.this", "this");
        Path temp = Files.createTempDirectory("gallery-selected-access-");
        try {
            Path file = temp.resolve("GalleryProbe.java"); Files.writeString(file, source);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(), file.toString()) != 0)
                throw new AssertionError("真实图库方法合成编译失败");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                try { loader.loadClass("GalleryProbe").getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
                catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError("真实图库行为失败", e.getCause()); }
            }
        } finally {
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); }
        }
    }

    /** 提取唯一真实方法，拒绝方法缺失或重载歧义。 */
    private static String method(String source, String name) {
        var unit = StaticJavaParser.parse(source.replace('\0', ' '));
        var matches = unit.findAll(MethodDeclaration.class, m -> m.getNameAsString().equals(name) && (!name.equals("onClose") || m.toString().contains("setCurrentSpoilerVisible")));
        if (matches.size() != 1) throw new AssertionError(name + " count=" + matches.size());
        var result = matches.get(0).clone(); result.getAnnotations().clear(); return result.toString() + "\n";
    }

    /** 执行原权限结果中的存储分支，保留 grantResults 首项旧逻辑用于反例。 */
    private static String permissionCallback(String source) {
        var unit = StaticJavaParser.parse(source);
        var branch = unit.findAll(com.github.javaparser.ast.stmt.IfStmt.class).stream()
            .filter(n -> n.getCondition().toString().equals("requestCode == REQUEST_CODE_EXTERNAL_STORAGE || requestCode == REQUEST_CODE_EXTERNAL_STORAGE_FOR_AVATAR"))
            .findFirst().orElseThrow();
        return "void permissionResult(int requestCode,String[] permissions,int[] grantResults) { boolean granted=grantResults.length>0 && grantResults[0]==0;"
            + branch.getThenStmt().toString().replace("MediaController.canReadGalleryMedia(this,", "MediaController.canReadGalleryMedia(activity,") + "}\n";
    }

    private static final String PREFIX = """
        import java.util.*;
        public class GalleryProbe {
          static class Build {static class VERSION {static int SDK_INT=34;} static class VERSION_CODES {static final int M=23;}}
          static class PackageManager {static final int PERMISSION_GRANTED=0;}
          static class Manifest {static class permission {static final String CAMERA="camera", READ_MEDIA_IMAGES="images", READ_MEDIA_VIDEO="video", READ_MEDIA_VISUAL_USER_SELECTED="selected", READ_MEDIA_AUDIO="audio", READ_EXTERNAL_STORAGE="storage";}}
          static class ContextCompat {static int checkSelfPermission(Context c,String p){return c.checkSelfPermission(p);}}
          static class ChatAttachAlertPhotoLayoutPreview {}
          static class Context {Resolver getContentResolver(){return new Resolver();}Set<String> grants=new HashSet<>(); int checkSelfPermission(String p){return grants.contains(p)?0:-1;}}
          static class Activity extends Context {List<String> requested=new ArrayList<>(); void requestPermissions(String[] p,int code){if(code!=4)throw new AssertionError();requested.addAll(Arrays.asList(p));}}
          static class AndroidUtilities {static Activity findActivity(Context c){return (Activity)c;} static ArrayList<Runnable> ui=new ArrayList<>(); static void runOnUIThread(Runnable r){if(Utilities.running||Thread.running)ui.add(r);else r.run();}static void runOnUIThread(Runnable r,int delay){ui.add(r);}static void cancelRunOnUIThread(Runnable r){ui.remove(r);}static void drain(){while(!ui.isEmpty())ui.remove(0).run();}}
          static class BasePermissionsActivity {static final int REQUEST_CODE_EXTERNAL_STORAGE=4;}
          static class Fragment {Activity activity; Activity getParentActivity(){return activity;}}
          static class Alert {Fragment baseFragment=new Fragment();boolean isPhotoPicker,storyMediaPicker,isStickerMode;int avatarPicker;Widget selectedMenuItem=new Widget();boolean showing=true,dismissed;boolean sent;int currentAccount;Delegate delegate;int getAdditionalMessagesCount(){return 0;}Widget getCommentView(){return new Widget();}boolean checkCaption(CharSequence text){return false;}void applyCaption(){}void setButtonPressed(boolean b){}boolean isCaptionAbove(){return false;}long dialogId=42;long getDialogId(){return dialogId;}ActionBar actionBar=new ActionBar();boolean isShowing(){return showing;}boolean isDismissed(){return dismissed;}}
          final Activity activity=new Activity(); final Alert parentAlert=new Alert();
          GalleryProbe(){parentAlert.baseFragment.activity=activity;parentAlert.delegate=new Delegate(()->sent++);ApplicationLoader.applicationContext=activity;}
          static final int REQUEST_CODE_EXTERNAL_STORAGE=4,REQUEST_CODE_EXTERNAL_STORAGE_FOR_AVATAR=151,compress=1;
          int alerts;void showPermissionErrorAlert(int id,String text){alerts++;}
          static class ImageLoader {static final ImageLoader instance=new ImageLoader();int checks;static ImageLoader getInstance(){return instance;}void checkMediaPaths(){checks++;}}
          static class ApplicationLoader {static Context applicationContext;}
          static class PhotoViewer {static final PhotoViewer instance=new PhotoViewer();boolean visible,hasCaptionForAllMedia,closePhotoAfterSelect,doneButtonPressed,closePhotoAfterSelectWithAnimation;CharSequence captionForAllMedia;void closePhoto(boolean a,boolean b){visible=false;}static PhotoViewer getInstance(){return instance;}boolean isVisible(){return visible;}}
          static class ChatActivity extends Fragment {}
          static class TextUtils {static String join(String delimiter,String[] values){return String.join(delimiter,values);}static boolean equals(String a,String b){return Objects.equals(a,b);}static boolean isEmpty(String s){return s==null||s.isEmpty();}}
          static class File {String path;File(String p){path=p;}String getAbsolutePath(){return path;}boolean delete(){return true;}}
          static class Environment {static final String DIRECTORY_DCIM="dcim";static File getExternalStoragePublicDirectory(String p){return new File("/synthetic/"+p);}}
          static class FileLog {static int errors;static void e(Throwable e){errors++;}}
          static class Thread {static boolean running;static final int MIN_PRIORITY=1;static ArrayList<Runnable> workers=new ArrayList<>();Runnable r;Thread(Runnable r){this.r=r;}void setPriority(int p){}void start(){workers.add(r);}static void drain(){while(!workers.isEmpty()){running=true;try{workers.remove(0).run();}finally{running=false;}}}}
          static class Utilities {static boolean running;static ArrayList<Runnable> jobs=new ArrayList<>();static final Queue globalQueue=new Queue();static class Queue {void postRunnable(Runnable r,int d){jobs.add(r);}void postRunnable(Runnable r){jobs.add(r);}}static void drain(){while(!jobs.isEmpty()){running=true;try{jobs.remove(0).run();}finally{running=false;}}}}
          static class NotificationCenter {static final int albumsDidLoad=1,cameraInitied=2;static GalleryProbe observer;static final NotificationCenter instance=new NotificationCenter();static NotificationCenter getGlobalInstance(){return instance;}void removeObserver(Object o,int id){}void postNotificationName(int id,Object... args){if(observer!=null)observer.didReceivedNotification(id,0,args);}}
          static class SparseArray<T> {Map<Integer,T> map=new HashMap<>();T get(int k){return map.get(k);}void put(int k,T v){map.put(k,v);}}
          static class R {static class string {static final int AllPhotos=1,AllMedia=2,AllVideos=3,GalleryAccessAllowAccessButton=4,PermissionNoStorageAvatar=5,PermissionStorageWithHint=6,EnablePhotoSpoiler=7;}static class raw {static final int permission_request_folder=1,photo_spoiler=2;}}
          static class LocaleController {static String getString(int id){return "label"+id;}}
          static class Widget {Runnable click;ArrayList<Widget> children=new ArrayList<>();void setOnClickListener(java.util.function.Consumer<Widget> listener){click=()->listener.accept(this);}void removeAllSubItems(){children.clear();}Widget addSubItem(int id,String s){Widget item=new Widget();children.add(item);return item;}Widget getPopupLayout(){return this;}void addView(Widget w){children.add(w);}void toggleSubMenu(){}void setCompoundDrawablesWithIntrinsicBounds(Object a,Object b,Object c,Object d){}void notifyDataSetChanged(){}void showTextView(){}void setText(String s){}void setAnimatedIcon(int id){}CharSequence getText(){return "synthetic";}void show(){}void showSubItem(int id){}void onItemClick(int id){}}
          static class AlbumButton extends Widget {AlbumButton(Context c,Object p,String name,int count,Object rp){}}
          static class ActionBar {Widget getActionBarMenuOnItemClick(){return new Widget();}}
          static class Cursor {int[] ids;int index=-1;boolean video,target;Cursor(int[] ids,boolean video){this.ids=ids;this.video=video;}boolean moveToNext(){return ++index<ids.length;}int getColumnIndex(String col){return Arrays.asList("id","bucket","name","data","date","orientation","width","height","size","duration").indexOf(col);}int getInt(int col){return col==0?ids[index]:col==1?10:col==8?100:0;}long getLong(int col){return getInt(col);}String getString(int col){return target?MediaStore.paths.getOrDefault(ids[index],"/synthetic/"+ids[index]):col==3?"/synthetic/"+ids[index]:"album";}void close(){}}
          static class Resolver {Cursor query(String uri,String[] projection,String where,String[] args,String order){check(Utilities.running,"targeted query is off UI thread");MediaStore.targetQueries++;if(MediaStore.failTarget)throw new SecurityException("synthetic revoke");Set<String> allowed=new HashSet<>(Arrays.asList(args));int[] ids=Arrays.stream(uri.equals("video")?MediaStore.videos:MediaStore.images).filter(id->allowed.contains(Integer.toString(id))).toArray();Cursor c=new Cursor(ids,uri.equals("video"));c.target=true;return c;}}
          static class VideoEditedInfo {}static class UserConfig {static int selectedAccount;}static class MediaDataController {static MediaDataController getInstance(int i){return new MediaDataController();}ArrayList<Object> getEntities(CharSequence[] text,boolean b){return new ArrayList<>();}}
          static class Delegate {Runnable sent;Delegate(Runnable r){sent=r;}void didPressedButton(int a,boolean b,boolean c,int d,int e,int f,boolean g,boolean h,long stars){sent.run();}}
          static class AlertsCreator {static ArrayList<java.util.function.LongConsumer> paid=new ArrayList<>();static void ensurePaidMessageConfirmation(int account,long dialog,int count,java.util.function.LongConsumer callback){paid.add(callback);}static int alerts;static Widget createSimpleAlert(Context c,Object title,String text,Object rp){alerts++;return new Widget();}}
          static class MediaStore {
            static int[] images={},videos={};static int imageQueries,videoQueries,targetQueries;static boolean failTarget;static Map<Integer,String> paths=new HashMap<>();
            static class MediaColumns {static final String _ID="id",DATA="data";}
            static class Columns {static final String _ID="id",BUCKET_ID="bucket",BUCKET_DISPLAY_NAME="name",DATA="data",DATE_MODIFIED="date",DATE_TAKEN="date",ORIENTATION="orientation",WIDTH="width",HEIGHT="height",SIZE="size",DURATION="duration";}
            static class Images {static class Media extends Columns {static final String EXTERNAL_CONTENT_URI="images";static Cursor query(Object resolver,String uri,String[] projection,Object where,Object args,Object order){boolean video=uri.equals("video");if(video)videoQueries++;else imageQueries++;return new Cursor((video?videos:images).clone(),video);}}}
            static class Video {static class Media extends Columns {static final String EXTERNAL_CONTENT_URI="video";}}
          }
          static GalleryProbe gallerySelectionOwner;
          int galleryPreviewValidationGeneration;boolean noGalleryPermissions,galleryRefreshPending,includeVideosInGallery=true,mediaEnabled=true,loading,cameraAnimationInProgress,cameraOpened;
          void hideCamera(boolean b){}void closeCamera(boolean b){}
          MediaController.AlbumEntry galleryAlbumEntry,selectedAlbumEntry,galleryAlbumBeforeRefresh;
          static final HashMap<Object,Object> selectedPhotos=new HashMap<>();final HashMap<Object,Object> gallerySelectionsAwaitingRefresh=new HashMap<>();
          static final ArrayList<Object> selectedPhotosOrder=new ArrayList<>(),cameraPhotos=new ArrayList<>();final ArrayList<Object> gallerySelectionOrderBeforeRefresh=new ArrayList<>();
          ArrayList<MediaController.AlbumEntry> dropDownAlbums;final Widget adapter=new Widget(),cameraAttachAdapter=new Widget(),dropDownContainer=new Widget(),dropDown=new Widget(),progressView=new Widget(),spoilerItem=new Widget();Object dropDownDrawable,resourcesProvider;
          HashMap<Object,Object> getSelectedPhotos(){return selectedPhotos;}ArrayList<Object> getSelectedPhotosOrder(){return selectedPhotosOrder;}void addToSelectedPhotos(MediaController.PhotoEntry e,int index){select(this,e);}
          void resumeCameraPreview(){}void setCurrentSpoilerVisible(int i,boolean b){}void onSelectedItemsCountChanged(int n){}int getSelectedCount(){return selectedPhotos.size();}
          void updatePhotosCounter(boolean b){}void updateCheckedPhotoIndices(){}void checkCamera(boolean b){}
          Context getContext(){return activity;}
          static int assertions;
          static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
        """;
    private static final String MEDIA_BOUNDARY = """
          static final java.util.concurrent.atomic.AtomicInteger galleryLoadGeneration=new java.util.concurrent.atomic.AtomicInteger();
          static AlbumEntry allMediaAlbumEntry,allPhotosAlbumEntry,allVideosAlbumEntry;
          static ArrayList<AlbumEntry> allMediaAlbums=new ArrayList<>(),allPhotoAlbums=new ArrayList<>();
          static Runnable broadcastPhotosRunnable,refreshGalleryRunnable;static boolean forceBroadcastNewPhotos,galleryLoading;static int galleryLoadedPermissionState=-1,galleryLoadingPermissionState;
          static String[] projectionPhotos={},projectionVideo={};
          static class PhotoEntry {VideoEditedInfo editedInfo;CharSequence caption;ArrayList<Object> entities;int imageId,bucketId;long dateTaken;String path,imagePath,thumbPath;boolean isVideo;PhotoEntry(int bucket,int id,long date,String path,int orientation,int duration,boolean video,int width,int height,long size){bucketId=bucket;imageId=id;dateTaken=date;this.path=path;isVideo=video;}void isLivePhoto(){}void reset(){}void copyFrom(PhotoEntry entry){imagePath=entry.imagePath;}}
          static class AlbumEntry {int bucketId;String bucketName;boolean videoOnly;PhotoEntry coverPhoto;ArrayList<PhotoEntry> photos=new ArrayList<>();SparseArray<PhotoEntry> photosByIds=new SparseArray<>();AlbumEntry(int id,String name,PhotoEntry first){bucketId=id;bucketName=name;coverPhoto=first;}void addPhoto(PhotoEntry p){photos.add(p);photosByIds.put(p.imageId,p);}}
        """;
    private static final String SCENARIOS = """
          static void flush(){for(int i=0;i<20;i++){Thread.drain();Utilities.drain();AndroidUtilities.drain();if(Thread.workers.isEmpty()&&Utilities.jobs.isEmpty()&&AndroidUtilities.ui.isEmpty())return;}throw new AssertionError("queues did not settle");}
          static MediaController.PhotoEntry photo(int id){return new MediaController.PhotoEntry(10,id,1,"/synthetic/"+id,0,0,false,1,1,1);}
          static void select(GalleryProbe p,MediaController.PhotoEntry e){p.selectedPhotos.put(e.imageId,e);if(!p.selectedPhotosOrder.contains(e.imageId))p.selectedPhotosOrder.add(e.imageId);}
          static void behavior(){
            Build.VERSION.SDK_INT=34;GalleryProbe p=new GalleryProbe();NotificationCenter.observer=p;p.activity.grants.add("selected");MediaStore.images=new int[]{1};MediaStore.videos=new int[]{2};
            MediaController.checkGallery();check(Thread.workers.isEmpty(),"first foreground without gallery cache does not scan library");
            p.loadGalleryPhotos();check(p.activity.requested.isEmpty(),"open never prompts");flush();check(p.galleryAlbumEntry.photos.size()==2,"selected images and video reach actual albums and UI");
            check(p.dropDownContainer.children.size()>p.dropDownAlbums.size(),"partial exposes explicit more action");p.dropDownContainer.children.get(0).click.run();check(p.activity.requested.contains("selected"),"more action requests actual selected permissions");p.activity.requested.clear();
            MediaController.PhotoEntry allowed=p.galleryAlbumEntry.photosByIds.get(1);allowed.imagePath="edited";select(p,allowed);MediaController.PhotoEntry revoked=p.galleryAlbumEntry.photosByIds.get(2);select(p,revoked);MediaController.PhotoEntry camera=photo(-1);p.cameraPhotos.add(camera);select(p,camera);
            MediaStore.images=new int[]{1};MediaStore.videos=new int[]{3};p.onResume();check(p.selectedPhotos.size()==1&&p.selectedPhotos.containsKey(-1),"pending verification cannot send stale gallery selection; camera kept");check(p.galleryAlbumEntry==null&&p.selectedAlbumEntry==null,"old visible albums removed during requery");check(p.activity.requested.isEmpty(),"resume never prompts");flush();
            check(p.galleryAlbumEntry.photos.size()==2&&p.galleryAlbumEntry.photosByIds.get(3)!=null&&p.galleryAlbumEntry.photosByIds.get(2)==null,"same-count reselection replaced identity");check(p.selectedPhotos.containsKey(1)&&!p.selectedPhotos.containsKey(2)&&p.selectedPhotos.containsKey(-1),"only still-authorized selection restored");check(((MediaController.PhotoEntry)p.selectedPhotos.get(1)).imagePath.equals("edited"),"restored editing state retained");
            p.checkStorage();p.clearSelectedPhotos();flush();check(p.selectedPhotos.isEmpty(),"explicit cancel never restored by late result");
            p.activity.grants.clear();p.onResume();flush();check(p.noGalleryPermissions&&p.galleryAlbumEntry==null&&p.selectedAlbumEntry==null,"revoke clears albums and UI");check(MediaController.allMediaAlbums.isEmpty(),"revoke clears shared albums");
            p.activity.grants.add("audio");MediaStore.imageQueries=MediaStore.videoQueries=0;p.checkStorage();flush();check(MediaStore.imageQueries==0&&MediaStore.videoQueries==0,"audio-only performs no visual query");
            p.activity.grants.clear();p.activity.grants.add("images");p.checkStorage();flush();check(MediaStore.imageQueries==1&&MediaStore.videoQueries==0,"full image category does not query ungranted video");
            p.activity.grants.add("selected");p.checkStorage();flush();check(p.dropDownContainer.children.size()>p.dropDownAlbums.size(),"mixed images-full videos-selected retains more");
            p.permissionResult(4,new String[]{"video","images","selected"},new int[]{-1,-1,0});check(p.alerts==0,"partial callback avoids erroneous settings dialog");
            p.permissionResult(4,new String[]{"audio"},new int[]{-1});check(p.alerts==1,"audio denied unaffected by visual grants");
            p.activity.grants.clear();p.permissionResult(4,new String[]{"video","images","selected"},new int[]{0,0,0});check(p.alerts==2,"current revoked state wins stale grant result");
            p.activity.grants.add("selected");check(!checkPhotoAndCameraPermission(p.activity),"camera denial remains independent");p.activity.grants.add("camera");check(checkPhotoAndCameraPermission(p.activity),"selected plus camera removes gallery badge");
            MediaStore.images=new int[]{10};MediaStore.videos=new int[]{};p.loadGalleryPhotos();Thread.drain();MediaStore.images=new int[]{11};p.checkStorage();Thread.drain();Collections.reverse(AndroidUtilities.ui);AndroidUtilities.drain();check(p.galleryAlbumEntry.photosByIds.get(11)!=null&&p.galleryAlbumEntry.photosByIds.get(10)==null,"old queued publish cannot overwrite newer result");
            MediaStore.images=new int[]{12};p.loadGalleryPhotos();Thread.drain();PhotoViewer.instance.visible=true;AndroidUtilities.ui.remove(0).run();PhotoViewer.instance.visible=false;MediaStore.images=new int[]{13};p.checkStorage();Thread.drain();AndroidUtilities.drain();check(p.galleryAlbumEntry.photosByIds.get(13)!=null&&p.galleryAlbumEntry.photosByIds.get(12)==null,"deferred viewer result cannot overwrite new selection");
            MediaStore.images=new int[]{14};MediaController.checkGallery();flush();check(p.galleryAlbumEntry.photosByIds.get(14)!=null,"foreground check reloads even same count");
            MediaStore.images=new int[]{};MediaStore.videos=new int[]{};p.checkStorage();flush();check(!p.noGalleryPermissions&&p.galleryAlbumEntry==null&&p.dropDownContainer.children.size()==1,"empty selected set retains explicit more action");
            p.activity.grants.addAll(Arrays.asList("images","video"));p.checkStorage();flush();check(p.dropDownContainer.children.isEmpty(),"full access no reselect action");
            p.activity.grants.removeAll(Arrays.asList("images","video"));int workers=Thread.workers.size();p.galleryRefreshPending=false;p.showRefresh(new ChatAttachAlertPhotoLayoutPreview());check(Thread.workers.size()==workers,"preview return keeps existing edits");p.showRefresh(null);check(Thread.workers.size()==workers+1,"show refreshes stale cache");p.showRefresh(null);check(Thread.workers.size()==workers+1,"show does not duplicate pending query");flush();
            MediaStore.images=new int[]{20};p.loadGalleryPhotos();flush();select(p,p.galleryAlbumEntry.photosByIds.get(20));p.checkStorage();p.checkStorage();check(p.selectedPhotos.isEmpty(),"overlapping refresh keeps selection suspended");flush();check(p.selectedPhotos.containsKey(20),"overlapping refresh restores only current allowed entry");
            // 原相册消失时必须退回当前可见集合，不能保留旧对象。
            p.selectedAlbumEntry=new MediaController.AlbumEntry(999,"gone",photo(99));p.checkStorage();flush();check(p.selectedAlbumEntry==p.galleryAlbumEntry,"removed album falls back to current snapshot");
            select(p,p.galleryAlbumEntry.photosByIds.get(20));p.checkStorage();p.onDismiss();flush();check(p.selectedPhotos.isEmpty(),"actual dismiss discards suspended selections "+p.selectedPhotos.keySet()+" pending "+p.gallerySelectionsAwaitingRefresh.keySet());
            select(p,p.galleryAlbumEntry.photosByIds.get(20));p.checkStorage();p.onDismissWithButtonClick(7);flush();check(p.selectedPhotos.isEmpty(),"submit dismissal discards suspended selections");
            select(p,p.galleryAlbumEntry.photosByIds.get(20));p.checkStorage();p.onDestroy();flush();check(p.selectedPhotos.isEmpty(),"destroy discards suspended selections");check(gallerySelectionOwner==null,"destroy releases its static selection owner");GalleryProbe currentOwner=new GalleryProbe();gallerySelectionOwner=currentOwner;p.onDestroy();check(gallerySelectionOwner==currentOwner,"destroy does not release another picker owner");ApplicationLoader.applicationContext=p.activity;
            select(p,p.galleryAlbumEntry.photosByIds.get(20));p.checkStorage();GalleryProbe other=new GalleryProbe();other.clearSelectedPhotos();flush();check(p.selectedPhotos.isEmpty(),"new picker owner prevents old snapshot restore");
            ApplicationLoader.applicationContext=p.activity;gallerySelectionOwner=p;MediaController.PhotoEntry reused=photo(20);reused.path="/different";select(p,reused);p.checkStorage();flush();check(p.selectedPhotos.isEmpty(),"same ID different path is not same file");reused=photo(20);reused.isVideo=true;select(p,reused);p.checkStorage();flush();check(p.selectedPhotos.isEmpty(),"same ID different media type is not same file");
            p.activity.grants.addAll(Arrays.asList("images","video"));p.checkStorage();flush();int stableQueries=MediaStore.imageQueries;int stableWorkers=Thread.workers.size();p.onResume();p.showRefresh(null);p.loadGalleryPhotos();check(Thread.workers.size()==stableWorkers,"stable full permission uses original cache on open and resume");flush();check(MediaStore.imageQueries==stableQueries,"stable full does not rescan on sheet open/resume");
            p.activity.grants.removeAll(Arrays.asList("images","video"));p.onResume();int partialWorkers=Thread.workers.size();p.loadGalleryPhotos();p.showRefresh(null);MediaController.checkGallery();check(Thread.workers.size()==partialWorkers,"same-cycle partial resume/open coalesce one in-flight scan");flush();
            Build.VERSION.SDK_INT=33;int queries=MediaStore.imageQueries;p.loadGalleryPhotos();flush();check(MediaStore.imageQueries==queries,"API33 existing cache hit kept");p.permissionResult(4,new String[]{"video","images"},new int[]{-1,0});check(p.alerts==3,"API33 callback unchanged");
            NotificationCenter.observer=null;
            previewBehavior();
          }
          static void previewBehavior(){
            Build.VERSION.SDK_INT=34;GalleryProbe p=new GalleryProbe();p.clearSelectedPhotos();NotificationCenter.observer=p;p.activity.grants.add("selected");MediaStore.images=new int[]{30};MediaStore.videos=new int[]{31};p.loadGalleryPhotos();flush();p.selectedAlbumEntry=p.galleryAlbumEntry;PhotoViewer.instance.visible=true;
            select(p,p.galleryAlbumEntry.photosByIds.get(30));select(p,p.galleryAlbumEntry.photosByIds.get(31));int before=MediaStore.targetQueries;p.providerEntry(0);check(p.sent==0&&MediaStore.targetQueries==before,"partial provider send awaits background validation");Utilities.drain();AndroidUtilities.drain();check(p.sent==1&&MediaStore.targetQueries==before+2,"two category lookup permits one send");
            p.providerEntry(0);p.providerEntry(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"double tap supersedes stale validation, one dispatch");
            p.providerEntry(0);p.parentAlert.dialogId++;Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"changed conversation prevents send");p.parentAlert.dialogId--;
            p.providerEntry(0);p.selectedPhotosOrder.remove((Object)31);p.selectedPhotos.remove(31);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"selection mutation prevents stale send");
            p.providerEntry(0);p.onDismissWithButtonClick(7);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"dismiss cancels async provider send");
            MediaStore.images=new int[]{};int alerts=AlertsCreator.alerts;p.paidEntry(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==2&&AlertsCreator.alerts==alerts+1,"revoked paid-confirmation entry blocked with permission hint");PhotoViewer.instance.visible=false;p.onClose();flush();check(p.galleryAlbumEntry!=null&&p.galleryAlbumEntry.photosByIds.get(30)==null,"close provider refreshes revoked cache");
            p.clearSelectedPhotos();MediaStore.images=new int[]{32};p.checkStorage();flush();select(p,p.galleryAlbumEntry.photosByIds.get(32));MediaStore.paths.put(32,"/changed");p.providerEntry(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"same ID changed path denied before send");MediaStore.paths.clear();flush();
            MediaStore.failTarget=true;p.providerEntry(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"query security failure never dispatches");MediaStore.failTarget=false;flush();
            p.activity.grants.addAll(Arrays.asList("images","video"));before=MediaStore.targetQueries;p.providerEntry(0);check(p.sent==3&&MediaStore.targetQueries==before&&Utilities.jobs.isEmpty(),"stable full access keeps synchronous original send path");
            p.activity.grants.clear();p.clearSelectedPhotos();MediaController.PhotoEntry camera=photo(-2);p.cameraPhotos.add(camera);select(p,camera);p.providerEntry(0);check(p.sent==4&&Utilities.jobs.isEmpty(),"camera-owned selection never asks gallery access");
            p.galleryRefreshPending=true;int queries=MediaStore.targetQueries;p.providerEntry(0);check(p.sent==5&&Utilities.jobs.isEmpty()&&MediaStore.targetQueries==queries,"camera-only sends during gallery refresh without waiting for viewer close");
            p.gallerySelectionsAwaitingRefresh.put(90,photo(90));p.providerEntry(0);check(p.sent==5&&!Utilities.jobs.isEmpty(),"camera shortcut cannot hide pending gallery selections");p.onDismiss();Utilities.drain();AndroidUtilities.drain();
            p.clearSelectedPhotos();NotificationCenter.observer=null;PhotoViewer.instance.visible=false;
            providerMethodBehavior();
          }
          static void providerMethodBehavior(){
            GalleryProbe p=new GalleryProbe();p.clearSelectedPhotos();p.activity.grants.add("selected");NotificationCenter.observer=p;MediaStore.images=new int[]{40};MediaStore.videos=new int[]{};p.loadGalleryPhotos();flush();select(p,p.galleryAlbumEntry.photosByIds.get(40));
            p.sendButtonPressed(0,null,true,0,0,false);check(p.sent==0&&AlertsCreator.paid.isEmpty(),"actual provider waits before paid/send chain");Utilities.drain();AndroidUtilities.drain();check(AlertsCreator.paid.size()==1&&p.sent==0,"actual provider reaches original paid boundary after verified");
            MediaStore.images=new int[]{};AlertsCreator.paid.remove(0).accept(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==0,"actual delayed paid callback rechecks revocation");flush();
            MediaStore.images=new int[]{41};p.checkStorage();flush();select(p,p.galleryAlbumEntry.photosByIds.get(41));p.sendButtonPressed(0,null,true,0,0,false);Utilities.drain();AndroidUtilities.drain();AlertsCreator.paid.remove(0).accept(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==1&&p.selectedPhotos.isEmpty()&&p.gallerySelectionsAwaitingRefresh.isEmpty(),"actual verified provider dispatches once and clears selections");
            p.activity.grants.addAll(Arrays.asList("images","video"));p.checkStorage();flush();select(p,p.galleryAlbumEntry.photosByIds.get(41));int queries=MediaStore.targetQueries;p.sendButtonPressed(0,null,true,0,0,false);check(AlertsCreator.paid.size()==1&&Utilities.jobs.isEmpty(),"actual full provider retains synchronous paid flow");AlertsCreator.paid.remove(0).accept(0);check(p.sent==2&&MediaStore.targetQueries==queries,"actual full send has no extra query");
            p.activity.grants.removeAll(Arrays.asList("images","video"));p.checkStorage();flush();select(p,p.galleryAlbumEntry.photosByIds.get(41));p.sendButtonPressed(0,null,true,0,0,false);Utilities.drain();AndroidUtilities.drain();java.util.function.LongConsumer oldPaid=AlertsCreator.paid.remove(0);
            p.clearSelectedPhotos();MediaStore.images=new int[]{42};p.checkStorage();flush();select(p,p.galleryAlbumEntry.photosByIds.get(42));oldPaid.accept(0);Utilities.drain();AndroidUtilities.drain();check(p.sent==2,"old paid confirmation cannot send newly chosen media");
            p.sendButtonPressed(0,null,true,0,0,false);Utilities.drain();AndroidUtilities.drain();oldPaid=AlertsCreator.paid.remove(0);((MediaController.PhotoEntry)p.selectedPhotos.get(42)).path="/mutated";oldPaid.accept(0);check(Utilities.jobs.isEmpty()&&p.sent==2,"paid context checks mutable path before new validation");
            p.clearSelectedPhotos();NotificationCenter.observer=null;
          }
          public static void main(String[] args){
            for(int sdk:new int[]{34,36}){
              Build.VERSION.SDK_INT=sdk; GalleryProbe p=new GalleryProbe();p.activity.grants.add("selected");
              check(!p.isNoGalleryPermissions(),"selected-only is usable API"+sdk);
              check(p.activity.requested.isEmpty(),"permission check never requests");
              p.requestGalleryPermission();check(p.activity.requested.equals(Arrays.asList("video","images","selected")),"user request includes selected API"+sdk);
              for(String grant:new String[]{"images","video"}){p=new GalleryProbe();p.activity.grants.add(grant);check(!p.isNoGalleryPermissions(),"single full visual category accepted "+grant);}
              p=new GalleryProbe();p.activity.grants.add("audio");check(p.isNoGalleryPermissions(),"audio never authorizes gallery");
              p=new GalleryProbe();check(p.isNoGalleryPermissions(),"denied remains denied");
            }
            for(int sdk:new int[]{23,29,32,33}){
              Build.VERSION.SDK_INT=sdk;GalleryProbe p=new GalleryProbe();p.activity.grants.add("selected");check(p.isNoGalleryPermissions(),"selected ignored below34");
              p.requestGalleryPermission();check(p.activity.requested.equals(sdk==33?Arrays.asList("video","images"):Arrays.asList("storage")),"legacy request unchanged");
              p.activity.grants.addAll(sdk==33?Arrays.asList("images","video"):Arrays.asList("storage"));check(!p.isNoGalleryPermissions(),"legacy full grants");
            }
            Build.VERSION.SDK_INT=22;check(!new GalleryProbe().isNoGalleryPermissions(),"pre23 unchanged");
            behavior();
            System.out.println("GallerySelectedAccessTest: "+assertions+" assertions PASS");
          }
        """;
}
